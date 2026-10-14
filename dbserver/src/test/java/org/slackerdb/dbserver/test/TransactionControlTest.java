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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PG 事务控制语句契约（P2 真事务）。
 *
 * <p><b>被测缺陷</b>（改造前）：{@code START TRANSACTION ISOLATION LEVEL REPEATABLE READ}
 * —— 也就是 {@code postgres_fdw} 开远端事务时发的语句 —— 被 SQLReplacer <b>改写成空串</b>：
 * 客户端收到 {@code BEGIN} 的成功应答，DuckDB 却什么都没开，于是远端 {@code ROLLBACK}
 * 一个字节都回滚不了。{@code BEGIN ISOLATION LEVEL ...} / {@code SET TRANSACTION ...}
 * 则直接 {@code 42601}。</p>
 *
 * <p><b>契约</b>：{@code START TRANSACTION ...} 真正翻译成 {@code BEGIN TRANSACTION} 并驱动会话状态
 * （ReadyForQuery 从 'I' 变 'T'）；{@code ROLLBACK} 必须真的回滚、{@code COMMIT} 必须真的提交；
 * 隔离级别中 READ COMMITTED / REPEATABLE READ 视为已满足（不打搅），
 * SERIALIZABLE / READ ONLY / DEFERRABLE 接受但发 NOTICE 说明不生效。</p>
 */
public class TransactionControlTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("txcontrol");
        cfg.setSqlHistory("OFF");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();

        try (PgWireClient client = newClient()) {
            client.sendQuery("CREATE OR REPLACE TABLE tx_t(id INTEGER, v VARCHAR)");
            client.readUntilReadyForQuery();
        }
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    private static PgWireClient newClient() throws Exception {
        return new PgWireClient("127.0.0.1", dbPort, "txcontrol", 10_000);
    }

    private static long rowCount(PgWireClient client) throws Exception {
        client.sendQuery("SELECT count(*) AS c FROM tx_t");
        List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();
        return Long.parseLong(PgWireClient.textValue(
                PgWireClient.dataRowValues(PgWireClient.ofType(frames, 'D').get(0)).get(0)));
    }

    private static void clearTable(PgWireClient client) throws Exception {
        client.sendQuery("DELETE FROM tx_t");
        client.readUntilReadyForQuery();
    }

    /** 头号翻转：START TRANSACTION 现在是真事务 —— ROLLBACK 真的回滚。 */
    @Test
    void startTransactionIsRealAndRollbackUndoesWork() throws Exception {
        try (PgWireClient client = newClient()) {
            clearTable(client);

            client.sendQuery("START TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            List<PgWireClient.Frame> started = client.readUntilReadyForQuery();
            assertEquals("CZ", PgWireClient.types(started),
                    "REPEATABLE READ 视为已满足：只回 CommandComplete，不应有 NOTICE，实际="
                            + PgWireClient.types(started));
            assertEquals("BEGIN", PgWireClient.ofType(started, 'C').get(0).text());
            assertEquals('T', PgWireClient.readyStatus(started),
                    "已进入事务块，ReadyForQuery 必须是 'T'（旧实现是空事务）");

            client.sendQuery("INSERT INTO tx_t VALUES (1, 'a')");
            client.readUntilReadyForQuery();
            assertEquals(1, rowCount(client), "事务内应看到自己插入的行");

            client.sendQuery("ROLLBACK");
            List<PgWireClient.Frame> rolledBack = client.readUntilReadyForQuery();
            assertEquals("ROLLBACK", PgWireClient.ofType(rolledBack, 'C').get(0).text());
            assertEquals('I', PgWireClient.readyStatus(rolledBack));
            assertEquals(0, rowCount(client),
                    "ROLLBACK 必须真的回滚（旧实现：DuckDB 没开事务，什么都没回滚）");
        }
    }

    @Test
    void commitPersistsWork() throws Exception {
        try (PgWireClient client = newClient()) {
            clearTable(client);

            client.sendQuery("START TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            client.readUntilReadyForQuery();
            client.sendQuery("INSERT INTO tx_t VALUES (2, 'b')");
            client.readUntilReadyForQuery();
            client.sendQuery("COMMIT");
            List<PgWireClient.Frame> committed = client.readUntilReadyForQuery();
            assertEquals("COMMIT", PgWireClient.ofType(committed, 'C').get(0).text());
            assertEquals('I', PgWireClient.readyStatus(committed));
            assertEquals(1, rowCount(client), "COMMIT 必须保留写入");

            clearTable(client);
        }
    }

    @Test
    void beginWithIsolationLevelIsAccepted() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("BEGIN ISOLATION LEVEL REPEATABLE READ");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();
            assertEquals("CZ", PgWireClient.types(frames),
                    "BEGIN ... ISOLATION LEVEL 旧行为是 42601，实际=" + PgWireClient.types(frames));
            assertEquals("BEGIN", PgWireClient.ofType(frames, 'C').get(0).text());
            client.sendQuery("ROLLBACK");
            client.readUntilReadyForQuery();
        }
    }

    /** 已在事务中再 BEGIN：PG 只 WARNING，这里回 NOTICE 且保持当前事务。 */
    @Test
    void beginInsideTransactionOnlyNotices() throws Exception {
        try (PgWireClient client = newClient()) {
            clearTable(client);

            client.sendQuery("BEGIN");
            client.readUntilReadyForQuery();
            client.sendQuery("BEGIN");
            List<PgWireClient.Frame> again = client.readUntilReadyForQuery();
            assertEquals("NCZ", PgWireClient.types(again),
                    "重复 BEGIN 应回 NOTICE + BEGIN（不能报错，也不能丢掉当前事务），实际="
                            + PgWireClient.types(again));
            assertTrue(PgWireClient.ofType(again, 'N').get(0).text().contains("already a transaction in progress"),
                    "NOTICE 内容应说明已有事务在进行");

            client.sendQuery("INSERT INTO tx_t VALUES (3, 'c')");
            client.readUntilReadyForQuery();
            client.sendQuery("ROLLBACK");
            client.readUntilReadyForQuery();
            assertEquals(0, rowCount(client), "重复 BEGIN 之后当前事务仍然有效（ROLLBACK 应回滚）");
        }
    }

    /** 做不到的隔离级别必须提示，不能假装。 */
    @Test
    void serializableRequestIsAcceptedWithHonestNotice() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("START TRANSACTION ISOLATION LEVEL SERIALIZABLE");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();
            assertEquals("NCZ", PgWireClient.types(frames), "SERIALIZABLE 应带 NOTICE，实际=" + PgWireClient.types(frames));
            assertTrue(PgWireClient.ofType(frames, 'N').get(0).text().contains("SERIALIZABLE"),
                    "NOTICE 必须点名 SERIALIZABLE 不受保证");
            client.sendQuery("ROLLBACK");
            client.readUntilReadyForQuery();
        }
    }

    @Test
    void setTransactionInsideBlockIsAcceptedAndOutsideIsRejected() throws Exception {
        try (PgWireClient client = newClient()) {
            // 事务块外：PG 语义 25P01
            client.sendQuery("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
            List<PgWireClient.Frame> outside = client.readUntilReadyForQuery();
            assertEquals("EZ", PgWireClient.types(outside));
            assertEquals("25P01", PgWireClient.errorField(outside, 'C'),
                    "事务块外的 SET TRANSACTION 应回 25P01");

            // 事务块内：接受 + NOTICE
            client.sendQuery("BEGIN");
            client.readUntilReadyForQuery();
            client.sendQuery("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE");
            List<PgWireClient.Frame> inside = client.readUntilReadyForQuery();
            assertEquals("NCZ", PgWireClient.types(inside),
                    "事务块内的 SET TRANSACTION 应被接受，实际=" + PgWireClient.types(inside));
            assertEquals("SET", PgWireClient.ofType(inside, 'C').get(0).text());
            client.sendQuery("ROLLBACK");
            client.readUntilReadyForQuery();
        }
    }

    /** PG 语义：失败事务里的 COMMIT 等价于回滚。 */
    @Test
    void commitInFailedTransactionRollsBack() throws Exception {
        try (PgWireClient client = newClient()) {
            clearTable(client);

            client.sendQuery("BEGIN");
            client.readUntilReadyForQuery();
            client.sendQuery("INSERT INTO tx_t VALUES (4, 'd')");
            client.readUntilReadyForQuery();

            client.sendQuery("SELECT * FROM no_such_table_for_tx_test");
            List<PgWireClient.Frame> failed = client.readUntilReadyForQuery();
            assertEquals("EZ", PgWireClient.types(failed));

            client.sendQuery("COMMIT");
            List<PgWireClient.Frame> committed = client.readUntilReadyForQuery();
            assertEquals("COMMIT", PgWireClient.ofType(committed, 'C').get(0).text());
            assertEquals('I', PgWireClient.readyStatus(committed));
            assertEquals(0, rowCount(client),
                    "失败事务中的 COMMIT 在 PG 里等价于回滚，写入必须消失");
        }
    }

    @Test
    void tagVariantsMatchPostgres() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("COMMIT TRANSACTION");
            assertEquals("COMMIT", PgWireClient.ofType(client.readUntilReadyForQuery(), 'C').get(0).text());
            client.sendQuery("END");
            assertEquals("COMMIT", PgWireClient.ofType(client.readUntilReadyForQuery(), 'C').get(0).text());
            client.sendQuery("ABORT TRANSACTION");
            assertEquals("ROLLBACK", PgWireClient.ofType(client.readUntilReadyForQuery(), 'C').get(0).text());
            client.sendQuery("ROLLBACK");
            assertEquals("ROLLBACK", PgWireClient.ofType(client.readUntilReadyForQuery(), 'C').get(0).text());
        }
    }

    /**
     * P2 + B1 集成：完整复刻 postgres_fdw 一个取数事务的语句序列。
     *
     * <p>START TRANSACTION（带隔离级别）→ DECLARE CURSOR → FETCH → CLOSE → COMMIT，
     * 并验证非 WITH HOLD 游标确实随 COMMIT 销毁。</p>
     */
    @Test
    void fdwTransactionShapeWorksEndToEnd() throws Exception {
        try (PgWireClient client = newClient()) {
            clearTable(client);
            client.sendQuery("INSERT INTO tx_t VALUES (10, 'x'), (11, 'y'), (12, 'z')");
            client.readUntilReadyForQuery();

            client.sendQuery("START TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            List<PgWireClient.Frame> started = client.readUntilReadyForQuery();
            assertEquals('T', PgWireClient.readyStatus(started));

            client.sendQuery("DECLARE fdw_c CURSOR FOR SELECT id, v FROM tx_t ORDER BY id");
            List<PgWireClient.Frame> declared = client.readUntilReadyForQuery();
            assertEquals("DECLARE CURSOR", PgWireClient.ofType(declared, 'C').get(0).text());

            client.sendQuery("FETCH 100 FROM fdw_c");
            List<PgWireClient.Frame> fetched = client.readUntilReadyForQuery();
            assertEquals(3, PgWireClient.ofType(fetched, 'D').size(), "FETCH 100 应取回全部 3 行");
            assertEquals("FETCH 3", PgWireClient.ofType(fetched, 'C').get(0).text());

            client.sendQuery("CLOSE fdw_c");
            client.readUntilReadyForQuery();

            client.sendQuery("COMMIT");
            List<PgWireClient.Frame> committed = client.readUntilReadyForQuery();
            assertEquals("COMMIT", PgWireClient.ofType(committed, 'C').get(0).text());
            assertEquals('I', PgWireClient.readyStatus(committed));

            client.sendQuery("FETCH 1 FROM fdw_c");
            assertEquals("34000", PgWireClient.errorField(client.readUntilReadyForQuery(), 'C'),
                    "非 WITH HOLD 游标应随 COMMIT 销毁");
        }
    }

    /**
     * 只读事务必须真的只读。
     *
     * <p>SlackDB 的 JDBC 驱动在 {@code readOnly=true} + {@code autoCommit=false} 时发的正是
     * {@code BEGIN READ ONLY}（见 {@code PGProperty.READ_ONLY_MODE}），而 DuckDB 支持
     * {@code BEGIN TRANSACTION READ ONLY} 并真的拒绝写入 —— 所以这里必须原样透传，
     * 把只读语义降级成 NOTICE 等于悄悄放宽权限。</p>
     */
    @Test
    void readOnlyTransactionIsEnforced() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("BEGIN READ ONLY");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();
            assertEquals("CZ", PgWireClient.types(frames),
                    "READ ONLY 由 DuckDB 支持：应原样透传、不打搅客户端，实际=" + PgWireClient.types(frames));
            assertEquals('T', PgWireClient.readyStatus(frames));

            client.sendQuery("SELECT count(*) FROM tx_t");
            assertEquals("TDCZ", PgWireClient.types(client.readUntilReadyForQuery()), "只读事务里必须能读");

            client.sendQuery("CREATE OR REPLACE TABLE tx_readonly_probe(id INTEGER)");
            List<PgWireClient.Frame> denied = client.readUntilReadyForQuery();
            assertEquals("EZ", PgWireClient.types(denied), "只读事务里必须拒绝写入");
            assertTrue(PgWireClient.errorField(denied, 'M').toLowerCase().contains("read-only"),
                    "错误信息应说明是只读限制，实际=" + PgWireClient.errorField(denied, 'M'));

            client.sendQuery("ROLLBACK");
            List<PgWireClient.Frame> rolledBack = client.readUntilReadyForQuery();
            assertEquals("ROLLBACK", PgWireClient.ofType(rolledBack, 'C').get(0).text());
            assertEquals('I', PgWireClient.readyStatus(rolledBack));
        }
    }

    /**
     * 子事务控制：明确报错（既不是 DuckDB 的语法错误，也不能静默降级）。
     *
     * <p>DuckDB 没有子事务，所以：事务块外按 PG 语义回 {@code 25P01}，
     * 事务块内回 {@code 0A000}（feature_not_supported）并说清原因。</p>
     */
    @Test
    void savepointFamilyFailsPredictably() throws Exception {
        try (PgWireClient client = newClient()) {
            for (String sql : new String[]{"SAVEPOINT s1", "RELEASE SAVEPOINT s1", "ROLLBACK TO SAVEPOINT s1"}) {
                client.sendQuery(sql);
                List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();
                assertEquals("EZ", PgWireClient.types(frames), sql + " 应报错，实际=" + PgWireClient.types(frames));
                assertEquals("25P01", PgWireClient.errorField(frames, 'C'),
                        sql + " 在事务块外应回 25P01（PG 语义）");
            }

            client.sendQuery("BEGIN");
            client.readUntilReadyForQuery();
            client.sendQuery("SAVEPOINT s2");
            List<PgWireClient.Frame> inside = client.readUntilReadyForQuery();
            assertEquals("EZ", PgWireClient.types(inside));
            assertEquals("0A000", PgWireClient.errorField(inside, 'C'),
                    "事务块内应回 0A000（feature_not_supported）");
            assertTrue(PgWireClient.errorField(inside, 'M').contains("no subtransactions"),
                    "错误信息必须说清原因，实际=" + PgWireClient.errorField(inside, 'M'));
            assertEquals('E', PgWireClient.readyStatus(inside), "出错后事务块应处于 aborted 状态");

            client.sendQuery("ROLLBACK");
            client.readUntilReadyForQuery();
        }
    }

    /**
     * 安全约束：{@code ROLLBACK TO SAVEPOINT} 绝不能被当成整事务回滚。
     *
     * <p>若被误当成 {@code ROLLBACK}，事务会立刻结束（ReadyForQuery 变 'I'）并丢掉插入 ——
     * 这正是分类顺序写错时的静默错误语义。</p>
     */
    @Test
    void rollbackToSavepointIsNotTreatedAsFullRollback() throws Exception {
        try (PgWireClient client = newClient()) {
            clearTable(client);
            client.sendQuery("BEGIN");
            client.readUntilReadyForQuery();
            client.sendQuery("INSERT INTO tx_t VALUES (30, 'savepoint')");
            client.readUntilReadyForQuery();

            client.sendQuery("ROLLBACK TO SAVEPOINT s_never_declared");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();
            assertEquals("EZ", PgWireClient.types(frames));
            assertEquals("0A000", PgWireClient.errorField(frames, 'C'));
            assertEquals('E', PgWireClient.readyStatus(frames),
                    "语句只是报错，事务块必须仍在（'E'）；若是 'I' 说明被当成了整事务回滚");

            client.sendQuery("ROLLBACK");
            client.readUntilReadyForQuery();
            assertEquals(0, rowCount(client));
        }
    }

    @Test
    void extendedProtocolSavepointFailsPredictably() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendParse("sp", "SAVEPOINT s1");
            client.sendBind("sp_p", "sp");
            client.sendExecute("sp_p", 0);
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("12EZ", PgWireClient.types(frames),
                    "扩展协议下也应给出同一个可预期的错误，实际=" + PgWireClient.types(frames));
            assertEquals("25P01", PgWireClient.errorField(frames, 'C'));
        }
    }

    /** 扩展协议路径下的事务语义与简单查询一致。 */
    @Test
    void extendedProtocolTransactionsAreReal() throws Exception {
        try (PgWireClient client = newClient()) {
            clearTable(client);

            client.sendParse("tx_begin", "START TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            client.sendBind("tx_begin_p", "tx_begin");
            client.sendExecute("tx_begin_p", 0);
            client.sendSync();
            List<PgWireClient.Frame> started = client.readUntilReadyForQuery();
            assertEquals("12CZ", PgWireClient.types(started),
                    "扩展协议下的 START TRANSACTION 应答不符，实际=" + PgWireClient.types(started));
            assertEquals('T', PgWireClient.readyStatus(started));

            client.sendQuery("INSERT INTO tx_t VALUES (20, 'e')");
            client.readUntilReadyForQuery();

            client.sendParse("tx_rollback", "ROLLBACK");
            client.sendBind("tx_rollback_p", "tx_rollback");
            client.sendExecute("tx_rollback_p", 0);
            client.sendSync();
            List<PgWireClient.Frame> rolledBack = client.readUntilReadyForQuery();
            assertEquals("12CZ", PgWireClient.types(rolledBack));
            assertEquals("ROLLBACK", PgWireClient.ofType(rolledBack, 'C').get(0).text());
            assertEquals('I', PgWireClient.readyStatus(rolledBack));
            assertEquals(0, rowCount(client), "扩展协议路径的 ROLLBACK 也必须真的回滚");
        }
    }
}
