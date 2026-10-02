package org.slackerdb.plsql.ast;

import java.util.List;

/**
 * PL/SQL 抽象语法树。
 *
 * <p>结构（控制流、声明、游标、异常处理）由本文件描述；表达式是独立的
 * {@link Expr} 树（P5），由 {@code org.slackerdb.plsql.expr.ExprCompiler}
 * 编译成「SQL + 绑定参数」，由 {@code ExprEvaluator} 求值。</p>
 */
public final class PlSqlAst {

    private PlSqlAst() {
    }

    /** 一个块：可选声明段 + 体 + 可选异常处理段。 */
    public record Block(List<Decl> declares, List<Stmt> statements, List<Handler> handlers, int line) {
    }

    // ---------- 声明 ----------
    public sealed interface Decl permits VariableDecl, CursorDecl {
    }

    public record VariableDecl(String name, String typeText, boolean notNull, Expr defaultValue, int line)
            implements Decl {
    }

    public record CursorDecl(String name, List<Param> parameters, String query, int line) implements Decl {
    }

    public record Param(String name, String typeText) {
    }

    // ---------- 异常处理 ----------
    public record Handler(List<String> exceptions, List<Stmt> body, int line) {
    }

    // ---------- 语句 ----------
    public sealed interface Stmt permits Sql, SelectInto, ExecuteImmediate, Assign, If, Loop, While, For, Exit,
            Continue, Fetch, OpenCursor, CloseCursor, Raise, Return, Commit, Rollback, Null, Nested {
    }

    /** 内嵌 SQL（已由遮罩层抽取原文）。 */
    public record Sql(String sql, int line) implements Stmt {
    }

    /** {@code SELECT ... INTO v1, v2 ...}：拆成 INTO 前后的 SQL 片段 + 目标列表。 */
    public record SelectInto(String sqlBefore, List<String> targets, String sqlAfter, int line) implements Stmt {
    }

    /**
     * 动态 SQL：{@code EXECUTE IMMEDIATE <sql> [INTO 目标...] [USING 值...]}。
     *
     * <p>{@code sql} 是求值为 SQL 文本的表达式（字面量、变量或 {@code ||} 拼接）；
     * {@code targets} 为空表示不接收结果集；{@code using} 按出现顺序绑定动态 SQL 的占位符。</p>
     */
    public record ExecuteImmediate(Expr sql, List<String> targets, List<Expr> using, int line) implements Stmt {
    }

    /** 赋值：{@code x := expr;} / {@code LET x = expr;}（legacyLet 标记兼容写法）。 */
    public record Assign(String target, Expr value, boolean legacyLet, int line) implements Stmt {
    }

    public record Branch(Expr condition, List<Stmt> body) {
    }

    public record If(List<Branch> branches, List<Stmt> elseBody, int line) implements Stmt {
    }

    public record Loop(String label, List<Stmt> body, int line) implements Stmt {
    }

    public record While(String label, Expr condition, List<Stmt> body, int line) implements Stmt {
    }

    /**
     * {@code FOR i IN a..b LOOP} 或历史写法 {@code FOR i IN [v1, v2] LOOP}。
     *
     * @param values 非 null 表示"列表形式"（此时 from/to 为 null）
     */
    public record For(String label, String variable, boolean reverse, Expr from, Expr to, List<Expr> values,
                      List<Stmt> body, int line) implements Stmt {
    }

    /** {@code EXIT [label] [WHEN cond];}（{@code BREAK} 记为 isBreak）。 */
    public record Exit(String label, Expr condition, boolean isBreak, int line) implements Stmt {
    }

    public record Continue(String label, Expr condition, int line) implements Stmt {
    }

    public record Fetch(String cursor, List<String> targets, int line) implements Stmt {
    }

    public record OpenCursor(String cursor, String argumentsText, int line) implements Stmt {
    }

    public record CloseCursor(String cursor, int line) implements Stmt {
    }

    /** {@code RAISE [name];} */
    public record Raise(String exceptionName, int line) implements Stmt {
    }

    public record Return(int line) implements Stmt {
    }

    public record Commit(int line) implements Stmt {
    }

    public record Rollback(int line) implements Stmt {
    }

    /** {@code NULL;} / {@code PASS;} */
    public record Null(int line) implements Stmt {
    }

    public record Nested(Block block, int line) implements Stmt {
    }
}
