package org.slackerdb.dbdesk.access.sql;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SqlHistoryStore} 的用例：会话内顺序、去重、上限裁剪。 */
class SqlHistoryStoreTest {

    @Test
    void newestFirstAndDedupeConsecutive() {
        SqlHistoryStore store = new SqlHistoryStore(50);
        store.append("select 1", Instant.parse("2026-10-22T00:00:00Z"));
        store.append("select 2", Instant.parse("2026-10-22T00:00:01Z"));
        store.append("select 2", Instant.parse("2026-10-22T00:00:02Z"));

        List<SqlHistoryStore.Entry> recent = store.recent(10);

        assertEquals(2, recent.size(), "连续重复不应重复记");
        assertEquals("select 2", recent.get(0).sql());
        assertEquals("select 1", recent.get(1).sql());
    }

    @Test
    void trimsToMaxEntries() {
        SqlHistoryStore store = new SqlHistoryStore(3);
        for (int i = 0; i < 6; i++) {
            store.append("select " + i, Instant.parse("2026-10-22T00:00:0" + i + "Z"));
        }

        List<SqlHistoryStore.Entry> recent = store.recent(10);

        assertEquals(3, recent.size());
        assertEquals("select 5", recent.get(0).sql());
        assertEquals("select 3", recent.get(2).sql());
    }

    @Test
    void defaultSessionLimitIsTwenty() {
        SqlHistoryStore store = new SqlHistoryStore();
        for (int i = 0; i < 25; i++) {
            store.append("select " + i, Instant.now());
        }

        assertEquals(20, store.size());
        assertEquals(20, store.recent(100).size());
        assertEquals("select 24", store.recent(1).get(0).sql());
    }

    @Test
    void ignoresBlankStatements() {
        SqlHistoryStore store = new SqlHistoryStore(10);
        store.append("   ", Instant.now());
        store.append(null, Instant.now());

        assertTrue(store.recent(10).isEmpty());
    }

    @Test
    void entryPreviewIsSingleLineAndShort() {
        SqlHistoryStore.Entry entry = new SqlHistoryStore.Entry(
                "select *\nfrom m0_probe\n" + "x".repeat(200), "2026-10-22T00:00:00Z");

        assertTrue(entry.preview().length() <= 81);
        assertTrue(entry.preview().startsWith("select *"));
    }
}
