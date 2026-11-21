package org.slackerdb.dbsyncer.source.mysql;

import org.slackerdb.dbsyncer.model.DdlWriter;
import org.slackerdb.dbsyncer.model.SourceTable;
import org.slackerdb.dbsyncer.model.TableMapping;
import org.slackerdb.dbsyncer.model.TargetTable;
import org.slackerdb.dbsyncer.model.TxPosition;
import org.slackerdb.dbsyncer.model.TypeMapper;
import org.slf4j.Logger;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MySQL DDL → 目标 DuckDB 的直接应用器。
 *
 */
public final class MySqlDdlApplier {

    @FunctionalInterface
    public interface MappingFactory {
        TableMapping create(String schema, String table) throws Exception;
    }

    private final List<TableMapping> mappingList;
    private final Map<String, TableMapping> mappings;
    private final DdlWriter writer;
    private final MappingFactory mappingFactory;
    private final Logger logger;

    public MySqlDdlApplier(List<TableMapping> mappingList, Map<String, TableMapping> mappings,
                            DdlWriter writer, MappingFactory mappingFactory, Logger logger) {
        this.mappingList = mappingList;
        this.mappings = mappings;
        this.writer = writer;
        this.mappingFactory = mappingFactory;
        this.logger = logger;
    }

    /** DDL 与检查点在同一 DuckDB 事务内提交，避免 DDL 已生效但位点未推进。 */
    public void applyAtomically(String defaultDatabase, String sql, String jobName, TxPosition position)
            throws Exception {
        writer.applyDdlTransaction(jobName, position, () -> apply(defaultDatabase, sql));
    }

    public void apply(String defaultDatabase, String sql) throws Exception {
        String statement = trim(sql);
        String lower = statement.toLowerCase(Locale.ROOT);
        if (isNonTableDdl(lower)) {
            logger.info("[DBSYNCER] ignoring non-table DDL: {}", statement);
            return;
        }
        if (lower.startsWith("create table ")) {
            applyCreate(defaultDatabase, statement.substring("create table ".length()).trim());
            return;
        }
        if (lower.startsWith("rename table ")) {
            applyRename(defaultDatabase, statement.substring("rename table ".length()).trim());
            return;
        }
        if (lower.startsWith("truncate ")) {
            String tableToken = statement.substring("truncate ".length()).trim();
            if (tableToken.toLowerCase(Locale.ROOT).startsWith("table ")) {
                tableToken = tableToken.substring("table ".length()).trim();
            }
            TableMapping mapping = mapping(defaultDatabase, firstToken(tableToken));
            if (mapping != null) {
                writer.truncateTable(mapping);
                logger.info("[DBSYNCER][{}] applied TRUNCATE to {}", mapping.jobName(), mapping.target().table);
            }
            return;
        }
        if (lower.startsWith("drop table ")) {
            String rest = statement.substring("drop table ".length()).trim();
            if (rest.toLowerCase(Locale.ROOT).startsWith("if exists ")) {
                rest = rest.substring("if exists ".length()).trim();
            }
            for (String tableToken : splitClauses(rest)) {
                TableMapping mapping = mapping(defaultDatabase, tableToken.trim());
                if (mapping != null) {
                    writer.dropTable(mapping);
                    unregister(mapping);
                    logger.info("[DBSYNCER][{}] applied DROP TABLE {}", mapping.jobName(),
                            mapping.target().table);
                }
            }
            return;
        }
        if (lower.startsWith("alter table ")) {
            applyAlter(defaultDatabase, statement.substring("alter table ".length()).trim());
            return;
        }
        // 既不是已知的非表对象，也不是我们认识的表级 DDL。
        // 被同步的表只会通过上面那些语句形式改结构，所以这里按"与本作业无关"忽略，
        // 不能因为同实例里别的业务执行了 CREATE USER/ALTER INSTANCE 就把同步作业暂停。
        logger.warn("[DBSYNCER] ignoring unrecognized DDL (no synced table involved): {}", statement);
    }

    /**
     * 与"被同步的表"无关的对象级 DDL。
     *
     * <p>MySQL 的 binlog 会记录整个实例的 DDL（没有 {@code binlog_do_db} 时尤其如此），
     * 所以这些语句必须显式忽略，否则会误触发"DDL 无法翻译 → 暂停作业"。</p>
     */
    private static boolean isNonTableDdl(String lower) {
        if (lower.startsWith("create temporary table ") || lower.startsWith("drop temporary table ")) {
            return true;
        }
        for (String prefix : new String[]{
                "create index ", "create unique index ", "create fulltext index ", "create spatial index ",
                "drop index ", "create view ", "drop view ", "create or replace view ",
                "create database ", "drop database ", "alter database ",
                "create trigger ", "drop trigger ", "create function ", "drop function ",
                "create procedure ", "drop procedure ", "create event ", "drop event ",
                "create user ", "alter user ", "drop user ", "rename user ", "create role ",
                "drop role ", "set default role ", "set role ", "grant ", "revoke ",
                "create tablespace ", "alter tablespace ", "drop tablespace ",
                "create undo tablespace ", "alter undo tablespace ", "drop undo tablespace ",
                "create logfile group ", "alter logfile group ", "drop logfile group ",
                "create server ", "alter server ", "drop server ",
                "create resource group ", "alter resource group ", "drop resource group ",
                "create spatial reference system ", "drop spatial reference system ",
                "create directory ", "drop directory ", "alter instance ", "install ", "uninstall "}) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void applyCreate(String defaultDatabase, String rest) throws Exception {
        String body = rest.trim();
        if (body.toLowerCase(Locale.ROOT).startsWith("if not exists ")) {
            body = body.substring("if not exists ".length()).trim();
        }
        String tableToken = firstToken(body);
        int paren = tableToken.indexOf('(');
        if (paren >= 0) {
            tableToken = tableToken.substring(0, paren);
        }
        String[] parts = tableParts(tableToken);
        String schema = parts[0] == null ? defaultDatabase : parts[0];
        TableMapping existing = mapping(defaultDatabase, tableToken);
        if (existing != null) {
            logger.info("[DBSYNCER][{}] CREATE TABLE ignored, already mapped: {}.{}",
                    existing.jobName(), schema, parts[1]);
            return;
        }
        TableMapping mapping = mappingFactory.create(schema, parts[1]);
        if (mapping == null) {
            logger.info("[DBSYNCER] CREATE TABLE ignored, table does not match include rules: {}.{}",
                    schema, parts[1]);
            return;
        }
        writer.prepare(List.of(mapping));
        register(mapping);
        logger.info("[DBSYNCER][{}] applied CREATE TABLE {} -> {}",
                mapping.jobName(), mapping.source().qualifiedName(), mapping.target().qualifiedName());
    }

    private void applyRename(String defaultDatabase, String rest) throws Exception {
        for (String pair : splitClauses(rest)) {
            String body = pair.trim();
            int to = body.toLowerCase(Locale.ROOT).indexOf(" to ");
            if (to < 0) {
                throw new UnsupportedOperationException("unsupported RENAME TABLE: " + body);
            }
            String oldToken = body.substring(0, to).trim();
            String newToken = body.substring(to + 4).trim();
            TableMapping oldMapping = mapping(defaultDatabase, oldToken);
            if (oldMapping == null) {
                logger.debug("RENAME TABLE ignored for unmapped table {}", oldToken);
                continue;
            }
            String[] parts = tableParts(newToken);
            String newSchema = parts[0] == null ? defaultDatabase : parts[0];
            TableMapping newMapping = mappingFactory.create(newSchema, parts[1]);
            if (newMapping == null) {
                unregister(oldMapping);
                logger.info("[DBSYNCER][{}] table renamed out of include rules, mapping removed: {} -> {}",
                        oldMapping.jobName(), oldMapping.source().qualifiedName(), newSchema + "." + parts[1]);
                continue;
            }
            if (!oldMapping.target().table.equalsIgnoreCase(newMapping.target().table)) {
                writer.renameTable(oldMapping, newMapping.target());
            }
            replace(oldMapping, newMapping);
            logger.info("[DBSYNCER][{}] applied RENAME TABLE {} -> {} (target {})",
                    newMapping.jobName(), oldMapping.source().qualifiedName(), newMapping.source().qualifiedName(),
                    newMapping.target().qualifiedName());
        }
    }

    private void register(TableMapping mapping) {
        mappings.put(mapping.sourceKey(), mapping);
        if (!mappingList.contains(mapping)) {
            mappingList.add(mapping);
        }
    }

    private void unregister(TableMapping mapping) {
        mappings.remove(mapping.sourceKey());
        mappingList.remove(mapping);
    }

    private void replace(TableMapping oldMapping, TableMapping newMapping) {
        unregister(oldMapping);
        register(newMapping);
    }

    private void applyAlter(String defaultDatabase, String rest) throws Exception {
        String tableToken = firstToken(rest);
        String clauses = rest.substring(tableToken.length()).trim();
        TableMapping mapping = mapping(defaultDatabase, tableToken);
        if (mapping == null) {
            logger.debug("DDL ignored for unmapped table {}: {}", tableToken, clauses);
            return;
        }
        String lowerClauses = clauses.toLowerCase(Locale.ROOT);
        if ((lowerClauses.startsWith("rename to ") || lowerClauses.startsWith("rename "))
                && !lowerClauses.startsWith("rename column ")
                && !lowerClauses.startsWith("rename index ")
                && !lowerClauses.startsWith("rename key ")) {
            applyAlterRename(defaultDatabase, mapping, clauses);
            return;
        }
        for (String clause : splitClauses(clauses)) {
            applyClause(mapping, clause.trim());
        }
    }

    private void applyAlterRename(String defaultDatabase, TableMapping oldMapping, String clauses)
            throws Exception {
        String token = clauses.substring(clauses.toLowerCase(Locale.ROOT).indexOf("rename") + 6).trim();
        if (token.toLowerCase(Locale.ROOT).startsWith("to ")) {
            token = token.substring(3).trim();
        }
        String[] parts = tableParts(firstToken(token));
        String newSchema = parts[0] == null ? defaultDatabase : parts[0];
        TableMapping newMapping = mappingFactory.create(newSchema, parts[1]);
        if (newMapping == null) {
            unregister(oldMapping);
            logger.info("[DBSYNCER][{}] table renamed out of include rules, mapping removed: {} -> {}",
                    oldMapping.jobName(), oldMapping.source().qualifiedName(), newSchema + "." + parts[1]);
            return;
        }
        if (!oldMapping.target().table.equalsIgnoreCase(newMapping.target().table)) {
            writer.renameTable(oldMapping, newMapping.target());
        }
        replace(oldMapping, newMapping);
        logger.info("[DBSYNCER][{}] applied ALTER TABLE RENAME {} -> {}", newMapping.jobName(),
                oldMapping.source().qualifiedName(), newMapping.source().qualifiedName());
    }

    private void applyClause(TableMapping mapping, String clause) throws Exception {
        String lower = clause.toLowerCase(Locale.ROOT);
        if (lower.startsWith("add ")) {
            String body = clause.substring(4).trim();
            if (body.toLowerCase(Locale.ROOT).startsWith("column ")) {
                body = body.substring("column ".length()).trim();
            }
            if (body.toLowerCase(Locale.ROOT).startsWith("if not exists ")) {
                body = body.substring("if not exists ".length()).trim();
            }
            String lowerBody = body.toLowerCase(Locale.ROOT);
            if (lowerBody.startsWith("index") || lowerBody.startsWith("key")
                    || lowerBody.startsWith("unique") || lowerBody.startsWith("primary")
                    || lowerBody.startsWith("constraint") || lowerBody.startsWith("foreign")
                    || lowerBody.startsWith("fulltext") || lowerBody.startsWith("spatial")) {
                logger.info("[DBSYNCER][{}] ignoring index/constraint DDL: {}", mapping.jobName(), clause);
                return;
            }
            String name = firstToken(body);
            String columnName = unquote(name);
            PositionClause position = splitPosition(body.substring(name.length()).trim());
            String type = cleanType(position.definition());
            if (mapping.target().target(columnName) != null) {
                alterType(mapping, columnName, type);
                repositionSource(mapping, columnName, position);
                return;
            }
            SourceTable.SourceColumn sourceColumn = sourceColumn(columnName, type);
            // 行事件是按「表的列顺序」按位置解码的，新增列必须落到它在源表里的真实下标，
            // 否则 AFTER/FIRST 之后的所有列都会错位（静默写错列）。
            mapping.source().columns.add(insertionIndex(mapping.source(), position), sourceColumn);
            TargetTable.TargetColumn targetColumn = new TargetTable.TargetColumn(
                    columnName, TypeMapper.map("mysql", sourceColumn), columnName, null);
            mapping.target().add(targetColumn);
            writer.addColumn(mapping, targetColumn);
            return;
        }
        if (lower.startsWith("drop ")) {
            String body = clause.substring(5).trim();
            String lowerBody = body.toLowerCase(Locale.ROOT);
            if (lowerBody.startsWith("index") || lowerBody.startsWith("key")
                    || lowerBody.startsWith("primary") || lowerBody.startsWith("foreign")
                    || lowerBody.startsWith("constraint") || lowerBody.startsWith("check")) {
                logger.info("[DBSYNCER][{}] ignoring index/constraint drop: {}", mapping.jobName(), clause);
                return;
            }
            if (lowerBody.startsWith("column ")) {
                body = body.substring("column ".length()).trim();
            }
            if (body.toLowerCase(Locale.ROOT).startsWith("if exists ")) {
                body = body.substring("if exists ".length()).trim();
            }
            String column = unquote(firstToken(body));
            mapping.source().columns.removeIf(c -> c.name.equalsIgnoreCase(column));
            mapping.target().remove(column);
            writer.dropColumn(mapping, column);
            return;
        }
        if (lower.startsWith("modify ")) {
            String body = clause.substring("modify ".length()).trim();
            if (body.toLowerCase(Locale.ROOT).startsWith("column ")) {
                body = body.substring("column ".length()).trim();
            }
            String token = firstToken(body);
            String name = unquote(token);
            PositionClause position = splitPosition(body.substring(token.length()).trim());
            alterType(mapping, name, cleanType(position.definition()));
            repositionSource(mapping, name, position);
            return;
        }
        if (lower.startsWith("change ")) {
            String body = clause.substring("change ".length()).trim();
            if (body.toLowerCase(Locale.ROOT).startsWith("column ")) {
                body = body.substring("column ".length()).trim();
            }
            String oldToken = firstToken(body);
            String remainder = body.substring(oldToken.length()).trim();
            String newToken = firstToken(remainder);
            PositionClause position = splitPosition(remainder.substring(newToken.length()).trim());
            String oldName = unquote(oldToken);
            String newName = unquote(newToken);
            if (!oldName.equalsIgnoreCase(newName)) {
                writer.renameColumn(mapping, oldName, newName);
                mapping.target().rename(oldName, newName);
                renameSource(mapping, oldName, newName);
            }
            alterType(mapping, newName, cleanType(position.definition()));
            repositionSource(mapping, newName, position);
            return;
        }
        if (lower.startsWith("alter column ")) {
            logger.info("[DBSYNCER][{}] ignoring column default/nullability DDL: {}", mapping.jobName(), clause);
            return;
        }
        if (lower.startsWith("rename column ")) {
            String body = clause.substring("rename column ".length()).trim();
            int to = body.toLowerCase(Locale.ROOT).indexOf(" to ");
            if (to < 0) {
                throw new UnsupportedOperationException("unsupported RENAME COLUMN: " + clause);
            }
            String oldName = unquote(body.substring(0, to).trim());
            String newName = unquote(body.substring(to + 4).trim());
            writer.renameColumn(mapping, oldName, newName);
            mapping.target().rename(oldName, newName);
            renameSource(mapping, oldName, newName);
            return;
        }
        String lowerClause = lower;
        if (lowerClause.startsWith("engine") || lowerClause.startsWith("default charset")
                || lowerClause.startsWith("character set") || lowerClause.startsWith("collate")
                || lowerClause.startsWith("comment") || lowerClause.startsWith("auto_increment")
                || lowerClause.startsWith("row_format") || lowerClause.startsWith("tablespace")
                || lowerClause.startsWith("convert to ") || lowerClause.startsWith("force")
                || lowerClause.startsWith("algorithm") || lowerClause.startsWith("lock")
                || lowerClause.startsWith("add partition") || lowerClause.startsWith("drop partition")
                || lowerClause.startsWith("enable keys") || lowerClause.startsWith("disable keys")
                || lowerClause.startsWith("rename index")) {
            logger.info("[DBSYNCER][{}] ignoring table option: {}", mapping.jobName(), clause);
            return;
        }
        throw new UnsupportedOperationException("unsupported ALTER TABLE clause: " + clause);
    }

    private void alterType(TableMapping mapping, String column, String type) throws Exception {
        TargetTable.TargetColumn targetColumn = mapping.target().target(column);
        if (targetColumn == null) {
            throw new UnsupportedOperationException("column [" + column + "] does not exist in target");
        }
        SourceTable.SourceColumn sourceColumn = mapping.source().column(column);
        if (sourceColumn == null) {
            throw new UnsupportedOperationException("column [" + column + "] does not exist in source metadata");
        }
        SourceTable.SourceColumn updated = sourceColumn(column, type);
        replaceSource(mapping, updated);
        String duckType = TypeMapper.map("mysql", updated);
        boolean primaryKey = mapping.target().primaryKey.stream()
                .anyMatch(pk -> pk.equalsIgnoreCase(targetColumn.name()));
        writer.alterColumnType(mapping, targetColumn.name(), duckType);
        mapping.target().remove(targetColumn.name());
        mapping.target().add(new TargetTable.TargetColumn(targetColumn.name(), duckType,
                targetColumn.sourceName(), targetColumn.constant()));
        if (primaryKey) {
            mapping.target().primaryKey.add(targetColumn.name());
        }
    }

    private void replaceSource(TableMapping mapping, SourceTable.SourceColumn updated) {
        for (int i = 0; i < mapping.source().columns.size(); i++) {
            if (mapping.source().columns.get(i).name.equalsIgnoreCase(updated.name)) {
                mapping.source().columns.set(i, updated);
                return;
            }
        }
        mapping.source().columns.add(updated);
    }

    private void renameSource(TableMapping mapping, String oldName, String newName) {
        for (int i = 0; i < mapping.source().columns.size(); i++) {
            SourceTable.SourceColumn column = mapping.source().columns.get(i);
            if (column.name.equalsIgnoreCase(oldName)) {
                SourceTable.SourceColumn renamed = new SourceTable.SourceColumn(newName, column.typeName,
                        column.jdbcType, column.size, column.decimalDigits);
                renamed.generated = column.generated;
                renamed.typeDefinition = column.typeDefinition;
                renamed.characterSet = column.characterSet;
                renamed.pgTypeOid = column.pgTypeOid;
                mapping.source().columns.set(i, renamed);
                return;
            }
        }
    }

    private TableMapping mapping(String defaultDatabase, String tableToken) {
        String[] parts = tableParts(tableToken);
        String schema = parts[0] == null ? defaultDatabase : parts[0];
        if (schema == null) {
            return null;
        }
        return mappings.get((schema + "." + parts[1]).toLowerCase(Locale.ROOT));
    }

    private static SourceTable.SourceColumn sourceColumn(String name, String typeDefinition) {
        String base = typeDefinition;
        int paren = base.indexOf('(');
        if (paren > 0) {
            base = base.substring(0, paren);
        }
        base = base.trim().toLowerCase(Locale.ROOT);
        int size = 0;
        int scale = 0;
        if (paren > 0) {
            int close = typeDefinition.indexOf(')', paren);
            String inside = close > paren ? typeDefinition.substring(paren + 1, close) : "";
            String[] pieces = inside.split(",");
            try {
                size = Integer.parseInt(pieces[0].trim());
            } catch (NumberFormatException ignored) {
            }
            if (pieces.length > 1) {
                try {
                    scale = Integer.parseInt(pieces[1].trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        SourceTable.SourceColumn column = new SourceTable.SourceColumn(name, base, Types.OTHER, size, scale);
        column.typeDefinition = typeDefinition;
        return column;
    }

    private static String cleanType(String type) {
        String value = type.trim();
        value = cut(value, " not null");
        value = cut(value, " null");
        value = cut(value, " default ");
        value = cut(value, " auto_increment");
        value = cut(value, " comment ");
        value = cut(value, " primary key");
        value = cut(value, " unique");
        value = cut(value, " first");
        value = cut(value, " after ");
        value = cut(value, " collate ");
        value = cut(value, " character set ");
        return value.trim();
    }

    private static String cut(String value, String marker) {
        int index = value.toLowerCase(Locale.ROOT).indexOf(marker);
        return index < 0 ? value : value.substring(0, index);
    }

    // ------------------------------------------------------------------ 列位置

    /**
     * 列定义末尾的 {@code FIRST} / {@code AFTER col} 子句。
     *
     * <p>MySQL 的行事件是**按表的列顺序**给值的，所以这里必须把列放到它在源表里的真实下标；
     * 否则 {@code ADD/MODIFY/CHANGE ... AFTER|FIRST} 之后，它后面的所有列都会错位。</p>
     */
    private record PositionClause(boolean specified, boolean first, String afterColumn, String definition) {
    }

    private static PositionClause splitPosition(String definition) {
        List<int[]> words = topLevelWords(definition);
        if (words.isEmpty()) {
            return new PositionClause(false, false, null, definition);
        }
        int[] last = words.get(words.size() - 1);
        String lastText = definition.substring(last[0], last[1]);
        if ("first".equalsIgnoreCase(lastText)) {
            return new PositionClause(true, true, null, definition.substring(0, last[0]).trim());
        }
        if (words.size() >= 2) {
            int[] previous = words.get(words.size() - 2);
            if ("after".equalsIgnoreCase(definition.substring(previous[0], previous[1]))) {
                return new PositionClause(true, false, unquote(lastText),
                        definition.substring(0, previous[0]).trim());
            }
        }
        return new PositionClause(false, false, null, definition);
    }

    /** 顶层（引号外、括号外）的"词"区间；括号里的空格和逗号不切词。 */
    private static List<int[]> topLevelWords(String text) {
        List<int[]> words = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                if (start < 0) {
                    start = i;
                }
                quote = c;
                continue;
            }
            if (c == '(') {
                if (start < 0) {
                    start = i;
                }
                depth++;
                continue;
            }
            if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
                continue;
            }
            if (depth == 0 && Character.isWhitespace(c)) {
                if (start >= 0) {
                    words.add(new int[]{start, i});
                    start = -1;
                }
                continue;
            }
            if (start < 0) {
                start = i;
            }
        }
        if (start >= 0) {
            words.add(new int[]{start, text.length()});
        }
        return words;
    }

    private int insertionIndex(SourceTable source, PositionClause position) {
        if (!position.specified()) {
            return source.columns.size();
        }
        if (position.first()) {
            return 0;
        }
        int after = indexOfColumn(source.columns, position.afterColumn());
        if (after < 0) {
            logger.warn("[DBSYNCER] ADD COLUMN ... AFTER [{}] on {}.{}: reference column not found,"
                            + " appending at the end (row decoding may misalign)",
                    position.afterColumn(), source.schema, source.table);
            return source.columns.size();
        }
        return after + 1;
    }

    private void repositionSource(TableMapping mapping, String column, PositionClause position) {
        if (!position.specified()) {
            return;
        }
        List<SourceTable.SourceColumn> columns = mapping.source().columns;
        int current = indexOfColumn(columns, column);
        if (current < 0) {
            return;
        }
        SourceTable.SourceColumn moved = columns.remove(current);
        int target;
        if (position.first()) {
            target = 0;
        } else {
            int after = indexOfColumn(columns, position.afterColumn());
            if (after < 0) {
                logger.warn("[DBSYNCER][{}] ALTER TABLE ... AFTER [{}] on {}.{}: reference column not found,"
                                + " keeping the column at the end",
                        mapping.jobName(), position.afterColumn(),
                        mapping.source().schema, mapping.source().table);
                target = columns.size();
            } else {
                target = after + 1;
            }
        }
        columns.add(Math.min(target, columns.size()), moved);
    }

    private static int indexOfColumn(List<SourceTable.SourceColumn> columns, String name) {
        if (name == null) {
            return -1;
        }
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name.equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitClauses(String text) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                result.add(text.substring(start, i));
                start = i + 1;
            }
        }
        result.add(text.substring(start));
        return result;
    }

    private static String[] tableParts(String token) {
        String value = token.trim();
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '`' || c == '"') {
                quote = c;
            } else if (c == '.') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        if (parts.size() >= 2) {
            return new String[]{parts.get(parts.size() - 2), parts.get(parts.size() - 1)};
        }
        return new String[]{null, parts.get(0)};
    }

    private static String firstToken(String text) {
        String value = text.trim();
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '`' || c == '"') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                return value.substring(0, i);
            }
        }
        return value;
    }

    private static String unquote(String value) {
        String result = value.trim();
        if (result.length() >= 2
                && ((result.startsWith("`") && result.endsWith("`"))
                || (result.startsWith("\"") && result.endsWith("\"")))) {
            result = result.substring(1, result.length() - 1);
        }
        return result.replace("``", "`").replace("\"\"", "\"");
    }

    private static String trim(String sql) {
        String value = stripComments(sql).trim();
        while (value.endsWith(";")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    /**
     * 去掉 {@code /* ... *}{@code /} 注释（引号内的内容原样保留）。
     *
     * <p>MySQL 8 会在 binlog 里给服务端生成的 DDL 追加 {@code /* generated by server *}{@code /}，
     * 不去掉会污染最后一个标识符，导致 DROP TABLE 被当成"未映射的表"静默跳过。</p>
     */
    private static String stripComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                out.append(c);
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    break;
                }
                out.append(' ');
                i = end + 1;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
