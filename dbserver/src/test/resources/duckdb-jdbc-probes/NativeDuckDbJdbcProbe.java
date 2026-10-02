import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

/**
 * DuckDB 原生 JDBC（duckdb_jdbc 1.5.6.0）对全部数据类型的支持情况探针，作为 SlackerDB 的基线对照。
 *
 * <p>每个类型跑以下互相独立的阶段（每个阶段都先清空表、写入、再读回比对，阶段之间不互相影响）：</p>
 * <ol>
 *   <li>{@code ddl}       —— {@code CREATE TABLE (c <type>)} 能否建表</li>
 *   <li>{@code insLit}    —— 字面量 INSERT</li>
 *   <li>{@code updLit}    —— 字面量 UPDATE</li>
 *   <li>{@code paramObj}  —— {@code PreparedStatement#setObject(Java 对象)} 绑定参数插入（可多候选）</li>
 *   <li>{@code paramText} —— {@code setString} + {@code CAST(? AS <type>)} 文本绑定插入</li>
 *   <li>{@code appender}  —— {@code DuckDBAppender} 追加一行（每种类型给多个候选 API，取第一个成功者）</li>
 *   <li>{@code appNull}   —— {@code DuckDBAppender#appendNull} 追加 NULL 行</li>
 * </ol>
 *
 * <p>读回校验：{@code SELECT c}，记录 ResultSetMetaData 报出的类型名 / java.sql.Types / class name，
 * 并把 {@code getObject}、{@code getString} 的结果与 DuckDB 自己的
 * {@code CAST(<literal> AS <type>)::VARCHAR} 文本比对。</p>
 *
 * <p>运行（在仓库根目录）：{@code java -cp <duckdb_jdbc-1.5.6.0.jar> jdbc-type-probe/NativeDuckDbJdbcProbe.java}</p>
 */
public class NativeDuckDbJdbcProbe {

    // ================================================================ 用例模型

    /** 参数绑定策略。 */
    interface Bind {
        void bind(PreparedStatement ps) throws SQLException;
    }

    /** Appender 写入策略。 */
    interface Append {
        void write(DuckDBAppender ap) throws SQLException;
    }

    record Writer(String name, Append append) {}

    record Binder(String name, Bind bind) {}

    record TypeCase(String label, String ddl, String prelude, String literal,
                    List<Binder> paramBinders, List<Writer> writers, String[] reads) {}

    static final List<TypeCase> CASES = new ArrayList<>();
    static final List<String> READ_NOTES = new ArrayList<>();

    /** 无 prelude 的用例。paramSpec 传 Java 值（自动 setObject）或 {@link BindSpec}。 */
    static void add(String label, String ddl, String literal, Object paramSpec, Object appenderValue,
                    String... reads) {
        addCase(label, ddl, null, literal, paramSpec, appenderValue, reads);
    }

    /** 带 prelude（如 CREATE TYPE）的用例。 */
    static void addT(String label, String ddl, String prelude, String literal, Object paramSpec,
                     Object appenderValue, String... reads) {
        addCase(label, ddl, prelude, literal, paramSpec, appenderValue, reads);
    }

    static void addCase(String label, String ddl, String prelude, String literal, Object paramSpec,
                        Object appenderValue, String... reads) {
        List<Binder> binders = new ArrayList<>();
        if (paramSpec instanceof BindSpec spec) {
            binders.addAll(spec.binders());
        } else if (paramSpec != null) {
            Object value = paramSpec;
            binders.add(new Binder("setObject(" + value.getClass().getSimpleName() + ")",
                    ps -> ps.setObject(1, value)));
        }
        List<Writer> writers = new ArrayList<>();
        if (appenderValue instanceof AppendSpec spec) {
            writers.addAll(spec.writers());
        } else if (appenderValue != null) {
            for (Append a : appendCandidates(appenderValue)) {
                writers.add(new Writer(describeAppend(appenderValue), a));
            }
        }
        CASES.add(new TypeCase(label, ddl, prelude, literal, binders, writers, reads));
    }

    record AppendSpec(List<Writer> writers) {}

    record BindSpec(List<Binder> binders) {}

    static Writer w(String name, Append a) {
        return new Writer(name, a);
    }

    static Binder b(String name, Bind bind) {
        return new Binder(name, bind);
    }

    /** 单一策略的 {@link BindSpec}。 */
    static BindSpec bindOnly(String name, Bind bind) {
        return new BindSpec(List.of(b(name, bind)));
    }

    /** LIST(STRUCT) 用例用的 Java 侧数据：两个 Map 组成的 List。 */
    static List<Map<String, Object>> structList() {
        return List.of(new LinkedHashMap<>(Map.of("a", 1)), new LinkedHashMap<>(Map.of("a", 2)));
    }

    // ================================================================ 测试值

    static final byte[] BLOB_BYTES = new byte[] {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
    static final String HUGEINT_MAX = "170141183460469231731687303715884105727";
    static final String UBIGINT_MAX = "18446744073709551615";
    static final String UHUGEINT_MAX = "340282366920938463463374607431768211455";
    static final String BIGNUM_VAL = "123456789012345678901234567890123456789";
    static final UUID UUID_VAL = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    static final String JSON_VAL = "{\"a\": 1}";

    /** 给一个 Java 值，按"最可能被 Appender 接受"的顺序列出候选 append 调用。 */
    static List<Append> appendCandidates(Object v) {
        List<Append> list = new ArrayList<>();
        if (v instanceof Boolean b) {
            list.add(ap -> ap.append(b.booleanValue()));
            list.add(ap -> ap.append(b));
        } else if (v instanceof Byte b) {
            list.add(ap -> ap.append(b.byteValue()));
            list.add(ap -> ap.append(b));
        } else if (v instanceof Short s) {
            list.add(ap -> ap.append(s.shortValue()));
            list.add(ap -> ap.append(s));
        } else if (v instanceof Integer i) {
            list.add(ap -> ap.append(i.intValue()));
            list.add(ap -> ap.append(i));
        } else if (v instanceof Long l) {
            list.add(ap -> ap.append(l.longValue()));
            list.add(ap -> ap.append(l));
        } else if (v instanceof Float f) {
            list.add(ap -> ap.append(f.floatValue()));
            list.add(ap -> ap.append(f));
        } else if (v instanceof Double d) {
            list.add(ap -> ap.append(d.doubleValue()));
            list.add(ap -> ap.append(d));
        } else if (v instanceof BigInteger bi) {
            list.add(ap -> ap.append(bi));
        } else if (v instanceof BigDecimal bd) {
            list.add(ap -> ap.append(bd));
        } else if (v instanceof String s) {
            list.add(ap -> ap.append(s));
        } else if (v instanceof byte[] bytes) {
            list.add(ap -> ap.appendByteArray(bytes));
            list.add(ap -> ap.append(bytes));
        } else if (v instanceof UUID u) {
            list.add(ap -> ap.append(u));
        } else if (v instanceof LocalDate d) {
            list.add(ap -> ap.append(d));
        } else if (v instanceof LocalTime t) {
            list.add(ap -> ap.append(t));
        } else if (v instanceof OffsetTime o) {
            list.add(ap -> ap.append(o));
        } else if (v instanceof LocalDateTime d) {
            list.add(ap -> ap.append(d));
        } else if (v instanceof OffsetDateTime d) {
            list.add(ap -> ap.append(d));
        } else if (v instanceof int[] ia) {
            list.add(ap -> ap.append(ia));
        } else if (v instanceof Map<?, ?> m) {
            list.add(ap -> ap.append(m));
        }
        return list;
    }

    static String describeAppend(Object v) {
        if (v instanceof Boolean) return "append(boolean)";
        if (v instanceof Byte) return "append(byte)";
        if (v instanceof Short) return "append(short)";
        if (v instanceof Integer) return "append(int)";
        if (v instanceof Long) return "append(long)";
        if (v instanceof Float) return "append(float)";
        if (v instanceof Double) return "append(double)";
        if (v instanceof BigInteger) return "append(BigInteger)";
        if (v instanceof BigDecimal) return "append(BigDecimal)";
        if (v instanceof byte[]) return "appendByteArray(byte[])";
        if (v instanceof UUID) return "append(UUID)";
        if (v instanceof LocalDate) return "append(LocalDate)";
        if (v instanceof LocalTime) return "append(LocalTime)";
        if (v instanceof OffsetTime) return "append(OffsetTime)";
        if (v instanceof LocalDateTime) return "append(LocalDateTime)";
        if (v instanceof OffsetDateTime) return "append(OffsetDateTime)";
        if (v instanceof Map) return "append(Map)";
        return "append(String)";
    }

    // ================================================================ 用例清单

    static {
        // ---------- 布尔 / 有符号数值 ----------
        add("BOOLEAN", "BOOLEAN", "TRUE", true, true);
        add("TINYINT", "TINYINT", "-42", (byte) -42, (byte) -42, "c::INTEGER");
        add("SMALLINT", "SMALLINT", "-12345", (short) -12345, (short) -12345);
        add("INTEGER", "INTEGER", "123456789", 123456789, 123456789);
        add("BIGINT", "BIGINT", "1234567890123", 1234567890123L, 1234567890123L);
        add("HUGEINT", "HUGEINT", HUGEINT_MAX, new BigInteger(HUGEINT_MAX), new BigInteger(HUGEINT_MAX));
        add("FLOAT", "FLOAT", "1.5", 1.5f, 1.5f);
        add("DOUBLE", "DOUBLE", "3.141592653589793", Math.PI, Math.PI);
        add("DECIMAL(18,4)", "DECIMAL(18,4)", "12345678.9012",
                new BigDecimal("12345678.9012"), new BigDecimal("12345678.9012"));
        add("DECIMAL(38,10)", "DECIMAL(38,10)", "1234567890123456789012345678.9012345678",
                new BigDecimal("1234567890123456789012345678.9012345678"),
                new BigDecimal("1234567890123456789012345678.9012345678"));
        add("BIGNUM", "BIGNUM", "'" + BIGNUM_VAL + "'::BIGNUM",
                new BigDecimal(BIGNUM_VAL), new BigDecimal(BIGNUM_VAL));

        // ---------- 无符号数值：Appender 按 C 类型严格匹配，这里给出位模式相同的候选 ----------
        add("UTINYINT", "UTINYINT", "250", (short) 250,
                new AppendSpec(List.of(
                        w("append(byte) 位模式", ap -> ap.append((byte) 250)),
                        w("append(short)", ap -> ap.append((short) 250)))),
                "c::INTEGER");
        add("USMALLINT", "USMALLINT", "60000", 60000,
                new AppendSpec(List.of(
                        w("append(short) 位模式", ap -> ap.append((short) 60000)),
                        w("append(int)", ap -> ap.append(60000)))),
                "c::INTEGER");
        add("UINTEGER", "UINTEGER", "4000000000", 4000000000L,
                new AppendSpec(List.of(
                        w("append(int) 位模式", ap -> ap.append((int) 4000000000L)),
                        w("append(long)", ap -> ap.append(4000000000L)))));
        add("UBIGINT", "UBIGINT", UBIGINT_MAX, new BigInteger(UBIGINT_MAX),
                new AppendSpec(List.of(
                        w("append(long) 位模式", ap -> ap.append(-1L)),
                        w("append(BigInteger)", ap -> ap.append(new BigInteger(UBIGINT_MAX))))));
        add("UHUGEINT", "UHUGEINT", UHUGEINT_MAX, new BigDecimal(UHUGEINT_MAX),
                new AppendSpec(List.of(
                        w("appendHugeInt(-1,-1)", ap -> ap.appendHugeInt(-1L, -1L)),
                        w("append(BigInteger)", ap -> ap.append(new BigInteger(UHUGEINT_MAX))))));

        // ---------- 字符 / 二进制 ----------
        add("VARCHAR", "VARCHAR", "'hello 世界'", "hello 世界", "hello 世界");
        add("BLOB", "BLOB", "from_hex('DEADBEEF')", BLOB_BYTES, BLOB_BYTES);
        add("BIT", "BIT", "'1010'::BIT", "1010", "1010");
        add("UUID", "UUID", "'" + UUID_VAL + "'::UUID", UUID_VAL, UUID_VAL);
        add("JSON", "JSON", "'" + JSON_VAL + "'::JSON", JSON_VAL, JSON_VAL);

        // ---------- 日期时间 ----------
        add("DATE", "DATE", "DATE '2024-02-29'", LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 29));
        add("TIME", "TIME", "TIME '12:34:56.789'",
                LocalTime.of(12, 34, 56, 789_000_000), LocalTime.of(12, 34, 56, 789_000_000));
        add("TIME_NS", "TIME_NS", "TIME_NS '12:34:56.123456789'",
                new BindSpec(List.of(
                        b("setObject(LocalTime)", ps -> ps.setObject(1, LocalTime.of(12, 34, 56, 123_456_789))),
                        b("setString", ps -> ps.setString(1, "12:34:56.123456789")))),
                new AppendSpec(List.of(
                        w("append(LocalTime)", ap -> ap.append(LocalTime.of(12, 34, 56, 123_456_789))))));
        add("TIMETZ", "TIMETZ", "TIMETZ '12:34:56.789+08'",
                new BindSpec(List.of(
                        b("setObject(OffsetTime)", ps -> ps.setObject(1,
                                OffsetTime.of(12, 34, 56, 789_000_000, ZoneOffset.ofHours(8)))),
                        b("setString", ps -> ps.setString(1, "12:34:56.789+08")))),
                OffsetTime.of(12, 34, 56, 789_000_000, ZoneOffset.ofHours(8)));
        add("TIMESTAMP", "TIMESTAMP", "TIMESTAMP '2024-02-29 12:34:56.789'",
                LocalDateTime.of(2024, 2, 29, 12, 34, 56, 789_000_000),
                LocalDateTime.of(2024, 2, 29, 12, 34, 56, 789_000_000));
        add("TIMESTAMP_S", "TIMESTAMP_S", "TIMESTAMP_S '2024-02-29 12:34:56'",
                LocalDateTime.of(2024, 2, 29, 12, 34, 56), LocalDateTime.of(2024, 2, 29, 12, 34, 56));
        add("TIMESTAMP_MS", "TIMESTAMP_MS", "TIMESTAMP_MS '2024-02-29 12:34:56.789'",
                LocalDateTime.of(2024, 2, 29, 12, 34, 56, 789_000_000),
                LocalDateTime.of(2024, 2, 29, 12, 34, 56, 789_000_000));
        add("TIMESTAMP_NS", "TIMESTAMP_NS", "TIMESTAMP_NS '2024-02-29 12:34:56.789123456'",
                LocalDateTime.of(2024, 2, 29, 12, 34, 56, 789_123_456),
                LocalDateTime.of(2024, 2, 29, 12, 34, 56, 789_123_456));
        add("TIMESTAMPTZ", "TIMESTAMPTZ", "TIMESTAMPTZ '2024-02-29 12:34:56.789+08'",
                OffsetDateTime.of(2024, 2, 29, 12, 34, 56, 789_000_000, ZoneOffset.ofHours(8)),
                OffsetDateTime.of(2024, 2, 29, 12, 34, 56, 789_000_000, ZoneOffset.ofHours(8)));
        add("INTERVAL", "INTERVAL", "INTERVAL '1 year 2 months 3 days 04:05:06.007'",
                "1 year 2 months 3 days 04:05:06.007",
                new AppendSpec(List.of(
                        w("append(String)", ap -> ap.append("1 year 2 months 3 days 04:05:06.007")))));

        // ---------- ENUM ----------
        addT("ENUM", "mood", "CREATE OR REPLACE TYPE mood AS ENUM ('sad','ok','happy')",
                "'happy'::mood", "happy", "happy");

        // ---------- 复合类型（重点） ----------
        add("LIST(INTEGER)", "INTEGER[]", "[1, NULL, 3]", Arrays.asList(1, null, 3),
                new AppendSpec(List.of(
                        w("append(Collection)", ap -> ap.append(Arrays.asList(1, null, 3))),
                        w("append(int[])", ap -> ap.append(new int[] {1, 2, 3})))),
                "c[1]", "c[2] IS NULL", "c[3]", "len(c)");

        add("LIST(VARCHAR)", "VARCHAR[]", "['a', 'b']", Arrays.asList("a", "b"),
                new AppendSpec(List.of(w("append(Collection)", ap -> ap.append(Arrays.asList("a", "b"))))),
                "c[1]", "len(c)");

        add("ARRAY(INTEGER,3)", "INTEGER[3]", "CAST([1, 2, 3] AS INTEGER[3])", Arrays.asList(1, 2, 3),
                new AppendSpec(List.of(w("append(int[])", ap -> ap.append(new int[] {1, 2, 3})))),
                "c[3]");

        add("STRUCT", "STRUCT(a INTEGER, b VARCHAR)", "{'a': 1, 'b': 'x'}",
                new LinkedHashMap<>(Map.of("a", 1, "b", "x")),
                new AppendSpec(List.of(w("beginStruct/endStruct",
                        ap -> ap.beginStruct().append(1).append("x").endStruct()))),
                "c.a", "c.b", "struct_extract(c,'a')", "c::VARCHAR");

        add("STRUCT(nested)", "STRUCT(a INTEGER, b VARCHAR[], s STRUCT(x INTEGER))",
                "{'a': 1, 'b': ['x','y'], 's': {'x': 7}}",
                new LinkedHashMap<>(Map.of("a", 1, "b", Arrays.asList("x", "y"),
                        "s", new LinkedHashMap<>(Map.of("x", 7)))),
                new AppendSpec(List.of(w("beginStruct/endStruct(nested)",
                        ap -> ap.beginStruct().append(1)
                                .append(Arrays.asList("x", "y"))
                                .beginStruct().append(7).endStruct()
                                .endStruct()))),
                "c.b[2]", "c.s.x", "c::VARCHAR");

        add("LIST(STRUCT)", "STRUCT(a INTEGER)[]", "[{'a': 1}, {'a': 2}]", structList(),
                new AppendSpec(List.of(
                        w("append(Collection of Map)", ap -> ap.append(structList())),
                        w("append(Iterable,2)", ap -> ap.append(structList(), 2)))),
                "c[1].a", "len(c)");

        add("MAP", "MAP(VARCHAR, INTEGER)", "MAP {'a': 1, 'b': 2}",
                new LinkedHashMap<>(Map.of("a", 1, "b", 2)),
                new AppendSpec(List.of(w("append(Map)",
                        ap -> ap.append(new LinkedHashMap<>(Map.of("a", 1, "b", 2)))))),
                "c['a']", "map_keys(c)", "cardinality(c)");

        add("MAP(STRUCT)", "MAP(VARCHAR, STRUCT(a INTEGER))", "MAP {'k': {'a': 1}}",
                new LinkedHashMap<>(Map.of("k", new LinkedHashMap<>(Map.of("a", 1)))),
                new AppendSpec(List.of(w("append(Map of Map)",
                        ap -> ap.append(new LinkedHashMap<>(Map.of("k", new LinkedHashMap<>(Map.of("a", 1)))))))),
                "c['k'].a");

        add("MAP(INTEGER,VARCHAR)", "MAP(INTEGER, VARCHAR)", "MAP {1: 'x'}",
                new LinkedHashMap<>(Map.of(1, "x")),
                new AppendSpec(List.of(w("append(Map)",
                        ap -> ap.append(new LinkedHashMap<>(Map.of(1, "x")))))),
                "c[1]");

        addT("UNION(num)", "udemo", "CREATE OR REPLACE TYPE udemo AS UNION(num INTEGER, str VARCHAR)",
                "union_value(num := 42)::udemo", 42,
                new AppendSpec(List.of(w("beginUnion('num')",
                        ap -> ap.beginUnion("num").append(42).endUnion()))),
                "union_tag(c)", "union_extract(c,'num')", "c::VARCHAR");

        addT("UNION(str)", "udemo", "CREATE OR REPLACE TYPE udemo AS UNION(num INTEGER, str VARCHAR)",
                "union_value(str := 'hi')::udemo", "hi",
                new AppendSpec(List.of(w("beginUnion('str')",
                        ap -> ap.beginUnion("str").append("hi").endUnion()))),
                "union_tag(c)", "union_extract(c,'str')");

        addT("UNION(nested)", "udemo2",
                "CREATE OR REPLACE TYPE udemo2 AS UNION(n INTEGER, s STRUCT(a INTEGER), l INTEGER[])",
                "union_value(s := {'a': 9})::udemo2",
                new LinkedHashMap<>(Map.of("a", 9)),
                new AppendSpec(List.of(
                        w("beginUnion('s')+beginStruct",
                                ap -> ap.beginUnion("s").beginStruct().append(9).endStruct().endUnion()),
                        w("beginUnion('l')+append(int[])",
                                ap -> ap.beginUnion("l").append(new int[] {1, 2}).endUnion()))),
                "union_tag(c)", "union_extract(c,'s')");

        add("VARIANT(int)", "VARIANT", "CAST(42 AS VARIANT)", 42,
                new AppendSpec(List.of(
                        w("append(int)", ap -> ap.append(42)),
                        w("append(String)", ap -> ap.append("42")),
                        w("beginStruct", ap -> ap.beginStruct().append(42).endStruct()),
                        w("beginUnion('value')", ap -> ap.beginUnion("value").append(42).endUnion()))),
                "variant_typeof(c)", "c::VARCHAR");

        add("VARIANT(struct)", "VARIANT", "CAST({'a': 1} AS VARIANT)",
                new LinkedHashMap<>(Map.of("a", 1)),
                new AppendSpec(List.of(
                        w("beginStruct", ap -> ap.beginStruct().append(1).endStruct()),
                        w("append(Map)", ap -> ap.append(new LinkedHashMap<>(Map.of("a", 1)))))),
                "variant_typeof(c)", "c::VARCHAR");

        add("VARIANT(list)", "VARIANT", "CAST([1, 2] AS VARIANT)", Arrays.asList(1, 2),
                new AppendSpec(List.of(
                        w("append(Collection)", ap -> ap.append(Arrays.asList(1, 2))),
                        w("append(String)", ap -> ap.append("[1, 2]")))),
                "variant_typeof(c)", "c::VARCHAR");

        add("VARIANT(string)", "VARIANT", "CAST('txt' AS VARIANT)", "txt",
                new AppendSpec(List.of(w("append(String)", ap -> ap.append("txt")))),
                "variant_typeof(c)", "c::VARCHAR");

        // ---------- 其它 ----------
        add("LIST(LIST)", "INTEGER[][]", "[[1, 2], [3]]", List.of(List.of(1, 2), List.of(3)),
                new AppendSpec(List.of(w("append(Collection of Collection)",
                        ap -> ap.append(List.of(List.of(1, 2), List.of(3)))))),
                "c[1][2]");
    }

    // ================================================================ 执行框架

    interface Body {
        String run() throws Exception;
    }

    record Stage(boolean ok, String detail, String reason) {
        static Stage ok(String detail) {
            return new Stage(true, detail, "");
        }

        static Stage fail(String reason) {
            return new Stage(false, "", reason);
        }
    }

    static final List<String> FAIL_DETAILS = new ArrayList<>();

    static Stage run(String label, String stage, Body body) {
        try {
            return Stage.ok(body.run());
        } catch (Exception | Error e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            FAIL_DETAILS.add("- `" + label + "` / " + stage + ": `" + e.getClass().getSimpleName()
                    + "`: " + msg.replace('\n', ' ').replace('\r', ' '));
            return Stage.fail(msg.replace('\n', ' '));
        }
    }

    static final Stage SKIPPED = Stage.fail("skipped (ddl failed)");

    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        // Windows 控制台默认不是 UTF-8，显式包一层，避免中文/特殊字符乱码
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        System.setOut(out);

        out.println("duckdb_jdbc 1.5.6.0 (duckdb " + duckdbVersion() + ")");

        StringBuilder md = new StringBuilder();
        md.append("# DuckDB 原生 JDBC (duckdb_jdbc 1.5.6.0) 数据类型支持矩阵\n\n");
        md.append("每个用例的每个阶段互相独立：写入前先 `DELETE FROM`，写完 `SELECT c` 读回，"
                + "并与 DuckDB 自身的文本表示（`CAST(literal AS type)::VARCHAR`）比对。\n\n");
        md.append("| # | 类型 | ddl | insLit | updLit | paramObj | paramText | appender | appNull | 读回 (元数据 + 值) |\n");
        md.append("|---|------|-----|--------|--------|----------|-----------|----------|---------|--------------------|\n");

        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            int i = 0;
            for (TypeCase tc : CASES) {
                i++;
                String table = "probe_" + i;
                if (tc.prelude() != null) {
                    try {
                        exec(conn, tc.prelude());
                    } catch (SQLException e) {
                        out.println("!! prelude failed for " + tc.label() + ": " + e.getMessage());
                    }
                }

                Stage ddl = run(tc.label(), "ddl", () -> {
                    exec(conn, "CREATE OR REPLACE TABLE " + table + "(c " + tc.ddl() + ")");
                    return "";
                });
                Stage insLit = ddl.ok() ? run(tc.label(), "insLit", () -> {
                    resetToLiteral(conn, table, tc);
                    return "";
                }) : SKIPPED;
                Stage updLit = ddl.ok() ? run(tc.label(), "updLit", () -> {
                    resetToLiteral(conn, table, tc);
                    exec(conn, "UPDATE " + table + " SET c = (" + tc.literal() + ")");
                    return "";
                }) : SKIPPED;
                Stage paramObj = ddl.ok() ? run(tc.label(), "paramObj", () -> {
                    if (tc.paramBinders().isEmpty()) {
                        throw new SQLException("no candidate");
                    }
                    exec(conn, "DELETE FROM " + table);
                    Exception last = null;
                    for (Binder binder : tc.paramBinders()) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO " + table + " VALUES (?)")) {
                            binder.bind().bind(ps);
                            ps.executeUpdate();
                            return binder.name();
                        } catch (Exception e) {
                            last = e;
                        }
                    }
                    throw last;
                }) : SKIPPED;
                Stage paramText = ddl.ok() ? run(tc.label(), "paramText", () -> {
                    exec(conn, "DELETE FROM " + table);
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO " + table + " VALUES (CAST(? AS " + tc.ddl() + "))")) {
                        ps.setString(1, expectedText(conn, tc));
                        ps.executeUpdate();
                    }
                    return "";
                }) : SKIPPED;
                Stage app = ddl.ok() ? run(tc.label(), "appender",
                        () -> appendOne(conn, table, tc.writers())) : SKIPPED;
                Stage appNull = ddl.ok() ? run(tc.label(), "appNull", () -> {
                    appendNull(conn, table);
                    try (Statement st = conn.createStatement();
                         ResultSet rs = st.executeQuery("SELECT c IS NULL FROM " + table)) {
                        rs.next();
                        if (!rs.getBoolean(1)) {
                            throw new SQLException("appendNull 之后该行不是 NULL");
                        }
                    }
                    return "";
                }) : SKIPPED;

                String readBack = insLit.ok() ? readBack(conn, table, tc) : "read skipped";
                String row = "| " + i + " | " + tc.label() + " | " + cell(ddl) + " | " + cell(insLit)
                        + " | " + cell(updLit) + " | " + cell(paramObj) + " | " + cell(paramText)
                        + " | " + cell(app) + " | " + cell(appNull) + " | " + esc(readBack) + " |";
                md.append(row).append('\n');
                out.println(row);

                if (tc.reads() != null && insLit.ok()) {
                    resetToLiteral(conn, table, tc);
                    for (String read : tc.reads()) {
                        READ_NOTES.add("- `" + tc.label() + "` : `" + read + "` → " + probeRead(conn, table, read));
                    }
                }
            }
        }

        md.append("\n## 失败明细\n\n");
        if (FAIL_DETAILS.isEmpty()) {
            md.append("（无）\n");
        } else {
            FAIL_DETAILS.forEach(d -> md.append(d).append('\n'));
        }
        md.append("\n## 复合类型子项/字段读取\n\n");
        READ_NOTES.forEach(d -> md.append(d).append('\n'));

        Path outFile = Path.of("jdbc-type-probe", "native-duckdb-jdbc-matrix.md");
        Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, md.toString(), StandardCharsets.UTF_8);
        out.println("\nreport: " + outFile.toAbsolutePath());
    }

    static String duckdbVersion() {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select version()")) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            return "?";
        }
    }

    static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /** 把表恢复到"只有一行 = literal"的状态，保证后续阶段互不影响。 */
    static void resetToLiteral(Connection conn, String table, TypeCase tc) throws SQLException {
        exec(conn, "DELETE FROM " + table);
        exec(conn, "INSERT INTO " + table + " VALUES (" + tc.literal() + ")");
    }

    /** DuckDB 自身对该 literal + 类型的文本表示，作为读回比对的期望值。 */
    static String expectedText(Connection conn, TypeCase tc) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT CAST((" + tc.literal() + ") AS " + tc.ddl() + ")::VARCHAR")) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** 依次尝试候选 Appender 策略，返回成功者名字。 */
    static String appendOne(Connection conn, String table, List<Writer> writers) throws Exception {
        exec(conn, "DELETE FROM " + table);
        if (writers.isEmpty()) {
            throw new SQLException("no candidate");
        }
        StringBuilder tried = new StringBuilder();
        for (Writer writer : writers) {
            try (DuckDBAppender ap = conn.unwrap(DuckDBConnection.class).createAppender(null, null, table)) {
                ap.beginRow();
                writer.append().write(ap);
                ap.endRow();
                ap.flush();
                return writer.name();
            } catch (Exception e) {
                if (tried.length() > 0) {
                    tried.append(" | ");
                }
                tried.append(writer.name()).append(" → ")
                        .append(e.getMessage() == null ? e.toString() : e.getMessage().replace('\n', ' '));
            }
        }
        throw new SQLException(tried.toString());
    }

    static void appendNull(Connection conn, String table) throws SQLException {
        exec(conn, "DELETE FROM " + table);
        try (DuckDBAppender ap = conn.unwrap(DuckDBConnection.class).createAppender(null, null, table)) {
            ap.beginRow();
            ap.appendNull();
            ap.endRow();
            ap.flush();
        }
    }

    static String readBack(Connection conn, String table, TypeCase tc) {
        try {
            resetToLiteral(conn, table, tc);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT c FROM " + table)) {
                ResultSetMetaData md = rs.getMetaData();
                rs.next();
                String text = rs.getString(1);
                String expected = expectedText(conn, tc);
                boolean match = expected == null ? text == null : expected.equals(text);
                return "meta=" + md.getColumnTypeName(1) + " Types=" + md.getColumnType(1)
                        + " cls=" + md.getColumnClassName(1).replace("org.duckdb.DuckDBResultSet$", "")
                        + " text=" + text + (match ? " ==duckdb" : " !=duckdb(" + expected + ")");
            }
        } catch (Exception e) {
            return "READ FAIL: " + e.getMessage();
        }
    }

    static String probeRead(Connection conn, String table, String read) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + read + " FROM " + table)) {
            ResultSetMetaData md = rs.getMetaData();
            rs.next();
            return "meta=" + md.getColumnTypeName(1) + " obj=" + rs.getObject(1) + " text=" + rs.getString(1);
        } catch (Exception e) {
            return "FAIL: " + e.getMessage();
        }
    }

    static String cell(Stage s) {
        if (s == null) {
            return "-";
        }
        if (s.ok()) {
            return s.detail().isEmpty() ? "OK" : "OK(" + s.detail() + ")";
        }
        return "FAIL";
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }
}
