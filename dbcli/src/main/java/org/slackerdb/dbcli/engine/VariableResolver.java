package org.slackerdb.dbcli.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 变量替换层：{@code ${name}} 的解析与替换。
 *
 * <h2>只有一种占位符外观</h2>
 * <p>变量写法**只有** {@code ${name}}。历史脚本里出现过的 mustache 风格双花括号写法<b>不再是
 * 替换语法</b>：它按普通文本原样保留（不报错、不替换），因此脚本里带双花括号的字面文本不会被
 * 误当成变量。这样"哪些字符是语法"只有一个答案，不必再解释"两种写法等价"以及它们在转义/嵌套上的差异。</p>
 *
 * <h2>三步解析（命中即停）</h2>
 * <ol>
 *   <li><b>已定义的变量</b>，按优先级先命中先用：
 *     <ol>
 *       <li>脚本内变量：{@code _SET @名称 值}；</li>
 *       <li>{@code --var NAME=VALUE}（可重复）；</li>
 *       <li>{@code ${ARGV1}}/{@code ${ARGV2}}…：{@code _START} 传给子脚本的参数（1 基；越界视为未定义）；</li>
 *     </ol></li>
 *   <li>否则取<b>同名环境变量</b>（{@code System.getenv(name)}）；</li>
 *   <li>都没有 = <b>不认识的变量</b> → <b>原样保留</b> {@code ${name}}（不报错、不替换成空串）。</li>
 * </ol>
 *
 * <p><b>不是变量引用的形态一律按普通文本原样保留</b>（不替换、不报错、也不会去取环境变量）：
 * 空名字 {@code ${}}/{@code ${ }}、反斜杠转义 {@code \${name}}、{@code ${} 与 {@code {} 之间被
 * 拆开的怪形态（如 {@code $ {name}}、{@code $\{name\}}），以及双花括号写法。</p>
 *
 * <h2>三条行为约定</h2>
 * <ul>
 *   <li><b>未知名字保持原样</b>（不替换成空串）：脚本里写的 {@code ${没定义}} 一眼能看见，
 *       而不是"悄悄变成空"；</li>
 *   <li><b>非法写法报可读错误</b>：{@code ${a+b}}、{@code ${f(x)}}、{@code ${name with space}} 这类
 *       不是"普通变量名"的替换目标一律抛 {@link VariableExpressionException}（不引入表达式引擎，
 *       也不静默算别的结果）；空名字不在此列 —— 它按普通文本原样保留；
 *   </li>
 *   <li>替换是**单遍**的：替换结果不再被本层重新扫描（否则值里出现 {@code ${x}} 会自我递归）。</li>
 * </ul>
 *
 * <h2>优先级</h2>
 * <p>命令行显式给定的值最硬，顺序是"脚本内变量（{@code _SET @x v}）> {@code --var} >
 * {@code ARGVn} > 同名环境变量"。顺序只在"同一个名字既给了 {@code --var}、环境里又有一份"
 * 时才看得出差别：定义了就用当前的值，环境变量只是兜底。</p>
 */
public final class VariableResolver {

    /** {@code ${name}}（名字两侧允许空白）。这是**唯一**的占位符外观。 */
    private static final Pattern DOLLAR = Pattern.compile("\\$\\{\\s*([^{}]*?)\\s*}");

    /** "普通变量名"：字母/下划线开头，后接字母数字下划线或点（{@code ARGV1} 也符合）。 */
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");

    /** {@code ARGVn}（大小写无关）。 */
    private static final Pattern ARGV_NAME = Pattern.compile("(?i)ARGV(\\d+)");

    private final Map<String, String> commandLineVariables;

    private final Map<String, String> scriptVariables = new LinkedHashMap<>();

    private List<String> argv = List.of();

    /**
     * @param commandLineVariables {@code --var NAME=VALUE}（可为 null，等价于空表）
     */
    public VariableResolver(Map<String, String> commandLineVariables) {
        this.commandLineVariables = commandLineVariables == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(commandLineVariables);
    }

    /** {@code _SET @名称 值}：登记一个脚本内变量（优先级最高）。 */
    public void setVariable(String name, String value) {
        scriptVariables.put(name, value);
    }

    /** {@code _START} 进入子脚本时设置子脚本的 {@code ARGVn}。 */
    public void setArgv(List<String> argv) {
        this.argv = argv == null ? List.of() : List.copyOf(argv);
    }

    public List<String> argv() {
        return argv;
    }

    /**
     * 供 {@code _ASSERT} 的 {@code VAR.<名>} 取值用：脚本内变量 + {@code --var} + {@code ARGVn}。
     *
     * <p>不含环境变量：环境变量由本类在替换期兜底，
     * 断言要取环境变量时用 {@code ENV.<名>} 显式取。</p>
     */
    public Map<String, String> snapshot() {
        // 顺序必须与 resolve() 的取值顺序一致：脚本变量 > --var > ARGVn。
        // 否则 `_set @x 1` 之后再 `_assert {% VAR.x = 1 %}` 会看到 --var 里的旧值，
        // 同一次运行里"替换"和"断言"对同一个名字给出两个答案。
        Map<String, String> snapshot = new LinkedHashMap<>(commandLineVariables);
        snapshot.putAll(scriptVariables);
        for (int index = 0; index < argv.size(); index++) {
            snapshot.putIfAbsent("ARGV" + (index + 1), argv.get(index));
        }
        return snapshot;
    }

    /**
     * 替换一段文本里的全部 {@code ${name}}。
     *
     * <p>三步顺序：已定义的变量 → 同名环境变量 → 原样保留。以下形态**都不是变量引用**，
     * 一律按普通文本原样保留（既不替换、也不报错、更不会去取同名环境变量）：
     * 双花括号写法（mustache 风格）、历史上的 {@code ${ENV(NAME)}} 写法、<b>空名字</b>
     * （{@code ${}} / {@code ${ }}）、以及**反斜杠转义**写法 {@code \${name}}
     * （{@code ${} 前面紧邻一个反斜杠时，整段连同反斜杠一起原样保留）。</p>
     *
     * @throws VariableExpressionException 替换目标是表达式/函数调用（不是普通变量名）
     */
    public String resolve(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return replacePlaceholders(text);
    }

    /**
     * 单遍扫描并替换。
     *
     * <p>用"手动拼 builder"而不是 {@code appendReplacement}：转义形态要能把
     * 匹配到的那一段**连同它前面的反斜杠**原样留在原位，跳过替换但继续扫描后面。</p>
     */
    private String replacePlaceholders(String text) {
        Matcher matcher = DOLLAR.matcher(text);
        StringBuilder builder = new StringBuilder();
        int copiedUpTo = 0;
        while (matcher.find()) {
            String token = matcher.group(0);
            // 反斜杠转义：紧邻 ${ 的那个反斜杠说明这是字面文本，整段不参与替换
            boolean escaped = matcher.start() > 0 && text.charAt(matcher.start() - 1) == '\\';
            String value = escaped ? null : valueOf(token, matcher.group(1));
            builder.append(text, copiedUpTo, matcher.start());
            builder.append(value == null ? token : value);
            copiedUpTo = matcher.end();
        }
        builder.append(text, copiedUpTo, text.length());
        return builder.toString();
    }

    /**
     * 解析一个占位符的值：已定义的变量 → 同名环境变量 → 未命中。
     *
     * @return 命中时返回值；<b>未命中返回 null</b>（调用方保持原样）
     * @throws VariableExpressionException 不是普通变量名（含表达式/函数调用）
     */
    private String valueOf(String token, String name) {
        if (name.isEmpty()) {
            // 空名字（${} / ${ }）不是变量引用：原样保留，绝不走"同名环境变量"那条路
            return null;
        }
        if (!PLAIN_NAME.matcher(name).matches()) {
            // 历史上的 ${ENV(NAME)} 写法已移除：它不再是特殊形态，也不属于表达式，
            // 因此与"不认识的变量"一样原样保留（不报错），要取环境变量直接写 ${NAME}
            if (name.regionMatches(true, 0, "ENV(", 0, 4) && name.endsWith(")")) {
                return null;
            }
            throw new VariableExpressionException("Unsupported variable expression [" + token
                    + "]: only a plain variable name such as ${name} is supported.");
        }
        String value = scriptVariables.get(name);
        if (value != null) {
            return value;
        }
        value = commandLineVariables.get(name);
        if (value != null) {
            return value;
        }
        Matcher argvMatcher = ARGV_NAME.matcher(name);
        if (argvMatcher.matches()) {
            int index;
            try {
                index = Integer.parseInt(argvMatcher.group(1));
            } catch (NumberFormatException overflow) {
                // 序号超出 int（例如 ${ARGV99999999999999}）：等同于"未定义"，
                // 而不是把一次脚本笔误升级成未捕获异常（进程 255 + Java 栈）。
                return null;
            }
            if (index >= 1 && index <= argv.size()) {
                return argv.get(index - 1);
            }
            return null;
        }
        return System.getenv(name);
    }

}
