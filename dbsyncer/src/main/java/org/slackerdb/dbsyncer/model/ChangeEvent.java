package org.slackerdb.dbsyncer.model;

import java.util.Map;
import java.util.Set;

/**
 * 一条逻辑变更。before/after 使用源列名；目标列映射在写入阶段完成。
 */
public record ChangeEvent(Op op, String sourceSchema, String sourceTable, Map<String, Object> before,
                          Map<String, Object> after, Set<String> missingColumns, long timestamp) {

    public enum Op {
        INSERT, UPDATE, DELETE, TRUNCATE
    }

    public ChangeEvent(Op op, String sourceSchema, String sourceTable, Map<String, Object> before,
                       Map<String, Object> after, Set<String> missingColumns, long timestamp) {
        this.op = op;
        this.sourceSchema = sourceSchema;
        this.sourceTable = sourceTable;
        this.before = before == null ? Map.of() : before;
        this.after = after == null ? Map.of() : after;
        this.missingColumns = missingColumns == null ? Set.of() : missingColumns;
        this.timestamp = timestamp;
    }

    public static ChangeEvent insert(String schema, String table, Map<String, Object> after, long timestamp) {
        return new ChangeEvent(Op.INSERT, schema, table, Map.of(), after, Set.of(), timestamp);
    }

    public static ChangeEvent update(String schema, String table, Map<String, Object> before,
                                     Map<String, Object> after, Set<String> missing, long timestamp) {
        return new ChangeEvent(Op.UPDATE, schema, table, before, after, missing, timestamp);
    }

    public static ChangeEvent delete(String schema, String table, Map<String, Object> before, long timestamp) {
        return new ChangeEvent(Op.DELETE, schema, table, before, Map.of(), Set.of(), timestamp);
    }

    public static ChangeEvent truncate(String schema, String table, long timestamp) {
        return new ChangeEvent(Op.TRUNCATE, schema, table, Map.of(), Map.of(), Set.of(), timestamp);
    }


}
