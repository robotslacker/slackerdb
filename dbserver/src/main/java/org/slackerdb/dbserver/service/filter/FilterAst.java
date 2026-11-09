package org.slackerdb.dbserver.service.filter;

import java.util.List;

/**
 * filter 表达式的抽象语法树。
 *
 * <p>{@link Expr} 是"取一个值"的节点（字面量/列/函数）；{@link Predicate} 是
 * "取 TRUE/FALSE/UNKNOWN"的节点。排序键与分页信息放在 {@link FilterProgram} 里。</p>
 */
public final class FilterAst {

    private FilterAst() {
    }

    public enum Op {
        EQ,
        NE,
        LT,
        LE,
        GT,
        GE
    }

    /** 取一个标量值。 */
    public sealed interface Expr permits Literal, ColumnRef, FunctionCall {
    }

    /** 字面量；{@code value} 为 {@code null} 时表示 SQL NULL。 */
    public record Literal(Object value, String text) implements Expr {
    }

    /** 列引用；{@code quoted} 表示原文使用双引号（精确匹配、不折叠大小写）。 */
    public record ColumnRef(String name, boolean quoted, int position) implements Expr {
    }

    /** 白名单函数调用（lower/upper/trim/length），恰好一个参数。 */
    public record FunctionCall(String name, Expr argument, int position) implements Expr {
    }

    /** 布尔谓词；求值结果为 {@code Boolean}，{@code null} 表示 UNKNOWN。 */
    public sealed interface Predicate permits Comparison, And, Or, Not, IsNull, InList, Like {
    }

    public record Comparison(Expr left, Op op, Expr right, int position) implements Predicate {
    }

    public record And(Predicate left, Predicate right) implements Predicate {
    }

    public record Or(Predicate left, Predicate right) implements Predicate {
    }

    public record Not(Predicate operand) implements Predicate {
    }

    public record IsNull(Expr operand, boolean negated) implements Predicate {
    }

    public record InList(Expr value, List<Expr> items, boolean negated) implements Predicate {
    }

    /**
     * LIKE/NOT LIKE。
     *
     * @param pattern  LIKE 模式（字符串字面量）
     * @param escape   转义字符；{@code null} 表示未指定 ESCAPE
     */
    public record Like(Expr value, String pattern, String escape, boolean negated, int position)
            implements Predicate {
    }

    /**
     * 排序键。
     *
     * @param ascending  是否升序（默认 true）
     * @param nullsFirst 是否 NULLS FIRST（默认 false，即 NULLS LAST，与 DuckDB 默认一致）
     */
    public record SortKey(Expr expr, boolean ascending, boolean nullsFirst) {
    }

    /**
     * 一条完整的 filter。
     *
     * @param predicate WHERE 条件；只有排序/分页时为 {@code null}
     * @param orderBy   排序键（可能为空）
     * @param limit     行数上限；{@code null} 表示不限（或 LIMIT ALL）
     * @param offset    跳过的行数，默认 0
     */
    public record FilterProgram(Predicate predicate, List<SortKey> orderBy, Integer limit, int offset) {
    }
}
