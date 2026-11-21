package org.slackerdb.dbsyncer.source.postgres;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * pgoutput 协议解码器（proto_version = 1）。
 *
 * <p>只负责把 WAL 消息解析成按列名索引的 before/after；表结构/目标映射在别处处理。</p>
 */
public final class PgOutputDecoder {

    private static final Logger log = LoggerFactory.getLogger(PgOutputDecoder.class);

    public interface Callback {
        void onInsert(RelationInfo relation, Map<String, Object> after);

        void onUpdate(RelationInfo relation, Map<String, Object> before, Map<String, Object> after,
                      Set<String> missingColumns);

        void onDelete(RelationInfo relation, Map<String, Object> before);

        void onTruncate(List<RelationInfo> relations, boolean cascade);

        void onBegin(long finalLsn);

        void onCommit(long endLsn);

        default void onRelation(RelationInfo relation) {
        }
    }

    public static final class RelationInfo {
        public final long relationId;
        public final String schema;
        public final String table;
        public final List<ColumnInfo> columns = new ArrayList<>();

        RelationInfo(long relationId, String schema, String table) {
            this.relationId = relationId;
            this.schema = schema;
            this.table = table;
        }
    }

    public record ColumnInfo(String name, boolean key, int typeOid) {
    }

    private final Map<Long, RelationInfo> relations = new HashMap<>();

    public void decode(char messageType, ByteBuffer buffer, Callback callback) {
        switch (messageType) {
            case 'R' -> decodeRelation(buffer, callback);
            case 'I' -> decodeInsert(buffer, callback);
            case 'U' -> decodeUpdate(buffer, callback);
            case 'D' -> decodeDelete(buffer, callback);
            case 'T' -> decodeTruncate(buffer, callback);
            case 'B' -> decodeBegin(buffer, callback);
            case 'C' -> decodeCommit(buffer, callback);
            case 'Y', 'O' -> {
                // Type / Origin: ignore
            }
            default -> log.debug("ignoring pgoutput message type {}", messageType);
        }
    }

    private void decodeRelation(ByteBuffer buffer, Callback callback) {
        long relationId = readInt32(buffer);
        String schema = readCString(buffer);
        String table = readCString(buffer);
        buffer.get(); // replica identity
        int columnCount = readInt16(buffer) & 0xFFFF;
        RelationInfo relation = new RelationInfo(relationId, schema, table);
        for (int i = 0; i < columnCount; i++) {
            boolean key = (buffer.get() & 0x01) != 0;
            String name = readCString(buffer);
            int typeOid = readInt32(buffer);
            readInt32(buffer); // type modifier
            relation.columns.add(new ColumnInfo(name, key, typeOid));
        }
        relations.put(relationId, relation);
        callback.onRelation(relation);
    }

    private void decodeInsert(ByteBuffer buffer, Callback callback) {
        long relationId = readInt32(buffer);
        RelationInfo relation = relations.get(relationId);
        if (relation == null) {
            return;
        }
        char tag = (char) (buffer.get() & 0xFF);
        if (tag != 'N') {
            return;
        }
        Tuple tuple = readTuple(buffer, relation);
        callback.onInsert(relation, tuple.values);
    }

    private void decodeUpdate(ByteBuffer buffer, Callback callback) {
        long relationId = readInt32(buffer);
        RelationInfo relation = relations.get(relationId);
        if (relation == null) {
            return;
        }
        Map<String, Object> before = null;
        char tag = (char) (buffer.get() & 0xFF);
        if (tag == 'K' || tag == 'O') {
            before = readTuple(buffer, relation).values;
            tag = (char) (buffer.get() & 0xFF);
        }
        if (tag != 'N') {
            return;
        }
        Tuple after = readTuple(buffer, relation);
        callback.onUpdate(relation, before == null ? Map.of() : before, after.values, after.missing);
    }

    private void decodeDelete(ByteBuffer buffer, Callback callback) {
        long relationId = readInt32(buffer);
        RelationInfo relation = relations.get(relationId);
        if (relation == null) {
            return;
        }
        char tag = (char) (buffer.get() & 0xFF);
        if (tag != 'K' && tag != 'O') {
            return;
        }
        Tuple before = readTuple(buffer, relation);
        callback.onDelete(relation, before.values);
    }

    private void decodeTruncate(ByteBuffer buffer, Callback callback) {
        int count = readInt32(buffer);
        // pgoutput 的 Truncate 只有**一个** option 字节（0x01 CASCADE / 0x02 RESTART IDENTITY），
        // 后面紧跟 count 个 Relation OID，没有逐表的 option。
        byte options = buffer.get();
        List<RelationInfo> targets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            RelationInfo relation = relations.get((long) readInt32(buffer));
            if (relation != null) {
                targets.add(relation);
            }
        }
        callback.onTruncate(targets, (options & 0x01) != 0);
    }

    private void decodeBegin(ByteBuffer buffer, Callback callback) {
        long finalLsn = readInt64(buffer);
        readInt64(buffer); // commit timestamp
        readInt32(buffer); // xid
        callback.onBegin(finalLsn);
    }

    private void decodeCommit(ByteBuffer buffer, Callback callback) {
        buffer.get(); // flags
        readInt64(buffer); // commit LSN（事务提交记录的位置）
        long endLsn = readInt64(buffer); // 事务结束 LSN
        readInt64(buffer); // commit timestamp
        // 检查点用事务结束 LSN：从它开始解码不会重放刚提交的事务
        // （INSERT 是 append，重放会撞主键）。
        callback.onCommit(endLsn);
    }

    private Tuple readTuple(ByteBuffer buffer, RelationInfo relation) {
        Map<String, Object> row = new LinkedHashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        int columnCount = readInt16(buffer) & 0xFFFF;
        for (int i = 0; i < columnCount; i++) {
            char tag = (char) (buffer.get() & 0xFF);
            ColumnInfo column = i < relation.columns.size() ? relation.columns.get(i) : null;
            String name = column == null ? "col_" + i : column.name;
            switch (tag) {
                case 'n' -> row.put(name, null);
                case 'u' -> // Unchanged TOAST value: not carried by logical replication.
                        missing.add(name);
                case 't', 'b' -> {
                    int length = readInt32(buffer);
                    byte[] data = new byte[length];
                    buffer.get(data);
                    String text = new String(data, StandardCharsets.UTF_8);
                    row.put(name, convert(column == null ? 0 : column.typeOid, text));
                }
                default -> {
                    log.warn("unknown tuple tag '{}' for {}.{}", tag, relation.schema, relation.table);
                    return new Tuple(row, missing);
                }
            }
        }
        return new Tuple(row, missing);
    }

    private Object convert(int oid, String text) {
        try {
            return switch (oid) {
                case 16 -> "t".equalsIgnoreCase(text) || "true".equalsIgnoreCase(text) || "1".equals(text);
                case 17 -> decodeBytea(text);
                case 20, 21, 23, 26 -> Long.parseLong(text.trim());
                case 700 -> Float.parseFloat(text.trim());
                case 701 -> Double.parseDouble(text.trim());
                case 1700 -> new BigDecimal(text.trim());
                case 1082 -> LocalDate.parse(text.trim());
                case 1083 -> LocalTime.parse(text.trim());
                case 1114 -> LocalDateTime.parse(text.trim().replace(' ', 'T'));
                case 1184 -> OffsetDateTime.parse(normalizeOffset(text.trim().replace(' ', 'T')));
                case 1266 -> text.trim();
                case 2950 -> java.util.UUID.fromString(text.trim());
                default -> text;
            };
        } catch (Exception e) {
            return text;
        }
    }

    private static byte[] decodeBytea(String text) {
        String value = text.trim();
        if (value.startsWith("\\x")) {
            value = value.substring(2);
        }
        if (value.length() % 2 != 0) {
            return value.getBytes(StandardCharsets.UTF_8);
        }
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < value.length(); i += 2) {
            result[i / 2] = (byte) Integer.parseInt(value.substring(i, i + 2), 16);
        }
        return result;
    }

    private static String normalizeOffset(String value) {
        int length = value.length();
        if (length >= 5) {
            char sign = value.charAt(length - 5);
            if ((sign == '+' || sign == '-') && Character.isDigit(value.charAt(length - 4))
                    && Character.isDigit(value.charAt(length - 3))
                    && Character.isDigit(value.charAt(length - 2))
                    && Character.isDigit(value.charAt(length - 1))) {
                return value.substring(0, length - 2) + ":" + value.substring(length - 2);
            }
        }
        return value;
    }

    private static int readInt16(ByteBuffer buffer) {
        return buffer.getShort();
    }

    private static int readInt32(ByteBuffer buffer) {
        return buffer.getInt();
    }

    private static long readInt64(ByteBuffer buffer) {
        return buffer.getLong();
    }

    private static String readCString(ByteBuffer buffer) {
        int start = buffer.position();
        while (buffer.hasRemaining() && buffer.get() != 0) {
            // scan
        }
        int end = buffer.position();
        int length = end - start - 1;
        byte[] bytes = new byte[length];
        buffer.position(start);
        buffer.get(bytes);
        buffer.position(end);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private record Tuple(Map<String, Object> values, Set<String> missing) {
    }
}
