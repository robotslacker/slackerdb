package org.slackerdb.plsql.expr;

import org.slackerdb.plsql.ast.Expr;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表达式编译器：{@link Expr} → 「SQL + 绑定参数」。
 *
 * <p>要点：</p>
 * <ul>
 *   <li><b>绝不把变量值拼进 SQL</b>：变量与游标属性一律编译成 {@code ?}，
 *       值在求值时由宿主绑定（防注入、防类型/locale 问题）；</li>
 *   <li>字面量按 SQL 字面量输出（字符串做 {@code ''} 转义）；</li>
 *   <li>编译结果按 AST 节点缓存（同一个节点在循环里只编译一次）；</li>
 *   <li>{@code &&}/{@code ==}/{@code !=}/{@code !} 等兼容写法在 AST 层已归一化，
 *       这里只处理标准运算符。</li>
 * </ul>
 *
 * <p>实例<b>非线程安全</b>：与解释器/块一一对应（每个块一个实例）。</p>
 */
public final class ExprCompiler {

    private final Map<Expr, CompiledExpr> cache = new IdentityHashMap<>();

    public CompiledExpr compile(Expr expr) {
        CompiledExpr cached = cache.get(expr);
        if (cached != null) {
            return cached;
        }
        List<Expr> binds = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        append(expr, sql, binds);
        CompiledExpr compiled = new CompiledExpr(sql.toString(), binds);
        cache.put(expr, compiled);
        return compiled;
    }

    private void append(Expr expr, StringBuilder sql, List<Expr> binds) {
        if (expr instanceof Expr.Literal literal) {
            appendLiteral(literal, sql);
            return;
        }
        if (expr instanceof Expr.Name || expr instanceof Expr.CursorAttr) {
            // 变量 / 游标属性 → 绑定参数
            sql.append('?');
            binds.add(expr);
            return;
        }
        if (expr instanceof Expr.Unary unary) {
            switch (unary.op()) {
                case NOT -> {
                    sql.append("(NOT ");
                    append(unary.operand(), sql, binds);
                    sql.append(')');
                }
                case NEG -> {
                    sql.append("(-");
                    append(unary.operand(), sql, binds);
                    sql.append(')');
                }
                default -> append(unary.operand(), sql, binds);
            }
            return;
        }
        if (expr instanceof Expr.Binary binary) {
            sql.append('(');
            append(binary.left(), sql, binds);
            sql.append(' ').append(operatorSql(binary.op())).append(' ');
            append(binary.right(), sql, binds);
            sql.append(')');
            return;
        }
        if (expr instanceof Expr.Function function) {
            sql.append(function.name()).append('(');
            for (int i = 0; i < function.arguments().size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                append(function.arguments().get(i), sql, binds);
            }
            sql.append(')');
            return;
        }
        if (expr instanceof Expr.IsNull isNull) {
            sql.append('(');
            append(isNull.operand(), sql, binds);
            sql.append(isNull.negated() ? " IS NOT NULL)" : " IS NULL)");
            return;
        }
        if (expr instanceof Expr.Like like) {
            sql.append('(');
            append(like.value(), sql, binds);
            sql.append(like.negated() ? " NOT LIKE " : " LIKE ");
            append(like.pattern(), sql, binds);
            sql.append(')');
            return;
        }
        if (expr instanceof Expr.InList inList) {
            sql.append('(');
            append(inList.value(), sql, binds);
            sql.append(inList.negated() ? " NOT IN (" : " IN (");
            for (int i = 0; i < inList.items().size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                append(inList.items().get(i), sql, binds);
            }
            sql.append("))");
            return;
        }
        if (expr instanceof Expr.Between between) {
            sql.append('(');
            append(between.value(), sql, binds);
            sql.append(between.negated() ? " NOT BETWEEN " : " BETWEEN ");
            append(between.low(), sql, binds);
            sql.append(" AND ");
            append(between.high(), sql, binds);
            sql.append(')');
            return;
        }
        if (expr instanceof Expr.CaseExpr caseExpr) {
            sql.append("(CASE");
            for (Expr.When when : caseExpr.whens()) {
                sql.append(" WHEN ");
                append(when.condition(), sql, binds);
                sql.append(" THEN ");
                append(when.result(), sql, binds);
            }
            if (caseExpr.elseExpr() != null) {
                sql.append(" ELSE ");
                append(caseExpr.elseExpr(), sql, binds);
            }
            sql.append(" END)");
            return;
        }
        throw new IllegalStateException("未识别的表达式节点：" + expr.getClass().getName());
    }

    private void appendLiteral(Expr.Literal literal, StringBuilder sql) {
        Object value = literal.value();
        if (value == null) {
            sql.append("NULL");
            return;
        }
        if (value instanceof Boolean bool) {
            sql.append(bool ? "true" : "false");
            return;
        }
        if (value instanceof java.math.BigDecimal) {
            // 直接使用原文，避免 BigDecimal.toString 产生科学计数法
            sql.append(literal.text());
            return;
        }
        // 字符串字面量：单引号转义（这是字面量，不是变量值，安全）
        sql.append('\'').append(String.valueOf(value).replace("'", "''")).append('\'');
    }

    private String operatorSql(Expr.Op op) {
        return switch (op) {
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            case DIV -> "/";
            case MOD -> "%";
            case POW -> "**";
            case CONCAT -> "||";
            case EQ -> "=";
            case NE -> "<>";
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
            case AND -> "AND";
            case OR -> "OR";
            case NOT -> "NOT";
            case NEG -> "-";
        };
    }
}
