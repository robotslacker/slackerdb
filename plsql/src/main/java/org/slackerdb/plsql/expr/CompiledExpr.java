package org.slackerdb.plsql.expr;

import org.slackerdb.plsql.ast.Expr;

import java.util.Collections;
import java.util.List;

/**
 * 表达式编译产物：一段带 {@code ?} 占位符的 SQL + 与占位符一一对应的绑定来源。
 *
 * <p>{@link #binds} 里的节点只会是 {@link Expr.Name} 与 {@link Expr.CursorAttr}
 * （变量值与游标运行态值），顺序与 {@code ?} 一致。</p>
 */
public final class CompiledExpr {

    private final String sql;
    private final List<Expr> binds;

    CompiledExpr(String sql, List<Expr> binds) {
        this.sql = sql;
        this.binds = Collections.unmodifiableList(binds);
    }

    /** {@code SELECT} 之后可直接执行的表达式文本。 */
    public String sql() {
        return sql;
    }

    /** 绑定来源（顺序与 {@code ?} 一致）。 */
    public List<Expr> binds() {
        return binds;
    }

    public boolean hasBinds() {
        return !binds.isEmpty();
    }

    @Override
    public String toString() {
        return sql + " binds=" + binds;
    }
}
