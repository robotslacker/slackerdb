package org.slackerdb.dbserver.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Request-level deadline math used by the data-service API. */
class ApiBudgetTest {

    @Test
    void noDeadlineMeansNoBudgetLimit() {
        assertNull(APIService.remainingMillis(null));
        assertEquals(30_000L, APIService.refreshWaitMs(null));
    }

    @Test
    void remainingTimeFollowsTheDeadline() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Long remaining = APIService.remainingMillis(deadline);
        assertTrue(remaining != null && remaining > 4_000 && remaining <= 5_000,
                "remaining should be about 5s, was " + remaining);
    }

    @Test
    void expiredDeadlineIsRejectedQuickly() {
        long expired = System.nanoTime() - TimeUnit.SECONDS.toNanos(1);
        Long remaining = APIService.remainingMillis(expired);
        assertTrue(remaining != null && remaining < 0, "expired deadline must be negative: " + remaining);
        assertEquals(1L, APIService.refreshWaitMs(expired),
                "an expired request may wait at most 1ms for a refresh");
    }

    @Test
    void refreshWaitNeverExceedsTheSingleFlightCap() {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        assertTrue(APIService.refreshWaitMs(deadline) <= 30_000L);
        long soon = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        assertTrue(APIService.refreshWaitMs(soon) <= 2_000L);
    }
}
