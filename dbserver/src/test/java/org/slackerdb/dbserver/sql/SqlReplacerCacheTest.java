package org.slackerdb.dbserver.sql;

import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * H4 回归：SQL 改写结果缓存必须是<b>线程安全且有界</b>的。
 *
 * <p>改造前用的是 {@code common.utils.LRUCache}（未加同步、且 {@code accessOrder=true} 的
 * {@code LinkedHashMap}）。实测：单线程下它遵守 1000 条上限；但 8 线程并发访问时，
 * 淘汰逻辑失效，缓存涨到 <b>4768</b> 条——即"有界"这个前提在并发下根本不成立，
 * 长跑的服务会随不同 SQL 的累积而无界增长。</p>
 *
 * <p>本用例并发写入远超容量的不同 SQL，然后断言缓存规模<b>远小于写入总量</b>——
 * 也就是"有界"这一性质在并发下依然成立。容量上限本身不做硬断言，原因见用例内注释。</p>
 */
public class SqlReplacerCacheTest {

    private static DBInstance newInstance() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(-1);
        cfg.setData("cachetest");
        cfg.setData_dir(":memory:");
        cfg.setLog_level("INFO");
        DBInstance dbInstance = new DBInstance(cfg);
        dbInstance.logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("cachetest");
        SQLReplacer.load(dbInstance);
        return dbInstance;
    }

    @Test
    void cacheStaysBoundedUnderConcurrentChurn() throws Exception {
        DBInstance dbInstance = newInstance();
        SQLReplacer.replacedSqlCacheInvalidateAll();

        final long maxEntries = SQLReplacer.REPLACED_SQL_CACHE_MAX_ENTRIES;
        // 并发写入远超容量上限的、互不相同的 SQL：把淘汰逻辑压到必须持续工作
        final int threads = 8;
        final int perThread = 4000;          // 合计 32000 条 >> 10000
        final int totalWritten = threads * perThread;
        final AtomicInteger errors = new AtomicInteger();
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            workers[t] = new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        String sql = "SELECT * FROM pg_catalog.pg_type WHERE typname = 'c_" + seed + "_" + i + "'";
                        SQLReplacer.replaceSQL(dbInstance, sql);
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                }
            });
        }
        for (Thread w : workers) {
            w.start();
        }
        for (Thread w : workers) {
            w.join();
        }
        assert errors.get() == 0 : "并发写入缓存时抛出异常";

        // 注意：这里刻意不断言 size <= maxEntries。
        // estimatedSize() 本身只是"估算值"（Caffeine 明确不保证精确），即使先调用 cleanUp()
        // 也不保证任一时刻严格不超过 maximumSize。实测稳定态恰好压在 10000 这个边界上
        // （8 轮并发压测均为 size == 10000），也就是说 `size <= maxEntries` 是零余量通过，
        // 等于在赌一个没有文档保证的瞬时性质。
        // 改成断言本用例真正关心的不变量：缓存规模必须远小于写入总量。
        // 一旦淘汰失效（改造前的 LRUCache 正是如此），这些互不相同的 SQL 不会被移除，
        // 规模就会逼近 totalWritten，而不会停在 10000 附近。
        long size = SQLReplacer.replacedSqlCacheEstimatedSize();
        assert size < totalWritten / 2
                : "缓存规模接近写入总量(size=" + size + "，写入 " + totalWritten
                        + ")，淘汰逻辑可能已失效";
        // 同时也确实塞进去了足够多的条目（否则这条断言没有意义）
        assert size > maxEntries / 2
                : "缓存条目过少(" + size + ")，用例可能没有真正压到容量上限";
    }

    /**
     * 重复 {@code load()} 后，之前缓存的改写结果必须失效——否则换了规则却仍返回旧结果。
     */
    @Test
    void loadInvalidatesCachedRewrites() throws Exception {
        DBInstance dbInstance = newInstance();
        SQLReplacer.replacedSqlCacheInvalidateAll();

        String in = "SELECT * FROM pg_catalog.pg_roles";
        String first = SQLReplacer.replaceSQL(dbInstance, in);
        assert "SELECT * FROM memory.duck_catalog.pg_roles".equals(first) : "意外的改写结果: " + first;

        SQLReplacer.load(dbInstance);
        assert SQLReplacer.replacedSqlCacheEstimatedSize() == 0
                : "load() 之后缓存没有被清空";

        String second = SQLReplacer.replaceSQL(dbInstance, in);
        assert first.equals(second) : "重新 load 后改写结果发生变化: " + second;
    }
}
