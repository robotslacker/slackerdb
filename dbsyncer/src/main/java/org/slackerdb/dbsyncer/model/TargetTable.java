package org.slackerdb.dbsyncer.model;

import org.slackerdb.dbsyncer.util.SqlIdentifiers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 目标 DuckDB 表结构（作业内解析一次，运行期复用）。 */
public final class TargetTable {

    public final String catalog;
    public final String schema;
    public final String table;
    public final List<TargetColumn> columns = new ArrayList<>();
    public final List<String> primaryKey = new ArrayList<>();
    public final Map<String, TargetColumn> byTargetName = new LinkedHashMap<>();

    public TargetTable(String catalog, String schema, String table) {
        this.catalog = catalog;
        this.schema = schema;
        this.table = table;
    }

    public String qualifiedName() {
        return SqlIdentifiers.qualified(catalog, schema, table);
    }

    public void add(TargetColumn column) {
        columns.add(column);
        byTargetName.put(column.name.toLowerCase(Locale.ROOT), column);
    }

    public void remove(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        byTargetName.remove(key);
        columns.removeIf(column -> column.name.equalsIgnoreCase(name));
        primaryKey.removeIf(pk -> pk.equalsIgnoreCase(name));
    }

    public void rename(String oldName, String newName) {
        String key = oldName.toLowerCase(Locale.ROOT);
        TargetColumn existing = byTargetName.remove(key);
        if (existing == null) {
            return;
        }
        String sourceName = existing.sourceName == null ? null
                : existing.sourceName.equalsIgnoreCase(oldName) ? newName : existing.sourceName;
        TargetColumn renamed = new TargetColumn(newName, existing.duckType, sourceName, existing.constant);
        columns.replaceAll(column -> column == existing ? renamed : column);
        byTargetName.put(newName.toLowerCase(Locale.ROOT), renamed);
        for (int i = 0; i < primaryKey.size(); i++) {
            if (primaryKey.get(i).equalsIgnoreCase(oldName)) {
                primaryKey.set(i, newName);
            }
        }
    }

    public TargetColumn target(String name) {
        return byTargetName.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * @param sourceName null 表示常量列。
     */
    public record TargetColumn(String name, String duckType, String sourceName, Object constant) {
    }
}
