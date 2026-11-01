package org.slackerdb.dbserver.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SingleFlight} 的行为约束：同一 key 并发只加载一次、失败共享且不粘住、等待有上限。
 *
 * <p>这些用例不启动服务器，直接覆盖"缓存集中过期时只允许一个线程刷新"的核心逻辑。</p>
 */
class SingleFlightTest {

    /** 同一 key 的 N 个并发调用只执行一次 loader，所有调用者拿到同一个结果对象。 */
    @Test
    void concurrentLoadsShareOneExecution() throws Exception {
        SingleFlight<String, Object> singleFlight = new SingleFlight<>();
        AtomicInteger executions = new AtomicInteger();
        Object loaded = new Object();
        int callers = 16;

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        List<Future<SingleFlight.Result<Object>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return singleFlight.load("same-key", 10_000, () -> {
                        executions.incrementAndGet();
                        // 放大刷新窗口，保证所有调用者都落在同一批并发里
                        Thread.sleep(100);
                        return loaded;
                    });
                }));
            }
            startGate.countDown();

            int leaders = 0;
            for (Future<SingleFlight.Result<Object>> future : futures) {
                SingleFlight.Result<Object> result = future.get(10, TimeUnit.SECONDS);
                assertSame(loaded, result.value(), "所有调用者必须拿到同一个刷新结果");
                if (result.loadedByCaller()) {
                    leaders++;
                }
            }
            assertEquals(1, executions.get(), "同一 key 的并发刷新只允许执行一次 loader");
            assertEquals(1, leaders, "有且仅有一个调用者是刷新者");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, singleFlight.inFlightCount(), "加载结束后不能残留 in-flight 记录");
    }

    /** 不同 key 互不阻塞：各自独立刷新。 */
    @Test
    void differentKeysLoadIndependently() throws Exception {
        SingleFlight<String, String> singleFlight = new SingleFlight<>();
        AtomicInteger executions = new AtomicInteger();

        SingleFlight.Result<String> first = singleFlight.load("key-a", 1_000, () -> {
            executions.incrementAndGet();
            return "a";
        });
        SingleFlight.Result<String> second = singleFlight.load("key-b", 1_000, () -> {
            executions.incrementAndGet();
            return "b";
        });

        assertEquals("a", first.value());
        assertEquals("b", second.value());
        assertEquals(2, executions.get());
    }

    /** leader 失败时等待者收到同一个异常，且失败不会被"粘住"：下一次调用会重新执行。 */
    @Test
    void failuresAreSharedAndNotSticky() throws Exception {
        SingleFlight<String, String> singleFlight = new SingleFlight<>();
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch leaderStarted = new CountDownLatch(1);
        CountDownLatch waiterStarted = new CountDownLatch(1);
        CountDownLatch releaseLeader = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> leader = pool.submit(() -> assertThrows(IllegalStateException.class, () ->
                    singleFlight.load("failing-key", 5_000, () -> {
                        executions.incrementAndGet();
                        leaderStarted.countDown();
                        assertTrue(releaseLeader.await(5, TimeUnit.SECONDS), "leader 未被释放");
                        throw new IllegalStateException("boom");
                    })));
            assertTrue(leaderStarted.await(5, TimeUnit.SECONDS), "leader 未启动");

            Future<?> waiter = pool.submit(() -> assertThrows(IllegalStateException.class, () -> {
                waiterStarted.countDown();
                singleFlight.load("failing-key", 5_000,
                        () -> {
                            throw new AssertionError("等待者不能自己去执行 loader");
                        });
            }));
            assertTrue(waiterStarted.await(5, TimeUnit.SECONDS), "等待者未启动");
            // 给等待者一点时间真正进入等待，再让 leader 失败
            Thread.sleep(100);
            releaseLeader.countDown();

            leader.get(5, TimeUnit.SECONDS);
            waiter.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, executions.get(), "一次并发失败只允许执行一次 loader");
        assertEquals(0, singleFlight.inFlightCount(), "失败后不能残留 in-flight 记录");

        // 再次调用：重新成为 leader 并成功（失败不会被缓存）
        SingleFlight.Result<String> retried = singleFlight.load("failing-key", 1_000, () -> {
            executions.incrementAndGet();
            return "ok";
        });
        assertEquals("ok", retried.value());
        assertTrue(retried.loadedByCaller());
        assertEquals(2, executions.get(), "失败不能被缓存，重试必须重新执行");
    }

    /** 等待者超时后抛 TimeoutException，由调用方决定降级；leader 仍会完成自己的加载。 */
    @Test
    void waitersTimeOutInsteadOfHangingForever() throws Exception {
        SingleFlight<String, String> singleFlight = new SingleFlight<>();
        CountDownLatch leaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLeader = new CountDownLatch(1);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<SingleFlight.Result<String>> leader = pool.submit(() ->
                    singleFlight.load("slow-key", 5_000, () -> {
                        leaderStarted.countDown();
                        assertTrue(releaseLeader.await(5, TimeUnit.SECONDS));
                        return "slow";
                    }));
            assertTrue(leaderStarted.await(5, TimeUnit.SECONDS));

            assertThrows(TimeoutException.class, () ->
                    singleFlight.load("slow-key", 50, () -> {
                        throw new AssertionError("等待者不能自己去执行 loader");
                    }));

            releaseLeader.countDown();
            assertEquals("slow", leader.get(5, TimeUnit.SECONDS).value());
        } finally {
            releaseLeader.countDown();
            pool.shutdownNow();
        }
        assertEquals(0, singleFlight.inFlightCount());
    }

    /** 单线程顺序调用不应留下任何 in-flight 记录。 */
    @Test
    void sequentialLoadsDoNotLeak() throws Exception {
        SingleFlight<String, String> singleFlight = new SingleFlight<>();
        for (int i = 0; i < 10; i++) {
            SingleFlight.Result<String> result = singleFlight.load("k", 100, () -> "v");
            assertTrue(result.loadedByCaller());
            assertEquals(0, singleFlight.inFlightCount());
        }
    }
}
