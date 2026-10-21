package org.slackerdb.dbcli.assertion;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code _ASSERT} 的固定形态求值器。
 *
 * <h2>语法</h2>
 * <pre>
 *   _ASSERT &lt;左值&gt; &lt;比较符&gt; &lt;字面量&gt; [, &lt;断言名&gt;]      ← 裸形态
 *   _ASSERT {% &lt;左值&gt; &lt;比较符&gt; &lt;字面量&gt; %} [, &lt;断言名&gt;]  ← 花括号外观
 * </pre>
 * 两种外观**都支持**；花括号由调用方去掉（IR 里的 expression 已含/不含花括号都行，本类会再剥一次）。
 *
 * <h2>左值</h2>
 * 可以是**取值器**（对 {@link ResultModel} 取值）或**字面量**：
 * <ul>
 *   <li>取值器：{@code RESULT.ERRORCODE}、{@code RESULT.ROWCOUNT}、{@code RESULT.STATUS}、
 *       {@code RESULT.ROWS.COUNT}、{@code RESULT.HEADERS[i]}、{@code RESULT.COLUMNS[i]}、
 *       {@code RESULT.ROWS[r][c]}、{@code VAR.NAME}、{@code ENV.NAME}，
 *       以及 {@code RUN.EXITCODE}、{@code JOB.NAME.STATUS}（引擎侧后续注入到 variables 里）</li>
 *   <li>字面量：数字 / {@code '字符串'} / {@code NULL} / {@code TRUE} / {@code FALSE}
 *       —— 用于常量断言，也正好让既有脚本里的 {@code _Assert {% 3==3 %}} 不用改写</li>
 * </ul>
 *
 * <h2>比较符</h2>
 * {@code =} {@code <>} {@code >} {@code >=} {@code <} {@code <=} {@code CONTAINS} {@code NOTCONTAINS}
 * {@code MATCHES}（**整串**正则匹配）{@code EXISTS}（右值必须为空，判断左值非 null）。
 *
 * <h2>状态文案（已冻结，逐字节断言）</h2>
 * <pre>
 *   通过(无名字) → Assert successful.
 *   通过(有名字) → Assert [name] successful.
 *   失败(无名字) → Assert fail.
 *   失败(有名字) → Assert [name] fail.
 *   非法        → Assert fail. SyntaxError =>[&lt;message&gt;]
 * </pre>
 */
public final class AssertEvaluator {

    /** 断言结果：{@code passed} 供引擎计错误数/退出码用，{@code status} 是写到 stdout 的状态行。 */
    public record Verdict(boolean passed, String status) {
    }

    /** 左值：常量或从模型取值。 */
    private sealed interface Operand permits Literal, Selector {
    }

    private record Literal(Object value) implements Operand {
    }

    private record Selector(String text) implements Operand {
    }

    /**
     * 比较符（长符号排在短符号之前，否则 {@code >=} 会被认成 {@code >}）。
     *
     * <p>查找必须**跳过引号内的字面量**：{@code _assert {% 'a=b' = 'c' %}} 的比较符是中间那个
     * {@code =}，不是引号里的 {@code =}（否则左值会被截成 {@code 'a}，报成语法错而不是 false）。</p>
     */
    private static final List<String> OPERATORS = List.of(
            "NOTCONTAINS", "CONTAINS", "MATCHES", "EXISTS", "==", "!=", "<>", ">=", "<=", "=", ">", "<");

    private static final Pattern INDEX_PATTERN = Pattern.compile("\\[(\\d+)]");

    private AssertEvaluator() {
    }

    /**
     * 求值一条断言。
     *
     * @param expression IR 里取出的断言正文（可含 {@code {% %} }，内部空白原样）
     * @param model      结果模型，可为 null（等价于空模型）
     * @param assertName 断言名，可为 null
     */
    public static Verdict evaluate(String expression, ResultModel model, String assertName) {
        try {
            return decide(evalExpression(expression, model), assertName);
        } catch (RuntimeException evaluationError) {
            // 任何求值期异常都变成 "Assert fail. SyntaxError =>[...]"，绝不把异常抛出到引擎外面
            // （例如 MATCHES 里写了非法正则会抛 PatternSyntaxException）。
            return new Verdict(false, "Assert fail. SyntaxError =>[" + evaluationError.getMessage() + "]");
        }
    }

    /** 只求布尔值（供引擎在"断言失败要计错误"时判断用）。 */
    static boolean evalExpression(String expression, ResultModel model) {
        String text = stripBraces(expression);
        if (text.isEmpty()) {
            throw new AssertSyntaxException("empty assertion");
        }
        int[] comparator = findComparator(text);
        if (comparator == null) {
            throw new AssertSyntaxException("cannot find a comparator in [" + text + "]");
        }
        String leftText = text.substring(0, comparator[0]).trim();
        String opText = normalizeOperator(
                text.substring(comparator[0], comparator[0] + comparator[1]).toUpperCase(Locale.ROOT));
        String rightText = text.substring(comparator[0] + comparator[1]).trim();
        if (leftText.isEmpty()) {
            throw new AssertSyntaxException("missing left operand in [" + text + "]");
        }

        Object left = resolveOperand(parseOperand(leftText), model);
        if ("EXISTS".equals(opText)) {
            if (!rightText.isEmpty()) {
                throw new AssertSyntaxException("EXISTS takes no right operand");
            }
            return left != null;
        }
        if (rightText.isEmpty()) {
            throw new AssertSyntaxException("missing right operand in [" + text + "]");
        }
        Object right = parseLiteral(rightText);
        return compare(left, opText, right);
    }

    /**
     * 找出比较符在文本里的位置（返回 {@code {起始下标, 长度}}；没有返回 null）。
     *
     * <p>扫描时跳过单/双引号里的内容（含反斜杠转义），因此引号里的 {@code =}/{@code >}/{@code CONTAINS}
     * 不会被当成比较符。词形比较符（{@code CONTAINS} 等）要求两侧不是标识符字符，
     * 避免把 {@code SOME_CONTAINS_THING} 这种名字切开。</p>
     */
    private static int[] findComparator(String text) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (inSingleQuote || inDoubleQuote) {
                char quote = inSingleQuote ? '\'' : '"';
                if (character == '\\' && index + 1 < text.length()) {
                    index++;
                    continue;
                }
                if (character == quote) {
                    inSingleQuote = false;
                    inDoubleQuote = false;
                }
                continue;
            }
            if (character == '\'') {
                inSingleQuote = true;
                continue;
            }
            if (character == '"') {
                inDoubleQuote = true;
                continue;
            }
            for (String operator : OPERATORS) {
                if (!text.regionMatches(true, index, operator, 0, operator.length())) {
                    continue;
                }
                boolean touchesWord = (index > 0 && isWordCharacter(text.charAt(index - 1)))
                        || (index + operator.length() < text.length()
                                && isWordCharacter(text.charAt(index + operator.length())));
                if (isWordOperator(operator) && touchesWord) {
                    continue;
                }
                return new int[] {index, operator.length()};
            }
        }
        return null;
    }

    private static boolean isWordOperator(String operator) {
        return Character.isLetter(operator.charAt(0));
    }

    private static boolean isWordCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_' || character == '.';
    }

    /**
     * 接受比较符的两个常见别名：{@code ==} 等价于 {@code =}、{@code !=} 等价于 {@code <>}。
     *
     * <p>为什么值得兼容：既有夹具 {@code src/test/resources/scripts/sanity.sql} 写的是
     * {@code _Assert {% 3==3 %}} 与 {@code _Assert {% 1==2 %}}，接受这两个别名后这类脚本
     * **一行都不用改**就能跑出 {@code Assert successful.}/{@code Assert fail.}。</p>
     */
    private static String normalizeOperator(String op) {
        if ("==".equals(op)) {
            return "=";
        }
        if ("!=" .equals(op)) {
            return "<>";
        }
        return op;
    }

    /** 去掉 {@code {% %} } 外壳；IR 里的花括号外观会带着内侧空格，这里不再动内部空白。 */
    private static String stripBraces(String expression) {
        String text = expression == null ? "" : expression.strip();
        if (text.startsWith("{%")) {
            text = text.substring(2);
            if (text.endsWith("%}")) {
                text = text.substring(0, text.length() - 2);
            }
        }
        return text.strip();
    }

    /**
     * 解析**右侧**：按文档，右侧永远是**字面量**（左侧才是取值器）。
     *
     * <p>以前右侧也走 {@link #parseOperand} 的"不认识就当取值器"分支，于是
     * {@code _assert RESULT.HEADERS[0] = a}、{@code _assert RESULT.STATUS = 1 row selected.}
     * 这种"和文本比较"的断言会报 {@code unknown selector [a]}——文档与实现不一致。
     * 现在裸词按字符串字面量处理（要比较一个取值器，先把它塞进变量，用 {@code VAR.x} 做**左**侧）。</p>
     */
    private static Object parseLiteral(String text) {
        String trimmed = text.strip();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if ("NULL".equals(upper)) {
            return null;
        }
        if ("TRUE".equals(upper)) {
            return Boolean.TRUE;
        }
        if ("FALSE".equals(upper)) {
            return Boolean.FALSE;
        }
        if (isQuoted(trimmed)) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        if (isNumber(trimmed)) {
            return new BigDecimal(trimmed);
        }
        return trimmed;
    }

    private static Operand parseOperand(String text) {
        String trimmed = text.strip();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if ("NULL".equals(upper)) {
            return new Literal(null);
        }
        if ("TRUE".equals(upper)) {
            return new Literal(Boolean.TRUE);
        }
        if ("FALSE".equals(upper)) {
            return new Literal(Boolean.FALSE);
        }
        if (isQuoted(trimmed)) {
            return new Literal(trimmed.substring(1, trimmed.length() - 1));
        }
        if (isNumber(trimmed)) {
            return new Literal(new BigDecimal(trimmed));
        }
        // 其余按取值器处理；不认识就报语法错（而不是静默留原文）
        return new Selector(trimmed);
    }

    private static boolean isQuoted(String text) {
        if (text.length() < 2) {
            return false;
        }
        char first = text.charAt(0);
        return (first == '\'' || first == '"') && text.charAt(text.length() - 1) == first;
    }

    private static boolean isNumber(String text) {
        return text.matches("[+-]?\\d+(\\.\\d+)?");
    }

    private static Object resolveOperand(Operand operand, ResultModel model) {
        if (operand instanceof Literal literal) {
            return literal.value();
        }
        return resolveSelector(((Selector) operand).text(), model);
    }

    /** 取值器解析：未知取值器一律报语法错，避免"写错了却当成 null 通过/失败"这种静默错误。 */
    private static Object resolveSelector(String selector, ResultModel model) {
        ResultModel result = model == null ? ResultModel.empty() : model;
        String upper = selector.toUpperCase(Locale.ROOT);

        switch (upper) {
            case "RESULT.ERRORCODE":
                return result.errorCode();
            case "RESULT.ROWCOUNT":
                return result.rowCount();
            case "RESULT.STATUS":
                return result.status();
            case "RESULT.ROWS.COUNT":
                return result.rows().size();
            case "RESULT.HEADERS.COUNT":
                return result.headers().size();
            case "RESULT.COLUMNS.COUNT":
                return result.columnTypes().size();
            default:
                break;
        }
        if (upper.startsWith("RESULT.HEADERS[")) {
            return result.headers().get(indexOf(selector, "RESULT.HEADERS", result.headers().size()));
        }
        if (upper.startsWith("RESULT.COLUMNS[")) {
            return result.columnTypes().get(indexOf(selector, "RESULT.COLUMNS", result.columnTypes().size()));
        }
        if (upper.startsWith("RESULT.ROWS[")) {
            return resolveCell(selector, result);
        }
        if (upper.startsWith("VAR.")) {
            return lookupVariable(selector.substring(4), result.variables());
        }
        if (upper.startsWith("ENV.")) {
            return System.getenv(selector.substring(4));
        }
        if (upper.startsWith("RUN.") || upper.startsWith("JOB.")) {
            // 引擎把子脚本退出码与作业状态塞进 variables（键就是完整取值器名字）
            return lookupVariable(selector, result.variables());
        }
        throw new AssertSyntaxException("unknown selector [" + selector + "]");
    }

    private static Object lookupVariable(String name, Map<String, String> variables) {
        if (variables.containsKey(name)) {
            return variables.get(name);
        }
        String upperName = name.toUpperCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(upperName)) {
                return entry.getValue();
            }
        }
        throw new AssertSyntaxException("unknown variable [" + name + "]");
    }

    /** {@code RESULT.ROWS[r][c]}：**0 基**，越界报语法错而不是静默 false。 */
    private static Object resolveCell(String selector, ResultModel model) {
        Matcher matcher = INDEX_PATTERN.matcher(selector);
        List<Integer> indexes = new java.util.ArrayList<>();
        while (matcher.find()) {
            indexes.add(Integer.parseInt(matcher.group(1)));
        }
        if (indexes.size() != 2) {
            throw new AssertSyntaxException("RESULT.ROWS needs [row][column]: [" + selector + "]");
        }
        int row = indexes.get(0);
        int column = indexes.get(1);
        if (row < 0 || row >= model.rows().size()) {
            throw new AssertSyntaxException("row index out of range: " + row + " (rows=" + model.rows().size() + ")");
        }
        List<Object> line = model.rows().get(row);
        if (column < 0 || column >= line.size()) {
            throw new AssertSyntaxException("column index out of range: " + column + " (columns=" + line.size() + ")");
        }
        return line.get(column);
    }

    private static int indexOf(String selector, String prefix, int size) {
        Matcher matcher = INDEX_PATTERN.matcher(selector);
        if (!matcher.find()) {
            throw new AssertSyntaxException(prefix + " needs [index]: [" + selector + "]");
        }
        int index = Integer.parseInt(matcher.group(1));
        if (index < 0 || index >= size) {
            throw new AssertSyntaxException("index out of range: " + index + " (size=" + size + ")");
        }
        return index;
    }

    private static boolean compare(Object left, String op, Object right) {
        switch (op) {
            case "=":
                return equalsValue(left, right);
            case "<>":
                return !equalsValue(left, right);
            case ">":
                return compareOrder(left, right) > 0;
            case ">=":
                return compareOrder(left, right) >= 0;
            case "<":
                return compareOrder(left, right) < 0;
            case "<=":
                return compareOrder(left, right) <= 0;
            case "CONTAINS":
                return asText(left).contains(asText(right));
            case "NOTCONTAINS":
                return !asText(left).contains(asText(right));
            case "MATCHES":
                return Pattern.compile(asText(right), Pattern.DOTALL).matcher(asText(left)).matches();
            default:
                throw new AssertSyntaxException("unknown comparator [" + op + "]");
        }
    }

    /** 相等语义：都是数字按数值比；否则按字符串比；NULL 只与 NULL 相等（便于 {@code RESULT.X = NULL}）。 */
    private static boolean equalsValue(Object left, Object right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        if (isNumeric(left) && isNumeric(right)) {
            return new BigDecimal(asText(left)).compareTo(new BigDecimal(asText(right))) == 0;
        }
        if (left instanceof Boolean || right instanceof Boolean) {
            return asText(left).equalsIgnoreCase(asText(right));
        }
        return asText(left).equals(asText(right));
    }

    private static int compareOrder(Object left, Object right) {
        if (left == null || right == null) {
            throw new AssertSyntaxException("cannot order-compare NULL");
        }
        if (isNumeric(left) && isNumeric(right)) {
            return new BigDecimal(asText(left)).compareTo(new BigDecimal(asText(right)));
        }
        return asText(left).compareTo(asText(right));
    }

    private static boolean isNumeric(Object value) {
        return value instanceof Number || (value instanceof String text && isNumber(text.strip()));
    }

    private static String asText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return String.valueOf(value);
    }

    private static Verdict decide(boolean passed, String assertName) {
        String suffix = (assertName == null || assertName.isEmpty()) ? "" : " [" + assertName + "]";
        return passed
                ? new Verdict(true, "Assert" + suffix + " successful.")
                : new Verdict(false, "Assert" + suffix + " fail.");
    }
}
