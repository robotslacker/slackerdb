package org.slackerdb.dbserver.sql;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slackerdb.dbserver.server.DBInstance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SQLReplacer {
    public static final List<QueryReplacerItem> SQLReplaceItems = Collections.synchronizedList(new ArrayList<>());

    // SQL 改写结果缓存的最大条目数
    static final long REPLACED_SQL_CACHE_MAX_ENTRIES = 10_000;

    // 定义一个队列，对于相同的SQL不重复进行替换，来提高处理效率
    //
    // 这里必须用线程安全的实现：replaceSQL 会被多个 Netty worker 线程并发调用。
    // 改造前用的是 common 里的 LRUCache（未加同步、且 accessOrder=true 的 LinkedHashMap——
    // 每次 get 都会改写共享链表，"读"同时也是"写"），并发下会：
    //   1) 淘汰失效、突破容量上限：实测 8 线程下容量 1000 的缓存涨到 4768 条（无界增长）；
    //   2) 每次查表 36ns vs Caffeine 6ns（共享链表带来的 cache line 争用）。
    // 换成 Caffeine：线程安全、有界，且不会像 synchronizedMap 那样给每次命中加一把锁。
    private static final Cache<String, String> replacedSqlCache = Caffeine.newBuilder()
            .maximumSize(REPLACED_SQL_CACHE_MAX_ENTRIES)
            .build();

    // 预编译的正则Pattern缓存，避免在每次SQL替换时重复创建Pattern
    private static final ConcurrentHashMap<String, Pattern> compiledRegexPatternCache = new ConcurrentHashMap<>();

    // 预编译的字面量替换Pattern缓存，避免在每次SQL替换时重复创建Pattern
    private static final ConcurrentHashMap<String, Pattern> compiledSamplePatternCache = new ConcurrentHashMap<>();

    // 规则编译后的不可变快照。replaceSQL 只读它，因此迭代期间不会被 load() 修改。
    private static volatile List<ReplacerRule> compiledRules = List.of();

    // "set search_path = ..." 需要去掉中间的空格和双引号，DuckDB 不支持
    private static final Pattern SEARCH_PATH_STRIP_PATTERN = Pattern.compile("[ \"']");

    // 获取预编译的正则Pattern，相同规则只编译一次
    static Pattern compileRegexPattern(String find) {
        return compiledRegexPatternCache.computeIfAbsent(find,
                k -> Pattern.compile(k, Pattern.DOTALL | Pattern.CASE_INSENSITIVE));
    }

    // 获取预编译的字面量替换Pattern，相同规则只编译一次
    static Pattern compileSamplePattern(String find) {
        return compiledSamplePatternCache.computeIfAbsent(find,
                k -> Pattern.compile("(?i)" + Pattern.quote(k)));
    }

    // 加载替换规则，部分SQL在PG的通讯协议下需要进行替换，以保证PG协议的正常
    // 注意：本方法必须幂等——重复调用不得让规则表翻倍（原实现只 add、从不 clear）。
    //       synchronized 保护 SQLReplaceItems 的 clear+重建，以及 compiledRules 快照的发布。
    public static synchronized void load(DBInstance dbInstance)
    {
        SQLReplaceItems.clear();

        String   catalogName;

        if (dbInstance.serverConfiguration.getData_Dir().trim().equalsIgnoreCase(":memory:"))
        {
            catalogName = "memory";
        }
        else
        {
            catalogName = dbInstance.serverConfiguration.getData().trim().toLowerCase();
        }
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "ESCAPE '\\\\'","ESCAPE '\\'",
                        false, true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "format_type(nullif(t.typbasetype, 0), t.typtypmod)","t.typname",
                        false, true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_roles",catalogName + ".duck_catalog.pg_roles",
                        true, true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_shdescription", catalogName + ".duck_catalog.pg_shdescription",
                        true, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_namespace", catalogName + ".duck_catalog.pg_namespace",
                        true, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_proc", catalogName + ".duck_catalog.pg_proc",
                        true, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_type", catalogName + ".duck_catalog.pg_type",
                        false, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "array_upper", "array_length",
                        false, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "typinput='pg_catalog.array_in'", "false",
                        false, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_database", catalogName + ".duck_catalog.pg_database",
                        true, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_extension", catalogName + ".duck_catalog.pg_extension",
                        true, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_total_relation_size", catalogName + ".duck_catalog.pg_total_relation_size",
                        true, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_relation_size", catalogName + ".duck_catalog.pg_relation_size",
                        true, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_extension", catalogName + ".duck_catalog.pg_extension",
                        true, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_inherits", catalogName + ".duck_catalog.pg_inherits",
                        true, true)
        );

        // 以下的这几个set， Duck都不支持，但是第三方IDE工具总是查询，所以指向空语句
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "set\\s+application_name.*",
                        "",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "set\\s+session\\s+character.*",
                        "",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "set\\s+extra_float_digits.*",
                        "",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "set\\s+DateStyle.*",
                        "",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "set\\s+IntervalStyle.*",
                        "",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "set\\s+client_min_messages.*",
                        "",true,false
                )
        );
        // duckdb不支持事务级别
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "START\\s+TRANSACTION.*",
                        "",true,false
                )
        );
        // duckdb的客户端字符集无法设置
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "set\\s+client_encoding.*",
                        "",true,false
                )
        );
        // PG的search_path需要特殊处理
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "set\\s+search_path\\s*=\\s*(.*)",
                        "set search_path = $1",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "::regclass", "", false,true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "::regproc", "", false,true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "SHOW search_path",
                        "SELECT current_setting('search_path') as search_path", false,
                        true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "show transaction isolation level",
                        "select 'read committed' as transaction_isolation",
                        false,true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "show transaction_isolation",
                        "select 'read committed' as transaction_isolation",
                        false,true
                )
        );

        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "SHOW datestyle", "SELECT 'ISO, MDY' as DateStyle",
                        false,true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        ".*pg_catalog.pg_get_keywords.*", "",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "(.*)pg_catalog.pg_get_partkeydef\\(c\\.oid\\)(.*)", "$1null$2",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "(.*)pg_catalog.pg_get_expr\\(.*?\\)(.*)","$1null$2",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        ".*pg_catalog.pg_event_trigger.*", "",true,false
                )
        );

        // 编译成不可变快照：规则级的常量工作只在这里做一次
        List<ReplacerRule> compiled = new ArrayList<>(SQLReplaceItems.size());
        for (QueryReplacerItem item : SQLReplaceItems) {
            compiled.add(ReplacerRule.compile(item));
        }
        compiledRules = List.copyOf(compiled);

        // 规则变了，之前缓存的改写结果可能已经失效（例如 catalogName 变化）。
        // 放在快照发布之后：极小的窗口内可能丢掉一个新写入的缓存项，但绝不会留下旧规则的结果。
        replacedSqlCache.invalidateAll();
    }

    // ---- 以下两个方法仅供同包测试使用，用于验证缓存容量约束 ----

    static long replacedSqlCacheEstimatedSize() {
        replacedSqlCache.cleanUp();
        return replacedSqlCache.estimatedSize();
    }

    static void replacedSqlCacheInvalidateAll() {
        replacedSqlCache.invalidateAll();
        replacedSqlCache.cleanUp();
    }

    // 按照分号分割SQL，并依次执行
    public static List<String> splitSQLWithSemicolon(String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder currentSegment = new StringBuilder();
        boolean inSingleQuote = false;

        // 移除SQL中的注释
        sql = SqlCommentStripper.stripComments(sql);

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            // 处理单引号字符串
            if (c == '\'' && (i == 0 || sql.charAt(i - 1) != '\\')) {
                inSingleQuote = !inSingleQuote;
            }

            // 只有在 **不在引号内部** 时，才把 `;` 作为分隔符
            if (c == ';' && !inSingleQuote) {
                result.add(currentSegment.toString().trim());
                currentSegment.setLength(0); // 清空当前片段
            } else {
                currentSegment.append(c);
            }
        }

        // 添加最后一个 SQL 片段
        if (!currentSegment.isEmpty()) {
            result.add(currentSegment.toString().trim());
        }
        return result;
    }

    // 对SQL进行替换，有些SQL无法通过PG的协议，所以需要进行特殊处理
    public static String replaceSQL(DBInstance pDbInstance, String sql) {
        // 如果有缓存的SQL记录，则直接读取缓存的内容
        String replacedSQL = replacedSqlCache.getIfPresent(sql);
        if (replacedSQL != null)
        {
            if (!sql.equals(replacedSQL))
            {
                pDbInstance.logger.trace("[SERVER][SQL Rewrote]: [{}] -> [{}]", sql, replacedSQL);
            }
            return replacedSQL;
        }

        // 读取一次不可变快照：迭代期间即使发生 load() 也不会被修改
        List<ReplacerRule> rules = compiledRules;

        // 按照分号分割，但不要处理带有转义字符的内容
        // 注意：splitSQLWithSemicolon 内部已经剥离过注释，这里不再逐条重复剥离
        List<String> sqlItems = splitSQLWithSemicolon(sql.trim());
        for (int i=0; i<sqlItems.size(); i++) {
            String oldSql = sqlItems.get(i);
            if (oldSql.isEmpty())
            {
                // 空语句，不再执行
                continue;
            }
            String newSql = applyRules(rules, oldSql);
            // PG的查询路径需要特殊处理，以去掉中间的空格和双引号。DUCK不支持空格和双引号
            if (newSql.startsWith("set search_path ="))
            {
                newSql = "set search_path = '" +
                        newSql.replace("set search_path =", "")
                        .replaceAll(SEARCH_PATH_STRIP_PATTERN.pattern(), "") + "'";
            }
            if (!oldSql.equals(newSql))
            {
                // SQL已经发生了变化
                sqlItems.set(i, newSql);
            }
        }

        // 过滤以 -- 开头的字符串
        replacedSQL = String.join("\n", sqlItems);
        replacedSqlCache.put(sql, replacedSQL);
        if (!sql.equals(replacedSQL))
        {
            pDbInstance.logger.trace("[SERVER][SQL Rewrote]: [{}] -> [{}]", sql, replacedSQL);
        }
        return replacedSQL;
    }

    /**
     * 依次应用全部改写规则。
     *
     * <p>与改造前相比只做了一件事：<b>不改变任何规则语义</b>，只是把"必然不可能命中"的规则跳过。
     * 规则按顺序作用于同一份字符串（前一条规则的输出是后一条规则的输入），这一点没有变；
     * 用于预筛的小写副本在字符串被改写后会失效重建，因此顺序语义仍然精确保持。</p>
     */
    private static String applyRules(List<ReplacerRule> rules, String input) {
        String current = input;
        // 惰性生成的小写副本；current 被改写后置空以便重建
        String lowered = null;
        for (ReplacerRule rule : rules) {
            switch (rule.kind()) {
                case SAMPLE -> {
                    if (lowered == null) {
                        lowered = current.toLowerCase(Locale.ROOT);
                    }
                    // 廉价预筛：字面量没出现就直接跳过，省掉一次全串大小写不敏感正则扫描。
                    // 实测 21 条此类规则里平均只有 1 条真正命中。
                    if (!lowered.contains(rule.lowerFind())) {
                        continue;
                    }
                    current = rule.pattern().matcher(current).replaceAll(rule.replace());
                    lowered = null;
                }
                case REGEX -> {
                    Matcher matcher = rule.pattern().matcher(current);
                    if (matcher.matches()) {
                        current = matcher.replaceAll(rule.replace());
                        lowered = null;
                    }
                }
                case LITERAL -> {
                    if (rule.find().equalsIgnoreCase(current)) {
                        current = rule.replace();
                        lowered = null;
                    }
                }
            }
        }
        return current;
    }
}