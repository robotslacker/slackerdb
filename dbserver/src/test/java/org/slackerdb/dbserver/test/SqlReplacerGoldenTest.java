package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.sql.QueryReplacerItem;
import org.slackerdb.dbserver.sql.SQLReplacer;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SQL 改写（{@link SQLReplacer}）的行为等价性回归测试。
 *
 * <p>改写规则直接影响发给 DuckDB 的语句，一旦行为漂移就是"悄悄发错 SQL"。
 * 这里用一份**由改造前实现生成的金标准**（{@code src/test/resources/sqlreplacer-golden.txt}，
 * 每行 {@code 输入<TAB>期望输出}）把改写结果钉死，覆盖：</p>
 * <ul>
 *   <li>每条规则各自的触发样本（语料由 {@link #corpus()} 从规则表自动派生）；</li>
 *   <li>大小写变体（规则匹配是 case-insensitive 的）；</li>
 *   <li>多语句、注释、带分号的字符串字面量；</li>
 *   <li>空语句与纯注释语句。</li>
 * </ul>
 */
public class SqlReplacerGoldenTest {

    static List<String> corpus() {
        List<String> list = new ArrayList<>();
        list.add("SELECT 1");
        list.add("select 1");
        list.add("SHOW transaction_read_only");
        list.add("show transaction_read_only");
        list.add("BEGIN");
        list.add("COMMIT");
        list.add("SET extra_float_digits = 3");
        list.add("SET application_name = 'DBeaver'");
        list.add("set session characteristics as transaction read only");
        list.add("SET DateStyle = 'ISO, MDY'");
        list.add("SET IntervalStyle = 'postgres'");
        list.add("set search_path = \"$user\", public");
        list.add("set search_path = public");
        list.add("SELECT * FROM t /* comment */ WHERE a = 1");
        list.add("SELECT * FROM t -- trailing\nWHERE a = 1");
        list.add("SELECT 'a;b' AS x; SELECT 2");
        list.add("INSERT INTO orders (id, name) VALUES (1, 'a')");
        list.add("UPDATE orders SET name = 'b' WHERE id = 1");
        list.add("SELECT array_upper(a, 1) FROM t");
        list.add("SELECT * FROM pg_catalog.pg_type");
        list.add("SELECT typinput='pg_catalog.array_in' FROM pg_catalog.pg_type");
        list.add("SELECT pg_catalog.pg_get_expr(x) FROM t");
        list.add("SELECT count(*) FROM pg_catalog.pg_event_trigger");
        list.add("SELECT * FROM pg_catalog.pg_roles");
        list.add("SELECT * FROM pg_catalog.pg_shdescription");
        list.add("SELECT * FROM pg_catalog.pg_namespace");
        list.add("SELECT * FROM pg_catalog.pg_proc");
        list.add("SELECT * FROM pg_catalog.pg_database");
        list.add("SELECT * FROM pg_catalog.pg_extension");
        list.add("SELECT * FROM pg_catalog.pg_inherits");
        list.add("SELECT pg_catalog.pg_total_relation_size('t')");
        list.add("SELECT pg_catalog.pg_relation_size('t')");
        list.add("SELECT format_type(nullif(t.typbasetype, 0), t.typtypmod) FROM t");
        list.add("SELECT * FROM pg_stat_activity");
        list.add("SELECT * FROM information_schema.tables WHERE table_name = 'x'");
        list.add("SELECT n.nspname, c.relname FROM pg_catalog.pg_class c "
                + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE c.relkind = 'r'");
        list.add("SELECT a.attname, format_type(a.atttypid, a.atttypmod) FROM pg_attribute a WHERE a.attnum > 0");
        list.add("SELECT * FROM pg_catalog.pg_type WHERE typname = 'int4' ORDER BY oid");
        list.add("EXPLAIN SELECT 1");
        list.add("SELECT 1; ");
        list.add("  ");
        list.add("");
        list.add("/* only comment */");
        // 大小写变体：规则匹配是 case-insensitive 的，改造后新增的"廉价预筛"也必须保持这一点
        list.add("SELECT * FROM PG_CATALOG.PG_TYPE");
        list.add("select * from Pg_Catalog.Pg_Roles where 1 = 1");
        list.add("SET EXTRA_FLOAT_DIGITS = 3");
        list.add("Show Transaction_Isolation");
        list.add("SELECT ARRAY_UPPER(a, 1) FROM t");
        list.add("SELECT * FROM pg_catalog.pg_type WHERE typname = 'INT4'");
        // 多语句 + 注释混排
        list.add("SELECT 1; /* c */ SELECT * FROM pg_catalog.pg_roles; -- tail\nSELECT 2");
        list.add("SET application_name='x'; SELECT * FROM pg_catalog.pg_proc");

        // 每条规则各造一条：既能触发 sampleReplace，也可能触发 regex
        for (QueryReplacerItem item : SQLReplacer.SQLReplaceItems) {
            String f = item.toFind();
            list.add(f);
            list.add("SELECT * FROM tbl_x WHERE col_y = '" + f + "'");
            list.add("SELECT * FROM tbl_x WHERE col_y LIKE '" + f + "%' ORDER BY 1");
        }
        return list;
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static DBInstance newInstance() throws Exception {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(-1);
        cfg.setData("replbench");
        cfg.setData_dir(":memory:");
        cfg.setLog_level("INFO");
        DBInstance dbInstance = new DBInstance(cfg);
        dbInstance.logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("replbench");
        SQLReplacer.load(dbInstance);
        return dbInstance;
    }

    @Test
    void matchesGoldenOutput() throws Exception {
        DBInstance dbInstance = newInstance();

        List<String> expected = new ArrayList<>();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("sqlreplacer-golden.txt")) {
            assert in != null : "缺少金标准文件 sqlreplacer-golden.txt";
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isEmpty()) {
                        expected.add(line);
                    }
                }
            }
        }

        List<String> corpus = corpus();
        assert corpus.size() == expected.size()
                : "语料条目数与金标准不一致：corpus=" + corpus.size() + " golden=" + expected.size();

        for (int i = 0; i < corpus.size(); i++) {
            String in = corpus.get(i);
            String[] parts = expected.get(i).split("\t", -1);
            assert parts.length == 2 : "金标准第 " + (i + 1) + " 行格式错误：" + expected.get(i);
            assert unescape(parts[0]).equals(in) : "语料第 " + (i + 1) + " 项与金标准不对应：" + escape(in);
            String want = unescape(parts[1]);
            String got = SQLReplacer.replaceSQL(dbInstance, in);
            assert want.equals(got)
                    : "SQL 改写结果与金标准不一致\n  输入: " + escape(in)
                    + "\n  期望: " + escape(want)
                    + "\n  实际: " + escape(got);
        }
    }

    /**
     * {@code load()} 必须幂等：重复加载不得让规则表翻倍（原实现只 add、从不 clear）。
     */
    @Test
    void loadIsIdempotent() throws Exception {
        DBInstance dbInstance = newInstance();
        int afterFirst = SQLReplacer.SQLReplaceItems.size();
        assert afterFirst > 0;

        SQLReplacer.load(dbInstance);
        assert SQLReplacer.SQLReplaceItems.size() == afterFirst
                : "load() 不是幂等的：" + afterFirst + " -> " + SQLReplacer.SQLReplaceItems.size();

        // 重复加载后改写行为也不应改变
        assert "SELECT * FROM memory.duck_catalog.pg_type".equals(
                SQLReplacer.replaceSQL(dbInstance, "SELECT * FROM pg_catalog.pg_type"))
                : "重复 load() 后改写结果发生变化";
    }

    /**
     * H4 回归：改写缓存必须线程安全。
     *
     * <p>缓存被多个 Netty worker 线程并发读写。改造前用的是未加同步、且开启了
     * {@code accessOrder} 的 {@code LinkedHashMap}——每次 {@code get()} 都会改写共享链表，
     * 因此"读"同时也是"写"，并发下属于未定义行为。</p>
     *
     * <p>这里断言的不变量很简单，但对任何缓存实现都必须成立：
     * <b>无论是否命中缓存、无论并发与否，改写结果都必须等于单线程下算出的期望值</b>。
     * 缓存损坏（条目丢失、返回错值、结构错乱抛异常）都会破坏它。</p>
     *
     * <p>语料规模刻意远大于缓存容量，让淘汰与结构变更持续发生。</p>
     */
    @Test
    void concurrentReplaceIsConsistent() throws Exception {
        DBInstance dbInstance = newInstance();

        // 期望值：单线程先算一遍
        final int distinct = 1200;
        List<String> corpus = new ArrayList<>(distinct * 2);
        for (int i = 0; i < distinct; i++) {
            corpus.add("SELECT c.relname FROM pg_catalog.pg_class c WHERE c.relname = 'tbl_" + i + "'");
        }
        // 再加一批会真正被改写的语句
        for (int i = 0; i < 200; i++) {
            corpus.add("SELECT * FROM pg_catalog.pg_type WHERE typname = 't" + i + "'");
        }
        java.util.Map<String, String> expected = new java.util.HashMap<>();
        for (String in : corpus) {
            expected.put(in, SQLReplacer.replaceSQL(dbInstance, in));
        }

        final int threads = 8;
        final int rounds = 400;
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            workers[t] = new Thread(() -> {
                try {
                    for (int r = 0; r < rounds; r++) {
                        for (int i = 0; i < corpus.size(); i++) {
                            String in = corpus.get((i + seed * 37 + r) % corpus.size());
                            String got = SQLReplacer.replaceSQL(dbInstance, in);
                            String want = expected.get(in);
                            if (want == null || !want.equals(got)) {
                                throw new IllegalStateException(
                                        "缓存返回了错误的改写结果\n  输入: " + in
                                                + "\n  期望: " + want + "\n  实际: " + got);
                            }
                        }
                        // 同时强制产生新的缓存项（未命中 -> 计算 -> put），与上面的读并发进行
                        String fresh = "SELECT * FROM pg_catalog.pg_type WHERE typname = 'fresh_" + seed + "_" + r + "'";
                        String freshGot = SQLReplacer.replaceSQL(dbInstance, fresh);
                        if (!freshGot.contains("memory.duck_catalog.pg_type")) {
                            throw new IllegalStateException("新语句改写结果不正确: " + freshGot);
                        }
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
        }
        for (Thread w : workers) {
            w.start();
        }
        for (Thread w : workers) {
            w.join();
        }

        assert failures.isEmpty() : "并发改写出现异常/错误结果：" + failures.get(0);
    }
}
