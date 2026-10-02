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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 历史记录队列的两条契约：
 *
 * <ol>
 *   <li><b>绝不丢弃</b>：{@code BoundedQueue.offer} 在队列满时阻塞等待空位，元素最终一定会入队
 *       —— 包括等待期间被中断的情况（见 {@link #producerBlocksInsteadOfDroppingWhenQueueIsFull}
 *       与 {@link #interruptedProducerStillEnqueuesAndKeepsInterruptFlag}）。
 *       "记录不下审计就阻塞业务"是刻意的设计要求。</li>
 *   <li><b>消费线程自愈</b>：历史写库失败不能让消费线程退出，否则队列不再被排空、
 *       生产者会被背压永久挂住（{@link #historyThreadSurvivesBackendFailureAndRecovers}）。</li>
 * </ol>
 *
 * <p><b>关于第 1 条的历史</b>：本队列一度改成"满了就丢弃 + 计数"，那是在消费线程还无法自愈时的
 * 临时降级；现在消费线程已改为在循环内捕获异常、回滚并退避重试，且审计不可丢是硬要求，
 * 因此入队恢复为阻塞式，并且<b>不再提供任何丢弃入口</b>（没有 tryOffer、没有带超时的入队）。
 * 代价是生产者（请求处理线程）会被慢消费端拖慢，所以 {@code getBlockedTotal()} /
 * {@code getBlockedMillis()} 是需要观测的背压信号。</p>
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
     * 队列满时 {@code offer} 必须<b>阻塞等待空位，不丢弃元素</b>；消费端腾出空位后该元素必须入队。
     *
     * <p>契约说明：这里锁定的是"背压、不丢数据"。历史上本用例断言的是相反的行为
     * （满了立刻返回 false 并计数丢弃），那是 H8 的临时降级方案 —— 当时的真正缺陷是
     * 消费线程一次写库失败就退出（现已改为循环内捕获 + 退避重试），
     * 因此不再需要用"丢历史"来换取"业务线程不挂"。</p>
     */
    @Test
    void producerBlocksInsteadOfDroppingWhenQueueIsFull() throws Exception {
        final int capacity = 4;
        BoundedQueue<String> queue = new BoundedQueue<>(capacity);
        assertEquals(capacity, queue.getCapacity(), "容量应为构造时指定的值");
        for (int i = 0; i < capacity; i++) {
            assert queue.offer("item-" + i) : "队列未满时必须入队成功";
        }
        assertEquals(0, queue.remainingCapacity(), "队列应已填满");

        // 队列已满：这次 offer 必须挂住，而不是返回 false 把元素丢掉
        AtomicReference<String> offeredResult = new AtomicReference<>("NOT-RETURNED");
        Thread producer = new Thread(() -> offeredResult.set("returned:" + queue.offer("overflow")),
                "blocked-producer");
        producer.start();
        producer.join(500);
        assert producer.isAlive() : "队列已满时 offer 必须阻塞，实际立刻返回了 " + offeredResult.get();
        assertEquals(capacity, queue.size(), "阻塞期间队列内容不应发生变化（本队列没有丢弃路径）");

        // 消费一个 → 被挂住的 offer 立即完成，且入队的是它自己的元素（没有丢、也没有串）
        assertEquals("item-0", queue.poll());
        producer.join(5_000);
        assert !producer.isAlive() : "腾出空位后 offer 应当立即返回";
        assert "returned:true".equals(offeredResult.get())
                : "腾出空位后 offer 应当入队成功，实际 " + offeredResult.get();

        // 全部元素都在：容量 4 + 后来那个 = 5 个，一个不少
        java.util.List<String> drained = new java.util.ArrayList<>();
        String value;
        while ((value = queue.poll()) != null) {
            drained.add(value);
        }
        assertEquals(java.util.List.of("item-1", "item-2", "item-3", "overflow"), drained,
                "被背压暂存的元素必须一个不少地按序保留");
        assertEquals(5L, queue.getOfferedTotal(), "入队尝试次数不符");
        assertEquals(1L, queue.getBlockedTotal(), "应记录到一次\"因满而等待\"");
        assert queue.getBlockedMillis() >= 400L
                : "等待时长应被累计，实际 " + queue.getBlockedMillis() + "ms";
    }

    /**
     * 中断也不能丢审计：等待空位时被 {@link Thread#interrupt()} 打断，
     * 必须继续等待并入队，同时把中断状态还原给调用方。
     *
     * <p>这是"任何情况都不丢"里最容易实现错的一条：{@code BlockingQueue.put} 一被打断就抛异常，
     * 顺势返回就会把这条审计丢掉（本仓库历史上就是"满了就丢弃 + 计数"）。</p>
     */
    @Test
    void interruptedProducerStillEnqueuesAndKeepsInterruptFlag() throws Exception {
        final int capacity = 2;
        BoundedQueue<String> queue = new BoundedQueue<>(capacity);
        assert queue.offer("a");
        assert queue.offer("b");

        AtomicReference<String> result = new AtomicReference<>("NOT-RETURNED");
        AtomicReference<Boolean> interruptFlagAfterReturn = new AtomicReference<>(null);
        Thread producer = new Thread(() -> {
            result.set("returned:" + queue.offer("must-not-be-lost"));
            interruptFlagAfterReturn.set(Thread.currentThread().isInterrupted());
        }, "interrupted-producer");
        producer.start();

        // 等它真的卡在 put 上，然后连续中断两次
        Thread.sleep(300);
        assert producer.isAlive() : "队列已满时 offer 必须阻塞";
        producer.interrupt();
        Thread.sleep(200);
        assert producer.isAlive() : "被中断后不允许放弃入队（否则这条审计就丢了）";
        producer.interrupt();
        Thread.sleep(200);
        assert producer.isAlive() : "重复中断同样不允许丢数据";

        // 腾出空位 → 它必须把元素交出来
        assertEquals("a", queue.poll());
        producer.join(5_000);
        assert !producer.isAlive() : "腾出空位后被中断的生产者也应当完成入队";
        assert "returned:true".equals(result.get()) : "入队结果异常：" + result.get();
        assert Boolean.TRUE.equals(interruptFlagAfterReturn.get())
                : "入队后必须把中断状态还原给调用方（不能悄悄吃掉）";

        java.util.List<String> drained = new java.util.ArrayList<>();
        String value;
        while ((value = queue.poll()) != null) {
            drained.add(value);
        }
        assertEquals(java.util.List.of("b", "must-not-be-lost"), drained,
                "被中断过的那条审计必须仍在队列里，一条都不能少");
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
