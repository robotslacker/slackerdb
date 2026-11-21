package org.slackerdb.dbsyncer.model;


import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 源表 → 目标表的映射（列/Rename/常量/主键/冲突策略）。
 */
public record TableMapping(SourceTable source, TargetTable target, String jobName) {

    public String sourceKey() {
        return (source.schema + "." + source.table).toLowerCase(Locale.ROOT);
    }

    /**
     * 把源行映射为目标行（包含常量列；不含 metadata 列）。
     *
     * @param includeMissingColumns true 时缺失的源列写成 null；false 时只保留事件里真实出现的列，
     *                              用于 UPDATE 保留未随事件下发的列（如未变化的 TOAST 值）
     */
    public Map<String, Object> mapRow(Map<String, Object> sourceRow, boolean includeMissingColumns) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (TargetTable.TargetColumn column : target.columns) {
            if (column.sourceName() == null) {
                result.put(column.name(), column.constant());
                continue;
            }
            SourceTable.SourceColumn sourceColumn = source.column(column.sourceName());
            String key = sourceColumn == null ? column.sourceName() : sourceColumn.name;
            if (sourceRow.containsKey(key)) {
                result.put(column.name(), sourceRow.get(key));
            } else if (includeMissingColumns) {
                result.put(column.name(), null);
            }
        }
        return result;
    }

    /** 目标主键列对应的源列名；列不存在或为常量列时返回 null。 */
    public String sourceColumnOf(String targetColumn) {
        TargetTable.TargetColumn column = target.target(targetColumn);
        return column == null ? null : column.sourceName();
    }

    /** 目标主键是否来自源库/配置的主键列（而非常量列）。 */
    public boolean hasPrimaryKey() {
        return !target.primaryKey.isEmpty();
    }

    public String display() {
        return source.qualifiedName() + " -> " + target.qualifiedName();
    }

    /** 便于日志与调试。 */
    @Override
    public String toString() {
        return display();
    }
}
