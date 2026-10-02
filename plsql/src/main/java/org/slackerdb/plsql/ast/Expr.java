package org.slackerdb.plsql.ast;

import java.util.List;

/**
 * 表达式 AST（P5）。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>变量与游标属性是<b>绑定值来源</b>（{@link Name}/{@link CursorAttr}），
 *       编译器把它们渲染成 {@code ?}，由宿主绑定参数执行——
 *       绝不把值拼进 SQL 文本；</li>
 *   <li>函数调用原样下推给后端（DuckDB），因此 PL/SQL 表达式能力与 SQL 一致；</li>
 *   <li>{@code AND}/{@code OR}/{@code NOT} 由求值器按三值逻辑<b>短路</b>实现。</li>
 * </ul>
 */
public sealed interface Expr {

    /** 位置（1 起）。 */
    record Pos(int line, int column) {
    }

    Pos pos();

    /** 字面量：{@code value} 为 BigDecimal / String / Boolean / null。 */
    record Literal(Pos pos, String text, Object value) implements Expr {
    }

    /** 变量引用（{@code x} 与 {@code :x} 等价）。 */
    record Name(Pos pos, String name) implements Expr {
    }

    /** 游标属性：{@code c%FOUND} 等（运行时值，编译为绑定参数）。 */
    record CursorAttr(Pos pos, String cursor, Attr attr) implements Expr {
        public enum Attr {
            FOUND, NOTFOUND, ROWCOUNT, ISOPEN
        }
    }

    record Unary(Pos pos, Op op, Expr operand) implements Expr {
    }

    record Binary(Pos pos, Op op, Expr left, Expr right) implements Expr {
    }

    record Function(Pos pos, String name, List<Expr> arguments) implements Expr {
    }

    record IsNull(Pos pos, Expr operand, boolean negated) implements Expr {
    }

    record Like(Pos pos, Expr value, Expr pattern, boolean negated) implements Expr {
    }

    record InList(Pos pos, Expr value, List<Expr> items, boolean negated) implements Expr {
    }

    record Between(Pos pos, Expr value, Expr low, Expr high, boolean negated) implements Expr {
    }

    record CaseExpr(Pos pos, List<When> whens, Expr elseExpr) implements Expr {
    }

    record When(Expr condition, Expr result) {
    }

    enum Op {
        ADD, SUB, MUL, DIV, MOD, POW, CONCAT,
        EQ, NE, LT, LE, GT, GE,
        AND, OR, NOT, NEG
    }
}
