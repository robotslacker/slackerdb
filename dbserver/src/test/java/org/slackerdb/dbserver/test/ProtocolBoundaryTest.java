package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.test.support.PgWireClient;

import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前导报文与废弃报文的边界契约。
 *
 * <p><b>被测缺陷</b>（改造前）：</p>
 * <ol>
 *   <li>{@code GSSENCRequest}（{@code Int32(8)+Int32(80877104)}）未识别，被回退指针当成
 *       StartupMessage 解析 → 客户端收到 {@code 3D000 "Database alias does not exist"} 并被断连。
 *       一个"不支持 GSSAPI 加密"被报成"库不存在"；</li>
 *   <li>带类型字节的 {@code 'F'}（FunctionCall，fastpath）被当成 CancelRequest 处理，
 *       结果是<b>静默断连</b>——客户端连错误都收不到。</li>
 * </ol>
 *
 * <p><b>契约</b>：GSSENCRequest 回单个 {@code 'N'} 并继续正常握手；
 * FunctionCall 回 {@code 0A000 feature_not_supported}，且连接保持可用。</p>
 */
public class ProtocolBoundaryTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("protocolboundary");
        cfg.setSqlHistory("OFF");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    /** GSSENCRequest → 单个 'N' → 随后的 StartupMessage 正常完成到 ReadyForQuery。 */
    @Test
    void gssEncRequestIsAnsweredWithNThenStartupSucceeds() throws Exception {
        assertTrue(PgWireClient.startupAfterGssEncRequest("127.0.0.1", dbPort, "protocolboundary", 10_000),
                "GSSENCRequest 应回单个 'N'，且随后的 StartupMessage 应正常完成（旧行为是 3D000 + 断连）");
    }

    /** FunctionCall 必须回 0A000，而不是静默断连；且连接随后仍可用。 */
    @Test
    void functionCallIsAnsweredWithFeatureNotSupported() throws Exception {
        try (PgWireClient client = new PgWireClient("127.0.0.1", dbPort, "protocolboundary", 10_000)) {
            // FunctionCall 报文体：Int32 函数 OID + Int16 参数格式码个数 + Int16 参数个数（内容本服务端不解析）
            client.sendFrameOfType('F', new byte[]{0, 0, 0, 1, 0, 0, 0, 0});

            PgWireClient.Frame answer = client.readFrame();
            assertNotNull(answer, "服务端必须对 FunctionCall 作出应答（旧行为是直接关连接）");
            assertEquals('E', answer.type, "应答应是 ErrorResponse；实际=" + answer.type);

            // 连接没有被关闭：紧接着的简单查询必须正常
            client.sendQuery("SELECT 'alive'::varchar AS s");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();
            assertEquals(0, PgWireClient.ofType(frames, 'E').size(),
                    "FunctionCall 之后连接应仍然可用；实际=" + PgWireClient.types(frames));
            assertEquals("alive", PgWireClient.textValue(
                    PgWireClient.dataRowValues(PgWireClient.ofType(frames, 'D').get(0)).get(0)));
        }
    }

    /**
     * 说明：连接首个报文位置的 CancelRequest 前导报文（无类型字节）不在本用例覆盖范围内 ——
     * {@code CancelRequestTest} 已经完整覆盖（固定首 8 字节、decodeTail、打断运行中的查询）。
     * 这里只覆盖"带类型字节的 'F' 是 FunctionCall 而不是 CancelRequest"这一区分。
     */
}
