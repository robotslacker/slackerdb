package org.slackerdb.dbsyncer.target;

import org.slackerdb.dbsyncer.config.SyncConfig;
import org.slackerdb.dbsyncer.model.SourceTable;
import org.slackerdb.dbsyncer.model.TableMapping;
import org.slackerdb.dbsyncer.model.TargetTable;
import org.slackerdb.dbsyncer.model.TypeMapper;
import org.slackerdb.dbsyncer.util.TablePattern;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 解析 include/exclude 规则、读取源表元数据、生成目标表结构。
 */
public record TableRouter(SyncConfig config, SyncConfig.ConnectionConfig connection,
                          SyncConfig.TargetConfig targetConfig, SyncConfig.JobConfig job,
                          String targetCatalog) {

    private static final Pattern TARGET_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,127}");

    public List<TableMapping> resolve(Connection sourceConnection) throws SQLException {
        RuleSet rules = RuleSet.compile(job.tables);
        List<TableMapping> mappings = new ArrayList<>();
        for (SourceRef ref : listTables(sourceConnection)) {
            String fullName = ref.schema + "." + ref.table;
            if (rules.excluded(fullName)) {
                continue;
            }
            RuleMatch rule = rules.ruleFor(fullName);
            if (rule == null) {
                continue;
            }
            SourceTable source = loadSourceTable(sourceConnection, ref.schema, ref.table);
            if (source.columns.isEmpty()) {
                continue;
            }
            mappings.add(buildMapping(source, rule));
        }
        if (mappings.isEmpty()) {
            throw new SQLException("no source table matched include/exclude rules for job [" + job.name + "]");
        }
        return mappings;
    }

    /**
     * 单表解析：DDL 动态建表/改表时使用。
     */
    public TableMapping resolveOne(Connection sourceConnection, String schema, String table)
            throws SQLException {
        RuleSet rules = RuleSet.compile(job.tables);
        String fullName = schema + "." + table;
        if (rules.excluded(fullName)) {
            return null;
        }
        RuleMatch rule = rules.ruleFor(fullName);
        if (rule == null) {
            return null;
        }
        SourceTable source = loadSourceTable(sourceConnection, schema, table);
        if (source.columns.isEmpty()) {
            return null;
        }
        return buildMapping(source, rule);
    }

    private List<SourceRef> listTables(Connection connection) throws SQLException {
        boolean mysql = this.connection.type == SyncConfig.SourceType.MYSQL;
        String sql;
        if (mysql) {
            sql = "SELECT table_schema, table_name FROM information_schema.tables"
                    + " WHERE table_type='BASE TABLE'"
                    + " AND table_schema NOT IN ('mysql','information_schema','performance_schema','sys')"
                    + " ORDER BY table_schema, table_name";
        } else {
            sql = "SELECT table_schema, table_name FROM information_schema.tables"
                    + " WHERE table_type='BASE TABLE'"
                    + " AND table_schema NOT IN ('pg_catalog','information_schema')"
                    + " ORDER BY table_schema, table_name";
        }
        List<SourceRef> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                result.add(new SourceRef(rs.getString(1), rs.getString(2)));
            }
        }
        return result;
    }

    private SourceTable loadSourceTable(Connection connection, String schema, String table)
            throws SQLException {
        boolean mysql = this.connection.type == SyncConfig.SourceType.MYSQL;
        DatabaseMetaData metaData = connection.getMetaData();
        SourceTable source = new SourceTable(schema, table, mysql ? "mysql" : "postgres");

        String catalogArg = mysql ? schema : null;
        String schemaArg = mysql ? null : schema;
        try (ResultSet rs = metaData.getColumns(catalogArg, schemaArg, table, "%")) {
            while (rs.next()) {
                SourceTable.SourceColumn column = new SourceTable.SourceColumn(
                        rs.getString("COLUMN_NAME"),
                        rs.getString("TYPE_NAME"),
                        rs.getInt("DATA_TYPE"),
                        rs.getInt("COLUMN_SIZE"),
                        rs.getInt("DECIMAL_DIGITS")
                );
                try {
                    String generated = rs.getString("IS_GENERATEDCOLUMN");
                    column.generated = ("YES".equalsIgnoreCase(generated) || "1".equals(generated));
                } catch (SQLException ignored) {
                    // 某些驱动没有该列
                }
                source.columns.add(column);
            }
        }
        if (mysql) {
            enrichMySqlColumns(connection, schema, table, source);
        }

        Map<Integer, String> primaryKeys = new LinkedHashMap<>();
        try (ResultSet rs = metaData.getPrimaryKeys(catalogArg, schemaArg, table)) {
            while (rs.next()) {
                primaryKeys.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        List<Integer> sequences = new ArrayList<>(primaryKeys.keySet());
        sequences.sort(Integer::compareTo);
        for (Integer sequence : sequences) {
            source.primaryKey.add(primaryKeys.get(sequence));
        }
        return source;
    }

    private void enrichMySqlColumns(Connection connection, String schema, String table, SourceTable source)
            throws SQLException {
        String sql = "SELECT column_name, column_type, character_set_name FROM information_schema.columns"
                + " WHERE table_schema=? AND table_name=?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    SourceTable.SourceColumn column = source.column(rs.getString(1));
                    if (column != null) {
                        column.typeDefinition = rs.getString(2);
                        column.characterSet = rs.getString(3);
                    }
                }
            }
        }
    }

    private TableMapping buildMapping(SourceTable source, RuleMatch ruleMatch) throws SQLException {
        SyncConfig.TableRule rule = ruleMatch.rule();
        String targetName;
        try {
            targetName = ruleMatch.pattern().expandTarget(rule.target, source.table,
                    source.qualifiedName());
        } catch (IllegalArgumentException e) {
            throw new SQLException("invalid target table pattern for " + source.qualifiedName() + ": "
                    + e.getMessage(), e);
        }
        if (!TARGET_IDENT.matcher(targetName).matches()) {
            throw new SQLException("invalid target table name [" + targetName + "] for "
                    + source.qualifiedName());
        }
        TargetTable target = new TargetTable(targetCatalog, targetConfig.schema, targetName);
        Map<String, String> rename = lowerKeys(rule.columns.rename);
        Map<String, String> types = lowerKeys(rule.columns.types);

        Set<String> targetColumns = new HashSet<>();
        List<SourceTable.SourceColumn> selected = selectedColumns(source, rule.columns);
        for (SourceTable.SourceColumn column : selected) {
            String targetColumn = rename.getOrDefault(column.name.toLowerCase(Locale.ROOT), column.name);
            requireUniqueColumn(targetColumns, targetColumn, source);
            String duckType = types.getOrDefault(targetColumn.toLowerCase(Locale.ROOT),
                    TypeMapper.map(source.dbType, column));
            target.add(new TargetTable.TargetColumn(targetColumn, duckType, column.name, null));
        }
        for (Map.Entry<String, String> entry : rule.columns.constants.entrySet()) {
            String targetColumn = entry.getKey();
            requireUniqueColumn(targetColumns, targetColumn, source);
            String duckType = types.getOrDefault(targetColumn.toLowerCase(Locale.ROOT),
                    inferConstantType(entry.getValue()));
            target.add(new TargetTable.TargetColumn(targetColumn, duckType, null, entry.getValue()));
        }

        List<String> sourcePk = rule.primaryKey.isEmpty() ? source.primaryKey : rule.primaryKey;
        for (String pk : sourcePk) {
            if (source.column(pk) == null) {
                throw new SQLException("primary key column [" + pk + "] not found in "
                        + source.qualifiedName());
            }
            String targetColumn = rename.getOrDefault(pk.toLowerCase(Locale.ROOT), pk);
            if (target.target(targetColumn) == null) {
                throw new SQLException("primary key column [" + pk + "] is excluded from target "
                        + target.qualifiedName());
            }
            target.primaryKey.add(target.target(targetColumn).name());
        }

        return new TableMapping(source, target, job.name);
    }

    private static void requireUniqueColumn(Set<String> seen, String targetColumn, SourceTable source)
            throws SQLException {
        if (!seen.add(targetColumn.toLowerCase(Locale.ROOT))) {
            throw new SQLException("duplicate target column [" + targetColumn + "] for "
                    + source.qualifiedName() + "; check columns.rename / columns.constants");
        }
    }

    private List<SourceTable.SourceColumn> selectedColumns(SourceTable source, SyncConfig.ColumnRule columns) {
        List<String> includes = new ArrayList<>();
        for (String value : columns.include) {
            includes.add(value.toLowerCase(Locale.ROOT));
        }
        List<String> excludes = new ArrayList<>();
        for (String value : columns.exclude) {
            excludes.add(value.toLowerCase(Locale.ROOT));
        }
        List<SourceTable.SourceColumn> result = new ArrayList<>();
        for (SourceTable.SourceColumn column : source.columns) {
            String lower = column.name.toLowerCase(Locale.ROOT);
            if (column.generated) {
                continue;
            }
            if (!includes.isEmpty() && !includes.contains(lower)) {
                continue;
            }
            if (excludes.contains(lower)) {
                continue;
            }
            result.add(column);
        }
        return result;
    }

    private Map<String, String> lowerKeys(Map<String, String> source) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            result.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }
        return result;
    }

    /**
     * 常量列的类型推导（配置没写 {@code columns.types} 时用）。
     *
     * <p>YAML 里的数字/布尔在模型里已经是字符串了，这里按字面量形状推断：
     * 整数 → BIGINT（超出 long 用 DECIMAL(38,0)）、小数 → DECIMAL(p,s)、科学计数法 → DOUBLE、
     * true/false → BOOLEAN，其余 → VARCHAR。需要别的类型时用 {@code columns.types} 覆盖。</p>
     */
    static String inferConstantType(String value) {
        if (value == null) {
            return "VARCHAR";
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return "VARCHAR";
        }
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return "BOOLEAN";
        }
        if (text.matches("[+-]?\\d+")) {
            try {
                Long.parseLong(text);
                return "BIGINT";
            } catch (NumberFormatException e) {
                return text.replaceFirst("^[+-]", "").length() <= 38 ? "DECIMAL(38,0)" : "VARCHAR";
            }
        }
        if (text.matches("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")) {
            if (text.contains("e") || text.contains("E")) {
                return "DOUBLE";
            }
            String unsigned = text.replaceFirst("^[+-]", "");
            int dot = unsigned.indexOf('.');
            int precision = dot < 0 ? unsigned.length() : unsigned.length() - 1;
            int scale = dot < 0 ? 0 : unsigned.length() - dot - 1;
            if (precision <= 38) {
                return "DECIMAL(" + precision + "," + scale + ")";
            }
            return "DOUBLE";
        }
        return "VARCHAR";
    }

    private record SourceRef(String schema, String table) {
    }

    private record RuleMatch(SyncConfig.TableRule rule, TablePattern pattern) {
    }

    /** include/exclude 规则编译结果；表匹配与排除共用同一套 {@link TablePattern} 语义。 */
    private record RuleSet(List<RuleMatch> includes, List<TablePattern> excludes) {

        static RuleSet compile(SyncConfig.TablesConfig tables) {
            List<RuleMatch> includes = new ArrayList<>();
            for (SyncConfig.TableRule rule : tables.include) {
                try {
                    includes.add(new RuleMatch(rule,
                            TablePattern.compile(rule.match, tables.caseSensitive)));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("invalid include match [" + rule.match + "]: "
                            + e.getMessage(), e);
                }
            }
            List<TablePattern> excludes = new ArrayList<>();
            for (String exclude : tables.exclude) {
                try {
                    excludes.add(TablePattern.compile(exclude, tables.caseSensitive));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("invalid exclude match [" + exclude + "]: "
                            + e.getMessage(), e);
                }
            }
            return new RuleSet(includes, excludes);
        }

        boolean excluded(String fullName) {
            for (TablePattern pattern : excludes) {
                if (pattern.matches(fullName)) {
                    return true;
                }
            }
            return false;
        }

        RuleMatch ruleFor(String fullName) {
            for (RuleMatch match : includes) {
                if (match.pattern().matches(fullName)) {
                    return match;
                }
            }
            return null;
        }
    }
}
