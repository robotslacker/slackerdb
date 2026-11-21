package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.Test;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.QueryConcurrencyGate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the global query admission gate and its configuration knob. */
class QueryConcurrencyGateTest {

    @Test
    void disabledGateAlwaysAcquiresAndNeverBlocks() throws Exception {
        QueryConcurrencyGate gate = new QueryConcurrencyGate(0);
        assertFalse(gate.isEnabled());
        assertTrue(gate.tryAcquire(0));
        assertTrue(gate.tryAcquire(0));
        assertEquals(0, gate.getActive());
        gate.release();
        gate.release();
        assertEquals(0, gate.getActive());
    }

    @Test
    void secondCallerFailsFastWhenTheOnlySlotIsBusy() throws Exception {
        QueryConcurrencyGate gate = new QueryConcurrencyGate(1);
        assertTrue(gate.tryAcquire(0));
        assertEquals(1, gate.getActive());

        long start = System.nanoTime();
        assertFalse(gate.tryAcquire(150));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs >= 100, "should have waited about the timeout, waited " + elapsedMs + "ms");

        gate.release();
        assertTrue(gate.tryAcquire(0));
        gate.release();
    }

    @Test
    void waitingCallerIsVisibleAndWakesUpOnRelease() throws Exception {
        QueryConcurrencyGate gate = new QueryConcurrencyGate(1);
        assertTrue(gate.tryAcquire(0));

        AtomicBoolean acquired = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try {
                acquired.set(gate.tryAcquire(2_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        }, "gate-waiter");
        waiter.start();

        long deadline = System.currentTimeMillis() + 1_000;
        while (gate.getWaiting() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, gate.getWaiting(), "waiter should be counted while blocked");

        gate.release();
        assertTrue(done.await(2, TimeUnit.SECONDS));
        waiter.join(2_000);
        assertTrue(acquired.get(), "waiter should get the released slot");
        assertFalse(waiter.isAlive());
        assertEquals(1, gate.getActive());
    }

    @Test
    void waitBudgetBelongsToTheGateConfiguration() {
        assertEquals(QueryConcurrencyGate.DEFAULT_WAIT_MS, new QueryConcurrencyGate(4).getWaitMs());
        assertEquals(1_234L, new QueryConcurrencyGate(4, 1_234L).getWaitMs());
        assertEquals(0L, new QueryConcurrencyGate(4, -5L).getWaitMs());
    }

    @Test
    void queueingConsumesTheExecutionBudget() {
        assertEquals(60_000L, QueryConcurrencyGate.remainingBudgetMs(60_000L, 0L));
        assertEquals(35_000L, QueryConcurrencyGate.remainingBudgetMs(60_000L, 25_000L));
        assertEquals(0L, QueryConcurrencyGate.remainingBudgetMs(60_000L, 60_000L));
        assertTrue(QueryConcurrencyGate.remainingBudgetMs(60_000L, 61_000L) < 0);
        // a negative wait (clock oddity) must not inflate the budget
        assertEquals(60_000L, QueryConcurrencyGate.remainingBudgetMs(60_000L, -5L));
    }

    @Test
    void configurationParsesAndRejectsNegativeValues() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();
        assertEquals(0, configuration.getMax_api_concurrent_queries());
        assertEquals(0, configuration.getMax_repl_concurrent_queries());

        configuration.setMax_api_concurrent_queries("12");
        configuration.setMax_repl_concurrent_queries("6");
        assertEquals(12, configuration.getMax_api_concurrent_queries());
        assertEquals(6, configuration.getMax_repl_concurrent_queries());

        assertThrows(ServerException.class, () -> configuration.setMax_api_concurrent_queries("-1"));
        assertThrows(ServerException.class, () -> configuration.setMax_api_concurrent_queries("abc"));
        assertThrows(ServerException.class, () -> configuration.setMax_repl_concurrent_queries("-1"));
        assertThrows(ServerException.class, () -> configuration.setMax_repl_concurrent_queries("abc"));
    }
}
