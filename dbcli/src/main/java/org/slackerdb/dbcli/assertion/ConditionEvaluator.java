package org.slackerdb.dbcli.assertion;

/**
 * 控制流条件的布尔求值。
 *
 * <p>{@code _IF} / {@code _ELSEIF} / {@code _LOOP WHILE|UNTIL} 与 {@code _ASSERT} 使用**同一套**
 * 固定形态语法（取值器 + 比较符 + 字面量），区别只在输出：断言要一行状态文案，控制流只要 true/false。
 * 因此这里复用 {@link AssertEvaluator} 的解析/比较实现，不复制 300 行比较逻辑。</p>
 *
 * <p>额外支持 {@code TRUE} / {@code FALSE} 两个常量条件（{@code _ASSERT} 不支持，保持原行为），
 * 方便写 {@code _IF {% TRUE %}} 这类显式开关。</p>
 */
public final class ConditionEvaluator {

    /** 求值结果：{@code error != null} 表示语法/取值错误，{@code value} 此时不可信。 */
    public record Condition(boolean value, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private ConditionEvaluator() {
    }

    /**
     * 求值一个条件。调用方可以把带/不带 {@code {% %}} 的文本直接传进来（幂等去壳）。
     *
     * @param expression 条件正文（可含 {@code {% %}} 外壳）
     * @param model      与 {@code _ASSERT} 相同的结果模型
     */
    public static Condition evaluate(String expression, ResultModel model) {
        String text = stripBraces(expression);
        if (text.isEmpty()) {
            return new Condition(false, "empty condition");
        }
        if ("TRUE".equalsIgnoreCase(text)) {
            return new Condition(true, null);
        }
        if ("FALSE".equalsIgnoreCase(text)) {
            return new Condition(false, null);
        }
        try {
            return new Condition(AssertEvaluator.evalExpression(text, model), null);
        } catch (RuntimeException evaluationError) {
            String message = evaluationError.getMessage();
            return new Condition(false, message == null || message.isBlank()
                    ? evaluationError.getClass().getSimpleName() : message);
        }
    }

    /** 去掉最外层 {@code {% %}} 并去掉首尾空白；没有外壳时只 strip。 */
    private static String stripBraces(String expression) {
        String text = expression == null ? "" : expression.strip();
        if (text.startsWith("{%")) {
            text = text.substring(2);
            if (text.endsWith("%}")) {
                text = text.substring(0, text.length() - 2);
            }
            return text.strip();
        }
        return text;
    }
}
