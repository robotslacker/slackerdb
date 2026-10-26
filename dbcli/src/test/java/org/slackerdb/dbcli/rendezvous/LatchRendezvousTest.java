package org.slackerdb.dbcli.rendezvous;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内会合点的行为测试（hermetic，不碰文件系统、不起进程）。
 *
 * <p>重点锁**点名大小写不敏感**：{@code GO} / {@code go} / {@code Go} 是同一个点。
 * 跨进程后端（{@link SqliteRendezvous}）用同一套规范化，它的真进程验证在
 * {@link SqliteRendezvousTest}；"作业里的 {@code _job wait timer:go} 与父脚本的
 * {@code _job timer GO} 真的会合"由 {@code engine.CrossProcessJobBarrierTest} 覆盖。</p>
 */
class LatchRendezvousTest {

    @Test
    @DisplayName("放行不分大小写：release(\"GO\") 唤醒 await(\"go\") / await(\"Go\")")
    void releaseAndAwaitMatchIgnoringCase() throws Exception {
        try (LatchRendezvous rendezvous = new LatchRendezvous()) {
            rendezvous.release("GO");

            assertTrue(rendezvous.await("go", 1_000L), "go 与 GO 必须是同一个点");
            assertTrue(rendezvous.await("Go", 1_000L), "Go 与 GO 必须是同一个点");
            assertTrue(rendezvous.await("GO", 1_000L));
            assertFalse(rendezvous.await("STOP", 100L), "别的点名不该被放行（超时是正常结果）");
        }
    }

    @Test
    @DisplayName("等待不分大小写：先 await(\"go\")、后 release(\"GO\") 也能唤醒（同一个闸门）")
    void awaitThenReleaseMatchesIgnoringCase() throws Exception {
        try (LatchRendezvous rendezvous = new LatchRendezvous()) {
            Thread waiter = new Thread(() -> {
                try {
                    assertTrue(rendezvous.await("go", 30_000L), "放行必须唤醒等待方");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "latch-test-waiter");
            waiter.setDaemon(true);
            waiter.start();

            // 到达即"点名存在"，且用的是规范形：大小写不同的写法看得见同一个点
            rendezvous.arrive("go", "WAITER");
            assertTrue(rendezvous.knows("GO"), "arrive(\"go\") 之后 knows(\"GO\") 必须为真");
            assertEquals(java.util.Set.of("WAITER"), rendezvous.arrivals("Go"),
                    "到达记录按规范形归并");

            rendezvous.release("GO");
            waiter.join(10_000L);
            assertFalse(waiter.isAlive(), "放行之后等待方必须已经返回");
        }
    }

    @Test
    @DisplayName("到达记录按规范形归并：GO 与 go 的参与者落在同一条记录里")
    void arrivalsAreMergedByNormalizedName() {
        try (LatchRendezvous rendezvous = new LatchRendezvous()) {
            rendezvous.arrive("GO", "C1");
            rendezvous.arrive("go", "C2");

            assertEquals(java.util.Set.of("C1", "C2"), rendezvous.arrivals("Go"),
                    "三个写法是同一条到达记录: " + rendezvous.arrivals("Go"));
        }
    }

    @Test
    @DisplayName("NO_TIMEOUT：一直等到放行为止（不会自己超时返回 false）")
    void noTimeoutWaitsUntilReleased() throws Exception {
        try (LatchRendezvous rendezvous = new LatchRendezvous()) {
            Thread waiter = new Thread(() -> {
                try {
                    assertTrue(rendezvous.await("gate", RendezvousBackend.NO_TIMEOUT),
                            "没有时限的等待只能在放行之后返回 true");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "latch-test-unlimited-waiter");
            waiter.setDaemon(true);
            waiter.start();

            Thread.sleep(200L);
            assertTrue(waiter.isAlive(), "没有时限时不该自己返回");
            rendezvous.release("gate");
            waiter.join(10_000L);
            assertFalse(waiter.isAlive(), "放行之后等待方必须已经返回");
            // 放行是幂等且"记住"的：后来的等待立即通过
            assertTrue(rendezvous.await("gate", RendezvousBackend.NO_TIMEOUT));
        }
    }

    @Test
    @DisplayName("close() 会唤醒没有时限的等待者并返回 false（切后端不再造成静默挂死）")
    void closeWakesUnlimitedWaiters() throws Exception {
        LatchRendezvous rendezvous = new LatchRendezvous();
        java.util.concurrent.atomic.AtomicBoolean released =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread waiter = new Thread(() -> {
            try {
                released.set(rendezvous.await("gate", RendezvousBackend.NO_TIMEOUT));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "latch-test-close-waiter");
        waiter.setDaemon(true);
        waiter.start();
        Thread.sleep(100L);
        assertTrue(waiter.isAlive(), "关闭之前应当还在等");

        rendezvous.close();

        waiter.join(5_000L);
        assertFalse(waiter.isAlive(), "close() 必须唤醒等待者（接口承诺'等到放行或后端关闭'）");
        assertFalse(released.get(), "被关闭不是被放行：必须返回 false");
    }

    @Test
    @DisplayName("规范化就是 toUpperCase(Locale.ROOT)（与跨进程后端同一份实现）")
    void normalizationIsSharedWithTheOtherBackend() {
        assertEquals("GO", RendezvousBackend.normalizePoint("go"));
        assertEquals("GO", RendezvousBackend.normalizePoint("Go"));
        assertEquals("GO", RendezvousBackend.normalizePoint("GO"));
        assertNull(RendezvousBackend.normalizePoint(null), "null 原样返回（由各后端自行处理）");
    }
}
