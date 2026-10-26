package org.slackerdb.dbserver.test;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.sql.SQLReplacer;
import org.slackerdb.dbserver.sql.antlr.CopyVisitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * psql 兼容性修复的回归测试（四类问题）。
 *
 * <p>这四项都是"实测 psql 18.6 报错、根因明确、修法很小"的缺陷，单独钉住防止回退：</p>
 * <ol>
 *   <li><b>COPY 的 STDIN/STDOUT 大小写</b>：ANTLR 的 {@code caseInsensitive = true} 对
 *       显式词法规则里的整串字面量不生效，`copy t from stdin` 被判成"不是 COPY FROM STDIN"
 *       并返回 0A000，而错误信息偏偏是 "Only support COPY .. FROM STDIN"。</li>
 *   <li><b>bytea 的 {@code \x} 字面量</b>：PG 语义是"十六进制解码成字节"，
 *       DuckDB 按文本处理，导致二进制数据被静默损坏（3 字节变 5 字节）。</li>
 *   <li><b>pg_get_userbyid 未改写</b>：宏已建在 duck_catalog，但漏了改写规则，
 *       psql 的 \dt/\l/\dn/\d 等取 Owner 列时全部失败。</li>
 *   <li><b>OPERATOR(pg_catalog.~) / COLLATE pg_catalog.default</b>：psql 的
 *       {@code \d <表>} 生成这两段，DuckDB 分别报"函数 pg_catalog 不存在"与
 *       "排序规则 pg_catalog 不存在"，且 {@code SET search_path} 无法绕过。</li>
 * </ol>
 */
public class PsqlCompatFixTest {

    private static DBInstance dbInstance;

    @BeforeAll
    static void setUp() throws Exception {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(-1);
        cfg.setData("psqlcompat");
        cfg.setData_dir(":memory:");
        cfg.setLog_level("INFO");
        dbInstance = new DBInstance(cfg);
        dbInstance.logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger("psqlcompat");
        // 不走 attachDatabase（那需要真正启动实例），显式对齐 catalogName，
        // 否则改写前缀与真实启动路径不一致（与 SqlReplacerGoldenTest 同样的处理）。
        dbInstance.refreshCatalogName();
        SQLReplacer.load(dbInstance);
    }

    private static String rewrite(String sql) {
        return SQLReplacer.replaceSQL(dbInstance, sql);
    }

    // ================================================================
    // 1. COPY：STDIN/STDOUT 大小写不敏感
    // ================================================================

    private static JSONObject parseCopy(String sql) {
        JSONObject parsed = CopyVisitor.parseCopyStatement(sql);
        assertEquals(0, parsed.getInteger("errorCode"),
                "COPY 语句应当能被解析，但失败了: " + sql + " -> " + parsed.getString("errorMsg"));
        return parsed;
    }

    @Test
    void copyStdinIsCaseInsensitive() {
        String[] variants = {
                "COPY t FROM STDIN;",
                "copy t from stdin;",
                "Copy t From Stdin;",
                "cOpY t fRoM sTdIn;",
                "COPY t FROM STDIN",
                "copy t from stdin with (format csv)",
        };
        for (String sql : variants) {
            JSONObject parsed = parseCopy(sql);
            assertEquals("FROM", parsed.getString("copyDirection"), "方向判定错误: " + sql);
            assertEquals("STDIN", parsed.getString("copyFilePath").toUpperCase(java.util.Locale.ROOT),
                    "数据源判定错误: " + sql);
            assertEquals("table", parsed.getString("copyType"), "copyType 判定错误: " + sql);
        }
    }

    /** DuckDB postgres 扩展的 attached INSERT 会发 schema-qualified 的带引号表名。 */
    @Test
    void copyQuotedSchemaQualifiedTableParses() {
        JSONObject parsed = parseCopy("COPY \"main\".\"t2\" FROM STDIN (FORMAT BINARY)");
        assertEquals("table", parsed.getString("copyType"));
        assertEquals("\"main\".\"t2\"", parsed.getString("table"));
        assertEquals("FROM", parsed.getString("copyDirection"));
        assertEquals("STDIN", parsed.getString("copyFilePath").toUpperCase(java.util.Locale.ROOT));

        JSONObject withColumns = parseCopy(
                "COPY \"main\".\"t2\" (id, name) FROM STDIN (FORMAT BINARY)");
        assertEquals("table", withColumns.getString("copyType"));
        assertEquals("\"main\".\"t2\"", withColumns.getString("table"));
    }

    @Test
    void copyStdoutIsCaseInsensitiveAndStillIdentifiedAsTo() {
        for (String sql : new String[]{"COPY t TO STDOUT;", "copy t to stdout;", "Copy t To Stdout;"}) {
            JSONObject parsed = parseCopy(sql);
            assertEquals("TO", parsed.getString("copyDirection"), "方向判定错误: " + sql);
        }
    }

    /** 反例：文件路径不是 STDIN 时不能被误判成 STDIN。 */
    @Test
    void copyFromFileIsNotStdin() {
        JSONObject parsed = parseCopy("copy t from '/tmp/x.csv';");
        assertEquals("FROM", parsed.getString("copyDirection"));
        assertTrue(!"STDIN".equalsIgnoreCase(parsed.getString("copyFilePath")),
                "文件路径被误判为 STDIN");
    }

    /** 表名大小写不能被改写破坏（`COPY CX` 的表名必须还是 CX 的语义）。 */
    @Test
    void copyKeepsTableName() {
        JSONObject parsed = parseCopy("COPY MyTable FROM STDIN;");
        assertEquals("MyTable", parsed.getString("table"), "表名被改坏了");
    }

    /** 选项解析仍然正常（FORMAT csv 与 FORMAT 'csv' 等价）。 */
    @Test
    void copyOptionsStillParsed() {
        JSONObject parsed = parseCopy("copy t from stdin with (format csv, header true);");
        JSONObject options = parsed.getJSONObject("options");
        assertNotNull(options, "选项没有被解析");
        assertEquals("csv", options.getString("FORMAT").toLowerCase(java.util.Locale.ROOT));
    }

    // ================================================================
    // 2. bytea 的 \x 十六进制字面量
    // ================================================================

    @Test
    void byteaHexLiteralIsDecodedNotTreatedAsText() {
        // '\x414243' 必须变成 3 个字节，而不是把 "\x414243" 这 8 个字符编码成文本。
        assertEquals("select from_hex('414243')", rewrite("select '\\x414243'::bytea"));
    }

    @Test
    void byteaHexLiteralVariants() {
        assertEquals("select from_hex('41')", rewrite("select '\\x41'::bytea"));
        assertEquals("select from_hex('')", rewrite("select '\\x'::bytea"));
        // 十六进制数字大小写都要覆盖
        assertEquals("select from_hex('CAFEBABE')", rewrite("select '\\xCAFEBABE'::bytea"));
        // 显式 pg_catalog 限定
        assertEquals("select from_hex('00FF')", rewrite("select '\\x00FF'::pg_catalog.bytea"));
        // 类型名大小写混合
        assertEquals("select from_hex('ABCD')", rewrite("select '\\xABCD'::ByteA"));
    }

    /**
     * 有意不覆盖 {@code ::BLOB}：{@code blob} 是 DuckDB 自己的类型名，PG 侧没有，
     * 放宽到 BLOB 会同时改掉类型名（需要输出 from_hex(...)::BLOB 才保语义），
     * 属于另一个问题。这里只钉住"PG 客户端会写的 bytea 形态"。
     */
    @Test
    void duckdbOnlyBlobCastIsLeftAlone() {
        assertEquals("select '\\xCAFEBABE'::BLOB", rewrite("select '\\xCAFEBABE'::BLOB"));
    }

    /** 反例：非十六进制内容、以及不是 \x 前缀的字面量都不能被改写。 */
    @Test
    void byteaNonHexLiteralIsLeftAlone() {
        // 内容含非十六进制字符：不该改写成 from_hex（否则会把错误提前成"函数参数错误"）
        assertTrue(!rewrite("select '\\xZZ'::bytea").contains("from_hex"),
                "'\\xZZ' 不应被改写成 from_hex");
        // 普通文本转换：保持原样，DuckDB 自己按文本编码（与 PG 语义一致）
        assertEquals("select 'ABC'::bytea", rewrite("select 'ABC'::bytea"));
        // 其他类型的 \x 字面量不应受影响（例如 text）
        assertEquals("select '\\x414243'::text", rewrite("select '\\x414243'::text"));
    }

    // ================================================================
    // 3. pg_get_userbyid 改写
    // ================================================================

    @Test
    void pgGetUserbyidIsRewrittenIntoTargetCatalog() {
        String rewritten = rewrite("select pg_catalog.pg_get_userbyid(c.relowner) from pg_catalog.pg_class c");
        assertTrue(rewritten.contains(".duck_catalog.pg_get_userbyid"),
                "pg_get_userbyid 没有被改写到 fake catalog: " + rewritten);
        assertTrue(!rewritten.contains("pg_catalog.pg_get_userbyid"),
                "pg_catalog.pg_get_userbyid 仍然残留: " + rewritten);
    }

    /** psql \dt 的真实形态（Owner 列）必须能改写。 */
    @Test
    void psqlBackslashDtOwnerQueryIsRewritten() {
        String psqlQuery = "SELECT n.nspname as \"Schema\", c.relname as \"Name\", "
                + "pg_catalog.pg_get_userbyid(c.relowner) as \"Owner\" "
                + "FROM pg_catalog.pg_class c LEFT JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace;";
        String rewritten = rewrite(psqlQuery);
        assertTrue(rewritten.contains(".duck_catalog.pg_get_userbyid"), rewritten);
        assertTrue(rewritten.contains(".duck_catalog.pg_namespace"), rewritten);
    }

    /** 同类漏改也必须一并生效（宏早已存在，只是没有规则）。 */
    @Test
    void siblingCatalogMacrosAreRewritten() {
        assertTrue(rewrite("select pg_catalog.pg_encoding_to_char(6)")
                .contains(".duck_catalog.pg_encoding_to_char"));
        assertTrue(rewrite("select pg_catalog.pg_tablespace_location(0)")
                .contains(".duck_catalog.pg_tablespace_location"));
        assertTrue(rewrite("select pg_catalog.pg_table_size(c.oid)")
                .contains(".duck_catalog.pg_table_size"));
    }

    // ================================================================
    // 4. OPERATOR(pg_catalog.~) / COLLATE pg_catalog.default
    // ================================================================

    @Test
    void pgOperatorRegexMatchIsRewritten() {
        String rewritten = rewrite("select 1 from t where c.relname OPERATOR(pg_catalog.~) '^(t)$'");
        assertTrue(rewritten.contains("regexp_matches(c.relname, '^(t)$')"),
                "OPERATOR(pg_catalog.~) 没有被改写成 regexp_matches: " + rewritten);
        assertTrue(!rewritten.contains("OPERATOR("), "OPERATOR( 仍然残留: " + rewritten);
    }

    @Test
    void pgOperatorNegativeRegexMatchIsRewritten() {
        String rewritten = rewrite("select 1 from t where c.relname OPERATOR(pg_catalog.!~) '^pg_'");
        assertTrue(rewritten.contains("NOT regexp_matches(c.relname, '^pg_')"),
                "OPERATOR(pg_catalog.!~) 没有被改写成 NOT regexp_matches: " + rewritten);
    }

    @Test
    void pgCatalogDefaultCollateIsStripped() {
        String rewritten = rewrite("select 1 from t where c.relname = 'x' COLLATE pg_catalog.default");
        assertTrue(!rewritten.toLowerCase(java.util.Locale.ROOT).contains("collate"),
                "COLLATE 没有被剥掉: " + rewritten);
    }

    /** psql \d <表> 第一条查询的完整形态必须能改写通过（这是修复的核心目标）。 */
    @Test
    void psqlBackslashDNameLookupQueryIsRewritten() {
        String psqlQuery = "SELECT c.oid, n.nspname, c.relname "
                + "FROM pg_catalog.pg_class c "
                + "LEFT JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE c.relname OPERATOR(pg_catalog.~) '^(fix_t)$' COLLATE pg_catalog.default "
                + "AND pg_catalog.pg_table_is_visible(c.oid) ORDER BY 2, 3;";
        String rewritten = rewrite(psqlQuery);
        assertTrue(rewritten.contains("regexp_matches(c.relname, '^(fix_t)$')"),
                "正则匹配没有改写: " + rewritten);
        assertTrue(!rewritten.toLowerCase(java.util.Locale.ROOT).contains("collate"),
                "COLLATE 没有剥掉: " + rewritten);
        assertTrue(!rewritten.contains("OPERATOR("), "OPERATOR( 仍然残留: " + rewritten);
    }

    /**
     * 性能回归：长文本字面量不得让 OPERATOR 改写退化成 O(n²)。
     *
     * <p>客户端会把参数**内联进 SQL**（psycopg2 实测就是发简单查询 + 内联字面量，
     * 见测试目录外的抓包结论），所以一个几十 KB 的文本参数会变成一条几十 KB 的语句文本。
     * 而 {@code PG_INFIX_REGEX_MATCH_PATTERN} 以标识符字符类开头、量词贪婪且无上界，
     * 正则引擎会在**每一个位置**尝试匹配、先把整段吃掉再逐字符回溯：</p>
     *
     * <p>修复前实测（32KB 全字母字面量）：线程栈长时间停在
     * {@code SQLReplacer.rewriteInfixRegexMatch} 的 {@code Matcher.find()} 上，
     * 64KB 字面量需要 <b>133 秒</b>；修复后同一条语句是**毫秒级**（只多一次 O(n) 的关键字预筛）。</p>
     *
     * <p>阈值给到 2 秒：修复后是毫秒级，相差三个数量级，所以只有真的退回 O(n²) 才会失败，
     * 不会被 CI 负载噪声误伤；同时也保证回归时测试在几十秒内失败，而不是把套件挂住。</p>
     */
    @Test
    void longTextLiteralDoesNotDegradeRewriteIntoQuadratic() {
        String big = "x".repeat(32 * 1024);            // 32KB 全字母：最坏形态
        String sql = "select length('" + big + "'::text)";

        long t0 = System.nanoTime();
        String rewritten = rewrite(sql);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(elapsedMs < 2000,
                "长文本字面量的 SQL 改写耗时异常（疑似 O(n²) 回归）：" + elapsedMs + " ms");
        assertTrue(rewritten.contains(big),
                "没有命中任何改写规则时，长字面量必须原样保留");
    }

    /**
     * 反向用例：长文本里**真的含有** {@code OPERATOR} 关键字时，预筛会放行，
     * 此时匹配本身也必须是线性的（模式锚在 OPERATOR 上、左操作数向回扫描）。
     *
     * <p>只有预筛、没有改匹配模式的中间版本在这条用例上实测要 56 秒 —— 也就是说
     * "存一段含 OPERATOR 的文档/SQL 片段"这种真实数据仍然能把连接卡住。</p>
     */
    @Test
    void longTextLiteralContainingOperatorKeywordStaysFast() {
        String big = "OPERATOR " + "x".repeat(32 * 1024);
        String sql = "select length('" + big + "'::text)";

        long t0 = System.nanoTime();
        String rewritten = rewrite(sql);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(elapsedMs < 2000,
                "含 OPERATOR 关键字的长字面量改写出 O(n²) 行为：" + elapsedMs + " ms");
        assertTrue(rewritten.contains(big), "字面量必须原样保留");
    }

    /** 左操作数的匹配语义必须与改造前等价（含"最左合法起始位置"这一细节）。 */
    @Test
    void infixOperatorLeftOperandSemanticsAreUnchanged() {
        // 限定名 / 普通列名
        assertTrue(rewrite("select 1 where c.relname OPERATOR(pg_catalog.~) '^(t)$'")
                .contains("regexp_matches(c.relname, '^(t)$')"));
        assertTrue(rewrite("select 1 where name OPERATOR(pg_catalog.~) '^a'")
                .contains("regexp_matches(name, '^a')"));
        // 数字开头：左操作数取"最左的合法起始位置"，即 abc 而不是 1abc
        assertTrue(rewrite("select 1 where 1abc OPERATOR(pg_catalog.~) '^a'")
                .contains("regexp_matches(abc, '^a')"),
                "数字开头时应当从第一个合法标识符字符开始取");
        // 左操作数不是标识符：不匹配、原样保留（宁可漏改）
        String noIdent = "select 1 where 'x' OPERATOR(pg_catalog.~) '^a'";
        assertEquals(noIdent, rewrite(noIdent));
        // 右操作数不是单引号字面量：不匹配、原样保留
        String noLiteral = "select 1 where c.relname OPERATOR(pg_catalog.~) col2";
        assertEquals(noLiteral, rewrite(noLiteral));
    }

    /** ::pg_catalog.<类型> 的 schema 限定必须去掉（DuckDB 的 cast 只接受不带限定的类型名）。 */
    @Test
    void pgCatalogCastTypeQualifierIsStripped() {
        assertEquals("select ''::text", rewrite("select ''::pg_catalog.text"));
        assertEquals("select ''::text", rewrite("select ''::pg_catalog . text"));
        assertEquals("select 1::varchar(10)", rewrite("select 1::pg_catalog.varchar(10)"));
    }

    /** 反例：不是 cast 的 pg_catalog. 引用不能被这条规则误改。 */
    @Test
    void nonCastPgCatalogReferencesAreUntouched() {
        String rewritten = rewrite("select * from pg_catalog.pg_type");
        assertTrue(!rewritten.contains("::"),
                "非 cast 的 pg_catalog 引用被误改: " + rewritten);
    }
}
