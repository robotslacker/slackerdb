package org.slackerdb.dbdesk.access.sql;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * SQL 执行历史：只存在于当前浏览器会话内，最多 20 条，不落盘。
 *
 * <p>新会话/重新打开窗口就是一份新历史；同一会话里的多个 SQL 标签共享这份记录。</p>
 */
public final class SqlHistoryStore {

    private static final int MAX_ENTRIES = 20;

    /** 一条历史：语句 + 时间（ISO-8601）。 */
    public record Entry(String sql, String at) {
        public String preview() {
            String firstLine = sql.lines().findFirst().orElse(sql).strip();
            return firstLine.length() <= 80 ? firstLine : firstLine.substring(0, 80) + "…";
        }
    }

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final int maxEntries;

    public SqlHistoryStore() {
        this(MAX_ENTRIES);
    }

    public SqlHistoryStore(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    /** 追加一条（连续重复忽略）；新的在最前，超过上限丢最旧。 */
    public synchronized void append(String sql, Instant at) {
        String statement = sql == null ? "" : sql.strip();
        if (statement.isEmpty()) {
            return;
        }
        if (!entries.isEmpty() && entries.peekFirst().sql().equals(statement)) {
            return;
        }
        entries.addFirst(new Entry(statement, at.toString()));
        while (entries.size() > maxEntries) {
            entries.removeLast();
        }
    }

    /** 最近的条目，**新的在前**。 */
    public synchronized List<Entry> recent(int limit) {
        int bound = Math.min(Math.max(1, limit), entries.size());
        List<Entry> result = new ArrayList<>(bound);
        int index = 0;
        for (Entry entry : entries) {
            if (index++ >= bound) {
                break;
            }
            result.add(entry);
        }
        return List.copyOf(result);
    }

    /** 测试/诊断用：当前条数。 */
    public synchronized int size() {
        return entries.size();
    }
}
