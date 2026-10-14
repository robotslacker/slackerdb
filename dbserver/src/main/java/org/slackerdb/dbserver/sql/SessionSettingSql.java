package org.slackerdb.dbserver.sql;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PG 特有的"会话设置/复位"语句识别：{@code SET}（含 {@code SESSION}/{@code LOCAL}）、
 * {@code RESET}、{@code DISCARD ALL}。
 *
 * <p><b>为什么需要它们</b>：这些语句 DuckDB 要么没有对应参数（{@code statement_timeout} 等 → {@code 3F000}），
 * 要么整条语法都不认（{@code RESET ALL} → {@code 0A000}、{@code DISCARD ALL} → {@code 42601}）。
 * 而连接池 / ORM / IDE 在建立连接时会例行发送它们，于是"能连上但一设参数就报错"。</p>
 *
 * <p><b>只接管道具式的、PG 独有且对结果无影响的参数</b>（{@link #ACCEPTED_BUT_IGNORED}）：
 * 记进会话表、回成功、并发一条 {@code NOTICE} 说明"本服务端接受但不会生效" ——
 * 明确告知而不是静默假装。DuckDB 自己认识的参数（{@code threads}、{@code TimeZone}、
 * {@code search_path} 等）一律不进这个名单，照常透传给 DuckDB。</p>
 */
public final class SessionSettingSql {

    /**
     * SlackDB 接受但<b>不生效</b>的 PG 参数名。
     *
     * <p>取舍标准：PG 客户端/池/ORM 会例行发送、且"被忽略"不会改变查询结果。
     * 不含 DuckDB 自己支持的参数（{@code threads}/{@code TimeZone}/{@code search_path}/{@code memory_limit}…）。</p>
     */
    private static final Set<String> ACCEPTED_BUT_IGNORED = Set.of(
            "statement_timeout",
            "lock_timeout",
            "idle_in_transaction_session_timeout",
            "idle_session_timeout",
            "default_transaction_isolation",
            "transaction_isolation",
            "server_version_num",
            "max_identifier_length",
            "standard_conforming_strings",
            "bytea_output",
            "timezone_abbreviations",
            "default_transaction_read_only",
            "transaction_read_only",
            "row_security",
            "check_function_bodies",
            "xmloption");

    private static final Pattern RESET_ALL = Pattern.compile(
            "^\\s*RESET\\s+ALL\\s*;?\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern RESET_ONE = Pattern.compile(
            "^\\s*RESET\\s+(\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_]*)\\s*;?\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern DISCARD_ALL = Pattern.compile(
            "^\\s*DISCARD\\s+ALL\\s*;?\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * {@code SET [SESSION|LOCAL] name {=|TO} value}。
     *
     * <p>值这一组刻意宽松（可能带引号、带单位、是 {@code on/off}、甚至是个表达式），
     * 这里只做"记录并忽略"，不解析语义。</p>
     */
    private static final Pattern SET_ASSIGNMENT = Pattern.compile(
            "^\\s*SET\\s+(?:(SESSION|LOCAL)\\s+)?(\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_]*)\\s*(?:=|\\bTO\\b)\\s*(.+?)\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * {@code SHOW <name>} 的目标参数名。
     *
     * <p>只回答"本服务端能给出确定答案"的参数（{@link #SHOW_DEFAULTS} 里列出的固定答案，
     * 或客户端在本会话里 {@code SET} 过并记进会话表的那些）。其余名字返回 {@code null}，
     * 继续走原有流程（由 DuckDB 回答或报错）——包括已经被 SQLReplacer 改写成真实查询的
     * {@code SHOW search_path} / {@code SHOW datestyle} / {@code SHOW timezone} 等。</p>
     */
    public static String showTarget(String sql) {
        if (sql == null) {
            return null;
        }
        Matcher matcher = SHOW_ONE.matcher(sql);
        if (!matcher.matches()) {
            return null;
        }
        String name = unquote(matcher.group(1));
        return SHOW_DEFAULTS.containsKey(name) ? name : null;
    }

    /**
     * 该参数的当前值：会话里 {@code SET} 过就用设置的值，否则用固定答案。
     *
     * @return {@code null} = 本服务端不回答这个参数
     */
    public static String showValue(org.slackerdb.dbserver.server.DBSession session, String name) {
        if (name == null) {
            return null;
        }
        String stored = session == null ? null : session.acceptedSettings().get(name);
        return stored != null ? stored : SHOW_DEFAULTS.get(name);
    }

    /** {@code SHOW <name>}。 */
    private static final Pattern SHOW_ONE = Pattern.compile(
            "^\\s*SHOW\\s+(\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_]*)\\s*;?\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * {@code SHOW} 的固定答案（PG 兼容值）。
     *
     * <p>刻意<b>不含</b>：{@code search_path} / {@code datestyle} / {@code timezone} /
     * {@code transaction_isolation} / {@code standard_conforming_strings} ——
     * 这些要么由 SQLReplacer 改写成真实查询、要么 DuckDB 自己就能回答，
     * 用固定答案会和真实会话状态不一致（那是撒谎）。</p>
     */
    private static final Map<String, String> SHOW_DEFAULTS = Map.ofEntries(
            Map.entry("server_version", org.slackerdb.dbserver.server.DBInstance.PG_COMPAT_VERSION),
            Map.entry("server_version_num", "150000"),
            Map.entry("max_identifier_length", "63"),
            Map.entry("client_encoding", "UTF8"),
            Map.entry("intervalstyle", "postgres"),
            Map.entry("extra_float_digits", "3"),
            Map.entry("default_transaction_isolation", "read committed"),
            Map.entry("timezone_abbreviations", "Default"),
            Map.entry("bytea_output", "hex"),
            Map.entry("row_security", "on"),
            Map.entry("xmloption", "content"),
            Map.entry("check_function_bodies", "on"),
            Map.entry("default_transaction_read_only", "off"),
            Map.entry("statement_timeout", "0"),
            Map.entry("lock_timeout", "0"),
            Map.entry("idle_in_transaction_session_timeout", "0"),
            Map.entry("idle_session_timeout", "0"));

    private SessionSettingSql() {
    }

    /** 一次 {@code SET} 赋值：参数名（已转小写、去引号）、原始值文本、是否 {@code SET LOCAL}。 */
    public record Assignment(String name, String value, boolean local) {
    }

    /** 是否 {@code RESET ALL}。 */
    public static boolean isResetAll(String sql) {
        return sql != null && RESET_ALL.matcher(sql).matches();
    }

    /** 是否 {@code DISCARD ALL}。 */
    public static boolean isDiscardAll(String sql) {
        return sql != null && DISCARD_ALL.matcher(sql).matches();
    }

    /**
     * 如果是 {@code RESET <name>} 且该名字属于"接受但忽略"的名单，返回名字；否则 {@code null}。
     *
     * <p>不在名单里的名字（例如 {@code RESET search_path}）返回 {@code null}，交给 DuckDB 处理。</p>
     */
    public static String resetTarget(String sql) {
        if (sql == null) {
            return null;
        }
        Matcher matcher = RESET_ONE.matcher(sql);
        if (!matcher.matches()) {
            return null;
        }
        String name = unquote(matcher.group(1));
        return ACCEPTED_BUT_IGNORED.contains(name) ? name : null;
    }

    /**
     * 如果是 {@code SET [SESSION|LOCAL] <名单内参数> = <值>}，返回赋值信息；否则 {@code null}。
     */
    public static Assignment setAssignment(String sql) {
        if (sql == null) {
            return null;
        }
        Matcher matcher = SET_ASSIGNMENT.matcher(sql);
        if (!matcher.matches()) {
            return null;
        }
        String name = unquote(matcher.group(2));
        if (!ACCEPTED_BUT_IGNORED.contains(name)) {
            return null;
        }
        boolean local = "LOCAL".equalsIgnoreCase(matcher.group(1));
        return new Assignment(name, matcher.group(3).trim(), local);
    }

    /** 该名字是否属于"接受但忽略"的名单（供测试与文档使用）。 */
    public static boolean isAcceptedButIgnored(String name) {
        return name != null && ACCEPTED_BUT_IGNORED.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * 一次接管的结果。
     *
     * @param commandTag     要回的命令标签（{@code SET} / {@code RESET} / {@code DISCARD ALL}）
     * @param errorSqlState  非空 = 要回错误（例如事务块里的 {@code DISCARD ALL}）
     * @param notice         非空 = 要发一条 NOTICE（"接受但不生效"的说明）
     */
    public record Takeover(String commandTag, String errorSqlState, String errorMessage, String notice) {
        public boolean isError() {
            return errorSqlState != null;
        }
    }

    /**
     * 把一条"会话设置/复位"语句的效果就地应用到会话上。
     *
     * <p><b>不</b>负责发送任何应答 —— 由调用方（简单查询/扩展协议两条路径）
     * 用 {@code SessionSettingResponder} 统一渲染。</p>
     *
     * @return {@code null} = 不属于本类处理的语句（调用方应继续走原有流程）
     */
    public static Takeover apply(org.slackerdb.dbserver.server.DBSession session, String sql) {
        if (isDiscardAll(sql)) {
            if (session.inTransaction()) {
                // PG 语义：DISCARD ALL 不能在事务块里执行
                return new Takeover(null, "25001",
                        "DISCARD ALL cannot run inside a transaction block", null);
            }
            session.resetSessionForDiscardAll();
            return new Takeover("DISCARD ALL", null, null, null);
        }
        if (isResetAll(sql)) {
            session.resetAcceptedSettings();
            return new Takeover("RESET", null, null, null);
        }
        String resetName = resetTarget(sql);
        if (resetName != null) {
            session.removeAcceptedSetting(resetName);
            return new Takeover("RESET", null, null, null);
        }
        Assignment assignment = setAssignment(sql);
        if (assignment != null) {
            session.acceptSetting(assignment.name(), assignment.value());
            String notice = assignment.local()
                    ? "SET LOCAL is not supported by SlackerDB (DuckDB has no transaction-scoped settings); "
                    + "the value of '" + assignment.name() + "' is accepted but has no effect."
                    : "'" + assignment.name() + "' is accepted but has no effect in SlackerDB "
                    + "(DuckDB has no such parameter).";
            return new Takeover("SET", null, null, notice);
        }
        return null;
    }

    private static String unquote(String identifier) {
        if (identifier.length() > 1 && identifier.charAt(0) == '"'
                && identifier.charAt(identifier.length() - 1) == '"') {
            identifier = identifier.substring(1, identifier.length() - 1).replace("\"\"", "\"");
        }
        return identifier.toLowerCase(Locale.ROOT);
    }
}
