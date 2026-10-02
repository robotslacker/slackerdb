package org.slackerdb.plsql.expr;

import org.slackerdb.plsql.PlSqlException;
import org.slackerdb.plsql.ast.Expr;
import org.slackerdb.plsql.spi.PlSqlHost;
import org.slackerdb.plsql.types.PlSqlTypeException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 表达式求值器。
 *
 * <p>职责划分：编译交给 {@link ExprCompiler}，值绑定交给 {@link ValueResolver}，
 * 实际计算交给宿主（{@code SELECT <expr>}）。</p>
 *
 * <p>特殊语义（在本地实现，不依赖后端优化）：</p>
 * <ul>
 *   <li>{@code AND}/{@code OR} <b>短路</b>且按三值逻辑：{@code false AND (1/0=1)} 不报错；</li>
 *   <li>条件为 {@code NULL} 时按 false 处理（{@link #evaluateCondition}）；</li>
 *   <li>纯字面量直接求值，不产生数据库往返。</li>
 * </ul>
 */
public final class ExprEvaluator {

    /** 变量与游标属性取值来源（由解释器实现）。 */
    public interface ValueResolver {
        Object resolve(Expr node);
    }

    private final PlSqlHost host;
    private final ExprCompiler compiler;

    public ExprEvaluator(PlSqlHost host, ExprCompiler compiler) {
        this.host = host;
        this.compiler = compiler;
    }

    public ExprEvaluator(PlSqlHost host) {
        this(host, new ExprCompiler());
    }

    public ExprCompiler compiler() {
        return compiler;
    }

    /** 求值；SQL 层错误（除零、未知函数等）以 {@link SQLException} 抛出。 */
    public Object evaluate(Expr expr, ValueResolver resolver) throws SQLException {
        if (expr instanceof Expr.Literal literal) {
            return literal.value();
        }
        if (expr instanceof Expr.Unary unary && unary.op() == Expr.Op.NOT) {
            return not(evaluate(unary.operand(), resolver));
        }
        if (expr instanceof Expr.Binary binary) {
            if (binary.op() == Expr.Op.AND) {
                return and(evaluate(binary.left(), resolver), () -> evaluate(binary.right(), resolver));
            }
            if (binary.op() == Expr.Op.OR) {
                return or(evaluate(binary.left(), resolver), () -> evaluate(binary.right(), resolver));
            }
            if (binary.op() == Expr.Op.DIV || binary.op() == Expr.Op.MOD) {
                checkZeroDivisor(binary.right(), resolver);
            }
        }
        // 本地快速路径：纯变量/字面量/算术/比较/三值逻辑/CASE 不产生数据库往返
        if (LocalEvaluator.isLocal(expr)) {
            try {
                return LocalEvaluator.eval(expr, resolver);
            } catch (LocalEvaluator.NotLocalException ignored) {
                // 类型/语义无法在本地复现：回退到数据库
            }
        }
        CompiledExpr compiled = compiler.compile(expr);
        if (compiled.binds().size() == 1 && compiled.sql().equals("?")) {
            // 单一变量：直接取值，省一次往返
            return resolver.resolve(compiled.binds().get(0));
        }
        List<Object> bindValues = new ArrayList<>(compiled.binds().size());
        for (Expr bind : compiled.binds()) {
            bindValues.add(resolver.resolve(bind));
        }
        return host.evaluate(compiled.sql(), bindValues);
    }

    /** 条件求值：NULL → false。 */
    public boolean evaluateCondition(Expr expr, ValueResolver resolver) throws SQLException {
        Object value = evaluate(expr, resolver);
        Boolean bool = toBoolean(value);
        return bool != null && bool;
    }

    /** 值 → 布尔：仅接受 BOOLEAN 与 NULL（其它类型报 42804）。 */
    public static Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new PlSqlTypeException(PlSqlTypeException.DATATYPE_MISMATCH,
                "Condition must be BOOLEAN, but was " + value.getClass().getSimpleName());
    }

    // ---------- 三值逻辑 ----------
    private interface Thunk {
        Object get() throws SQLException;
    }

    /**
     * 除零检查：后端 DuckDB 的 {@code /} 会返回 {@code Infinity} 而非报错，
     * 与 PL/SQL 语义不符，因此对**可直接取值**的除数（字面量/变量/游标属性）做本地检查，
     * 命中即抛 {@code ZERO_DIVIDE(22012)}，从而能被 {@code WHEN ZERO_DIVIDE} 捕获。
     */
    private void checkZeroDivisor(Expr divisor, ValueResolver resolver) throws SQLException {
        Object value;
        if (divisor instanceof Expr.Literal) {
            value = evaluate(divisor, resolver);
        } else if (divisor instanceof Expr.Name || divisor instanceof Expr.CursorAttr) {
            value = resolver.resolve(divisor);
        } else {
            return; // 复杂表达式交给数据库
        }
        if (value instanceof Number number && number.doubleValue() == 0.0d) {
            throw new PlSqlException("divisor is equal to zero", PlSqlException.ZERO_DIVIDE, null);
        }
    }

    private static Object and(Object left, Thunk right) throws SQLException {
        Boolean leftValue = toBoolean(left);
        if (Boolean.FALSE.equals(leftValue)) {
            return Boolean.FALSE; // 短路：右侧不求值
        }
        Object rightValue = right.get();
        Boolean rightBool = toBoolean(rightValue);
        if (Boolean.FALSE.equals(rightBool)) {
            return Boolean.FALSE;
        }
        if (leftValue == null || rightBool == null) {
            return null;
        }
        return Boolean.TRUE;
    }

    private static Object or(Object left, Thunk right) throws SQLException {
        Boolean leftValue = toBoolean(left);
        if (Boolean.TRUE.equals(leftValue)) {
            return Boolean.TRUE; // 短路
        }
        Boolean rightBool = toBoolean(right.get());
        if (Boolean.TRUE.equals(rightBool)) {
            return Boolean.TRUE;
        }
        if (leftValue == null || rightBool == null) {
            return null;
        }
        return Boolean.FALSE;
    }

    private static Object not(Object value) {
        Boolean bool = toBoolean(value);
        return bool == null ? null : !bool;
    }
}
