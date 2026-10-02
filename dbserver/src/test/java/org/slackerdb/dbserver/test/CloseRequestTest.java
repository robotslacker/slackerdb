package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.entity.ParsedStatement;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.server.DBSession;
import org.slackerdb.dbserver.test.support.PgWireClient;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Close（{@code 'C'}）报文的契约测试。
 *
 * <p><b>被测缺陷</b>：{@code CloseRequest.decode()} 把报文体整段当成名字（含结尾的 0），
 * 于是缓存 key 变成 {@code "PreparedStatement-s1\0"}，与 Parse/Bind 存进去的 key 对不上 ——
 * Close 清不掉任何语句/门户，却仍然照常回 {@code CloseComplete}，<b>静默泄漏到会话结束</b>。
 * 真实驱动一定会写这个结尾 0（pgjdbc 与 dbdriver 的
 * {@code QueryExecutorImpl.sendCloseStatement}/{@code sendClosePortal} 都以
 * {@code sendChar(0)} 收尾），所以真机上是"看着正常、资源一直涨"。</p>
 *
 * <p>本用例用裸协议客户端断言两件事：</p>
 * <ol>
 *   <li><b>可观测契约</b>：Close 回恰好一个 CloseComplete，随后 Sync 回一个 ReadyForQuery；</li>
 *   <li><b>服务端状态</b>：Close 之后缓存项真的被摘掉、句柄真的被关掉
 *       （这是唯一能证明"释放了"的判据，驱动侧在这一点上不可观测）。</li>
 * </ol>
 *
 * <p>另外锁定两条容易改坏的语义：</p>
 * <ul>
 *   <li>关闭<b>门户</b>只能释放门户自己的结果集（PortalSuspended 之后它是开着的），
 *       <b>不能</b>关闭与它共享句柄的预备语句 —— 否则同名语句的下一次 Bind/Execute 直接失效；</li>
 *   <li>匿名语句的 key 必须与 Parse 的规则一致（统一记作 {@code NONAME}），
 *       匿名门户与 Bind 一致（空串）。</li>
 * </ul>
 */
public class CloseRequestTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("closeproto");
        cfg.setSqlHistory("OFF");
        cfg.setLog_level("INFO");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE close_t(id INT)");
            st.execute("INSERT INTO close_t VALUES (1), (2), (3)");
        }
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:" + dbPort + "/closeproto", "", "");
    }

    private static PgWireClient newClient() throws Exception {
        return new PgWireClient("127.0.0.1", dbPort, "closeproto", 10_000);
    }

    /** 裸客户端的 pid 就是服务端的 sessionId（BackendKeyData 里写的就是它）。 */
    private static DBSession sessionOf(PgWireClient client) {
        DBSession session = dbInstance.getSession(client.backendPid());
        assertNotNull(session, "按 BackendKeyData 的 pid 找不到会话，用例前提不成立");
        return session;
    }

    // ================================================================
    // 一、关语句：缓存项要被摘掉、句柄要被关掉
    // ================================================================

    @Test
    void closeNamedStatementReleasesIt() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendParse("s1", "SELECT * FROM close_t WHERE id > $1");
            client.sendDescribe('S', "s1");
            client.sendSync();
            assertEquals("1tTZ", PgWireClient.types(client.readUntilReadyForQuery()),
                    "Parse+Describe('S') 的应答序列不符合预期");

            DBSession session = sessionOf(client);
            ParsedStatement statement = session.getParsedStatement("PreparedStatement-s1");
            assertNotNull(statement, "Parse 之后没有缓存语句");
            assertNotNull(statement.preparedStatement, "Parse 之后没有预备语句句柄");
            assertFalse(statement.preparedStatement.isClosed(), "语句句柄一开始应当是打开的");

            client.sendClose('S', "s1");
            client.sendSync();
            assertEquals("3Z", PgWireClient.types(client.readUntilReadyForQuery()),
                    "Close 应答应当恰好是一个 CloseComplete('3') + 一个 ReadyForQuery('Z')");

            assertNull(session.getParsedStatement("PreparedStatement-s1"),
                    "Close('S') 之后服务端仍然缓存着该语句：结尾 0 又被当成名字的一部分了");
            assertTrue(statement.preparedStatement.isClosed(),
                    "Close('S') 之后语句句柄没有被关闭（资源泄漏）");

            // 关闭语句不能把会话搞坏
            client.sendQuery("SELECT 1");
            assertTrue(PgWireClient.types(client.readUntilReadyForQuery()).endsWith("Z"),
                    "Close 之后会话不可用");
        }
    }

    @Test
    void closeUnnamedStatementUsesSameKeyAsParse() throws Exception {
        try (PgWireClient client = newClient()) {
            DBSession session = sessionOf(client);

            client.sendParse("", "SELECT 1");
            client.sendSync();
            assertEquals("1Z", PgWireClient.types(client.readUntilReadyForQuery()));
            assertNotNull(session.getParsedStatement("PreparedStatement-NONAME"),
                    "匿名语句在 Parse 侧记作 NONAME");

            client.sendClose('S', "");
            client.sendSync();
            assertEquals("3Z", PgWireClient.types(client.readUntilReadyForQuery()));

            assertNull(session.getParsedStatement("PreparedStatement-NONAME"),
                    "Close('S') 匿名语句没有清掉 NONAME 缓存项（key 规则不一致）");
        }
    }

    // ================================================================
    // 二、关门户：释放挂起的结果集，但不许动共享的语句句柄
    // ================================================================

    @Test
    void closePortalReleasesSuspendedResultSetButKeepsStatement() throws Exception {
        try (PgWireClient client = newClient()) {
            DBSession session = sessionOf(client);

            client.sendParse("s2", "SELECT id FROM close_t ORDER BY id");
            client.sendBind("p2", "s2");
            client.sendDescribe('P', "p2");
            client.sendExecute("p2", 1);            // 只取 1 行 → 门户挂起
            client.sendSync();
            List<PgWireClient.Frame> suspended = client.readUntilReadyForQuery();

            // 顺带补上协议层对 PortalSuspended 的断言缺口
            assertEquals("12TDsZ", PgWireClient.types(suspended),
                    "门户分批（maxRows=1）的应答序列不符合预期");

            ParsedStatement portal = session.getParsedStatement("Portal-p2");
            assertNotNull(portal, "Bind 之后没有缓存门户");
            assertNotNull(portal.resultSet, "门户挂起之后结果集应当是打开着的");
            assertFalse(portal.resultSet.isClosed(), "门户挂起之后结果集应当是打开着的");

            ParsedStatement statement = session.getParsedStatement("PreparedStatement-s2");
            assertNotNull(statement, "语句缓存项丢失");

            client.sendClose('P', "p2");
            client.sendSync();
            assertEquals("3Z", PgWireClient.types(client.readUntilReadyForQuery()));

            assertNull(session.getParsedStatement("Portal-p2"),
                    "Close('P') 之后服务端仍然缓存着该门户");
            assertTrue(portal.resultSet.isClosed(),
                    "Close('P') 之后挂起的结果集没有被关闭（资源泄漏）");
            assertFalse(statement.preparedStatement.isClosed(),
                    "关闭门户把共享的预备语句也关掉了（PG 语义下不允许）");

            // 反向确认：语句还能继续用（这正是"别关共享句柄"要保护的行为）
            client.sendBind("p3", "s2");
            client.sendExecute("p3", 0);
            client.sendSync();
            List<PgWireClient.Frame> rows = client.readUntilReadyForQuery();
            assertEquals("2DDDCZ", PgWireClient.types(rows),
                    "关闭门户之后，同名语句应当仍能完整返回 3 行");
            assertEquals("SELECT 3", PgWireClient.ofType(rows, 'C').get(0).text(),
                    "CommandComplete 的命令标签不符");
        }
    }

    @Test
    void closeUnnamedPortalUsesSameKeyAsBind() throws Exception {
        try (PgWireClient client = newClient()) {
            DBSession session = sessionOf(client);

            client.sendParse("s4", "SELECT 1");
            client.sendBind("", "s4");
            client.sendSync();
            assertEquals("12Z", PgWireClient.types(client.readUntilReadyForQuery()));
            assertNotNull(session.getParsedStatement("Portal-"),
                    "匿名门户在 Bind 侧就是空串 key");

            client.sendClose('P', "");
            client.sendSync();
            assertEquals("3Z", PgWireClient.types(client.readUntilReadyForQuery()));

            assertNull(session.getParsedStatement("Portal-"),
                    "Close('P') 匿名门户没有清掉空串 key（key 规则不一致）");
        }
    }

    // ================================================================
    // 三、幂等与"绝不挂住"
    // ================================================================

    /**
     * 重复 Close、以及 Close 一个从未 Parse 过的名字，都必须有应答。
     *
     * <p>注意：这里<b>不</b>断言"未知名字回 CloseComplete"——PG 规范要求回
     * {@code 26000 invalid_sql_statement_name} / {@code 34000 invalid_cursor_name}，
     * 当前实现还回 CloseComplete。本用例只锁定"必须有应答、会话必须还能用"这条
     * 在当前与未来实现下都成立的契约（不响应才是真正致命的：客户端会挂到空闲超时）。</p>
     */
    @Test
    void repeatedAndUnknownCloseAlwaysAnswersAndKeepsSessionUsable() throws Exception {
        try (PgWireClient client = newClient()) {
            // 先 Parse 再连续关两次
            client.sendParse("s5", "SELECT 1");
            client.sendSync();
            client.readUntilReadyForQuery();

            for (int round = 0; round < 2; round++) {
                client.sendClose('S', "s5");
                client.sendSync();
                assertEquals("3Z", PgWireClient.types(client.readUntilReadyForQuery()),
                        "第 " + round + " 次重复 Close 的应答不符合预期");
            }

            // 从未 Parse/Bind 过的名字：必须有应答（且不能把连接搞断）
            client.sendClose('S', "never_parsed_statement");
            client.sendClose('P', "never_bound_portal");
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();
            assertEquals("33Z", PgWireClient.types(frames),
                    "对未知名字的 Close 也必须有应答，实际序列=" + PgWireClient.types(frames));

            client.sendQuery("SELECT 'ok'");
            List<PgWireClient.Frame> query = client.readUntilReadyForQuery();
            // DataRow 报文体 = Int16(列数) + Int32(数据长度) + 数据，因此值从偏移 6 开始。
            // 用 VARCHAR 字面量是为了不受"定长类型按二进制编码"的影响（INTEGER 会走二进制格式）。
            PgWireClient.Frame row = PgWireClient.ofType(query, 'D').get(0);
            assertEquals("ok", row.cstring(6),
                    "Close 未知名字之后会话不可用，DataRow 报文体="
                            + org.slackerdb.common.utils.Utils.bytesToHex(row.body));
        }
    }
}
