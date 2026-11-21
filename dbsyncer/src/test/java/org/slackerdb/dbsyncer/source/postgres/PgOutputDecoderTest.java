package org.slackerdb.dbsyncer.source.postgres;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgOutputDecoderTest {

    private final List<Map<String, Object>> inserts = new ArrayList<>();
    private final List<Map<String, Object>> updates = new ArrayList<>();
    private final List<Map<String, Object>> deletes = new ArrayList<>();
    private final List<String> truncated = new ArrayList<>();
    private long commitEndLsn;

    private final PgOutputDecoder decoder = new PgOutputDecoder();

    @Test
    void decodesInsertWithTypes() {
        PgOutputDecoder.Callback callback = callback();
        decoder.decode('R', relation(1, "public", "t", 20, "id", 25, "name"), callback);
        decoder.decode('I', insert(1, 7L, "alice"), callback);
        decoder.decode('C', commit(12345L, 12399L), callback);

        assertEquals(1, inserts.size());
        assertEquals(7L, inserts.get(0).get("id"));
        assertEquals("alice", inserts.get(0).get("name"));
        // 检查点用事务结束 LSN（不是 commit LSN），否则重启会重放刚提交的事务
        assertEquals(12399L, commitEndLsn);
    }

    @Test
    void decodesUpdateWithBeforeAndMissingToastColumn() {
        PgOutputDecoder.Callback callback = callback();
        decoder.decode('R', relation(1, "public", "t", 20, "id", 25, "name"), callback);
        decoder.decode('U', update(1, 7L, "alice", 7L), callback);
        assertEquals(1, updates.size());
        assertEquals(7L, updates.get(0).get("id"));
        assertTrue(updates.get(0).containsKey("name"));
    }

    @Test
    void decodesDelete() {
        PgOutputDecoder.Callback callback = callback();
        decoder.decode('R', relation(1, "public", "t", 20, "id", 25, "name"), callback);
        decoder.decode('D', delete(1, 7L), callback);
        assertEquals(1, deletes.size());
        assertEquals(7L, deletes.get(0).get("id"));
    }

    @Test
    void decodesTruncateWithMultipleRelations() {
        PgOutputDecoder.Callback callback = callback();
        decoder.decode('R', relation(1, "public", "a", 20, "id", 25, "name"), callback);
        decoder.decode('R', relation(2, "public", "b", 20, "id", 25, "name"), callback);
        decoder.decode('T', truncate(1, 2), callback);

        assertEquals(List.of("public.a", "public.b"), truncated);
    }

    private PgOutputDecoder.Callback callback() {
        return new PgOutputDecoder.Callback() {
            @Override
            public void onInsert(PgOutputDecoder.RelationInfo relation, Map<String, Object> after) {
                inserts.add(after);
            }

            @Override
            public void onUpdate(PgOutputDecoder.RelationInfo relation, Map<String, Object> before,
                                 Map<String, Object> after, Set<String> missingColumns) {
                updates.add(after);
            }

            @Override
            public void onDelete(PgOutputDecoder.RelationInfo relation, Map<String, Object> before) {
                deletes.add(before);
            }

            @Override
            public void onTruncate(List<PgOutputDecoder.RelationInfo> relations, boolean cascade) {
                for (PgOutputDecoder.RelationInfo relation : relations) {
                    truncated.add(relation.schema + "." + relation.table);
                }
            }

            @Override
            public void onBegin(long finalLsn) {
            }

            @Override
            public void onCommit(long endLsn) {
                commitEndLsn = endLsn;
            }
        };
    }

    private static ByteBuffer relation(long id, String schema, String table, int idOid, String idName,
                                       int nameOid, String name) {
        ByteBuffer buffer = ByteBuffer.allocate(256);
        buffer.putInt((int) id);
        cstring(buffer, schema);
        cstring(buffer, table);
        buffer.put((byte) 'd');
        buffer.putShort((short) 2);
        addColumn(buffer, idName, idOid, true);
        addColumn(buffer, name, nameOid, false);
        buffer.flip();
        return buffer;
    }

    private static void addColumn(ByteBuffer buffer, String name, int oid, boolean key) {
        buffer.put((byte) (key ? 1 : 0));
        cstring(buffer, name);
        buffer.putInt(oid);
        buffer.putInt(-1);
    }

    private static ByteBuffer insert(long relationId, long id, String name) {
        ByteBuffer buffer = ByteBuffer.allocate(128);
        buffer.putInt((int) relationId);
        buffer.put((byte) 'N');
        buffer.putShort((short) 2);
        text(buffer, String.valueOf(id));
        text(buffer, name);
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer update(long relationId, long id, String name, long newId) {
        ByteBuffer buffer = ByteBuffer.allocate(160);
        buffer.putInt((int) relationId);
        buffer.put((byte) 'K');
        buffer.putShort((short) 1);
        text(buffer, String.valueOf(id));
        buffer.put((byte) 'N');
        buffer.putShort((short) 2);
        text(buffer, String.valueOf(newId));
        text(buffer, name);
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer delete(long relationId, long id) {
        ByteBuffer buffer = ByteBuffer.allocate(64);
        buffer.putInt((int) relationId);
        buffer.put((byte) 'K');
        buffer.putShort((short) 1);
        text(buffer, String.valueOf(id));
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer commit(long commitLsn, long endLsn) {
        ByteBuffer buffer = ByteBuffer.allocate(32);
        buffer.put((byte) 0);
        buffer.putLong(commitLsn);
        buffer.putLong(endLsn);
        buffer.putLong(System.currentTimeMillis());
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer truncate(long... relationIds) {
        ByteBuffer buffer = ByteBuffer.allocate(16 + relationIds.length * 4);
        buffer.putInt(relationIds.length);
        buffer.put((byte) 0);
        for (long relationId : relationIds) {
            buffer.putInt((int) relationId);
        }
        buffer.flip();
        return buffer;
    }

    private static void cstring(ByteBuffer buffer, String value) {
        buffer.put(value.getBytes(StandardCharsets.UTF_8));
        buffer.put((byte) 0);
    }

    private static void text(ByteBuffer buffer, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        buffer.put((byte) 't');
        buffer.putInt(bytes.length);
        buffer.put(bytes);
    }
}
