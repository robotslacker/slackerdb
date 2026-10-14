package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.server.DBSession;
import org.slackerdb.dbserver.sql.CursorSql;
import org.slackerdb.dbserver.test.support.PgWireClient;

import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PG 游标族语句契约：{@code DECLARE ... CURSOR FOR} / {@code FETCH} / {@code MOVE} / {@code CLOSE}。
 *
 * <p><b>被测缺陷</b>（改造前）：这些语句一律到不了执行层 —— {@code PlSqlDetector} 会把任何以
 * {@code DECLARE} 开头的语句当成 PL/SQL 块，{@code DECLARE c CURSOR FOR ...} 因此报
 * {@code 42601 mismatched input 'CURSOR'}；{@code FETCH}/{@code CLOSE}/{@code MOVE} 则被 DuckDB
 * 以 {@code 42601 syntax error} 拒绝。而 {@code postgres_fdw} 取远端数据<b>总是</b>走这条路径
 * （{@code DECLARE cN CURSOR FOR ...} → 反复 {@code FETCH 100} → {@code CLOSE}），
 * 所以外部表读取此前完全不可用。</p>
 *
 * <p><b>契约</b>：fdw 形状的分批取数可用；非 WITH HOLD 游标随事务结束销毁；WITH HOLD 跨 COMMIT 存活；
 * 游标不存在回 {@code 34000}、重名声明回 {@code 42P03}；需要滚动结果集的方向明确回 {@code 0A000}；
 * 真正的 PL/SQL {@code DECLARE} 块不被抢走。</p>
 */
public class CursorSupportTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("cursorsupport");
        cfg.setSqlHistory("OFF");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();

        try (PgWireClient client = newClient()) {
            client.sendQuery("CREATE OR REPLACE TABLE cur_t AS "
                    + "SELECT range AS id, 'row-' || range AS v FROM range(5)");
            client.readUntilReadyForQuery();
        }
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    private static PgWireClient newClient() throws Exception {
        return new PgWireClient("127.0.0.1", dbPort, "cursorsupport", 10_000);
    }

    private static DBSession activeSession() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            DBSession found = null;
            for (DBSession session : dbInstance.dbSessions.values()) {
                if (session.channel != null && session.channel.isActive()) {
                    if (found != null) {
                        found = null;
                        break;
                    }
                    found = session;
                }
            }
            if (found != null) {
                return found;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("没有找到唯一活跃会话");
    }

    private static List<List<byte[]>> rowsOf(List<PgWireClient.Frame> frames) {
        return PgWireClient.ofType(frames, 'D').stream().map(PgWireClient::dataRowValues).toList();
    }

    /** postgres_fdw 的真实取数形状：DECLARE → FETCH n × N → CLOSE。 */
    @Test
    void fdwStylePagingWorksEndToEnd() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("DECLARE c1 CURSOR FOR SELECT id, v FROM cur_t ORDER BY id");
            List<PgWireClient.Frame> declared = client.readUntilReadyForQuery();
            assertEquals("CZ", PgWireClient.types(declared),
                    "DECLARE 应只回 CommandComplete（旧行为是 42601），实际=" + PgWireClient.types(declared));
            assertEquals("DECLARE CURSOR", PgWireClient.ofType(declared, 'C').get(0).text());

            client.sendQuery("FETCH 2 FROM c1");
            List<PgWireClient.Frame> first = client.readUntilReadyForQuery();
            assertEquals("TDDCZ", PgWireClient.types(first),
                    "FETCH 应回 行描述 + 行 + CommandComplete，实际=" + PgWireClient.types(first));
            List<PgWireClient.FieldDesc> fields =
                    PgWireClient.parseRowDescription(PgWireClient.ofType(first, 'T').get(0));
            assertEquals(List.of("id", "v"), fields.stream().map(f -> f.name).toList());
            assertEquals("row-0", PgWireClient.textValue(rowsOf(first).get(0).get(1)));
            assertEquals("row-1", PgWireClient.textValue(rowsOf(first).get(1).get(1)));
            assertEquals("FETCH 2", PgWireClient.ofType(first, 'C').get(0).text());

            client.sendQuery("FETCH 2 FROM c1");
            List<PgWireClient.Frame> second = client.readUntilReadyForQuery();
            assertEquals("TDDCZ", PgWireClient.types(second));
            assertEquals("row-2", PgWireClient.textValue(rowsOf(second).get(0).get(1)));

            // 最后一批只有 1 行（与 fdw "取到的行数 < fetch_size 即 EOF" 的判定一致）
            client.sendQuery("FETCH 2 FROM c1");
            List<PgWireClient.Frame> third = client.readUntilReadyForQuery();
            assertEquals("TDCZ", PgWireClient.types(third));
            assertEquals("FETCH 1", PgWireClient.ofType(third, 'C').get(0).text());
            assertEquals("row-4", PgWireClient.textValue(rowsOf(third).get(0).get(1)));

            // 再取一次：0 行，标签 FETCH 0
            client.sendQuery("FETCH 2 FROM c1");
            List<PgWireClient.Frame> fourth = client.readUntilReadyForQuery();
            assertEquals("TCZ", PgWireClient.types(fourth));
            assertEquals("FETCH 0", PgWireClient.ofType(fourth, 'C').get(0).text());

            client.sendQuery("CLOSE c1");
            List<PgWireClient.Frame> closed = client.readUntilReadyForQuery();
            assertEquals("CZ", PgWireClient.types(closed));
            assertEquals("CLOSE CURSOR", PgWireClient.ofType(closed, 'C').get(0).text());
            assertEquals(0, activeSession().cursorCount());
        }
    }

    @Test
    void fetchAllReturnsEverythingRemaining() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("DECLARE c_all CURSOR FOR SELECT id FROM cur_t ORDER BY id");
            client.readUntilReadyForQuery();
            client.sendQuery("FETCH ALL FROM c_all");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();
            assertEquals(5, rowsOf(frames).size(), "FETCH ALL 应返回剩余全部行");
            assertEquals("FETCH 5", PgWireClient.ofType(frames, 'C').get(0).text());
            client.sendQuery("CLOSE c_all");
            client.readUntilReadyForQuery();
        }
    }

    @Test
    void moveAdvancesWithoutReturningRows() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("DECLARE c_mv CURSOR FOR SELECT id FROM cur_t ORDER BY id");
            client.readUntilReadyForQuery();

            client.sendQuery("MOVE 2 IN c_mv");
            List<PgWireClient.Frame> moved = client.readUntilReadyForQuery();
            assertEquals("CZ", PgWireClient.types(moved), "MOVE 不返回行：" + PgWireClient.types(moved));
            assertEquals("MOVE 2", PgWireClient.ofType(moved, 'C').get(0).text());

            client.sendQuery("FETCH 1 FROM c_mv");
            List<PgWireClient.Frame> after = client.readUntilReadyForQuery();
            assertEquals("2", PgWireClient.textValue(rowsOf(after).get(0).get(0)),
                    "MOVE 2 之后 FETCH 应拿到第 3 行（id=2）");

            client.sendQuery("CLOSE c_mv");
            client.readUntilReadyForQuery();
        }
    }

    @Test
    void nonHoldCursorIsDroppedAtTransactionEnd() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("BEGIN");
            client.readUntilReadyForQuery();
            client.sendQuery("DECLARE c_tx CURSOR FOR SELECT id FROM cur_t");
            client.readUntilReadyForQuery();

            client.sendQuery("COMMIT");
            client.readUntilReadyForQuery();
            assertEquals(0, activeSession().cursorCount(), "非 WITH HOLD 游标应随 COMMIT 销毁");

            client.sendQuery("FETCH 1 FROM c_tx");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();
            assertEquals("EZ", PgWireClient.types(frames));
            assertEquals("34000", PgWireClient.errorField(frames, 'C'));

            // ROLLBACK 同样销毁
            client.sendQuery("BEGIN");
            client.readUntilReadyForQuery();
            client.sendQuery("DECLARE c_rb CURSOR FOR SELECT id FROM cur_t");
            client.readUntilReadyForQuery();
            client.sendQuery("ROLLBACK");
            client.readUntilReadyForQuery();
            client.sendQuery("FETCH 1 FROM c_rb");
            assertEquals("34000", PgWireClient.errorField(client.readUntilReadyForQuery(), 'C'));
        }
    }

    @Test
    void withHoldCursorSurvivesCommit() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("BEGIN");
            client.readUntilReadyForQuery();
            client.sendQuery("DECLARE c_hold CURSOR WITH HOLD FOR SELECT id FROM cur_t ORDER BY id");
            List<PgWireClient.Frame> declared = client.readUntilReadyForQuery();
            assertEquals("CZ", PgWireClient.types(declared), "WITH HOLD 声明应成功：" + PgWireClient.types(declared));

            client.sendQuery("COMMIT");
            client.readUntilReadyForQuery();
            assertEquals(1, activeSession().cursorCount(), "WITH HOLD 游标应跨 COMMIT 存活");

            client.sendQuery("FETCH 2 FROM c_hold");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();
            assertEquals(2, rowsOf(frames).size());
            assertEquals("0", PgWireClient.textValue(rowsOf(frames).get(0).get(0)));
            assertEquals("1", PgWireClient.textValue(rowsOf(frames).get(1).get(0)));

            client.sendQuery("CLOSE c_hold");
            client.readUntilReadyForQuery();
            assertEquals(0, activeSession().cursorCount());
        }
    }

    @Test
    void duplicateDeclareAndUnknownCursorUseProtocolErrorCodes() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("DECLARE c_dup CURSOR FOR SELECT id FROM cur_t");
            client.readUntilReadyForQuery();

            client.sendQuery("DECLARE c_dup CURSOR FOR SELECT id FROM cur_t");
            List<PgWireClient.Frame> duplicate = client.readUntilReadyForQuery();
            assertEquals("EZ", PgWireClient.types(duplicate));
            assertEquals("42P03", PgWireClient.errorField(duplicate, 'C'), "重名声明应回 42P03");

            client.sendQuery("FETCH 1 FROM no_such_cursor");
            List<PgWireClient.Frame> unknown = client.readUntilReadyForQuery();
            assertEquals("34000", PgWireClient.errorField(unknown, 'C'), "不存在的游标应回 34000");

            client.sendQuery("CLOSE no_such_cursor");
            assertEquals("34000", PgWireClient.errorField(client.readUntilReadyForQuery(), 'C'));

            client.sendQuery("CLOSE ALL");
            List<PgWireClient.Frame> closedAll = client.readUntilReadyForQuery();
            assertEquals("CZ", PgWireClient.types(closedAll));
            assertEquals(0, activeSession().cursorCount());
        }
    }

    @Test
    void scrollableDirectionsAreRejectedHonestly() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("DECLARE c_scroll CURSOR FOR SELECT id FROM cur_t");
            client.readUntilReadyForQuery();

            client.sendQuery("FETCH BACKWARD 1 FROM c_scroll");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();
            assertEquals("EZ", PgWireClient.types(frames));
            assertEquals("0A000", PgWireClient.errorField(frames, 'C'),
                    "需要可滚动结果集的方向必须明确报 0A000，不能假装滚动");

            client.sendQuery("CLOSE c_scroll");
            client.readUntilReadyForQuery();
        }
    }

    // ================================================================
    // 扩展协议路径：Parse/Describe/Execute 下同样可用
    // ================================================================

    @Test
    void extendedProtocolCursorFlowWorks() throws Exception {
        try (PgWireClient client = newClient()) {
            // DECLARE
            client.sendParse("e_decl", "DECLARE ec CURSOR FOR SELECT id FROM cur_t ORDER BY id");
            client.sendBind("e_decl_p", "e_decl");
            client.sendExecute("e_decl_p", 0);
            client.sendSync();
            List<PgWireClient.Frame> declared = client.readUntilReadyForQuery();
            assertEquals("12CZ", PgWireClient.types(declared),
                    "扩展协议下的 DECLARE 应答不符，实际=" + PgWireClient.types(declared));
            assertEquals("DECLARE CURSOR", PgWireClient.ofType(declared, 'C').get(0).text());

            // FETCH：行描述来自 Describe('P')，Execute 不得重复发 'T'
            client.sendParse("e_fetch", "FETCH 2 FROM ec");
            client.sendBind("e_fetch_p", "e_fetch");
            client.sendDescribe('P', "e_fetch_p");
            client.sendExecute("e_fetch_p", 0);
            client.sendSync();
            List<PgWireClient.Frame> fetched = client.readUntilReadyForQuery();
            assertEquals("12TDDCZ", PgWireClient.types(fetched),
                    "FETCH 的行描述必须只来自 Describe('P')，实际=" + PgWireClient.types(fetched));
            assertEquals("0", PgWireClient.textValue(rowsOf(fetched).get(0).get(0)));
            assertEquals("FETCH 2", PgWireClient.ofType(fetched, 'C').get(0).text());

            // CLOSE
            client.sendParse("e_close", "CLOSE ec");
            client.sendBind("e_close_p", "e_close");
            client.sendExecute("e_close_p", 0);
            client.sendSync();
            List<PgWireClient.Frame> closed = client.readUntilReadyForQuery();
            assertEquals("12CZ", PgWireClient.types(closed));
            assertEquals("CLOSE CURSOR", PgWireClient.ofType(closed, 'C').get(0).text());
            assertEquals(0, activeSession().cursorCount());
        }
    }

    @Test
    void extendedProtocolFetchOfUnknownCursorReturns34000() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendParse("e_bad", "FETCH 1 FROM does_not_exist");
            client.sendBind("e_bad_p", "e_bad");
            client.sendDescribe('P', "e_bad_p");
            client.sendExecute("e_bad_p", 0);
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            // Describe 阶段游标还不存在 → NoData('n')；执行阶段回 34000
            assertEquals("12nEZ", PgWireClient.types(frames),
                    "未知游标的应答序列不符，实际=" + PgWireClient.types(frames));
            assertEquals("34000", PgWireClient.errorField(frames, 'C'));
        }
    }

    /**
     * 反向约束：真正的 PL/SQL {@code DECLARE} 块不能被游标解析抢走。
     *
     * <p>PL/SQL 是 {@code CURSOR <名字> IS <查询>}（CURSOR 在名字前、用 IS），
     * PG 的 SQL 游标是 {@code DECLARE <名字> ... CURSOR ... FOR}（名字在前、用 FOR）。</p>
     */
    @Test
    void plsqlDeclareBlocksAreNotTreatedAsCursors() {
        assertNull(CursorSql.parse("DECLARE CURSOR cur IS SELECT 1"), "PL/SQL 游标声明不能被当成 SQL 游标");
        assertNull(CursorSql.parse("DECLARE v INTEGER; BEGIN v := 1; END"), "PL/SQL 变量声明不能被当成游标");
        assertNull(CursorSql.parse("DO $$ BEGIN NULL; END $$"), "匿名块不能被当成游标");
        assertNull(CursorSql.parse("SELECT 1"), "普通查询不能被当成游标");

        CursorSql.CursorStatement parsed =
                CursorSql.parse("DECLARE c1 CURSOR FOR SELECT id FROM t");
        assertNotNull(parsed, "PG 形式的游标声明必须被识别");
        assertEquals(CursorSql.CursorStatement.Kind.DECLARE, parsed.kind());
        assertEquals("c1", parsed.declare().name());
        assertEquals("SELECT id FROM t", parsed.declare().query());
        assertTrue(!parsed.declare().withHold());
        assertTrue(CursorSql.parse("DECLARE c2 CURSOR WITH HOLD FOR SELECT 1").declare().withHold());
    }
}
