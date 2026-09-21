package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H5 验收测试：把 JDBC 业务处理从 Netty I/O 线程上摘下来之后，
 * <b>一条慢查询不再拖住其它连接</b>。
 *
 * <p>测量方法：固定 2 个 I/O 线程、16 个连接（因此每个 event loop 上恰好 8 个连接）。
 * 在 conn0 上跑一条数秒的重查询，同时让其余 15 个连接<b>同时</b>各发一条 {@code SELECT 1}，
 * 观察它们的延迟。</p>
 *
 * <p>两个用例构成正负对照：</p>
 * <ul>
 *   <li>{@code withoutBusinessExecutor_longQueryBlocksSameEventLoop}（负对照）：
 *       {@code business_threads=0}（旧行为）—— 与长查询同 event loop 的连接会被拖满整个查询时长；</li>
 *   <li>{@code businessExecutor_preventHeadOfLineBlocking}（正例）：
 *       业务线程数 ≥ 连接数 —— 没有任何连接被拖累。</li>
 * </ul>
 *
 * <p>为避免机器快慢导致断言失效，判定用的是"探针延迟占长查询时长的比例"而不是绝对毫秒数。</p>
 */
public class BusinessExecutorTest {

    private static final int IO_THREADS = 2;
    private static final int CONNECTIONS = 16;

    /** 一条可压满 DuckDB、持续数秒的重查询。 */
    private static final String LONG_QUERY =
            "SELECT count(*) FROM range(15000) a, range(15000) b WHERE (a.range + b.range) % 7 = 0";

    @Test
    void withoutBusinessExecutor_longQueryBlocksSameEventLoop() throws Exception {
        ProbeResult r = runProbe(0);   // 0 = 关闭业务线程组，回到旧行为
        System.out.println("BIZEXEC disabled: longQueryMs=" + r.longQueryMs
                + " maxProbeMs=" + r.maxProbeUs / 1000 + " blocked=" + r.blockedCount);
        assert r.longQueryMs > 300 : "长查询太短，用例无效：" + r.longQueryMs + "ms";
        assert r.blockedCount > 0
                : "旧行为下应当有连接被拖累，但没有任何探针超过 1s（max=" + r.maxProbeUs / 1000 + "ms）";
        assert r.maxProbeUs > r.longQueryMs * 1000 / 2
                : "被拖累的探针延迟(" + r.maxProbeUs / 1000 + "ms)应接近长查询时长(" + r.longQueryMs + "ms)";
    }

    @Test
    void businessExecutor_preventHeadOfLineBlocking() throws Exception {
        ProbeResult r = runProbe(CONNECTIONS * 2);   // 业务线程数 >= 连接数
        System.out.println("BIZEXEC enabled: longQueryMs=" + r.longQueryMs
                + " maxProbeMs=" + r.maxProbeUs / 1000 + " blocked=" + r.blockedCount);
        assert r.longQueryMs > 300 : "长查询太短，用例无效：" + r.longQueryMs + "ms";
        assert r.blockedCount == 0
                : "启用业务线程组后不应再有连接被拖累，但有 " + r.blockedCount
                  + " 个探针超过 1s（max=" + r.maxProbeUs / 1000 + "ms）";
        assert r.maxProbeUs < r.longQueryMs * 1000 / 10
                : "探针延迟(" + r.maxProbeUs / 1000 + "ms)应远小于长查询时长(" + r.longQueryMs + "ms)";
    }

    private static final class ProbeResult {
        long longQueryMs;
        long maxProbeUs;
        int blockedCount;
    }

    private ProbeResult runProbe(int businessThreads) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("bizexec");
        cfg.setLog_level("INFO");
        cfg.setSqlHistory("OFF");
        cfg.setMax_workers(IO_THREADS);
        cfg.setBusiness_threads(businessThreads);
        int dbPort = cfg.getPort();

        DBInstance dbInstance = new DBInstance(cfg);
        dbInstance.start();

        String url = "jdbc:postgresql://127.0.0.1:" + dbPort + "/bizexec";
        List<Connection> conns = new ArrayList<>();
        try {
            for (int i = 0; i < CONNECTIONS; i++) {
                Connection c = DriverManager.getConnection(url, "", "");
                c.setAutoCommit(true);
                conns.add(c);
            }
            // 预热
            for (Connection c : conns) {
                timeOne(c, "SELECT 1");
            }

            ProbeResult result = new ProbeResult();

            // 单独量一次长查询时长（作为判定基准）
            result.longQueryMs = timeOne(conns.get(0), LONG_QUERY) / 1_000_000L;

            Thread longRunner = new Thread(() -> {
                try (Statement stmt = conns.get(0).createStatement()) {
                    stmt.executeQuery(LONG_QUERY).close();
                } catch (Exception ignored) {
                }
            });
            longRunner.start();
            Thread.sleep(200);   // 让长查询先跑起来

            // 所有探针必须同时放行：串行测会被"第一个被阻塞的探针"吃掉整个时间窗，
            // 后面的连接其实是在长查询结束之后才被测到（会得出错误结论）。
            final AtomicLong maxProbeUs = new AtomicLong();
            final AtomicLong blockedCount = new AtomicLong();
            final CountDownLatch ready = new CountDownLatch(CONNECTIONS - 1);
            final CountDownLatch go = new CountDownLatch(1);
            List<Thread> probes = new ArrayList<>();
            for (int i = 1; i < CONNECTIONS; i++) {
                final Connection c = conns.get(i);
                Thread t = new Thread(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        long us = timeOne(c, "SELECT 1") / 1000;
                        synchronized (maxProbeUs) {
                            if (us > maxProbeUs.get()) {
                                maxProbeUs.set(us);
                            }
                        }
                        if (us > 1_000_000) {
                            blockedCount.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    }
                });
                probes.add(t);
                t.start();
            }
            ready.await();
            go.countDown();
            for (Thread t : probes) {
                t.join(180_000);
            }
            longRunner.join(180_000);

            result.maxProbeUs = maxProbeUs.get();
            result.blockedCount = (int) blockedCount.get();
            return result;
        }
        finally {
            for (Connection c : conns) {
                try { c.close(); } catch (Exception ignored) { }
            }
            dbInstance.stop();
        }
    }

    private static long timeOne(Connection conn, String sql) throws Exception {
        long start = System.nanoTime();
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) { /* drain */ }
        }
        return System.nanoTime() - start;
    }
}
