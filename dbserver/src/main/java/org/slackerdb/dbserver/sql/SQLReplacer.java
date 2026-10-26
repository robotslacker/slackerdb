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
    /**
     * {@code 'schema.table'::pg_catalog.regclass} → 真正的 oid 查询（B4）。
     *
     * <p>{@code postgres_fdw} 的 {@code ANALYZE} 会发（见 {@code deparse.c} 的
     * {@code deparseAnalyzeSizeSql} / {@code deparseAnalyzeInfoSql}）：</p>
     *
     * [source,sql]
     * ----
     * SELECT pg_catalog.pg_relation_size('main.t'::pg_catalog.regclass) / 8192
     * SELECT reltuples, relkind FROM pg_catalog.pg_class WHERE oid = 'main.t'::pg_catalog.regclass
     * ----
     *
     * <p>DuckDB 没有 {@code regclass} 类型（旧行为直接报 {@code Type with name regclass does not exist}）。
     * 这里把它翻译成**真正的 oid 查询**而不是删掉转换留下裸名字：后面的 {@code pg_relation_size} 与
     * {@code pg_class} 都是按 oid 取值的，翻译后整套现有机制直接可用。</p>
     *
     * <p>之所以不走 {@code SQLReplaceItems}：规则表里的 {@code REGEX} 规则要求<b>整条语句</b>匹配
     * （见 {@code ReplacerRule}），而这里需要的是"语句片段 + 捕获组"的替换。</p>
     */
    private static final Pattern REGCLASS_CAST_PATTERN = Pattern.compile(
            "'([^']*)'\\s*::\\s*pg_catalog\\s*\\.\\s*regclass", Pattern.CASE_INSENSITIVE);

    private static final String REGCLASS_CAST_REPLACEMENT =
            "(select coalesce("
                    + "(select table_oid from duckdb_tables "
                    + "where database_name = getvariable('current_database') "
                    + "and (schema_name || '.' || table_name = '$1' or table_name = '$1')), "
                    + "(select view_oid from duckdb_views "
                    + "where database_name = getvariable('current_database') "
                    + "and (schema_name || '.' || view_name = '$1' or view_name = '$1'))))";

    /** 把语句里出现的 {@code 'x'::pg_catalog.regclass} 片段替换成 oid 子查询（没有则原样返回）。 */
    private static String rewriteRegclassCast(String sql) {
        Matcher matcher = REGCLASS_CAST_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return sql;
        }
        return matcher.replaceAll(REGCLASS_CAST_REPLACEMENT);
    }

    /**
     * PG 的 {@code bytea} 十六进制字面量 → DuckDB 的 {@code from_hex()}。
     *
     * <p>PG 把 {@code '\x414243'::bytea} 定义为"把 \x 后面的十六进制串解码成字节"，
     * 也就是 3 个字节 {@code 41 42 43}。DuckDB 收到同一个字面量时是按<b>文本</b>处理：
     * 把字符 {@code \ x 4 1 4 2 4 3} 本身编码成 UTF-8 字节，于是得到 5 个字节
     * {@code 4134323433} —— 即 {@code '\x414243'::bytea} 实测 {@code octet_length} 为 5
     * 而不是 3，二进制数据被静默损坏。</p>
     *
     * <p>改写目标 {@code from_hex('414243')} 实测返回恰好 3 个字节的 BLOB
     * （{@code hex()} = {@code 414243}），是 DuckDB 原生函数，不依赖 fake catalog。
     * 只改写"显式写了 {@code \x} 前缀且内容全是十六进制"的字面量：其它写法
     * （{@code 'abc'::bytea}、{@code '\xZZ'::bytea}）保持原样，由 DuckDB 自己报错，
     * 避免把本来合法的写法改坏。</p>
     */
    private static final Pattern BYTEA_HEX_LITERAL_PATTERN = Pattern.compile(
            "'\\\\x([0-9A-Fa-f]*)'\\s*(::\\s*(?:pg_catalog\\s*\\.\\s*)?bytea)",
            Pattern.CASE_INSENSITIVE);

    private static String rewriteByteaHexLiteral(String sql) {
        Matcher matcher = BYTEA_HEX_LITERAL_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return sql;
        }
        return matcher.replaceAll("from_hex('$1')");
    }

    /**
     * 把 PG 的 {@code OPERATOR(pg_catalog.~)} / {@code OPERATOR(pg_catalog.!~)} 以及
     * {@code COLLATE pg_catalog.default} 改写成 DuckDB 能接受的等价形式。
     *
     * <p>psql 的 {@code \d <表>} 生成的是</p>
     * [source,sql]
     * ----
     * WHERE c.relname OPERATOR(pg_catalog.~) '^(t)$' COLLATE pg_catalog.default
     * ----
     * <p>DuckDB 会把 {@code OPERATOR(pg_catalog.~)} 里的 {@code pg_catalog} 当成<b>函数名</b>
     * 解析，报 {@code Scalar Function with name pg_catalog does not exist}；{@code COLLATE
     * pg_catalog.default} 则报 {@code Collation with name pg_catalog does not exist}。
     * 两者都无法靠 {@code SET search_path} 绕过（报错发生在函数/排序规则解析阶段）。</p>
     *
     * <p>特别注意：<b>不能用 DuckDB 的 {@code ~} 替代</b>。DuckDB 里 {@code ~} 是<b>按位取反</b>
     * （实测 {@code 'abc' ~ 'b'} 返回 {@code false}，且 {@code duckdb_functions()} 里 {@code ~}
     * 的参数是整型），语义完全不同。PG 的 {@code ~} 是正则匹配，对应 DuckDB 的
     * {@code regexp_matches(string, regex)}。{@code !~} 则对应 {@code NOT regexp_matches(...)}。</p>
     */
    private static final Pattern PG_DEFAULT_COLLATE_PATTERN = Pattern.compile(
            "\\s+COLLATE\\s+(?:pg_catalog\\s*\\.\\s*)?(?:default|\"default\"|C)\\b",
            Pattern.CASE_INSENSITIVE);

    private static String rewritePgCatalogOperatorAndCollate(String sql) {
        // 中缀写法：A OPERATOR(pg_catalog.~) 'pattern'  →  regexp_matches(A, 'pattern')
        String result = rewriteInfixRegexMatch(sql);
        // COLLATE pg_catalog.default：DuckDB 没有 PG 的排序规则命名空间，
        // 默认排序即其唯一行为，直接剥掉。
        // 同样先做廉价预筛：这个模式以 \s+ 开头，也会在每个位置尝试并吃掉整段空白再回溯，
        // 长文本参数里若有大段空白同样会退化。
        if (containsIgnoreCase(result, "collate")) {
            Matcher collateMatcher = PG_DEFAULT_COLLATE_PATTERN.matcher(result);
            if (collateMatcher.find()) {
                result = collateMatcher.replaceAll("");
            }
        }
        return result;
    }

    /**
     * 大小写不敏感的"包含"判断，且不分配整串的小写副本。
     *
     * <p>用于给昂贵的正则做廉价预筛。之所以不用
     * {@code sql.toLowerCase(Locale.ROOT).contains(...)}：这条路径上可能出现几十 KB 的
     * 语句文本（客户端会把长参数内联进 SQL），少一次整串复制就少一次大对象分配。</p>
     */
    private static boolean containsIgnoreCase(String haystack, String needleLower) {
        int limit = haystack.length() - needleLower.length();
        for (int i = 0; i <= limit; i++) {
            if (haystack.regionMatches(true, i, needleLower, 0, needleLower.length())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把 {@code <左> OPERATOR(pg_catalog.~) <右>} 这种中缀写法转成
     * {@code regexp_matches(<左>, <右>)} 的匹配模式。
     *
     * <p>左操作数按 PG 的 {@code OPERATOR(...)} 语法只允许"限定名/列引用"（psql 生成的是
     * {@code c.relname} 这类），不做通用表达式解析 —— 宁可漏改也不要误改，漏改会照旧报错，
     * 误改会静默改变语义。右操作数同样只吃单引号字符串字面量（psql 的 pattern 就是字面量）。</p>
     *
     * <p><b>为什么锚在 OPERATOR 上、而不是从左边开始写。</b>左操作数的字符集是
     * {@code [A-Za-z_0-9$.]}，如果把它放在模式开头，正则引擎会在**每一个位置**尝试匹配，
     * 而且量词贪婪无上界：遇到长的"标识符样"串（客户端把长文本参数内联进 SQL 时就是如此）
     * 每个起始位置都要吞掉整段再逐字符回溯，总代价 O(n²)（实测 64KB 字面量 133 秒）。
     * 这里改为只匹配罕见的 {@code OPERATOR(...)} 关键字本身，左操作数用一次**向回扫描**
     * 得出（见 {@link #rewriteInfixRegexMatch}），整体是 O(n)。</p>
     *
     * <p>组的含义：{@code 1} = {@code ~} 或 {@code !~}；{@code 2} = 单引号字符串字面量。</p>
     */
    private static final Pattern PG_INFIX_REGEX_OPERATOR_PATTERN = Pattern.compile(
            "OPERATOR\\s*\\(\\s*pg_catalog\\s*\\.\\s*(!?~)\\s*\\)\\s*('(?:[^']|'')*')",
            Pattern.CASE_INSENSITIVE);

    /** PG {@code \s} 的含义（未开启 UNICODE_CHARACTER_CLASS 时）。 */
    private static boolean isPgWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r';
    }

    /** 与模式里左操作数的 {@code [A-Za-z_0-9$.]} 保持一致。 */
    private static boolean isIdentifierChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '$' || c == '.';
    }

    /** 与模式里左操作数的首字符 {@code [A-Za-z_]} 保持一致。 */
    private static boolean isIdentifierStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static String rewriteInfixRegexMatch(String sql) {
        // 廉价预筛：连 OPERATOR 这个**必需**关键字都没有，规则必然不命中，直接返回。
        // 绝大多数语句（尤其是内联了长文本参数的语句）走到这里就结束了。
        if (!containsIgnoreCase(sql, "operator")) {
            return sql;
        }

        Matcher matcher = PG_INFIX_REGEX_OPERATOR_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return sql;
        }

        StringBuilder out = new StringBuilder(sql.length());
        int last = 0;
        do {
            // 向回找左操作数：先跳过空白，再吃掉最长的一段标识符字符。
            int identEnd = matcher.start();
            while (identEnd > 0 && isPgWhitespace(sql.charAt(identEnd - 1))) {
                identEnd--;
            }
            int runStart = identEnd;
            while (runStart > 0 && isIdentifierChar(sql.charAt(runStart - 1))) {
                runStart--;
            }
            // 与原来的 "([A-Za-z_][A-Za-z_0-9$.]*)\s*OPERATOR" 等价：
            // 最左的合法起始位置决定左操作数（例如 "1abc" 要取 "abc"，而不是整段 "1abc"）。
            int operandStart = runStart;
            while (operandStart < identEnd && !isIdentifierStart(sql.charAt(operandStart))) {
                operandStart++;
            }
            // 左操作数必须是"限定名/列引用"：非空、首字符是字母/下划线，且不能与前一次改动重叠；
            // 否则按原样保留 —— 宁可漏改（照旧报错），也不要误改（静默改变语义）。
            if (operandStart >= identEnd || operandStart < last) {
                continue;
            }

            out.append(sql, last, operandStart);
            String left = sql.substring(operandStart, identEnd);
            String op = matcher.group(1);
            String right = matcher.group(2);
            String call = "regexp_matches(" + left + ", " + right + ")";
            out.append("!~".equals(op) ? "NOT " + call : call);
            last = matcher.end();
        } while (matcher.find());
        out.append(sql, last, sql.length());
        return out.toString();
    }

    /**
     * 把 {@code ::pg_catalog.<类型>} 的 schema 限定去掉，只留类型名。
     *
     * <p>psql 的 {@code \d <表>} 第二条查询里有</p>
     * [source,sql]
     * ----
     * CASE WHEN c.reloftype = 0 THEN '' ELSE c.reloftype::pg_catalog.regtype::pg_catalog.text END
     * ----
     * <p>DuckDB 的 cast 语法只接受<b>不带 catalog/schema 限定</b>的类型名，
     * {@code ::pg_catalog.text} 直接 {@code Parser Error: syntax error at or near "text"}。
     * 而 PG 的内置类型（text/int4/varchar…）本来就总在搜索路径里，去掉限定完全等价 ——
     * 这与 DuckDB 自己 {@code ::text} 可用、{@code ::pg_catalog.text} 不可用的实测一致。</p>
     *
     * <p>安全性：{@code ::pg_catalog.} 这个字面序列只可能出现在类型转换处
     * （表名/函数名前面不会带 {@code ::}），因此这条改写不会误伤别的构造。
     * 类型名后的长度/精度修饰（{@code varchar(10)}）与多词类型（{@code timestamp with time zone}）
     * 都保留原样传给 DuckDB；DuckDB 不认的伪类型（restype 等）由已有的
     * {@code ::regclass}/{@code ::regproc} 规则继续兜底。</p>
     */
    private static final Pattern PG_CATALOG_CAST_TYPE_PATTERN = Pattern.compile(
            "::\\s*pg_catalog\\s*\\.\\s*([A-Za-z_][A-Za-z_0-9]*(?:\\s*\\([^)]*\\))?"
                    + "(?:\\s+with\\s+time\\s+zone|\\s+without\\s+time\\s+zone)?)",
            Pattern.CASE_INSENSITIVE);

    private static String rewritePgCatalogCastType(String sql) {
        Matcher matcher = PG_CATALOG_CAST_TYPE_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return sql;
        }
        return matcher.replaceAll("::$1");
    }

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

        // fake catalog（duck_catalog）所在的 catalog 名。
        //
        // 唯一来源是 DBInstance.catalogName（见其字段说明）。历史上这里自己按
        // data_dir / data 推导，而 StartupRequest 那边写死 "memory"，两者会在 data 为空时
        // 分叉：这里得到空串，于是 pg_catalog.pg_type 被改写成非法的 ".duck_catalog.pg_type"，
        // 任何触发 INTERVAL/类型元数据查询的客户端都直接报
        // "Parser Error: syntax error at or near \".\""。
        String   catalogName = dbInstance.catalogName;
        if (catalogName == null || catalogName.isBlank()) {
            // 兜底：后端连接串固定是 jdbc:duckdb:memory:db<UUID>，DuckDB 的默认 catalog 就叫 memory
            catalogName = "memory";
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
        // pg_get_userbyid：SlackerCatalog 里已经建好了宏
        // （`create or replace macro duck_catalog.pg_get_userbyid(a) as (select 'system')`），
        // 但**当时漏了这条改写规则**，于是客户端发来的显式限定名 pg_catalog.pg_get_userbyid(...)
        // 原样落到 DuckDB —— 而 DuckDB 的 pg_catalog 里没有这个函数，报
        // "Scalar Function with name pg_get_userbyid does not exist"。
        // psql 的 \dt / \dv / \di / \ds / \dm / \l / \db / \dn / \d / \dS 等元命令都要取 Owner
        // 列，全部因此失败。补上这一条即可让这批元命令恢复。
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_get_userbyid", catalogName + ".duck_catalog.pg_get_userbyid",
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
        // regtype：DuckDB 既没有这个类型也没有对应函数（实测 "Type with name regtype does not exist"）。
        // psql 只在 `\d <表>` 里用它来显示列的默认类型名（`c.reloftype::pg_catalog.regtype::pg_catalog.text`），
        // 去掉 cast 不会造成静默错误 —— DuckDB 少了这层"类型名格式化"，但 format_type 那条规则
        // 已经把等价信息补上了。
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "::regtype", "", false,true
                )
        );
        // pg_table_size：DuckDB 没有，用已有的 pg_relation_size 语义顶替
        // （psql 的 `\dt+` 要显示 Size 列，走 pg_size_pretty(pg_table_size(oid))；pg_size_pretty 是 DuckDB 原生函数）。
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_table_size", catalogName + ".duck_catalog.pg_table_size",
                        true, true)
        );
        // pg_encoding_to_char / pg_tablespace_location：SlackerCatalog 早已建好同名宏，
        // 这里同样只是补上漏掉的改写规则（`\l` `\l+` 要 encoding，`\db` 要 tablespace location）。
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_encoding_to_char", catalogName + ".duck_catalog.pg_encoding_to_char",
                        true, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_tablespace_location", catalogName + ".duck_catalog.pg_tablespace_location",
                        true, true)
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
        // SHOW timezone：DuckDB 里 TimeZone 是真实参数（`SET timezone` 会透传给 DuckDB），
        // 但 `SHOW timezone` 这种写法 DuckDB 不认（当表名 → 42P01）。
        // 改写成读真实参数，保证与 SET 的结果一致 —— 这里**不能**用固定答案，否则就是撒谎。
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "SHOW timezone", "SELECT current_setting('TimeZone') as \"TimeZone\"",
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

        // SQLAlchemy（psycopg2 / psycopg / asyncpg 任一驱动）在建立连接的第一条语句就会执行
        // "select pg_catalog.version()"，并用 ^(?:PostgreSQL|EnterpriseDB) (\d+)\.?(\d+)? 解析结果；
        // 解析不出来直接抛 AssertionError("Could not determine version from string")。
        // DuckDB 没有 pg_catalog.version()，原样发过去是 "Referenced column pg_catalog was not found"，
        // 结果是 SQLAlchemy 根本连不上（engine.connect() 就失败）。
        //
        // 只改写带 pg_catalog. 前缀的写法：裸 version() 仍返回 DuckDB 自己的版本串，
        // 免得改变 pgjdbc / DBeaver 依据 getDatabaseProductVersion() 做的 haveMinimumServerVersion() 判定。
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.version()",
                        "'PostgreSQL " + DBInstance.PG_COMPAT_VERSION + " (SlackerDB)'",
                        false, true
                )
        );

        // 同一个连接阶段还会执行 "show standard_conforming_strings"（SQLAlchemy 用它决定
        // 反斜杠转义策略），DuckDB 没有这个配置项，会报 "Table with name
        // standard_conforming_strings does not exist"。
        // 取值必须与 StartupRequest 下发的 ParameterStatus 一致（都是 on）。
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "show standard_conforming_strings",
                        "select 'on' as standard_conforming_strings",
                        false, true
                )
        );

        // 运行期统计函数：DuckDB 没有 pg_stat_* 视图，pg_catalog 下也没有任何 pg_stat_get_* 函数，
        // 而客户端展示表/索引统计时会直接写 pg_catalog.pg_stat_get_numscans(i.indexrelid) 这类调用
        // （缺函数时整条元数据查询报 "Scalar Function with name pg_stat_get_numscans does not exist"）。
        // SlackerCatalog 为常用的一组建了返回 NULL 的空壳宏；这里只改写**命名空间前缀**：
        // 客户端的函数名有几十个，逐个写规则既容易漏，又要在热路径上多跑几十次字面量预筛
        // （见 applyRules 的预筛），而"前缀 + 宏"的组合补一个新函数只需要加一行宏。
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_stat_get_", catalogName + ".duck_catalog.pg_stat_get_",
                        true, true)
        );

        // pg_aggregate：与 pg_proc 一样是"只满足存在 + 列齐全"的空关系（见 SlackerCatalog）。
        // 客户端枚举聚合函数会写 `pg_proc p JOIN pg_aggregate a ON a.aggfnoid = p.oid`，
        // 缺表时整条查询报 "Table with name pg_aggregate does not exist"。
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_aggregate", catalogName + ".duck_catalog.pg_aggregate",
                        true, true)
        );

        // pg_stat_activity：同样是空关系。会话的**实时**信息不通过 PG 目录提供 ——
        // SlackDB 自己的管理面才是权威入口（/status 的 sessions、POST /sessions/{id}/kill、
        // 以及 SQL 的 KILL <sessionId>）。这里保留一张零行的表，只是让客户端的会话面板
        // "显示为空"，而不是整条查询报 "Table with name pg_stat_activity does not exist"。
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_stat_activity", catalogName + ".duck_catalog.pg_stat_activity",
                        true, true)
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

    /**
     * 从 {@code SET application_name = 'x'} 里取出应用名；不是这条语句时返回 {@code null}。
     *
     * <p><b>为什么需要它</b>：pgjdbc 系驱动（含本项目 {@code dbdriver}）在握手完成后用一条
     * {@code SET application_name = '...'} 上报来源，而这条语句在下面会被替换成空语句
     * （DuckDB 不支持该设置）。要在管理端辨识"这条连接是谁的"（会话面板、安全地终止会话），
     * 就必须在替换之**前**把值截留下来，记到 {@code DBSession.applicationName} 上。</p>
     *
     * <p>只做最小解析：支持 {@code =} 与 {@code TO} 两种写法、单引号字符串（{@code ''} 转义）、
     * 大小写不敏感；不做完整 SQL 解析（真要完整解析请走 {@code plsql} 的分析器，代价不值得）。</p>
     *
     * @param sql 客户端发来的原始 SQL（替换前）
     * @return 应用名；不是 SET application_name 语句时返回 {@code null}
     */
    public static String extractApplicationName(String sql) {
        if (sql == null || sql.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher matcher = APPLICATION_NAME_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).replace("''", "'");
    }

    /** {@code SET application_name = 'x'} / {@code SET application_name TO 'x'} 的取值模式。 */
    private static final java.util.regex.Pattern APPLICATION_NAME_PATTERN =
            java.util.regex.Pattern.compile(
                    "(?is)\\bset\\s+application_name\\s*(?:=|\\bto\\b)\\s*'((?:[^']|'')*)'");

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
            String newSql = applyRules(rules,
                    rewritePgCatalogCastType(
                            rewritePgCatalogOperatorAndCollate(
                                    rewriteByteaHexLiteral(
                                            rewriteRegclassCast(oldSql)))));
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

    /** 未限定的 pg_namespace / pg_attribute / pg_type（前面不能是 "." 或标识符字符）。 */
    private static final Pattern BARE_PG_NAMESPACE =
            Pattern.compile("(?<![.\\w])pg_namespace\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_PG_ATTRIBUTE =
            Pattern.compile("(?<![.\\w])pg_attribute\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_PG_TYPE =
            Pattern.compile("(?<![.\\w])pg_type\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 把 catalog introspection 查询里的未限定 {@code pg_namespace} / {@code pg_attribute} /
     * {@code pg_type} 指到 fake catalog。
     *
     * <p>为什么需要单独一遍：{@link #replaceSQL} 的 REGEX 规则是<b>整串匹配</b>
     * （{@link ReplacerRule.Kind#REGEX} 用 {@code matches()} 判断），无法做
     * "语句中间出现的裸表名"替换；而 DuckDB postgres 扩展加载 catalog 时发的正是
     * {@code pg_class JOIN pg_namespace JOIN pg_attribute JOIN pg_type} 这种未限定名。</p>
     *
     * <p>只在语句具备 catalog introspection 的特征时才改写（有 pg_class/attrelid/attnum/
     * nspname/relnamespace/pg_indexes/typnamespace 等），避免把普通 SQL 里恰好出现的字符串改坏。</p>
     */
    public static String rewriteCatalogIntrospection(DBInstance pDbInstance, String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        String lower = sql.toLowerCase(Locale.ROOT);
        boolean namespace = lower.contains("pg_namespace")
                && (lower.contains("nspname") || lower.contains("relnamespace")
                || lower.contains("pg_class") || lower.contains("pg_indexes"));
        boolean attribute = lower.contains("pg_attribute")
                && (lower.contains("attrelid") || lower.contains("atttypid")
                || lower.contains("attnum") || lower.contains("pg_class"));
        boolean type = lower.contains("pg_type")
                && (lower.contains("pg_class") || lower.contains("atttypid")
                || lower.contains("typnamespace"));
        if (!namespace && !attribute && !type) {
            return sql;
        }
        String catalogName = pDbInstance.catalogName;
        if (catalogName == null || catalogName.isBlank()) {
            catalogName = "memory";
        }
        if (namespace) {
            sql = BARE_PG_NAMESPACE.matcher(sql).replaceAll(
                    Matcher.quoteReplacement(catalogName + ".duck_catalog.pg_namespace"));
        }
        if (attribute) {
            sql = BARE_PG_ATTRIBUTE.matcher(sql).replaceAll(
                    Matcher.quoteReplacement(catalogName + ".duck_catalog.pg_attribute"));
        }
        if (type) {
            sql = BARE_PG_TYPE.matcher(sql).replaceAll(
                    Matcher.quoteReplacement(catalogName + ".duck_catalog.pg_type"));
        }
        return sql;
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
