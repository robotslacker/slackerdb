package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBDataSourcePool;
import org.slackerdb.dbserver.server.DBInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * 回归：握手（{@code StartupRequest}）失败时，必须把已经借出的后端连接还回连接池。
 *
 * <p><b>为什么单独立这个用例</b>：{@code StartupRequest} 取得连接后要依次执行
 * {@code set current_database} / {@code set search_path} / {@code use <db>}，
 * 中间任何一步失败都会提前返回。历史实现只在 {@code use} 失败的那条分支里归还连接，
 * 最外层 {@code catch (SQLException)} 只 {@code ctx.close()} 就返回了——于是
 * <b>每失败一次就泄漏一个池槽位</b>。池上限是 256，泄漏满之后整台服务器
 * （pgwire、数据服务、MCP 共用一个池）再也建不起任何连接，而且不会自愈。</p>
 *
 * <p>本用例覆盖两条提前返回路径：</p>
 * <ol>
 *   <li>{@link #failedUseDoesNotLeakPooledConnection()} —— {@code use <不存在的库>} 失败；</li>
 *   <li>{@link #failedSqlDuringStartupDoesNotLeakPooledConnection()} —— 握手期的 SQL 直接语法
 *       报错（库名里带单引号，拼进 {@code set variable current_database = '<库名>'} 就是坏 SQL），
 *       走的是最外层 {@code catch} 分支。</li>
 * </ol>
 *
 * <p>两条都断言"失败之后使用中的连接数回到 0"以及"随后仍能正常建连"——
 * 后者才是用户真正看到的现象：池被泄漏光之后连正确的库也连不上。</p>
 */
public class StartupFailureLeakTest {

    /** 池里"使用中"的连接数必须能在超时内回到期望值（失败与归还之间有很小的时序竞争）。 */
    private static int awaitUsedConnections(DBDataSourcePool pool, int expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        int used;
        do {
            used = pool.getUsedConnectionPoolSize();
            if (used == expected) {
                return used;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        return used;
    }

    private static ServerConfiguration newConfig(String instanceName) {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData(instanceName);
        cfg.setData_dir(":memory:");
        return cfg;
    }

    @Test
    void failedUseDoesNotLeakPooledConnection() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        final String instanceName = "leakprobe";
        ServerConfiguration cfg = newConfig(instanceName);
        DBInstance instance = new DBInstance(cfg);
        instance.start();
        try {
            String badUrl = "jdbc:postgresql://127.0.0.1:" + cfg.getPort() + "/nosuchdb";

            // 反复失败：每一次都必须把槽位还回来，不能累积
            for (int attempt = 1; attempt <= 5; attempt++) {
                String message;
                try (Connection conn = DriverManager.getConnection(badUrl, "", "")) {
                    assert false : "连接不存在的库 [" + badUrl + "] 不应该成功（拿到连接 " + conn + "）";
                    return;
                } catch (SQLException expected) {
                    message = String.valueOf(expected.getMessage());
                }
                assert message.contains("does not exist")
                        : "连接不存在的库应当收到服务端的 3D000 说明，实际: " + message;

                int used = awaitUsedConnections(instance.dbDataSourcePool, 0, 2_000);
                assert used == 0
                        : "第 " + attempt + " 次握手失败后仍有 " + used + " 条连接挂在池上"
                                + "（每次握手失败都必须归还，否则 256 次之后整台服务器拒收所有连接）";
            }

            // 失败若干次之后，正常连接必须仍然可用（槽位没有被耗光）
            assertInstanceIsStillUsable(cfg, instanceName);
        } finally {
            instance.stop();
        }
    }

    @Test
    void failedSqlDuringStartupDoesNotLeakPooledConnection() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        final String instanceName = "leakprobe2";
        ServerConfiguration cfg = newConfig(instanceName);
        DBInstance instance = new DBInstance(cfg);
        instance.start();
        try {
            // 库名里带一个单引号（URL 里写成 %27）：拼进
            // "set variable current_database = '<库名>'" 就是坏 SQL，
            // 于是握手在第一条语句上就抛 SQLException —— 走的正是最外层 catch 分支。
            String brokenUrl = "jdbc:postgresql://127.0.0.1:" + cfg.getPort() + "/leak%27probe";
            String message;
            try (Connection conn = DriverManager.getConnection(brokenUrl, "", "")) {
                assert false : "握手期 SQL 报错的连接不应该成功（拿到连接 " + conn + "）";
                return;
            } catch (SQLException expected) {
                message = String.valueOf(expected.getMessage());
            }
            // 确认走的不是 use 失败那条分支（那条会给 3D000 说明），而是最外层 catch：
            // 两者都会归还连接，但只有后者曾经漏掉归还。
            assert !message.contains("does not exist")
                    : "本用例要求命中握手期 SQL 报错那条分支，实际拿到的是 use 失败的报错: " + message;

            int used = awaitUsedConnections(instance.dbDataSourcePool, 0, 2_000);
            assert used == 0
                    : "握手期 SQL 报错后仍有 " + used + " 条连接挂在池上 —— "
                            + "最外层 catch 必须归还连接，否则每次失败都泄漏一个槽位";

            assertInstanceIsStillUsable(cfg, instanceName);
        } finally {
            instance.stop();
        }
    }

    /** 失败之后实例必须仍然可用：能正常建连并执行查询。 */
    private static void assertInstanceIsStillUsable(ServerConfiguration cfg, String instanceName)
            throws SQLException {
        String url = "jdbc:postgresql://127.0.0.1:" + cfg.getPort() + "/" + instanceName;
        try (Connection conn = DriverManager.getConnection(url, "", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            assert rs.next() && rs.getInt(1) == 1
                    : "握手失败若干次之后，正常连接也必须仍然可用";
        }
    }
}
