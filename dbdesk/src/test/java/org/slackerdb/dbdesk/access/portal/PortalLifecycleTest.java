package org.slackerdb.dbdesk.access.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M6：门户生命周期（长期有效/撤销）。 */
class PortalLifecycleTest {

    @TempDir
    Path tempDir;

    private static Portal portal(String expiresAt, boolean revoked) {
        return new Portal(null, "门户", "dash-1", null, 0, null, expiresAt, revoked);
    }

    @Test
    @DisplayName("门户一律长期有效；撤销后不可用（忽略历史 expiresAt）")
    void usableAtHonoursRevocationOnly() {
        Instant now = Instant.parse("2026-10-23T00:00:00Z");

        assertTrue(portal(null, false).usableAt(now), "无过期时间应长期有效");
        assertTrue(portal("2026-12-31T23:59:59Z", false).usableAt(now), "不再支持过期时间");
        assertTrue(portal("2026-01-01T00:00:00Z", false).usableAt(now), "历史过期时间也不再生效");
        assertTrue(portal("not-a-time", false).usableAt(now), "历史坏时间也不再导致失效");
        assertFalse(portal(null, true).usableAt(now), "撤销后不可用");
    }

    @Test
    @DisplayName("状态文案：有效（长期）/ 已撤销")
    void statusText() {
        Instant now = Instant.parse("2026-10-23T00:00:00Z");
        assertEquals("有效（长期）", portal(null, false).statusAt(now));
        assertEquals("有效（长期）", portal("2026-12-31T23:59:59Z", false).statusAt(now));
        assertEquals("有效（长期）", portal("2026-01-01T00:00:00Z", false).statusAt(now));
        assertEquals("已撤销", portal("2026-12-31T23:59:59Z", true).statusAt(now));
    }

    @Test
    @DisplayName("撤销标记能落盘并读回")
    void persistsRevocation() throws Exception {
        Path file = tempDir.resolve("portals.json");
        PortalStore store = new PortalStore(file);
        Portal saved = store.save(portal(null, false));

        Portal loaded = store.find(saved.id()).orElseThrow();
        assertFalse(loaded.revoked());

        store.save(loaded.withLifecycle(null, true));
        Portal revoked = store.find(saved.id()).orElseThrow();
        assertTrue(revoked.revoked(), "撤销标记应持久化");
    }
}
