package org.slackerdb.dbserver.test;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.utils.BoundedQueue;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.TimeZone;
import java.util.function.BooleanSupplier;

/**
 * H8 回归测试：历史记录队列的"阻塞式背压"与"消费线程异常退出"。
 *
 * <p>改造前的两个缺陷：</p>
 * <ol>
 *   <li>{@code BoundedQueue.offer} 内部使用 {@code queue.put}，队列满时会<b>永久阻塞调用线程</b>。
 *       而调用点运行在 Netty EventLoop / Jetty 线程上；</li>
 *   <li>历史消费线程的 {@code catch (SQLException)} 写在 while 循环之外，
 *       任何一次写库失败都会让线程直接退出。此后队列不再被排空，
 *       第 10001 条记录起生产者会被永久阻塞 —— 整个 PG 端口停止响应。</li>
 * </ol>
 */
public class HistoryQueueTest {
    static int dbPort;
    static DBInstance dbInstance;

    private static final String HISTORY_TABLE_DDL = """
            CREATE TABLE memory.sysaux.SQL_HISTORY
            (
                ID             BIGINT PRIMARY KEY,
                ServerID       INT,
                SessionID      INT,
                ClientIP       TEXT,
                StartTime      DateTime,
                EndTime        DateTime,
                Elapsed        INT GENERATED ALWAYS AS (DATEDIFF('SECOND', StartTime, EndTime)),
                SqlID          INT,
                SQL            TEXT,
                SqlCode        INT,
                AffectedRows   BIGINT,
                ErrorMsg       TEXT
            )
            """;

    @BeforeAll
    static void initAll() throws ServerException {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setData("hist");
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("ON");
        dbPort = serverConfiguration.getPort();

        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
        assert dbInstance.instanceState.equalsIgnoreCase("IDLE");
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + dbPort + "/hist", "", "");
    }

    /**
     * 队列满时 offer 必须立刻返回 false，绝不阻塞调用线程 —— 这是"整库挂死"的根因。
     */
    @Test
    void producerNeverBlocksWhenQueueIsFull() {
        final int capacity = 100;
        BoundedQueue<String> queue = new BoundedQueue<>(capacity);

        for (int i = 0; i < capacity; i++) {
            assert queue.offer("item-" + i) : "队列未满时必须入队成功";
        }

        long startNano = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            assert !queue.offer("overflow-" + i) : "队列已满时必须返回 false";
        }
        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000L;

        assert queue.getOfferedTotal() == capacity + 10_000L;
        assert queue.getDroppedTotal() == 10_000L;
        assert queue.size() == capacity;
        assert elapsedMs < 1_000L : "队列满时 offer 不应阻塞，实际耗时 " + elapsedMs + "ms";

        // 消费一个之后又能重新入队
        assert "item-0".equals(queue.poll());
        assert queue.offer("after-drain");
        assert queue.getDroppedTotal() == 10_000L : "成功入队不应增加丢弃计数";
    }

    /**
     * 历史写入失败时消费线程必须存活并在后端恢复后继续落库。
     * 改造前该线程会直接退出，测试中的线程存活断言会失败。
     *
     * <p><b>本用例会故意制造一次写入失败</b>：{@link #breakHistoryTable} 把 {@code sysaux.SQL_HISTORY}
     * 换成只有 {@code ID} 一列的残缺表，历史消费线程随即抛出
     * <pre>Binder Error: Table "SQL_HISTORY" does not have a column with name "ServerID"</pre>
     * 这正是被验证的容错路径（{@code DBInstance.DBInstanceSQLHistoryThread} 捕获异常、回滚、
     * 退避 5 秒后重连重试），并非真实故障。</p>
     *
     * <p>为了不让这段"意料之中的报错"出现在构建日志里造成误解，故障窗口内会把该实例的 logger
     * 临时关闭（见 {@link #silenceExpectedFailureLogs}），测试结束后恢复。</p>
     */
    @Test
    void historyThreadSurvivesBackendFailureAndRecovers() throws Exception {
        // 1) 正常情况下历史能够落库
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE OR REPLACE TABLE histProbe (id INTEGER)");
                stmt.execute("INSERT INTO histProbe VALUES (1)");
            }
            conn.commit();
        }
        awaitTrue(() -> historyRowCount() > 0, 20_000, "历史记录未能正常落库");

        int n = 0;
        boolean restoreLogging = false;
        try {
            // 2) 破坏历史表结构，使后续历史写入必然失败（列不匹配 -> prepare/execute 抛异常）。
            //    这是预期内的失败，先静音日志再动手，避免 ERROR 堆栈干扰阅读。
            silenceExpectedFailureLogs();
            restoreLogging = true;
            breakHistoryTable();

            // 3) 持续产生历史记录，让消费线程真实撞上写入失败，并跨过多个重试周期
            long deadline = System.currentTimeMillis() + 8_000;
            try (Connection conn = connect()) {
                conn.setAutoCommit(false);
                while (System.currentTimeMillis() < deadline) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("INSERT INTO histProbe VALUES (" + (++n) + ")");
                    }
                    conn.commit();
                    Thread.sleep(50);
                }
            }

            // 关键断言：消费线程没有因为写库失败而退出
            assert historyThreadAlive() : "历史消费线程在后端写入失败后不应退出";
        }
        finally {
            // 4) 无论断言是否通过都要恢复表结构和日志级别，避免影响后续用例
            restoreHistoryTable();
            if (restoreLogging) {
                restoreFailureLogs();
            }
        }

        // 残缺表期间的历史记录全部丢失，因此这里必然是 0；
        // 后面只要能重新落库，就证明消费线程真的自愈了，而不是靠残留数据碰巧达标。
        long rowsBeforeRestore = historyRowCount();
        assert rowsBeforeRestore == 0
                : "残缺表期间不应有任何历史记录落库，实际 " + rowsBeforeRestore + " 条";

        long recoveredDeadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < recoveredDeadline && historyRowCount() == 0) {
            try (Connection conn = connect()) {
                conn.setAutoCommit(false);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("INSERT INTO histProbe VALUES (" + (++n) + ")");
                }
                conn.commit();
            }
            Thread.sleep(200);
        }

        assert historyRowCount() > 0 : "后端恢复后历史记录应能继续落库";
        assert historyThreadAlive() : "历史消费线程应持续存活";
    }

    /** 故意把历史表换成缺少 ServerID 等列的残缺结构，用来触发写入失败。 */
    private static void breakHistoryTable() throws Exception {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS memory.sysaux.SQL_HISTORY");
                stmt.execute("CREATE TABLE memory.sysaux.SQL_HISTORY (ID BIGINT)");
            }
            conn.commit();
        }
    }

    /** 恢复历史表的正常结构，让消费线程可以重新落库。 */
    private static void restoreHistoryTable() throws Exception {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS memory.sysaux.SQL_HISTORY");
                stmt.execute(HISTORY_TABLE_DDL);
            }
            conn.commit();
        }
    }

    private static long historyRowCount() {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM memory.sysaux.SQL_HISTORY")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
        catch (Exception e) {
            // 表不存在或被破坏时按 0 处理
            return 0;
        }
    }

    /** 通过线程名判断历史消费线程是否存活：该方式对改造前后的代码都适用。 */
    private static boolean historyThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> "Session-SQLHistory".equals(t.getName()) && t.isAlive());
    }

    /**
     * 临时关闭本实例的日志，让"故意制造的写入失败"不再往构建日志里打 ERROR 堆栈。
     * 历史消费线程与 DBInstance 共用同一个 logback logger 实例，因此改级别即刻生效。
     */
    private static void silenceExpectedFailureLogs() {
        ((ch.qos.logback.classic.Logger) dbInstance.logger).setLevel(Level.OFF);
    }

    /** 恢复日志级别，避免影响同一 JVM 中的其它用例。 */
    private static void restoreFailureLogs() {
        ((ch.qos.logback.classic.Logger) dbInstance.logger).setLevel(Level.INFO);
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
        assert condition.getAsBoolean() : message;
    }
}
