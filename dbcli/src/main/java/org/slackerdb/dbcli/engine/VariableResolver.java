package org.slackerdb.dbcli.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slackerdb.dbcli.common.GlobalVariableStore;

/**
 * 变量替换层：{@code ${name}} 的解析与替换。
 *
 * <h2>作用域</h2>
 * <p>{@code _SET} 变量分三层，优先级 **LOCAL > SESSION > GLOBAL**：</p>
 * <ul>
 *   <li><b>LOCAL</b>：当前 job/会话，不传给 fork 出来的 job；{@code _SET @x v} 与
 *       {@code _SET LOCAL @x v} 都写这一层；</li>
 *   <li><b>SESSION</b>：当前 job 及 fork/派生的 job；fork 时做快照，子 job 的修改不回传父 job/并行 job；</li>
 *   <li><b>GLOBAL</b>：同一 WORK 内所有进程共享（SQLite 文件），子进程修改父进程下一次读取可见；</li>
 * </ul>
 * <p>{@code _ECHO @name} 捕获、{@code API.*} 运行时变量仍是 LOCAL 层的旁路：捕获写 LOCAL，
 * 运行时变量排在 LOCAL 之后、SESSION 之前。</p>
 *
 * <h2>只有一种占位符外观</h2>
 * <p>变量写法**只有** {@code ${name}}。历史脚本里出现过的 mustache 风格双花括号写法<b>不再是
 * 替换语法</b>：它按普通文本原样保留（不报错、不替换），因此脚本里带双花括号的字面文本不会被
 * 误当成变量。这样"哪些字符是语法"只有一个答案，不必再解释"两种写法等价"以及它们在转义/嵌套上的差异。</p>
 *
 * <h2>三步解析（命中即停）</h2>
 * <ol>
 *   <li><b>已定义的变量</b>，按作用域优先级先命中先用：
 *     <ol>
 *       <li>LOCAL：{@code _SET @名称 值} / {@code _SET LOCAL @名称 值} / {@code _ECHO @名称} 捕获；</li>
 *       <li>运行时变量：{@code _API} 的 {@code API.*}（每轮请求后整体替换）；</li>
 *       <li>SESSION：{@code _SET SESSION @名称 值}（fork 时快照给子 job）；</li>
 *       <li>GLOBAL：{@code _SET GLOBAL @名称 值}（同一 WORK 的 SQLite 文件）；</li>
 *       <li>{@code --var NAME=VALUE}（可重复）；</li>
 *       <li>{@code ${ARGV1}}/{@code ${ARGV2}}…：{@code _START} 传给子脚本的参数（1 基；越界视为未定义）；</li>
 *     </ol></li>
 *   <li>否则取<b>同名环境变量</b>（{@code System.getenv(name)}）；</li>
 *   <li>都没有 = <b>不认识的变量</b> → <b>原样保留</b> {@code ${name}}（不报错、不替换成空串）。
 *       例外：{@code API.*} 命名空间查不到时严格报错，见 {@link #resolve(String)}。</li>
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
 * <p>命令行显式给定的值最软，顺序是"LOCAL > 运行时 > SESSION > GLOBAL > {@code --var} > {@code ARGVn} >
 * 同名环境变量"。作用域只在同名变量同时出现在多层时才看得出差别；GLOBAL 读取的是同一 WORK 的
 * SQLite 文件，因此父子进程/并行 job 互相可见。</p>
 */
public final class VariableResolver {

    /** {@code ${name}}（名字两侧允许空白）。这是**唯一**的占位符外观。 */
    private static final Pattern DOLLAR = Pattern.compile("\\$\\{\\s*([^{}]*?)\\s*}");

    /** "普通变量名"：字母/下划线开头，后接字母数字下划线或点（{@code ARGV1} 也符合）。 */
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");

    /** {@code ARGVn}（大小写无关）。 */
    private static final Pattern ARGV_NAME = Pattern.compile("(?i)ARGV(\\d+)");

    private final Map<String, String> commandLineVariables;

    /** LOCAL：{@code _SET @x} / {@code _SET LOCAL @x}，以及 {@code _ECHO @x} 捕获。 */
    private final Map<String, String> scriptVariables = new LinkedHashMap<>();

    /** SESSION：fork 时快照给子 job；本 job 的修改不回传父/并行 job。 */
    private final Map<String, String> sessionVariables = new LinkedHashMap<>();

    /** GLOBAL：同一 WORK 的 SQLite 文件；每次查询读取，保证父子进程互相可见。 */
    private final GlobalVariableStore globalVariables;

    /**
     * 运行时变量（当前只有 {@code _API} 的 {@code API.*}）：每次请求完成后**整体替换**。
     *
     * <p>它不是脚本变量：优先级在脚本变量之后、命令行变量之前；这样 {@code _SET @x} 仍可覆盖，
     * 而 {@code API.STATUS} 不会被 {@code --var API.STATUS=...} 悄悄顶掉。</p>
     */
    private Map<String, String> runtimeVariables = Map.of();

    private List<String> argv = List.of();

    /**
     * @param commandLineVariables {@code --var NAME=VALUE}（可为 null，等价于空表）
     */
    public VariableResolver(Map<String, String> commandLineVariables) {
        this(commandLineVariables, Map.of(), null);
    }

    /**
     * @param sessionVariables fork 时从父 job 继承的 SESSION 快照（可为 null）
     * @param globalVariables  同一 WORK 的 GLOBAL 存储（可为 null：只用 LOCAL/SESSION/--var）
     */
    public VariableResolver(Map<String, String> commandLineVariables,
                            Map<String, String> sessionVariables,
                            GlobalVariableStore globalVariables) {
        this(commandLineVariables, sessionVariables, Map.of(), globalVariables);
    }

    /**
     * @param localVariables 作业参数等 LOCAL 初始值（可为 null）；优先级最高
     */
    public VariableResolver(Map<String, String> commandLineVariables,
                            Map<String, String> sessionVariables,
                            Map<String, String> localVariables,
                            GlobalVariableStore globalVariables) {
        this.commandLineVariables = commandLineVariables == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(commandLineVariables);
        if (sessionVariables != null) {
            this.sessionVariables.putAll(sessionVariables);
        }
        if (localVariables != null) {
            this.scriptVariables.putAll(localVariables);
        }
        this.globalVariables = globalVariables;
    }

    /** {@code _SET @名称 值} / {@code _SET LOCAL @名称 值}：当前 job 的 LOCAL 变量。 */
    public void setVariable(String name, String value) {
        scriptVariables.put(name, value);
    }

    /** {@code _SET SESSION @名称 值}：当前 job 及后续 fork 的 job 可见。 */
    public void setSessionVariable(String name, String value) {
        sessionVariables.put(name, value);
    }

    /** {@code _SET GLOBAL @名称 值}：写入同一 WORK 的 SQLite 存储，所有 job/进程可见。 */
    public void setGlobalVariable(String name, String value) {
        if (globalVariables == null) {
            throw new IllegalStateException("Global variable store is not available in this context.");
        }
        globalVariables.set(name, value);
    }

    /** fork 子 job 时传给它的 SESSION 快照（只读副本）。 */
    public Map<String, String> sessionSnapshot() {
        return Map.copyOf(sessionVariables);
    }

    /**
     * {@code _API} 完成后整体替换运行时变量。
     *
     * <p>整体替换（而不是逐项 put）保证上一次响应的 {@code API.JSON.old_token} 不会残留。</p>
     */
    public void setRuntimeVariables(Map<String, String> variables) {
        runtimeVariables = variables == null ? Map.of() : new LinkedHashMap<>(variables);
    }

    /**
     * 查询一个变量（不抛异常）：脚本变量 → 运行时变量 → 命令行 → ARGVn → 环境变量。
     *
     * <p>{@code _API} 的 {@code bodyvar=} 用它读取 {@code _ECHO @name} 捕获的文本；
     * 未定义返回 null。</p>
     */
    public String lookup(String name) {
        if (name == null) {
            return null;
        }
        String value = scriptVariables.get(name);
        if (value != null) {
            return value;
        }
        value = runtimeVariables.get(name);
        if (value != null) {
            return value;
        }
        value = sessionVariables.get(name);
        if (value != null) {
            return value;
        }
        value = globalVariables == null ? null : globalVariables.get(name);
        if (value != null) {
            return value;
        }
        value = commandLineVariables.get(name);
        if (value != null) {
            return value;
        }
        Matcher argvMatcher = ARGV_NAME.matcher(name);
        if (argvMatcher.matches()) {
            try {
                int index = Integer.parseInt(argvMatcher.group(1));
                return index >= 1 && index <= argv.size() ? argv.get(index - 1) : null;
            } catch (NumberFormatException overflow) {
                return null;
            }
        }
        return System.getenv(name);
    }

    /** {@code _START} 进入子脚本时设置子脚本的 {@code ARGVn}。 */
    public void setArgv(List<String> argv) {
        this.argv = argv == null ? List.of() : List.copyOf(argv);
    }

    public List<String> argv() {
        return argv;
    }

    /**
     * 供 {@code _ASSERT} 的 {@code VAR.<名>} 取值用：LOCAL + 运行时 + SESSION + GLOBAL +
     * {@code --var} + {@code ARGVn}（与 {@link #lookup(String)} 同一优先级）。
     *
     * <p>不含环境变量：环境变量由本类在替换期兜底，
     * 断言要取环境变量时用 {@code ENV.<名>} 显式取。</p>
     */
    public Map<String, String> snapshot() {
        // 顺序必须与 resolve() 的取值顺序一致：脚本变量 > 运行时变量 > --var > ARGVn。
        // 否则 `_set @x 1` 之后再 `_assert {% VAR.x = 1 %}` 会看到 --var 里的旧值，
        // 同一次运行里"替换"和"断言"对同一个名字给出两个答案。
        Map<String, String> snapshot = new LinkedHashMap<>(commandLineVariables);
        if (globalVariables != null) {
            snapshot.putAll(globalVariables.snapshot());
        }
        snapshot.putAll(sessionVariables);
        snapshot.putAll(runtimeVariables);
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
     * 把 {@code ${name}} 替换成等长的中性文本 {@code x}，**只用于块内语句的结构探测**。
     *
     * <p>控制流在“读取块”时还不知道变量在执行时的值；直接用原文做词法/语法探测，可能因为
     * {@code ${...}} 里的字符（尤其 {@code _HOST} 等命令模式）被误判成没写完。探测只关心
     * 语句边界（{@code ;}、行首 {@code /}、{@code $$} 等），变量值不影响边界，因此统一换成
     * {@code x} 即可；真正执行时仍用原文并展开。</p>
     */
    public String maskForAnalysis(String text) {
        if (text == null || text.isEmpty() || !text.contains("${")) {
            return text;
        }
        Matcher matcher = DOLLAR.matcher(text);
        StringBuilder builder = new StringBuilder();
        int copiedUpTo = 0;
        while (matcher.find()) {
            builder.append(text, copiedUpTo, matcher.start());
            builder.append('x');
            copiedUpTo = matcher.end();
        }
        builder.append(text, copiedUpTo, text.length());
        return builder.toString();
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
        value = runtimeVariables.get(name);
        if (value != null) {
            return value;
        }
        value = sessionVariables.get(name);
        if (value != null) {
            return value;
        }
        value = globalVariables == null ? null : globalVariables.get(name);
        if (value != null) {
            return value;
        }
        // API 命名空间是“动态变量”：查不到时严格报错，避免把 ${API.JSON.token}
        // 原样发给服务端（普通变量仍保持“未定义原样保留”的既有语义）。
        if (name.startsWith("API.")) {
            throw new VariableExpressionException("Unknown API value [" + token + "].");
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
