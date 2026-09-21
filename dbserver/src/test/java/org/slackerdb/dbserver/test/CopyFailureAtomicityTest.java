package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.sql.PostgresSQLUtil;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失败的 COPY 必须整体不生效——不能留下"半截数据"。
 *
 * <p>背景：{@code DuckDBAppender} 在事务之外 {@code close()} 会<b>立即提交</b>。
 * 原来的实现里，只要第 N 行出错，前 N-1 行就已经 append 进 Appender 了，
 * 而错误收尾依然会（直接或间接）调用 {@code close()}，于是这些"半截数据"被提交进表——
 * 这与 PG 语义（失败的 COPY 整条语句不生效）不符。</p>
 *
 * <p>修法：QueryRequest 在创建 Appender 之前用显式事务包裹本次 COPY，
 * CopyDoneRequest 失败时整体 ROLLBACK（实测 JDBC 的 setAutoCommit(false)+rollback()
 * 对 Appender 无效，必须用显式 BEGIN/COMMIT/ROLLBACK）。</p>
 *
 * <p>下面每个用例都特意构造"前几行合法、后面某行非法"的数据，
 * 这样如果失败路径没有回滚，前几行就会留下来，断言立刻能发现。</p>
 */
public class CopyFailureAtomicityTest {

    static int dbPort;
    static DBInstance dbInstance;

    @BeforeAll
    static void initAll() throws ServerException {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setData("mem");
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("OFF");
        dbPort = serverConfiguration.getPort();

        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();
        assertTrue(dbInstance.instanceState.equalsIgnoreCase("RUNNING"));
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + dbPort + "/mem", "", "");
    }

    private static long copyIn(Connection conn, String sql, String csv) throws Exception {
        return new CopyManager((BaseConnection) conn).copyIn(sql, new StringReader(csv));
    }

    private static long copyIn(Connection conn, String sql, byte[] binary) throws Exception {
        // ByteArrayInputStream 不持有系统资源，close() 无副作用，无需 try-with-resources
        return new CopyManager((BaseConnection) conn).copyIn(sql, new ByteArrayInputStream(binary));
    }

    /** 用同一条连接统计——这样未提交的数据也能被看到，能真实反映"是否留下来了"。 */
    private static long count(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ============================================================
    // CSV
    // ============================================================

    /**
     * CSV 第 1 行合法、第 2 行的 id 不是数字（抛 NumberFormatException）。
     * 该异常走 catch 分支，修复前会一路落到方法尾部 close() Appender 从而提交第 1 行。
     */
    @Test
    void csvTypeErrorAfterValidRowLeavesNoRows() throws Exception {
        try (Connection conn = connect()) {
            conn.createStatement().execute(
                    "create or replace table t_csv_atomic(id integer, name varchar, note varchar)");

            boolean failed = false;
            try {
                copyIn(conn, "COPY t_csv_atomic FROM STDIN WITH (FORMAT csv)",
                        "1,Alice,x\nnot-a-number,Bob,y\n");
            } catch (SQLException e) {
                failed = true;
            }
            assertTrue(failed, "第 2 行 id 不是数字，必须报错");
            assertEquals(0, count(conn, "t_csv_atomic"),
                    "失败的 COPY 不能留下已写入的部分行（第 1 行）");
        }
    }

    /** 列数不符：前两行合法、第 3 行列数不足。同样不能留下前两行。 */
    @Test
    void csvColumnCountMismatchLeavesNoRows() throws Exception {
        try (Connection conn = connect()) {
            conn.createStatement().execute(
                    "create or replace table t_csv_atomic2(id integer, name varchar, note varchar)");

            boolean failed = false;
            try {
                copyIn(conn, "COPY t_csv_atomic2 FROM STDIN WITH (FORMAT csv)",
                        "1,Alice,x\n2,Bob,y\n3,Cathy\n");
            } catch (SQLException e) {
                failed = true;
                assertTrue(e.getMessage() != null && e.getMessage().contains("column size not match"),
                        "应报列数不符，实际: " + e.getMessage());
            }
            assertTrue(failed, "列数不符必须报错");
            assertEquals(0, count(conn, "t_csv_atomic2"),
                    "失败的 COPY 不能留下已写入的部分行（前两行）");
        }
    }

    /**
     * 第 1 行完整合法、第 2 行 CSV 本身畸形（引号未闭合）。
     *
     * <p>这是最能暴露问题的一种：畸形在第 2 行<b>解析阶段</b>就抛出（不是 append 阶段），
     * 所以第 1 行已经干净地 append 完成、Appender 也不处于"半行"状态，
     * 异常走 catch 分支后修复前会一路落到方法尾部执行 {@code close()} 从而把第 1 行提交掉。</p>
     */
    @Test
    void csvMalformedRowAfterValidRowLeavesNoRows() throws Exception {
        try (Connection conn = connect()) {
            conn.createStatement().execute(
                    "create or replace table t_csv_atomic3(id integer, name varchar, note varchar)");

            boolean failed = false;
            try {
                copyIn(conn, "COPY t_csv_atomic3 FROM STDIN WITH (FORMAT csv)",
                        "1,Alice,x\n2,\"unclosed\n");
            } catch (SQLException e) {
                failed = true;
            }
            assertTrue(failed, "引号未闭合必须报错");
            assertEquals(0, count(conn, "t_csv_atomic3"),
                    "失败的 COPY 不能留下已写入的部分行（第 1 行）");
        }
    }

    /**
     * 失败后即使会话结束，部分行也不能被"迟到"提交。
     *
     * <p>覆盖提前 return 的分支（列数不符）：修复前这些分支直接 return，
     * 跳过了方法尾部，Appender 一直开着；等到会话结束 {@code closeSession()}
     * 再去 close() 它时，前两行就被提交进表了。这里用一条<b>新连接</b>来观察最终落库结果。</p>
     */
    @Test
    void partialRowsAreNotCommittedWhenSessionEnds() throws Exception {
        try (Connection conn = connect()) {
            conn.createStatement().execute(
                    "create or replace table t_csv_atomic4(id integer, name varchar, note varchar)");
            try {
                // 前两行合法、第 3 行列数不足 ⇒ 走提前 return 的错误分支
                copyIn(conn, "COPY t_csv_atomic4 FROM STDIN WITH (FORMAT csv)",
                        "1,Alice,x\n2,Bob,y\n3,Cathy\n");
                assertTrue(false, "列数不符应当报错");
            } catch (SQLException expected) {
                // 预期失败
            }
        } // 会话在此关闭

        try (Connection fresh = connect()) {
            assertEquals(0, count(fresh, "t_csv_atomic4"),
                    "失败的 COPY 的部分行不能因为会话结束时的 Appender.close() 而被提交");
        }
    }

    // ============================================================
    // BINARY
    // ============================================================

    /**
     * BINARY 第 1 行是合法 INTEGER(4 字节)，第 2 行给了 Long(8 字节) ⇒ 长度校验失败。
     * 前 1 行已 append，失败路径必须把它丢掉。
     */
    @Test
    void binaryTypeMismatchAfterValidRowLeavesNoRows() throws Exception {
        byte[] payload = PostgresSQLUtil.convertPGRowToByte(List.of(
                new Object[]{1},      // Integer -> 4 字节，与 INT 列匹配
                new Object[]{2L}));   // Long    -> 8 字节，长度不符

        try (Connection conn = connect()) {
            conn.createStatement().execute("create or replace table t_bin_atomic(id integer)");

            boolean failed = false;
            try {
                copyIn(conn, "COPY t_bin_atomic (id) FROM STDIN WITH (FORMAT BINARY)", payload);
            } catch (SQLException e) {
                failed = true;
                assertTrue(e.getMessage() != null && e.getMessage().contains("data type mismatch"),
                        "应报类型/长度不匹配，实际: " + e.getMessage());
            }
            assertTrue(failed, "长度不匹配必须报错");
            assertEquals(0, count(conn, "t_bin_atomic"),
                    "失败的 COPY 不能留下已写入的部分行（第 1 行）");
        }
    }

    // ============================================================
    // 成功路径不能受影响
    // ============================================================

    @Test
    void csvSuccessStillCommitsEveryRow() throws Exception {
        try (Connection conn = connect()) {
            conn.createStatement().execute(
                    "create or replace table t_csv_ok(id integer, name varchar, note varchar)");

            long n = copyIn(conn, "COPY t_csv_ok FROM STDIN WITH (FORMAT csv)",
                    "1,Alice,x\n2,Bob,y\n3,Cathy,z\n");

            assertEquals(3, n, "copyIn 应返回写入行数");
            assertEquals(3, count(conn, "t_csv_ok"), "成功的 COPY 必须把数据提交进表");
        }
    }

    @Test
    void binarySuccessStillCommitsEveryRow() throws Exception {
        byte[] payload = PostgresSQLUtil.convertPGRowToByte(List.of(
                new Object[]{1},
                new Object[]{2},
                new Object[]{3}));

        try (Connection conn = connect()) {
            conn.createStatement().execute("create or replace table t_bin_ok(id integer)");

            long n = copyIn(conn, "COPY t_bin_ok (id) FROM STDIN WITH (FORMAT BINARY)", payload);

            assertEquals(3, n, "copyIn 应返回写入行数");
            assertEquals(3, count(conn, "t_bin_ok"), "成功的 COPY 必须把数据提交进表");
        }
    }

    /** 失败之后同一连接必须仍可用，且下一次 COPY 能正常提交。 */
    @Test
    void sessionUsableAfterFailureAndNextCopyCommits() throws Exception {
        try (Connection conn = connect()) {
            conn.createStatement().execute(
                    "create or replace table t_recover(id integer, name varchar, note varchar)");

            try {
                copyIn(conn, "COPY t_recover FROM STDIN WITH (FORMAT csv)", "1,Alice,x\nbad,Bob,y\n");
                assertTrue(false, "应当报错");
            } catch (SQLException expected) {
                // 预期的失败
            }
            assertEquals(0, count(conn, "t_recover"), "失败后不应有残留数据");

            // 同一条连接继续做一次成功的 COPY
            long n = copyIn(conn, "COPY t_recover FROM STDIN WITH (FORMAT csv)", "7,Tom,t\n8,Jerry,j\n");
            assertEquals(2, n);
            assertEquals(2, count(conn, "t_recover"), "失败后的下一次 COPY 必须正常提交");
        }
    }

    /**
     * 客户端自己开的事务不能被失败的 COPY 连带回滚：
     * 服务端只回滚"自己为 COPY 开的事务"，客户端事务里先前的工作必须保留。
     */
    @Test
    void failedCopyDoesNotRollBackClientTransaction() throws Exception {
        try (Connection conn = connect()) {
            conn.createStatement().execute("create or replace table t_client_txn(id integer)");
            conn.createStatement().execute("begin");
            conn.createStatement().execute("insert into t_client_txn values (1)");

            boolean failed = false;
            try {
                // 第 1 行就非法，确保没有部分行干扰断言
                copyIn(conn, "COPY t_client_txn FROM STDIN WITH (FORMAT csv)", "not-a-number\n");
            } catch (SQLException e) {
                failed = true;
            }
            assertTrue(failed, "非法输入必须报错");

            assertEquals(1, count(conn, "t_client_txn"),
                    "失败的 COPY 不应把客户端事务里先前插入的行一起回滚");

            conn.createStatement().execute("rollback");
            assertEquals(0, count(conn, "t_client_txn"), "客户端 ROLLBACK 后应清空");
        }
    }

    /** 成功路径在客户端事务中也不能替客户端提交。 */
    @Test
    void successfulCopyInsideClientTransactionIsNotAutoCommitted() throws Exception {
        try (Connection conn = connect()) {
            conn.createStatement().execute("create or replace table t_client_txn2(id integer)");
            conn.createStatement().execute("begin");

            long n = copyIn(conn, "COPY t_client_txn2 FROM STDIN WITH (FORMAT csv)", "1\n2\n");
            assertEquals(2, n);
            assertEquals(2, count(conn, "t_client_txn2"), "事务内应可见");

            conn.createStatement().execute("rollback");
            assertEquals(0, count(conn, "t_client_txn2"),
                    "服务端不应替客户端提交：ROLLBACK 后数据必须消失");
        }
    }
}
