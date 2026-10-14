package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.test.support.PgWireClient;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结果列格式协商契约（PG 协议 Bind 报文）。
 *
 * <p><b>协议规定</b>：Bind 报文的结果格式码个数为 0 → 所有结果列用默认格式（<b>文本</b>）；
 * 为 1 → 该值广播到所有列；等于列数 → 逐列。服务端必须按客户端请求回报 RowDescription 的
 * format 码，并发出与之匹配的字节。</p>
 *
 * <p><b>被测缺陷</b>（改造前）：{@code BindRequest} 从不解析这段格式码，
 * {@code RowEncoder} 只按"是否扩展协议"决定格式，于是 int2/int4/int8/bool/float4/float8/date/bytea
 * 一律按二进制下发。请求文本的客户端（最典型的是 {@code postgres_fdw} 的 DML RETURNING：
 * 它用 resultFormat=0 取回 RETURNING 行）会把这些字节当文本喂给类型输入函数 ——
 * 报错或静默错值。</p>
 *
 * <p><b>本用例覆盖</b>：0 个格式码、显式 0、广播 1、逐列、以及"请求二进制但该类型没有二进制
 * 编码器时必须如实回落文本"，外加 fdw 形状的 {@code INSERT ... RETURNING} 与
 * 语句级 Describe 恒为文本（协议规定）。</p>
 */
public class ResultFormatNegotiationTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("resultfmt");
        cfg.setSqlHistory("OFF");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();

        try (Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:" + dbPort + "/resultfmt", "", "");
             Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE rt_fmt(id INTEGER, v VARCHAR)");
            st.execute("INSERT INTO rt_fmt VALUES (7, 'x')");
        }
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    private static PgWireClient newClient() throws Exception {
        return new PgWireClient("127.0.0.1", dbPort, "resultfmt", 10_000);
    }

    /** 跑一条 Parse/Bind/Describe('P')/Execute/Sync，返回沿途报文。 */
    private static List<PgWireClient.Frame> run(PgWireClient client, String statementName, String portalName,
                                               String sql, int[] resultFormats) throws Exception {
        client.sendParse(statementName, sql);
        client.sendBind(portalName, statementName, resultFormats);
        client.sendDescribe('P', portalName);
        client.sendExecute(portalName, 0);
        client.sendSync();
        return client.readUntilReadyForQuery();
    }

    private static PgWireClient.FieldDesc firstField(List<PgWireClient.Frame> frames) {
        return PgWireClient.parseRowDescription(PgWireClient.ofType(frames, 'T').get(0)).get(0);
    }

    private static byte[] firstValue(List<PgWireClient.Frame> frames) {
        return PgWireClient.dataRowValues(PgWireClient.ofType(frames, 'D').get(0)).get(0);
    }

    // ================================================================
    // 一、文本请求（0 个格式码 / 显式 0）必须发文本
    // ================================================================

    @Test
    void noFormatCodesMeansText() throws Exception {
        try (PgWireClient client = newClient()) {
            List<PgWireClient.Frame> frames =
                    run(client, "s_0", "p_0", "SELECT 10::integer AS n", new int[0]);
            PgWireClient.FieldDesc field = firstField(frames);
            byte[] value = firstValue(frames);

            assertEquals(0, field.formatCode, "0 个结果格式码 = 协议默认文本，必须声明 format=0");
            assertEquals("10", PgWireClient.textValue(value),
                    "声明文本却发了二进制字节：" + java.util.Arrays.toString(value));
        }
    }

    @Test
    void explicitTextFormatCodeSendsText() throws Exception {
        try (PgWireClient client = newClient()) {
            List<PgWireClient.Frame> frames =
                    run(client, "s_t", "p_t", "SELECT 10::integer AS n", new int[]{0});
            assertEquals(0, firstField(frames).formatCode);
            assertEquals("10", PgWireClient.textValue(firstValue(frames)));
        }
    }

    // ================================================================
    // 二、显式请求二进制：有编码器才二进制，没有就如实回落文本
    // ================================================================

    @Test
    void explicitBinaryFormatCodeSendsBinary() throws Exception {
        try (PgWireClient client = newClient()) {
            List<PgWireClient.Frame> frames =
                    run(client, "s_b", "p_b", "SELECT 10::integer AS n", new int[]{1});
            PgWireClient.FieldDesc field = firstField(frames);
            byte[] value = firstValue(frames);

            assertEquals(1, field.formatCode, "显式请求二进制时应声明 format=1");
            assertEquals(4, value.length, "int4 的二进制必须是 4 字节");
            assertEquals(10, ((value[0] & 0xFF) << 24) | ((value[1] & 0xFF) << 16)
                    | ((value[2] & 0xFF) << 8) | (value[3] & 0xFF), "二进制值应为 10");
        }
    }

    @Test
    void broadcastBinaryKeepsTextForTypesWithoutBinaryEncoder() throws Exception {
        try (PgWireClient client = newClient()) {
            List<PgWireClient.Frame> frames = run(client, "s_mix", "p_mix",
                    "SELECT 42::integer AS n, 'ab'::varchar AS s", new int[]{1});
            List<PgWireClient.FieldDesc> fields =
                    PgWireClient.parseRowDescription(PgWireClient.ofType(frames, 'T').get(0));
            List<byte[]> values = PgWireClient.dataRowValues(PgWireClient.ofType(frames, 'D').get(0));

            assertEquals(1, fields.get(0).formatCode, "int4 请求二进制应给二进制");
            assertEquals(4, values.get(0).length);
            assertEquals(0, fields.get(1).formatCode,
                    "varchar 没有二进制编码器：请求了二进制也必须如实回落文本");
            assertEquals("ab", PgWireClient.textValue(values.get(1)));
        }
    }

    @Test
    void perColumnFormatCodesAreHonored() throws Exception {
        try (PgWireClient client = newClient()) {
            List<PgWireClient.Frame> frames = run(client, "s_col", "p_col",
                    "SELECT 42::integer AS n, 'ab'::varchar AS s", new int[]{0, 1});
            List<PgWireClient.FieldDesc> fields =
                    PgWireClient.parseRowDescription(PgWireClient.ofType(frames, 'T').get(0));
            List<byte[]> values = PgWireClient.dataRowValues(PgWireClient.ofType(frames, 'D').get(0));

            assertEquals(0, fields.get(0).formatCode, "逐列 0 → int4 文本");
            assertEquals("42", PgWireClient.textValue(values.get(0)));
            assertEquals(0, fields.get(1).formatCode, "varchar 无二进制编码器 → 仍为文本");
            assertEquals("ab", PgWireClient.textValue(values.get(1)));
        }
    }

    @Test
    void timestamptzHasNoBinaryEncoderSoFallsBackToText() throws Exception {
        try (PgWireClient client = newClient()) {
            List<PgWireClient.Frame> frames = run(client, "s_ts", "p_ts",
                    "SELECT '2024-01-02 03:04:05+00'::timestamptz AS ts", new int[]{1});
            PgWireClient.FieldDesc field = firstField(frames);
            String text = PgWireClient.textValue(firstValue(frames));

            assertEquals(0, field.formatCode, "timestamptz 无二进制编码器，必须声明 format=0");
            assertTrue(text.contains("-") && text.contains(":"),
                    "回落文本后应是可读的时间字符串，实际：" + text);
        }
    }

    // ================================================================
    // 三、postgres_fdw 的实际形状：DML RETURNING 用 resultFormat=0 取文本
    // ================================================================

    @Test
    void insertReturningWithTextResultFormatReturnsText() throws Exception {
        try (PgWireClient client = newClient()) {
            List<PgWireClient.Frame> frames = run(client, "ins_s", "ins_p",
                    "INSERT INTO rt_fmt(id, v) VALUES (8, 'y') RETURNING id", new int[0]);
            PgWireClient.FieldDesc field = firstField(frames);
            byte[] value = firstValue(frames);

            assertEquals(0, field.formatCode,
                    "fdw 用 resultFormat=0 取 RETURNING，服务端必须回文本");
            assertEquals("8", PgWireClient.textValue(value),
                    "声明文本却发了二进制字节：" + java.util.Arrays.toString(value));
            assertEquals("INSERT 0 1", PgWireClient.ofType(frames, 'C').get(0).text());
        }
    }

    // ================================================================
    // 四、语句级 Describe 的 format 码按协议恒为 0（即使 Bind 请求了二进制）
    // ================================================================

    @Test
    void statementDescribeAlwaysReportsTextFormat() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendParse("s_desc", "SELECT 10::integer AS n");
            client.sendBind("p_desc", "s_desc", new int[]{1});   // 门户请求二进制
            client.sendDescribe('S', "s_desc");                  // 但描述的是语句
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            List<PgWireClient.FieldDesc> fields =
                    PgWireClient.parseRowDescription(PgWireClient.ofType(frames, 'T').get(0));
            assertEquals(1, fields.size());
            assertEquals(0, fields.get(0).formatCode,
                    "协议规定：RowDescription 来自语句级 Describe 时格式码尚未确定，恒为 0");

            // 同一个门户自己描述时必须给出二进制（与 Execute 的字节一致）
            client.sendDescribe('P', "p_desc");
            client.sendExecute("p_desc", 0);
            client.sendSync();
            List<PgWireClient.Frame> portalFrames = client.readUntilReadyForQuery();
            assertEquals(1, firstField(portalFrames).formatCode,
                    "门户级 Describe 必须回报 Bind 时请求的二进制格式");
            assertEquals(4, firstValue(portalFrames).length);
        }
    }

    /** 文本回落的边界：空串与 NULL 不能因为格式协商而错位。 */
    @Test
    void nullAndEmptyValuesKeepTheirShape() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendParse("s_null", "SELECT NULL::varchar AS a, ''::varchar AS b");
            client.sendBind("p_null", "s_null", new int[]{1});
            client.sendDescribe('P', "p_null");
            client.sendExecute("p_null", 0);
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            List<byte[]> values = PgWireClient.dataRowValues(PgWireClient.ofType(frames, 'D').get(0));
            assertEquals(null, values.get(0), "NULL 必须保持 NULL（长度为 -1）");
            assertEquals(0, values.get(1).length, "空串必须是 0 字节，而不是 NULL");
            assertEquals("", new String(values.get(1), StandardCharsets.UTF_8));
        }
    }
}
