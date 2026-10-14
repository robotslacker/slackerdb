package org.slackerdb.dbserver.sql;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PG 游标族语句的解析：{@code DECLARE ... CURSOR ... FOR} / {@code FETCH} / {@code MOVE} / {@code CLOSE}。
 *
 * <p><b>为什么必须单独解析</b>：{@code postgres_fdw} 取远端数据<b>总是</b>走游标
 * （{@code DECLARE cN CURSOR FOR <select>} → 反复 {@code FETCH 100 FROM cN} → {@code CLOSE cN}），
 * 而 DuckDB 没有这些语句；更麻烦的是 {@code PlSqlDetector} 会把任何以 {@code DECLARE} 开头的
 * 语句当成 PL/SQL 块，于是 {@code DECLARE c CURSOR FOR ...} 在语法分析阶段就报
 * {@code 42601 mismatched input 'CURSOR'}，根本到不了执行层。</p>
 *
 * <p><b>与 PL/SQL 的区分</b>：PL/SQL 的游标声明是 {@code CURSOR <名字> IS <查询>}
 * （{@code CURSOR} 在名字<b>之前</b>、用 {@code IS}），而 PG 的 SQL 游标是
 * {@code DECLARE <名字> ... CURSOR ... FOR <查询>}（名字在 {@code CURSOR} 之前、用 {@code FOR}）。
 * 本类的正则只匹配后者，因此真正的 PL/SQL 块不会被抢走。</p>
 *
 * <p>v1 只支持"向前"的方向：{@code NEXT} / {@code FORWARD n|ALL} / 裸计数 / {@code ALL}；
 * {@code BACKWARD} / {@code ABSOLUTE} / {@code RELATIVE} 明确报错（DuckDB 的 JDBC 结果集不能后退），
 * 而不是假装滚动。</p>
 */
public final class CursorSql {

    /** 标识符：普通标识符或带双引号的标识符。 */
    private static final String IDENT = "(\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*)";

    private static final Pattern DECLARE = Pattern.compile(
            "^\\s*DECLARE\\s+" + IDENT + "\\s+"
                    + "(?:BINARY\\s+|INSENSITIVE\\s+|ASENSITIVE\\s+|SCROLL\\s+|NO\\s+SCROLL\\s+)*"
                    + "CURSOR\\s+"
                    + "(?:(WITH|WITHOUT)\\s+HOLD\\s+)?"
                    + "FOR\\s+([\\s\\S]+?)\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** 方向子句：NEXT/PRIOR/FIRST/LAST/ABSOLUTE n/RELATIVE n/FORWARD .../BACKWARD .../裸计数/ALL。 */
    private static final String DIRECTION =
            "(?:(NEXT|PRIOR|FIRST|LAST|"
                    + "ABSOLUTE\\s+\\d+|RELATIVE\\s+\\d+|"
                    + "FORWARD(?:\\s+ALL|\\s+\\d+)?|BACKWARD(?:\\s+ALL|\\s+\\d+)?|"
                    + "ALL|\\d+)\\s+)?";

    private static final Pattern FETCH = Pattern.compile(
            "^\\s*FETCH\\s+" + DIRECTION + "(?:FROM|IN)\\s+" + IDENT + "\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MOVE = Pattern.compile(
            "^\\s*MOVE\\s+" + DIRECTION + "(?:FROM|IN)\\s+" + IDENT + "\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CLOSE = Pattern.compile(
            "^\\s*CLOSE\\s+(ALL|\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*)\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private CursorSql() {
    }

    /** {@code DECLARE ... CURSOR ... FOR <查询>}。 */
    public record Declare(String name, boolean withHold, String query) {
    }

    /** {@code FETCH} / {@code MOVE} 的目标与步长。{@code count < 0} 表示 {@code ALL}（取到结束）。 */
    public record Step(String name, int count, boolean backward) {
    }

    /** 三类语句的识别结果。 */
    public record CursorStatement(Kind kind, Declare declare, Step step, String closeName, boolean closeAll) {
        public enum Kind { DECLARE, FETCH, MOVE, CLOSE }
    }

    /** 是否为受支持的游标族语句（用它替换 {@code PlSqlDetector} 之前的分支判断）。 */
    public static boolean isCursorStatement(String sql) {
        return parse(sql) != null;
    }

    /** 解析游标族语句；不属于本类的返回 {@code null}。 */
    public static CursorStatement parse(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }

        Matcher declare = DECLARE.matcher(sql);
        if (declare.matches()) {
            boolean withHold = declare.group(2) != null && "WITH".equalsIgnoreCase(declare.group(2));
            return new CursorStatement(CursorStatement.Kind.DECLARE,
                    new Declare(unquote(declare.group(1)), withHold, declare.group(3).trim()),
                    null, null, false);
        }

        Matcher fetch = FETCH.matcher(sql);
        if (fetch.matches()) {
            return new CursorStatement(CursorStatement.Kind.FETCH, null,
                    step(fetch.group(1), unquote(fetch.group(2))), null, false);
        }

        Matcher move = MOVE.matcher(sql);
        if (move.matches()) {
            return new CursorStatement(CursorStatement.Kind.MOVE, null,
                    step(move.group(1), unquote(move.group(2))), null, false);
        }

        Matcher close = CLOSE.matcher(sql);
        if (close.matches()) {
            String target = close.group(1);
            if ("ALL".equalsIgnoreCase(target)) {
                return new CursorStatement(CursorStatement.Kind.CLOSE, null, null, null, true);
            }
            return new CursorStatement(CursorStatement.Kind.CLOSE, null, null, unquote(target), false);
        }

        return null;
    }

    /**
     * 把方向子句解析成步长。
     *
     * @param direction 方向子句（可为 {@code null} = 默认 {@code NEXT}）
     * @param name      游标名
     */
    private static Step step(String direction, String name) {
        if (direction == null || direction.isBlank()) {
            return new Step(name, 1, false);                 // 默认 FETCH NEXT = 1 行
        }
        String normalized = direction.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("ALL") || normalized.equals("FORWARD ALL")) {
            return new Step(name, -1, false);
        }
        if (normalized.equals("FORWARD")) {
            return new Step(name, 1, false);
        }
        if (normalized.startsWith("BACKWARD")) {
            return new Step(name, normalized.endsWith("ALL") ? -1 : parseCount(normalized), true);
        }
        if (normalized.equals("NEXT")) {
            return new Step(name, 1, false);
        }
        if (normalized.equals("PRIOR") || normalized.equals("FIRST") || normalized.equals("LAST")
                || normalized.startsWith("ABSOLUTE") || normalized.startsWith("RELATIVE")) {
            // 需要可滚动结果集：明确报"不支持"，绝不假装滚动
            return new Step(name, Integer.MIN_VALUE, true);
        }
        if (normalized.startsWith("FORWARD ")) {
            return new Step(name, parseCount(normalized), false);
        }
        return new Step(name, parseCount(normalized), false);   // 裸计数：FETCH 100 FROM c
    }

    private static int parseCount(String text) {
        StringBuilder digits = new StringBuilder();
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                digits.insert(0, c);
            } else if (digits.length() > 0) {
                break;
            }
        }
        if (digits.length() == 0) {
            return 1;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String unquote(String identifier) {
        if (identifier.length() > 1 && identifier.charAt(0) == '"'
                && identifier.charAt(identifier.length() - 1) == '"') {
            return identifier.substring(1, identifier.length() - 1).replace("\"\"", "\"");
        }
        return identifier;
    }
}
