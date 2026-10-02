package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.core.BaseConnection;
import org.postgresql.copy.CopyManager;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL 审计覆盖面测试：<b>任何执行过 SQL 的入口都必须进历史表</b>。
 *
 * <p>设计要求是"记不下审计就阻塞业务、并且任何情况下不丢弃"，前提当然是<b>每条语句都得先被登记</b>。
 * 改造前只有扩展协议（Parse/Bind/Execute）会写历史，简单查询（'Q'）和 COPY 一条都不写 ——
 * 也就是说 psql、{@code Statement.execute("a;b;c")}、{@code preferQueryMode=simple}、
 * 以及 {@code CopyManager} 发出的语句在审计里完全不存在。</p>
 *
 * <p>本用例覆盖这些入口：</p>
 * <ul>
 *   <li>简单查询（'Q'）：成功、失败、以及被 SQLReplacer 改写成空语句的 {@code SET}；</li>
 *   <li>扩展协议（默认模式）：成功、失败；</li>
 *   <li>COPY FROM STDIN：成功、被客户端 CopyFail 放弃。</li>
 * </ul>
 *
 * <p>历史是异步落库的（消费线程每 1000 行或队列排空时提交），所以断言前用带超时的轮询等待。</p>
 */
public class SqlHistoryCoverageTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("histcov");
        cfg.setSqlHistory("ON");
        cfg.setLog_level("INFO");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:" + dbPort + "/histcov", "", "");
    }

    private static Connection connectSimpleMode() throws Exception {
        return DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:" + dbPort + "/histcov?preferQueryMode=simple", "", "");
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /**
     * 历史表里出现过该 SQL 文本的语句条数。
     *
     * <p>注意：历史表本身<b>没有</b> Type 列 —— {@code INSERT}/{@code UPDATE} 只是消费线程
     * 用来决定"插入新行 / 更新已有行"的记录类型，落库后同一个语句只会有一行。</p>
     */
    private static long historyCount(String sqlFragment) {
        return queryLong("SELECT COUNT(*) FROM memory.sysaux.SQL_HISTORY WHERE SQL LIKE ?", sqlFragment);
    }

    /** 该语句的审计是否已经收尾（EndTime 已回填）。 */
    private static long historyClosedCount(String sqlFragment) {
        return queryLong("SELECT COUNT(*) FROM memory.sysaux.SQL_HISTORY "
                + "WHERE EndTime IS NOT NULL AND SQL LIKE ?", sqlFragment);
    }

    /** 该语句的审计是否带上了错误信息。 */
    private static long historyErrorCount(String sqlFragment) {
        return queryLong("SELECT COUNT(*) FROM memory.sysaux.SQL_HISTORY "
                + "WHERE ErrorMsg IS NOT NULL AND ErrorMsg <> '' AND SQL LIKE ?", sqlFragment);
    }

    /** 该语句审计里记录的影响行数（取最大值）。 */
    private static long historyAffectedRows(String sqlFragment) {
        return queryLong("SELECT COALESCE(MAX(AffectedRows), 0) FROM memory.sysaux.SQL_HISTORY "
                + "WHERE SQL LIKE ?", sqlFragment);
    }

    private static long queryLong(String sql, String sqlFragment) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + sqlFragment + "%");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
        catch (Exception e) {
            // 表还没建好或正被消费线程重建时按 0 处理
            return 0;
        }
    }

    private static void awaitHistory(String sqlFragment, String scene) throws InterruptedException {
        awaitTrue(() -> historyCount(sqlFragment) > 0, 20_000,
                scene + " 没有进历史表（找不到包含 [" + sqlFragment + "] 的审计记录）"
                        + "；当前历史表内容=" + auditedStatements());
    }

    private static void awaitTrue(BooleanSupplier condition, long timeoutMs, String message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    // ================================================================
    // 一、简单查询路径（'Q'）—— 改造前这条路径一条历史都不写
    // ================================================================

    @Test
    void simpleQueryStatementsAreAudited() throws Exception {
        try (Connection conn = connectSimpleMode()) {
            exec(conn, "CREATE OR REPLACE TABLE hist_simple(id INTEGER, note VARCHAR)");
            exec(conn, "INSERT INTO hist_simple VALUES (1, 'simple-marker')");

            long rows;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT note FROM hist_simple WHERE id = 1")) {
                rs.next();
                rows = rs.getString(1).equals("simple-marker") ? 1 : 0;
            }
            assertTrue(rows == 1, "用例前提不成立：简单查询没能读到数据");
        }

        awaitHistory("CREATE OR REPLACE TABLE hist_simple", "简单查询 DDL");
        awaitHistory("INSERT INTO hist_simple VALUES (1, 'simple-marker')", "简单查询 INSERT");
        awaitHistory("SELECT note FROM hist_simple WHERE id = 1", "简单查询 SELECT");
    }

    /** 被 SQLReplacer 改写成空语句的 SET 也要留下痕迹（审计记的是客户端原文）。 */
    @Test
    void rewrittenToEmptyStatementsAreAudited() throws Exception {
        try (Connection conn = connectSimpleMode()) {
            exec(conn, "SET extra_float_digits = 3");
        }

        awaitHistory("SET extra_float_digits = 3", "被改写成空语句的 SET");
    }

    /** 失败的简单查询同样要进历史表（并且带上错误信息）。 */
    @Test
    void failedSimpleQueryIsAudited() throws Exception {
        try (Connection conn = connectSimpleMode()) {
            try {
                exec(conn, "SELECT * FROM hist_no_such_table_marker");
            }
            catch (SQLException expected) {
                // 预期失败
            }
        }

        awaitHistory("hist_no_such_table_marker", "失败的简单查询");

        // 失败的那条记录必须被收尾成"有错误信息"，而不是停在"进行中"
        awaitTrue(() -> historyErrorCount("hist_no_such_table_marker") > 0, 20_000,
                "失败的简单查询没有被收尾（历史记录里没有错误信息）");
    }

    // ================================================================
    // 二、扩展协议路径（默认模式）—— 改造前已覆盖，这里防回归
    // ================================================================

    @Test
    void extendedProtocolStatementsAreAudited() throws Exception {
        try (Connection conn = connect()) {
            exec(conn, "CREATE OR REPLACE TABLE hist_ext(id INTEGER, note VARCHAR)");

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO hist_ext VALUES (?, ?)")) {
                ps.setInt(1, 1);
                ps.setString(2, "extended-marker");
                ps.executeUpdate();
            }
        }

        // 审计记录的是**实际执行**的 SQL：扩展协议路径上 SQLReplacer 会把 `?` 改写成
        // DuckDB 的 `$n` 占位符，所以历史里的文本是 "VALUES ($1, $2)" 而不是 "(?, ?)"。
        awaitHistory("INSERT INTO hist_ext VALUES ($1, $2)", "扩展协议 INSERT（参数化）");
    }

    /**
     * 扩展协议下被改写成空语句的 {@code SET} 也要进审计。
     *
     * <p>改写后的 SQL 是空串，审计只能靠 Parse 阶段留下的客户端原文；
     * 改造前这条路径在历史表里什么都看不到（简单查询路径则会留下记录，两条路径口径不一致）。</p>
     */
    @Test
    void extendedProtocolSetIsAuditedUsingClientText() throws Exception {
        try (Connection conn = connect()) {
            exec(conn, "SET extra_float_digits = 3");
        }

        awaitHistory("SET extra_float_digits = 3", "扩展协议 SET");
    }

    /**
     * 失败事务块里被 25P02 拒绝的语句也要进审计，<b>简单查询与扩展协议两条路径都要</b>。
     *
     * <p>用 autoCommit=false 让驱动自己开事务块：语句失败后事务块进入 aborted 状态，
     * 后续语句必被 25P02 拒绝。两条路径的失败点不同 —— 简单查询在 prepare/execute，
     * 扩展协议通常落在 Parse（表不存在这类错误 DuckDB 在 prepare 时就报），
     * 因此这条用例同时守着"Parse/Bind 失败也要中止事务块"这个语义（见 TransactionSemanticsTest）。</p>
     */
    @Test
    void rejectedStatementInsideFailedTransactionIsAudited() throws Exception {
        assertRejectedStatementIsAudited("simple", "rejected_marker_25p02_simple");
        assertRejectedStatementIsAudited("extended", "rejected_marker_25p02_ext");
    }

    private static void assertRejectedStatementIsAudited(String mode, String marker) throws Exception {
        SQLException rejection = null;
        try (Connection conn = mode.equals("simple") ? connectSimpleMode() : connect()) {
            conn.setAutoCommit(false);
            try {
                exec(conn, "SELECT * FROM hist_rejected_marker_" + mode);
            }
            catch (SQLException expected) {
                // 先把事务块打成失败状态
            }
            try {
                exec(conn, "SELECT 42 AS " + marker);
            }
            catch (SQLException expected) {
                // 预期被 25P02 拒绝
                rejection = expected;
            }
            conn.rollback();
        }

        awaitHistory("hist_rejected_marker_" + mode, mode + "：失败事务里的第一条语句");
        awaitHistory(marker, mode + "：失败事务里被拒绝的语句");
        final SQLException clientSide = rejection;
        awaitTrue(() -> historyErrorCount(marker) > 0, 20_000,
                mode + "：被拒绝的语句没有被收尾成错误记录"
                        + "；客户端收到=" + (clientSide == null
                        ? "<没有异常：语句居然执行成功了>"
                        : clientSide.getSQLState() + ":" + clientSide.getMessage())
                        + "；审计行=" + auditRow(marker)
                        + "；全表=" + auditedStatements());
    }

    /** 取某条语句的审计行详情（SQL / EndTime / SqlCode / ErrorMsg），失败时用于定位。 */
    private static String auditRow(String sqlFragment) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT SQL, EndTime, SqlCode, ErrorMsg FROM memory.sysaux.SQL_HISTORY "
                             + "WHERE SQL LIKE ? ORDER BY ID")) {
            ps.setString(1, "%" + sqlFragment + "%");
            try (ResultSet rs = ps.executeQuery()) {
                List<String> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add("sql=[" + rs.getString(1) + "] end=[" + rs.getObject(2)
                            + "] code=" + rs.getInt(3) + " err=[" + rs.getString(4) + "]");
                }
                return rows.toString();
            }
        }
        catch (Exception e) {
            return "<unreadable: " + e.getMessage() + ">";
        }
    }

    // ================================================================
    // 三、COPY FROM STDIN —— 改造前这条路径一条历史都不写
    // ================================================================

    @Test
    void copyFromStdinIsAudited() throws Exception {
        try (Connection conn = connect()) {
            exec(conn, "CREATE OR REPLACE TABLE hist_copy(id INTEGER)");

            CopyManager copyManager = new CopyManager(conn.unwrap(BaseConnection.class));
            long rows = copyManager.copyIn("COPY hist_copy FROM STDIN (FORMAT csv)",
                    new StringReader("1\n2\n3\n"));
            assertTrue(rows == 3, "COPY 应写入 3 行，实际 " + rows);
        }

        awaitHistory("COPY hist_copy FROM STDIN", "COPY FROM STDIN");

        // COPY 的记录必须带上写入行数（收尾到 AffectedRows）
        awaitTrue(() -> historyAffectedRows("COPY hist_copy FROM STDIN") == 3, 20_000,
                "COPY 的审计记录没有回填写入行数（期望 3）");
    }

    /** 客户端中途放弃的 COPY 也要收尾（不能留下永远"进行中"的审计记录）。 */
    @Test
    void abortedCopyIsAuditedAndClosed() throws Exception {
        try (Connection conn = connect()) {
            exec(conn, "CREATE OR REPLACE TABLE hist_copy_fail(id INTEGER)");

            CopyManager copyManager = new CopyManager(conn.unwrap(BaseConnection.class));
            boolean failed = false;
            try {
                copyManager.copyIn("COPY hist_copy_fail FROM STDIN (FORMAT csv)",
                        new StringReader("1\nnot-a-number\n"));
            }
            catch (SQLException expected) {
                failed = true;
            }
            assertTrue(failed, "用例前提不成立：类型不匹配的 COPY 应当失败");

            // 会话必须仍然可用
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT 1")) {
                assertTrue(rs.next(), "COPY 失败之后会话不可用");
            }
        }

        awaitHistory("COPY hist_copy_fail FROM STDIN", "失败的 COPY");
        awaitTrue(() -> historyClosedCount("COPY hist_copy_fail FROM STDIN") > 0, 20_000,
                "失败的 COPY 没有被收尾（审计记录停在\"进行中\"）");
        assertTrue(historyAffectedRows("COPY hist_copy_fail FROM STDIN") == 0,
                "失败的 COPY 不应记录写入行数");
    }

    /** 汇总辅助：打印当前审计到的语句文本，失败时便于定位。 */
    private static List<String> auditedStatements() {
        List<String> statements = new ArrayList<>();
        try (Connection conn = connect();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT SQL FROM memory.sysaux.SQL_HISTORY ORDER BY ID")) {
            while (rs.next()) {
                statements.add(rs.getString(1));
            }
        }
        catch (Exception e) {
            statements.add("<history unreadable: " + e.getMessage() + ">");
        }
        return statements;
    }

    @Test
    void auditTrailContainsEveryStatementFamily() throws Exception {
        // 依次跑一遍全部入口，最后一次性核对（便于失败时看全貌）
        try (Connection simple = connectSimpleMode()) {
            exec(simple, "CREATE OR REPLACE TABLE hist_all(id INTEGER)");
            exec(simple, "INSERT INTO hist_all VALUES (1)");
            exec(simple, "SET extra_float_digits = 3");
        }
        try (Connection extended = connect()) {
            try (PreparedStatement ps = extended.prepareStatement(
                    "INSERT INTO hist_all VALUES (?)")) {
                ps.setInt(1, 2);
                ps.executeUpdate();
            }
            CopyManager copyManager = new CopyManager(extended.unwrap(BaseConnection.class));
            copyManager.copyIn("COPY hist_all FROM STDIN (FORMAT csv)", new StringReader("3\n"));
        }

        awaitHistory("INSERT INTO hist_all VALUES (1)", "简单查询 INSERT");
        awaitHistory("INSERT INTO hist_all VALUES ($1)", "扩展协议 INSERT");
        awaitHistory("SET extra_float_digits = 3", "SET");
        awaitHistory("COPY hist_all FROM STDIN", "COPY");

        List<String> audited = auditedStatements();
        for (String expected : List.of("INSERT INTO hist_all VALUES (1)",
                "INSERT INTO hist_all VALUES ($1)",
                "SET extra_float_digits = 3",
                "COPY hist_all FROM STDIN")) {
            assertFalse(audited.stream().noneMatch(s -> s != null && s.contains(expected)),
                    "历史表里缺少语句 [" + expected + "]，实际记录=" + audited);
        }
    }
}
