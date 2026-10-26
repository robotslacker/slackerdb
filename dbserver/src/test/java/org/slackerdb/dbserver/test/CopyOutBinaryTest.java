package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.test.support.PgWireClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code COPY ... TO STDOUT WITH (FORMAT binary)} 的协议级验收。
 *
 * <p><b>为什么要解析原始字节</b>：二进制 COPY 的价值就在于"客户端拿到的字节必须符合
 * PostgreSQL 各类型的 {@code *send} 约定"。只看"导出成功"没有意义 —— 长度字段、字节序、
 * PG 纪元（2000-01-01）以及 numeric 的 base-10000 编码，任何一处写错都会让真实客户端
 * （psql / libpq / pgjdbc）读出错值或直接报错。</p>
 *
 * <p><b>为什么要往返</b>：{@link org.slackerdb.dbserver.sql.CopyOutBinaryEncoder}（导出）
 * 与 {@code CopyDoneRequest} 的 BINARY 解码（导入）必须互为逆运算。这里把整张表导出成
 * 二进制流、再原样 {@code COPY ... FROM STDIN (FORMAT binary)} 灌进同构表，逐值比较。</p>
 */
public class CopyOutBinaryTest {

    private static final byte[] PGCOPY_SIGNATURE = new byte[]{
            0x50, 0x47, 0x43, 0x4F, 0x50, 0x59, 0x0A, (byte) 0xFF, 0x0D, 0x0A, 0x00};

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("copyoutbin");
        cfg.setSqlHistory("OFF");
        cfg.setLog_level("INFO");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    private static PgWireClient connect() throws IOException {
        return new PgWireClient("127.0.0.1", dbPort, "copyoutbin", 15_000);
    }

    private static List<PgWireClient.Frame> query(PgWireClient client, String sql) throws IOException {
        client.sendQuery(sql);
        return client.readUntilReadyForQuery();
    }

    private static String commandTag(List<PgWireClient.Frame> frames) {
        List<PgWireClient.Frame> completes = PgWireClient.ofType(frames, 'C');
        return completes.isEmpty() ? null : completes.get(completes.size() - 1).text();
    }

    /** 把全部 CopyData 载荷按顺序拼成一次 COPY 的完整二进制流。 */
    private static byte[] copyPayload(List<PgWireClient.Frame> frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (PgWireClient.Frame frame : PgWireClient.ofType(frames, 'd')) {
            out.writeBytes(frame.body);
        }
        return out.toByteArray();
    }

    /** 解析后的二进制行：每个字段是原始字节，SQL NULL 用 {@code null} 表示。 */
    private static List<byte[]> parseRow(byte[] payload, int rowIndex) {
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.position(19);
        for (int i = 0; i < rowIndex; i++) {
            skipRow(buffer);
        }
        int columns = buffer.getShort();
        List<byte[]> fields = new ArrayList<>(columns);
        for (int i = 0; i < columns; i++) {
            int length = buffer.getInt();
            if (length == -1) {
                fields.add(null);
            } else {
                byte[] value = new byte[length];
                buffer.get(value);
                fields.add(value);
            }
        }
        return fields;
    }

    private static void skipRow(ByteBuffer buffer) {
        int columns = buffer.getShort();
        assertTrue(columns >= 0, "行头应为非负列数，实际=" + columns);
        for (int i = 0; i < columns; i++) {
            int length = buffer.getInt();
            if (length > 0) {
                buffer.position(buffer.position() + length);
            }
        }
    }

    private static long rowCount(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.position(19);
        long rows = 0;
        while (true) {
            int columns = buffer.getShort();
            if (columns == -1) {
                return rows;
            }
            for (int i = 0; i < columns; i++) {
                int length = buffer.getInt();
                if (length > 0) {
                    buffer.position(buffer.position() + length);
                }
            }
            rows++;
        }
    }

    private static long int16(byte[] value) {
        return ByteBuffer.wrap(value).getShort();
    }

    private static long int32(byte[] value) {
        return ByteBuffer.wrap(value).getInt();
    }

    private static long int64(byte[] value) {
        return ByteBuffer.wrap(value).getLong();
    }

    // ================================================================
    // 报文序列与头部
    // ================================================================

    @Test
    void binaryCopyOutEmitsCorrectProtocolSequence() throws Exception {
        try (PgWireClient client = connect()) {
            query(client, "drop table if exists cb;");
            query(client, "create table cb(id int, nm varchar);");
            query(client, "insert into cb values (1,'a'),(2,'b'),(3,NULL);");

            List<PgWireClient.Frame> frames = query(client, "copy cb to stdout with (format binary);");
            String types = PgWireClient.types(frames);

            assertTrue(types.startsWith("H"), "应以 CopyOutResponse('H') 开头，实际=" + types);
            assertTrue(types.contains("d"), "应含 CopyData('d')，实际=" + types);
            assertTrue(types.contains("c"), "应含 CopyDone('c')，实际=" + types);
            assertEquals('Z', types.charAt(types.length() - 1), "应以 ReadyForQuery 收尾，实际=" + types);
            assertEquals("COPY 3", commandTag(frames), "命令标签应为 COPY 3");

            // H 的报文体：int8 整体格式 + int16 列数 + 每列 int16 格式码
            PgWireClient.Frame response = PgWireClient.ofType(frames, 'H').get(0);
            assertEquals(1, response.body[0], "整体格式应为二进制(1)");
            assertEquals(2, response.int16(1), "cb 表有 2 列");
            assertEquals(1, response.int16(3), "第 1 列格式码应为二进制(1)");
            assertEquals(1, response.int16(5), "第 2 列格式码应为二进制(1)");
        }
    }

    @Test
    void extendedProtocolAlsoSupportsBinaryCopyOut() throws Exception {
        try (PgWireClient client = connect()) {
            query(client, "drop table if exists cb;");
            query(client, "create table cb(id int);");
            query(client, "insert into cb values (1),(2);");

            client.sendParse("", "copy cb to stdout with (format binary);");
            client.sendBind("", "");
            client.sendExecute("", 0);
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            String types = PgWireClient.types(frames);
            assertTrue(types.contains("H"), "扩展协议也应进入 COPY OUT，实际=" + types);
            assertEquals("COPY 2", commandTag(frames));
            PgWireClient.Frame response = PgWireClient.ofType(frames, 'H').get(0);
            assertEquals(1, response.body[0], "整体格式应为二进制(1)");
        }
    }

    @Test
    void binaryStreamCarriesPgCopyHeaderAndTrailer() throws Exception {
        try (PgWireClient client = connect()) {
            query(client, "drop table if exists cb;");
            query(client, "create table cb(id int);");
            query(client, "insert into cb values (1);");

            byte[] payload = copyPayload(query(client, "copy cb to stdout with (format binary);"));
            assertEquals(19 + 2 + 4 + 4 + 2, payload.length,
                    "19 字节头 + 1 行(2B 列数 + 4B 长度 + 4B 值) + 2B 结束标志");

            byte[] signature = new byte[11];
            System.arraycopy(payload, 0, signature, 0, 11);
            assertArrayEquals(PGCOPY_SIGNATURE, signature, "PGCOPY 签名必须逐字节一致");
            assertEquals(0, ByteBuffer.wrap(payload, 11, 4).getInt(), "flags 必须为 0（不带 OID）");
            assertEquals(0, ByteBuffer.wrap(payload, 15, 4).getInt(), "头部扩展区长度必须为 0");
            assertEquals((short) -1, ByteBuffer.wrap(payload, payload.length - 2, 2).getShort(),
                    "数据流必须以 Int16 -1 结束");
        }
    }

    // ================================================================
    // 各类型的字节形态（按 PostgreSQL 的 *send 约定）
    // ================================================================

    @Test
    void binaryEncodingMatchesPostgresWireFormat() throws Exception {
        try (PgWireClient client = connect()) {
            query(client, "drop table if exists cbt;");
            query(client, "create table cbt("
                    + "b boolean, i2 smallint, i4 integer, i8 bigint, "
                    + "f float, d double, dec decimal(10,2), v varchar, "
                    + "bl blob, u uuid, dt date, tm time, ts timestamp, tstz timestamptz);");
            query(client, "insert into cbt values ("
                    + "true, -2, -3, -4, "
                    + "1.5, 2.5, 12.34, 'hé', "
                    + "from_hex('CAFE'), '00112233-4455-6677-8899-aabbccddeeff', "
                    + "DATE '2000-01-02', TIME '00:00:01.000002', "
                    + "TIMESTAMP '2000-01-01 00:00:01.000003', "
                    + "TIMESTAMPTZ '2000-01-01 00:00:01.000004+00');");

            byte[] payload = copyPayload(query(client, "copy cbt to stdout with (format binary);"));
            List<byte[]> f = parseRow(payload, 0);

            assertArrayEquals(new byte[]{0x01}, f.get(0), "BOOLEAN 为 1 字节 0/1");
            assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xFE}, f.get(1), "SMALLINT 为 int2 大端");
            assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFD},
                    f.get(2), "INTEGER 为 int4 大端");
            assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFC},
                    f.get(3), "BIGINT 为 int8 大端");
            assertArrayEquals(new byte[]{0x3F, (byte) 0xC0, 0x00, 0x00}, f.get(4), "FLOAT 为 float4 大端");
            assertArrayEquals(new byte[]{0x40, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00},
                    f.get(5), "DOUBLE 为 float8 大端");
            // numeric: nDigits=2, weight=0, sign=0(+), dscale=2, digits=[12, 3400]
            assertArrayEquals(new byte[]{0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02,
                            0x00, 0x0C, 0x0D, 0x48},
                    f.get(6), "DECIMAL 为 PG numeric 的 base-10000 编码");
            assertArrayEquals("hé".getBytes(StandardCharsets.UTF_8), f.get(7), "VARCHAR 为原始 UTF-8");
            assertArrayEquals(new byte[]{(byte) 0xCA, (byte) 0xFE}, f.get(8), "BLOB 为原始字节");
            assertArrayEquals(new byte[]{0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                            (byte) 0x88, (byte) 0x99, (byte) 0xAA, (byte) 0xBB,
                            (byte) 0xCC, (byte) 0xDD, (byte) 0xEE, (byte) 0xFF},
                    f.get(9), "UUID 为 16 字节");
            assertEquals(1, int32(f.get(10)), "DATE 为自 2000-01-01 的天数");
            assertEquals(1_000_002L, int64(f.get(11)), "TIME 为自午夜的微秒");
            assertEquals(1_000_003L, int64(f.get(12)), "TIMESTAMP 为自 2000-01-01 的微秒");
            assertEquals(1_000_004L, int64(f.get(13)), "TIMESTAMPTZ 为 UTC 自 2000-01-01 的微秒");
        }
    }

    @Test
    void nullFieldsAreEncodedAsMinusOneLength() throws Exception {
        try (PgWireClient client = connect()) {
            List<PgWireClient.Frame> frames = query(client,
                    "copy (select 1::integer as a, null::varchar as b) to stdout with (format binary);");
            List<byte[]> row = parseRow(copyPayload(frames), 0);
            assertEquals(2, row.size());
            assertEquals(1, int32(row.get(0)));
            assertNull(row.get(1), "SQL NULL 的字段长度必须是 -1");
        }
    }

    @Test
    void emptyResultStillWritesHeaderAndTrailer() throws Exception {
        try (PgWireClient client = connect()) {
            List<PgWireClient.Frame> frames = query(client,
                    "copy (select 1::integer as a where false) to stdout with (format binary);");
            byte[] payload = copyPayload(frames);
            assertEquals(21, payload.length, "空结果也应只有 19 字节头 + 2 字节结束标志");
            assertEquals((short) -1, ByteBuffer.wrap(payload, 19, 2).getShort());
            assertEquals("COPY 0", commandTag(frames));

            PgWireClient.Frame response = PgWireClient.ofType(frames, 'H').get(0);
            assertEquals(1, response.body[0], "空结果的 CopyOutResponse 仍应声明二进制格式");
        }
    }

    // ================================================================
    // 往返：导出 → 以 BINARY 灌回同构表 → 逐值相等
    // ================================================================

    @Test
    void binaryRoundTripPreservesEverySupportedType() throws Exception {
        try (PgWireClient client = connect()) {
            String ddl = "create table rb_%s("
                    + "b boolean, i1 tinyint, i2 smallint, i4 integer, i8 bigint, "
                    + "u1 utinyint, u2 usmallint, u4 uinteger, u8 ubigint, "
                    + "h hugeint, uh uhugeint, f float, d double, dec decimal(18,3), "
                    + "v varchar, js json, en enum('a','b'), bl blob, uu uuid, "
                    + "dt date, tm time, ttz timetz, "
                    + "ts timestamp, tss timestamp_s, tsm timestamp_ms, tsn timestamp_ns, "
                    + "tstz timestamptz)";
            query(client, "drop table if exists rb_src;");
            query(client, "drop table if exists rb_dst;");
            query(client, String.format(ddl, "src"));
            query(client, String.format(ddl, "dst"));

            query(client, "insert into rb_src values ("
                    + "true, -5, -300, -70000, -5000000000, "
                    + "200, 60000, 4000000000, 18446744073709551615, "
                    + "170141183460469231731687303715884105727, "
                    + "340282366920938463463374607431768211455, "
                    + "1.5, 2.5, 123.450, "
                    + "'héllo', '{\"a\": 1}', 'b', from_hex('CAFE'), "
                    + "'00112233-4455-6677-8899-aabbccddeeff', "
                    + "DATE '2020-01-02', TIME '03:04:05.123456', TIMETZ '03:04:05.123456+08', "
                    + "TIMESTAMP '2020-01-02 03:04:05.123456', TIMESTAMP_S '2020-01-02 03:04:05', "
                    + "TIMESTAMP_MS '2020-01-02 03:04:05.123', "
                    + "TIMESTAMP_NS '2020-01-02 03:04:05.123456000', "
                    + "TIMESTAMPTZ '2020-01-02 03:04:05.123456+08');");
            // 全 NULL 行：每个字段的 -1 长度都要能被导入侧正确识别
            query(client, "insert into rb_src values ("
                    + "null,null,null,null,null,null,null,null,null,null,null,"
                    + "null,null,null,null,null,null,null,null,null,null,null,"
                    + "null,null,null,null,null);");

            byte[] payload = copyPayload(
                    query(client, "copy rb_src to stdout with (format binary);"));
            assertEquals(2, rowCount(payload), "导出应包含 2 行");

            copyInBytes(client, "copy rb_dst from stdin with (format binary);", payload);

            assertTablesEqual(client, "rb_src", "rb_dst");
        }
    }

    /** 负值 / 边界值：PG 纪元之前的时间戳、numeric 的负号、各整型的最小值都必须往返无损。 */
    @Test
    void binaryRoundTripHandlesNegativeAndBoundaryValues() throws Exception {
        try (PgWireClient client = connect()) {
            String ddl = "create table eb_%s("
                    + "i2 smallint, i4 integer, i8 bigint, h hugeint, uh uhugeint, "
                    + "dec decimal(18,3), dt date, tm time, ttz timetz, ts timestamp, tstz timestamptz)";
            query(client, "drop table if exists eb_src;");
            query(client, "drop table if exists eb_dst;");
            query(client, String.format(ddl, "src"));
            query(client, String.format(ddl, "dst"));
            query(client, "insert into eb_src values ("
                    + "-32768, -2147483648, -9223372036854775808, "
                    + "-170141183460469231731687303715884105728, 0, "
                    + "-123.450, DATE '1999-12-31', TIME '23:59:59.999999', "
                    + "TIMETZ '23:59:59.999999-05', "
                    + "TIMESTAMP '1999-12-31 23:59:59.999999', "
                    + "TIMESTAMPTZ '1969-12-31 23:59:59.999999+00');");

            byte[] payload = copyPayload(query(client, "copy eb_src to stdout with (format binary);"));
            copyInBytes(client, "copy eb_dst from stdin with (format binary);", payload);
            assertTablesEqual(client, "eb_src", "eb_dst");
        }
    }

    @Test
    void realPgJdbcCopyManagerCanConsumeBinaryCopyOut() throws Exception {
        try (PgWireClient client = connect()) {
            query(client, "drop table if exists cb;");
            query(client, "create table cb(id int, nm varchar);");
            query(client, "insert into cb values (1,'a'),(2,'b');");
        }

        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:" + dbPort + "/copyoutbin?user=test")) {
            org.postgresql.copy.CopyManager copyManager = new org.postgresql.copy.CopyManager(
                    connection.unwrap(org.postgresql.core.BaseConnection.class));
            org.postgresql.copy.CopyOut copyOut =
                    copyManager.copyOut("COPY cb TO STDOUT WITH (FORMAT binary)");

            ByteArrayOutputStream data = new ByteArrayOutputStream();
            byte[] chunk;
            while ((chunk = copyOut.readFromCopy()) != null) {
                data.writeBytes(chunk);
            }
            assertEquals(2, copyOut.getHandledRowCount(), "pgjdbc 应看到 COPY 2");

            byte[] payload = data.toByteArray();
            assertTrue(payload.length > 21, "应收到完整的二进制 COPY 流");
            byte[] signature = new byte[11];
            System.arraycopy(payload, 0, signature, 0, 11);
            assertArrayEquals(PGCOPY_SIGNATURE, signature);
        }
    }

    /** 列清单 / 子查询形态同样要按选出的列编码（列数来自结果集，不是表定义）。 */
    @Test
    void binaryCopyOutWorksWithColumnListAndSubquery() throws Exception {
        try (PgWireClient client = connect()) {
            query(client, "drop table if exists cb_cols;");
            query(client, "create table cb_cols(i integer, s varchar, d date);");
            query(client, "insert into cb_cols values (1,'x',DATE '2020-01-01');");

            PgWireClient.Frame response = PgWireClient.ofType(
                    query(client, "copy cb_cols(s, i) to stdout with (format binary);"), 'H').get(0);
            assertEquals(2, response.int16(1), "只应导出列清单里的 2 列");

            List<PgWireClient.Frame> frames = query(client,
                    "copy (select count(*) as n from cb_cols) to stdout with (format binary);");
            List<byte[]> row = parseRow(copyPayload(frames), 0);
            assertEquals(8, row.get(0).length, "聚合结果应按 BIGINT(int8) 编码");
            assertEquals(1L, int64(row.get(0)));
        }
    }

    // ================================================================
    // 错误路径：不支持的类型必须在发数据之前拒绝
    // ================================================================

    @Test
    void unsupportedTypeIsRejectedBeforeAnyCopyData() throws Exception {
        try (PgWireClient client = connect()) {
            query(client, "drop table if exists cb_interval;");
            query(client, "create table cb_interval(i integer, iv interval);");
            query(client, "insert into cb_interval values (1, INTERVAL '1 day');");

            List<PgWireClient.Frame> frames =
                    query(client, "copy cb_interval to stdout with (format binary);");
            assertEquals("0A000", PgWireClient.errorField(frames, 'C'));
            assertTrue(PgWireClient.types(frames).indexOf('H') < 0,
                    "类型不支持时不应发 CopyOutResponse，实际=" + PgWireClient.types(frames));
            assertTrue(PgWireClient.types(frames).indexOf('d') < 0,
                    "类型不支持时不应发任何 CopyData，实际=" + PgWireClient.types(frames));
            assertEquals('I', PgWireClient.readyStatus(frames), "出错后必须回 ReadyForQuery");
        }
    }

    @Test
    void compositeTypeIsRejectedWithActionableMessage() throws Exception {
        try (PgWireClient client = connect()) {
            query(client, "drop table if exists cb_list;");
            query(client, "create table cb_list(v integer[]);");

            List<PgWireClient.Frame> frames =
                    query(client, "copy cb_list to stdout with (format binary);");
            assertEquals("0A000", PgWireClient.errorField(frames, 'C'));
            assertNotNull(PgWireClient.errorField(frames, 'M'));
            assertTrue(PgWireClient.errorField(frames, 'M').contains("FORMAT csv"),
                    "错误信息应告诉客户端改用 csv/text，实际=" + PgWireClient.errorField(frames, 'M'));
        }
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 通过 COPY IN 子协议把二进制载荷灌回去（先等 CopyInResponse，发完再等 ReadyForQuery）。 */
    private static void copyInBytes(PgWireClient client, String copySql, byte[] payload)
            throws IOException {
        client.sendQuery(copySql);

        List<PgWireClient.Frame> started = new ArrayList<>();
        while (true) {
            PgWireClient.Frame frame = client.readFrame();
            assertNotNull(frame, "连接在 CopyInResponse 之前关闭");
            started.add(frame);
            if (frame.type == 'G' || frame.type == 'E' || frame.type == 'Z') {
                break;
            }
        }
        assertEquals('G', started.get(started.size() - 1).type,
                "COPY FROM STDIN 应回 CopyInResponse('G')，实际=" + PgWireClient.types(started));

        int chunk = 8192;
        for (int offset = 0; offset < payload.length; offset += chunk) {
            int length = Math.min(chunk, payload.length - offset);
            byte[] part = new byte[length];
            System.arraycopy(payload, offset, part, 0, length);
            client.sendCopyData(part);
        }
        client.sendCopyDone();
        List<PgWireClient.Frame> done = client.readUntilReadyForQuery();
        assertNotNull(commandTag(done), "COPY IN 应回 CommandComplete，实际="
                + PgWireClient.types(done) + " message=" + PgWireClient.errorField(done, 'M'));
    }

    private static void assertTablesEqual(PgWireClient client, String left, String right) throws IOException {
        List<PgWireClient.Frame> diff = query(client,
                "select count(*) as n from ("
                        + "select * from " + left + " except all select * from " + right
                        + " union all "
                        + "select * from " + right + " except all select * from " + left
                        + ") d;");
        List<PgWireClient.Frame> rows = PgWireClient.ofType(diff, 'D');
        assertEquals(1, rows.size(), "差异查询应返回 1 行");
        assertEquals("0", PgWireClient.textValue(PgWireClient.dataRowValues(rows.get(0)).get(0)),
                "往返后 " + left + " 与 " + right + " 不一致");
    }
}
