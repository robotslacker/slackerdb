package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.test.support.PgWireClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 扩展协议"出错后丢弃到 Sync"契约（PG 协议）。
 *
 * <p><b>协议规定</b>：Parse / Bind / Describe / Execute / Close 任一条失败后，
 * 后端丢弃后续前端报文（不产生任何应答），直到收到 Sync 才回 ReadyForQuery 并恢复处理。</p>
 *
 * <p><b>被测缺陷</b>（改造前）：dbserver 没有"等待 Sync"这个状态，每条报文都被独立处理，
 * 于是 Parse 失败之后的 Bind 仍回 {@code BindComplete}、Execute 仍回一个空
 * {@code CommandComplete}。客户端读到与它发送的报文不匹配的应答，
 * 严格实现（以及做批量执行的 postgres_fdw）会认为协议状态机脱节。</p>
 *
 * <p>关键点：同一个 TCP 段里可能已经排好了 Parse/Bind/Execute/Sync 四条报文，
 * 因此拦截必须发生在<b>逐条处理</b>时，而不是解码时。</p>
 */
public class DiscardUntilSyncTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("discardsync");
        cfg.setSqlHistory("OFF");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    private static PgWireClient newClient() throws Exception {
        return new PgWireClient("127.0.0.1", dbPort, "discardsync", 10_000);
    }

    /**
     * 流水线：Parse（表不存在，必然失败）+ Bind + Execute + Sync。
     * 期望只收到一个 ErrorResponse 和一个 ReadyForQuery —— 中间两条必须被丢弃。
     */
    @Test
    void parseErrorDiscardsFollowingMessagesUntilSync() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendParse("bad_s", "SELECT * FROM no_such_table_for_discard_test");
            client.sendBind("bad_p", "bad_s");
            client.sendExecute("bad_p", 0);
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals(1, PgWireClient.ofType(frames, 'E').size(), "应恰好收到一个 ErrorResponse");
            assertTrue(PgWireClient.errorField(frames, 'C') != null, "ErrorResponse 必须带 SQLSTATE");
            assertEquals("EZ", PgWireClient.types(frames),
                    "Parse 失败后 Bind/Execute 必须被丢弃（不得回 BindComplete/CommandComplete），"
                            + "Sync 之后才回 ReadyForQuery；实际报文序列=" + PgWireClient.types(frames));
            assertEquals('I', PgWireClient.readyStatus(frames), "未处于事务块时应回 'I'");
        }
    }

    /** Sync 之后会话必须恢复正常：紧接着的一条完整查询要能跑通。 */
    @Test
    void syncRestoresNormalProcessing() throws Exception {
        try (PgWireClient client = newClient()) {
            // 先制造一次失败 + Sync
            client.sendParse("bad2_s", "SELECT * FROM no_such_table_for_discard_test");
            client.sendBind("bad2_p", "bad2_s");
            client.sendExecute("bad2_p", 0);
            client.sendSync();
            client.readUntilReadyForQuery();

            // 再跑一条正常查询：Parse/Bind/Describe/Execute/Sync 正常往返
            client.sendParse("good_s", "SELECT 7::integer AS n");
            client.sendBind("good_p", "good_s");
            client.sendDescribe('P', "good_p");
            client.sendExecute("good_p", 0);
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals(0, PgWireClient.ofType(frames, 'E').size(), "Sync 之后不应再有错误");
            List<byte[]> values = PgWireClient.dataRowValues(PgWireClient.ofType(frames, 'D').get(0));
            assertEquals("7", PgWireClient.textValue(values.get(0)));
        }
    }

    /** Bind 阶段失败（二进制参数长度不符）同样要丢弃随后的 Execute。 */
    @Test
    void bindErrorDiscardsExecuteUntilSync() throws Exception {
        try (PgWireClient client = newClient()) {
            // 按 int4 声明参数，却只发 3 字节二进制 → 服务端应回 22P03
            client.sendParse("bp_s", "SELECT $1::integer AS n", 23);
            client.sendBindWithBinaryParam("bp_p", "bp_s", new byte[]{0, 0, 1});
            client.sendExecute("bp_p", 0);
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("22P03", PgWireClient.errorField(frames, 'C'), "二进制参数长度不符应回 22P03");
            // Parse 成功会先回 ParseComplete('1')，随后 Bind 失败回 'E'，Execute 必须被丢弃，
            // Sync 才回 ReadyForQuery('Z') —— 关键是不能出现 '2'(BindComplete) 或 'C'(CommandComplete)。
            assertEquals("1EZ", PgWireClient.types(frames),
                    "Bind 失败后 Execute 必须被丢弃；实际报文序列=" + PgWireClient.types(frames));
        }
    }

    /**
     * 简单查询路径不受影响：Query('Q') 出错后不需要 Sync，
     * 服务端必须照常回 ErrorResponse + ReadyForQuery（否则现有客户端全会被卡住）。
     */
    @Test
    void simpleQueryErrorStillAnswersImmediately() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("SELECT * FROM no_such_table_for_discard_test");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("EZ", PgWireClient.types(frames),
                    "简单查询出错必须直接回 ErrorResponse + ReadyForQuery；实际=" + PgWireClient.types(frames));
            assertEquals('I', PgWireClient.readyStatus(frames));

            // 出错之后同一个连接上再发一条简单查询，必须正常
            client.sendQuery("SELECT 'ok'::varchar AS s");
            List<PgWireClient.Frame> again = client.readUntilReadyForQuery();
            assertEquals(0, PgWireClient.ofType(again, 'E').size(),
                    "简单查询出错后不应影响同一连接上的后续查询");
            assertEquals("ok", PgWireClient.textValue(
                    PgWireClient.dataRowValues(PgWireClient.ofType(again, 'D').get(0)).get(0)));
        }
    }

    /** 丢弃期间收到 Terminate 必须照常关闭连接（不能把关闭报文也丢掉）。 */
    @Test
    void terminateStillWorksWhileWaitingForSync() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendParse("term_s", "SELECT * FROM no_such_table_for_discard_test");
            client.sendBind("term_p", "term_s");
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();
            assertEquals("EZ", PgWireClient.types(frames));

            client.sendTerminate();
            // 服务端应立即关闭连接：后续读返回 null（而非抛 IO 异常）
            assertEquals(null, client.readFrame(), "Terminate 后服务端应关闭连接");
        }
    }
}
