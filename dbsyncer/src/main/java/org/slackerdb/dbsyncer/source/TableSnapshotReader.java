package org.slackerdb.dbsyncer.source;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快照读取：在一张表的查询结果上流式分块回调。
 *
 * <p>PostgreSQL / MySQL 的快照共用同一套分块逻辑，只在 SQL 引号风格上不同，避免两处各写一遍
 * "读行 + 攒块 + 尾块回调"。块大小直接取 {@code snapshot.fetchSize}：它同时决定 JDBC fetch size
 * 和每块提交的行数（块越大提交次数越少）。</p>
 */
public final class TableSnapshotReader {

    private TableSnapshotReader() {
    }

    @FunctionalInterface
    public interface RowConsumer {
        void accept(TableMappingRef table, List<Map<String, Object>> rows, long totalRows) throws Exception;
    }

    /**
     * @param sql 已按源库方言引用好标识符的 {@code SELECT *} 语句
     * @return 读取到的总行数
     */
    public static long read(java.sql.Connection connection, TableMappingRef table, String sql,
                            int fetchSize, RowConsumer consumer) throws Exception {
        int chunkSize = Math.max(1, fetchSize);
        long total = 0;
        try (Statement statement = connection.createStatement()) {
            statement.setFetchSize(Math.max(1, fetchSize));
            try (ResultSet rs = statement.executeQuery(sql)) {
                List<Map<String, Object>> rows = new ArrayList<>(chunkSize);
                while (rs.next()) {
                    rows.add(readRow(rs));
                    total++;
                    if (rows.size() >= chunkSize) {
                        consumer.accept(table, rows, total);
                        rows = new ArrayList<>(chunkSize);
                    }
                }
                if (!rows.isEmpty()) {
                    consumer.accept(table, rows, total);
                }
            }
        }
        return total;
    }

    /** 一行 → 列名到值的映射；数组统一转文本，其余保持 JDBC 原生类型。 */
    public static Map<String, Object> readRow(ResultSet rs) throws SQLException {
        var metadata = rs.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            Object value = rs.getObject(i);
            if (value instanceof java.sql.Array array) {
                value = array.toString();
            }
            row.put(metadata.getColumnName(i), value);
        }
        return row;
    }
}
