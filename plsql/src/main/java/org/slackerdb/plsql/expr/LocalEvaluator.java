package org.slackerdb.plsql.expr;

import org.slackerdb.plsql.PlSqlException;
import org.slackerdb.plsql.ast.Expr;
import org.slackerdb.plsql.types.PlSqlTypeException;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * 本地快速求值。
 *
 * <p>纯字面量/变量/算术/比较/三值逻辑/CASE 的表达式在 JVM 内直接算完，
 * <b>不产生数据库往返</b>——这是把"每轮循环两次往返、约 660µs"降到微秒级的关键。
 * 语义与数据库路径保持一致（由 L4 用例双向锁定）。</p>
 *
 * <p>遇到本地无法可靠复现的语义时抛 {@link NotLocalException} 回退到数据库：
 * 字符串 LIKE、函数调用、混合类型比较、时间类型比较等。</p>
 */
final class LocalEvaluator {

    /** 回退信号：调用方改用数据库求值。 */
    static final class NotLocalException extends RuntimeException {
        NotLocalException() {
            super(null, null, false, false);
        }
    }

    private static final NotLocalException NOT_LOCAL = new NotLocalException();

    private LocalEvaluator() {
    }

    /** 该表达式能否在本地安全求值（结构性判断，不含类型判断）。 */
    static boolean isLocal(Expr expr) {
        if (expr instanceof Expr.Literal || expr instanceof Expr.Name || expr instanceof Expr.CursorAttr) {
            return true;
        }
        if (expr instanceof Expr.Unary unary) {
            return isLocal(unary.operand());
        }
        if (expr instanceof Expr.Binary binary) {
            return isLocal(binary.left()) && isLocal(binary.right());
        }
        if (expr instanceof Expr.IsNull isNull) {
            return isLocal(isNull.operand());
        }
        if (expr instanceof Expr.InList inList) {
            if (!isLocal(inList.value())) {
                return false;
            }
            for (Expr item : inList.items()) {
                if (!isLocal(item)) {
                    return false;
                }
            }
            return true;
        }
        if (expr instanceof Expr.Between between) {
            return isLocal(between.value()) && isLocal(between.low()) && isLocal(between.high());
        }
        if (expr instanceof Expr.CaseExpr caseExpr) {
            for (Expr.When when : caseExpr.whens()) {
                if (!isLocal(when.condition()) || !isLocal(when.result())) {
                    return false;
                }
            }
            return caseExpr.elseExpr() == null || isLocal(caseExpr.elseExpr());
        }
        // Function / Like 交给数据库
        return false;
    }

    static Object eval(Expr expr, ExprEvaluator.ValueResolver resolver) {
        if (expr instanceof Expr.Literal literal) {
            return literal.value();
        }
        if (expr instanceof Expr.Name || expr instanceof Expr.CursorAttr) {
            return resolver.resolve(expr);
        }
        if (expr instanceof Expr.Unary unary) {
            Object operand = eval(unary.operand(), resolver);
            return switch (unary.op()) {
                case NOT -> not(operand);
                case NEG -> negate(operand);
                case ADD -> operand;
                default -> throw NOT_LOCAL;
            };
        }
        if (expr instanceof Expr.Binary binary) {
            return binary(binary, resolver);
        }
        if (expr instanceof Expr.IsNull isNull) {
            boolean result = eval(isNull.operand(), resolver) == null;
            return result != isNull.negated();
        }
        if (expr instanceof Expr.InList inList) {
            Object value = eval(inList.value(), resolver);
            if (value == null) {
                return null;
            }
            boolean found = false;
            boolean sawNull = false;
            for (Expr item : inList.items()) {
                Object candidate = eval(item, resolver);
                if (candidate == null) {
                    sawNull = true;
                    continue;
                }
                if (equalsValue(value, candidate)) {
                    found = true;
                    break;
                }
            }
            if (found) {
                return !inList.negated();
            }
            if (sawNull) {
                return null;
            }
            return inList.negated();
        }
        if (expr instanceof Expr.Between between) {
            Object value = eval(between.value(), resolver);
            Object low = eval(between.low(), resolver);
            Object high = eval(between.high(), resolver);
            if (value == null || low == null || high == null) {
                return null;
            }
            int lowSign = compareOrFail(value, low);
            int highSign = compareOrFail(value, high);
            boolean inside = lowSign >= 0 && highSign <= 0;
            return between.negated() != inside;
        }
        if (expr instanceof Expr.CaseExpr caseExpr) {
            for (Expr.When when : caseExpr.whens()) {
                if (truthy(eval(when.condition(), resolver))) {
                    return eval(when.result(), resolver);
                }
            }
            return caseExpr.elseExpr() == null ? null : eval(caseExpr.elseExpr(), resolver);
        }
        throw NOT_LOCAL;
    }

    // ---------- 运算 ----------
    private static Object binary(Expr.Binary binary, ExprEvaluator.ValueResolver resolver) {
        Expr.Op op = binary.op();
        if (op == Expr.Op.AND || op == Expr.Op.OR) {
            Object left = eval(binary.left(), resolver);
            Boolean leftValue = booleanOrNull(left);
            if (op == Expr.Op.AND && Boolean.FALSE.equals(leftValue)) {
                return Boolean.FALSE;
            }
            if (op == Expr.Op.OR && Boolean.TRUE.equals(leftValue)) {
                return Boolean.TRUE;
            }
            Object right = eval(binary.right(), resolver);
            Boolean rightValue = booleanOrNull(right);
            if (op == Expr.Op.AND) {
                if (Boolean.FALSE.equals(rightValue)) {
                    return Boolean.FALSE;
                }
                return leftValue == null || rightValue == null ? null : Boolean.TRUE;
            }
            if (Boolean.TRUE.equals(rightValue)) {
                return Boolean.TRUE;
            }
            return leftValue == null || rightValue == null ? null : Boolean.FALSE;
        }

        Object left = eval(binary.left(), resolver);
        Object right = eval(binary.right(), resolver);

        switch (op) {
            case EQ, NE -> {
                if (left == null || right == null) {
                    return null;
                }
                boolean equal = compareOrFail(left, right) == 0;
                return op == Expr.Op.EQ ? equal : !equal;
            }
            case LT, LE, GT, GE -> {
                if (left == null || right == null) {
                    return null;
                }
                int sign = compareOrFail(left, right);
                return switch (op) {
                    case LT -> sign < 0;
                    case LE -> sign <= 0;
                    case GT -> sign > 0;
                    default -> sign >= 0;
                };
            }
            case CONCAT -> {
                if (left == null || right == null) {
                    return null;
                }
                return text(left) + text(right);
            }
            case ADD, SUB, MUL, DIV, MOD -> {
                if (left == null || right == null) {
                    return null;
                }
                return arithmetic(op, left, right);
            }
            case POW -> {
                if (left == null || right == null) {
                    return null;
                }
                return Math.pow(number(left).doubleValue(), number(right).doubleValue());
            }
            default -> throw NOT_LOCAL;
        }
    }

    /** 三路比较；类型无法在本地可靠比较时抛回退（交给数据库）。 */
    private static int compareOrFail(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return number(left).compareTo(number(right));
        }
        if (left instanceof String a && right instanceof String b) {
            return a.compareTo(b);
        }
        if (left instanceof Boolean a && right instanceof Boolean b) {
            return a.equals(b) ? 0 : (a ? 1 : -1);
        }
        // 混合类型 / 时间类型：交给数据库，避免猜隐式转换与排序规则
        throw NOT_LOCAL;
    }

    private static boolean equalsValue(Object left, Object right) {
        return compareOrFail(left, right) == 0;
    }

    private static Object arithmetic(Expr.Op op, Object left, Object right) {
        BigDecimal a = number(left);
        BigDecimal b = number(right);
        return switch (op) {
            case ADD -> a.add(b);
            case SUB -> a.subtract(b);
            case MUL -> a.multiply(b);
            case DIV -> {
                if (b.signum() == 0) {
                    throw new PlSqlException("divisor is equal to zero", PlSqlException.ZERO_DIVIDE, null);
                }
                yield a.divide(b, MathContext.DECIMAL64);
            }
            default -> {
                if (b.signum() == 0) {
                    throw new PlSqlException("divisor is equal to zero", PlSqlException.ZERO_DIVIDE, null);
                }
                yield a.remainder(b, MathContext.DECIMAL64);
            }
        };
    }

    private static Object negate(Object value) {
        if (value == null) {
            return null;
        }
        return number(value).negate();
    }

    private static Object not(Object value) {
        Boolean bool = booleanOrNull(value);
        return bool == null ? null : !bool;
    }

    private static Boolean booleanOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new PlSqlTypeException(PlSqlTypeException.DATATYPE_MISMATCH,
                "Condition must be BOOLEAN, but was " + value.getClass().getSimpleName());
    }

    private static boolean truthy(Object value) {
        Boolean bool = booleanOrNull(value);
        return bool != null && bool;
    }

    private static BigDecimal number(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        throw NOT_LOCAL;
    }

    private static String text(Object value) {
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        throw NOT_LOCAL;
    }
}
