package org.slackerdb.dbcli.engine;

import org.slackerdb.dbcli.assertion.ResultModel;
import org.slackerdb.dbcli.job.Job;
import org.slackerdb.dbcli.job.JobManager;
import org.slackerdb.dbcli.sql.SqlExecutor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 脚本执行的会话状态：一次运行中最容易被"跨语句"使用的那些东西都集中在这里。
 *
 * <p>为什么需要一个显式的上下文对象：脚本的执行是**线性推进**的，很多状态天然跨语句
 * （变量表、{@code _SET} 选项、{@code _WHENEVER} 设置、spool 目标、上一条 SQL 的
 * {@link ResultModel}、退出码、子脚本栈）。把它们塞进 {@code ScriptRunner} 的字段会让
 * "一次运行"无法与"下一次运行"分开；集中到本类之后，{@link ScriptRunner} 只负责推进，
 * {@code _START} 递归也共享同一份会话状态（与 `_EXIT` 必须是"进程级退出"这一点一致）。</p>
 *
 * <h2>几条状态纪律</h2>
 * <ul>
 *   <li>{@link #lastResult()} 只在 SQL 语句执行后更新：{@code _ASSERT} 取的就是"最近一条 SQL 的结果"
 *       （断言自身不改变它）；</li>
 *   <li>{@link #requestExit(int)} 一旦调用，整次运行立刻收尾（{@code _EXIT}/{@code _QUIT}/
 *       {@code _WHENEVER ERROR EXIT n}），且**显式退出码优先于错误计数**；</li>
 *   <li>{@link #finalExitCode()} 是"进程退出码"的唯一计算点（§3.7）：显式 {@code _EXIT n} 压过一切（含 0），
 *       否则返回 <b>0</b>——脚本内部发生过多少错误都不改进程退出码（README §9.3）。要门禁就在脚本里写
 *       {@code _WHENEVER ERROR EXIT 1} 或结尾 {@code _EXIT 1}；命令行层面的失败（用法错误、{@code --ref}
 *       不一致、未捕获异常）由 {@code Cli}/{@code Main} 决定，不走这里。</li>
 * </ul>
 */
public final class ScriptContext implements AutoCloseable {

    /**
     * 选项表只有一份定义（{@link SetOption}）：默认值、类型、合法取值、打印顺序与落地点都在那里。
     * 本类只持有"这次会话当前的值"。
     */

    /**
     * {@code _WHENEVER ERROR CONTINUE|EXIT n} 的设置。
     *
     * @param condition IR 的 {@code condition}（当前只有 {@code error}）
     * @param action    {@code continue} / {@code exit}
     * @param exitCode  {@code exit} 时使用的退出码
     */
    public record Whenever(String condition, String action, int exitCode) {

        /** 默认行为：错误计数后继续（{@code _WHENEVER ERROR EXIT n} 才会中断）。 */
        public static Whenever continueOnError() {
            return new Whenever("error", "continue", 0);
        }
    }

    private final SqlExecutor sqlExecutor;
    private final ScriptOutput output;
    private final VariableResolver variables;
    private final Path workingDirectory;
    private final CommandDispatcher dispatcher;

    private final Map<String, String> options = defaultOptions();

    /** 正在执行的脚本栈（栈顶 = 当前脚本；用于错误定位、错误文案与 {@code _START} 的相对路径回退）。 */
    private final List<ScriptFrame> frames = new ArrayList<>();

    private Whenever whenever = Whenever.continueOnError();

    private ResultModel lastResult = ResultModel.empty();

    private int errorCount;

    private int exitCode;

    /** 最近一次 {@code _START} 子脚本的退出码；没跑过子脚本时为 null（见 {@link #assertModel()}）。 */
    private Integer lastSubScriptExitCode;

    private boolean exitRequested;

    /**
     * 本次会话是否负责"作业失败传播"（默认 true；**作业线程里的上下文是 false**）。
     *
     * <p>为什么必须区分：作业线程与父脚本共享同一个 {@link CommandDispatcher}（因此也共享同一个
     * {@code JobManager}）。如果作业线程在收尾时也去补报/认领"失败的兄弟作业"，就会有两个后果：
     * ① 失败信息被写进**别的作业**的 {@code .err}；② 认领之后父脚本收尾时不再报它，
     * 于是"父脚本没 WAIT 过的失败作业"被静默放过——正是本阶段要修的毛病。
     * 作业自己的退出码也不该取决于兄弟作业的状态，所以 {@link #finalExitCode()} 同样跳过这条判断。</p>
     */
    private boolean jobFailureReporting = true;

    public ScriptContext(SqlExecutor sqlExecutor, ScriptOutput output, VariableResolver variables,
                         Path workingDirectory) {
        this(sqlExecutor, output, variables, workingDirectory, null);
    }

    /**
     * @param dispatcher 预置的分派器；{@code null} 表示自建一个。
     *                   <p><b>为什么会传进来</b>：作业线程必须有**自己的一份**会话状态（独立连接、独立变量、
     *                   独立输出），但 {@code _JOB} 的管理器必须是**父脚本那一个**——否则作业里再起的孙作业
     *                   会落到另一个管理器上，定时点（屏障）也就跨不过去。作业侧因此把自己的
     *                   {@code CommandDispatcher}（共享父管理器的那个）交给这里。</p>
     */
    public ScriptContext(SqlExecutor sqlExecutor, ScriptOutput output, VariableResolver variables,
                         Path workingDirectory, CommandDispatcher dispatcher) {
        this(sqlExecutor, output, variables, workingDirectory, dispatcher, true);
    }

    /**
     * @param jobFailureReporting {@code false} 表示这是**作业线程**的上下文：它不负责补报/认领
     *                            "失败的兄弟作业"，退出码也不取决于兄弟作业（见字段注释）
     */
    public ScriptContext(SqlExecutor sqlExecutor, ScriptOutput output, VariableResolver variables,
                         Path workingDirectory, CommandDispatcher dispatcher, boolean jobFailureReporting) {
        this.sqlExecutor = sqlExecutor;
        this.output = output;
        this.variables = variables;
        this.workingDirectory = workingDirectory;
        this.dispatcher = dispatcher != null ? dispatcher : new CommandDispatcher(sqlExecutor, workingDirectory);
        this.jobFailureReporting = jobFailureReporting;
    }

    public SqlExecutor sqlExecutor() {
        return sqlExecutor;
    }

    public ScriptOutput output() {
        return output;
    }

    public VariableResolver variables() {
        return variables;
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    public CommandDispatcher dispatcher() {
        return dispatcher;
    }

    public ResultModel lastResult() {
        return lastResult;
    }

    public void setLastResult(ResultModel lastResult) {
        this.lastResult = lastResult == null ? ResultModel.empty() : lastResult;
    }

    public Whenever whenever() {
        return whenever;
    }

    public void setWhenever(Whenever whenever) {
        this.whenever = whenever == null ? Whenever.continueOnError() : whenever;
    }

    /** 错误计数（{@code _WHENEVER ERROR} 之外的错误一律累计；见 {@link #finalExitCode()}）。 */
    public int errorCount() {
        return errorCount;
    }

    /** 记一次错误（打印与 {@code _WHENEVER} 判定由 {@link ScriptRunner} 负责）。 */
    public void countError() {
        errorCount = errorCount + 1;
    }

    public int exitCode() {
        return exitCode;
    }

    public boolean exitRequested() {
        return exitRequested;
    }

    /** 本次会话是否负责"作业失败传播"（见字段注释；作业线程为 false）。 */
    public boolean jobFailureReporting() {
        return jobFailureReporting;
    }

    /** 请求立刻结束整次运行并返回指定退出码（{@code _EXIT n} / {@code _WHENEVER ERROR EXIT n}）。 */
    public void requestExit(int code) {
        this.exitCode = code;
        this.exitRequested = true;
    }

    /**
     * 进程退出码（§3.7）：**只由显式决定与命令行层面的成败决定**。
     *
     * <ol>
     *   <li>{@code _EXIT n} / {@code _QUIT n} / {@code _WHENEVER ERROR EXIT n}：就是 n（含 0）；</li>
     *   <li>否则 <b>0</b>——脚本里发生过多少 SQL 错误 / 断言失败 / {@code _COMPARE} 失败 / 子作业失败，
     *       都<b>不</b>改变退出码（它们照样打印、照样计数，只是不再是"进程失败"的信号）；</li>
     *   <li>命令行层面的成败另算：用法错误 2、{@code --ref} 比对不一致或 ref 不存在 1、
     *       未捕获异常 255、客户端崩溃 70（见 {@code Cli}）。</li>
     * </ol>
     *
     * <p><b>为什么</b>：退出码表达的是"这次调用本身成不成功"，而"脚本内部有几个错"是脚本自己的事
     * ——要让它变成失败，就在脚本里写 {@code _WHENEVER ERROR EXIT 1}（或结尾 {@code _EXIT 1}）。
     * 想当门禁就用 {@code --ref}：比对不一致是命令行层面的失败，恒为 1。</p>
     */
    public int finalExitCode() {
        return exitRequested ? exitCode : 0;
    }

    // ---------------------------------------------------------------- _SET 选项

    /** 选项表的初始值：全部来自 {@link SetOption}（本类是唯一持有"当前值"的地方）。 */
    private static Map<String, String> defaultOptions() {
        Map<String, String> defaults = new LinkedHashMap<>();
        for (SetOption option : SetOption.values()) {
            defaults.put(option.optionName(), option.defaultValue());
        }
        return defaults;
    }

    /** 当前选项表（键小写）。 */
    public Map<String, String> options() {
        return Map.copyOf(options);
    }

    /** 选项表的固定顺序（{@code _SET} 无参打印用）。 */
    public List<String> optionOrder() {
        return SetOption.names();
    }

    public String optionDefault(String name) {
        SetOption option = SetOption.of(name);
        return option == null ? "" : option.defaultValue();
    }

    public String option(String name) {
        return name == null ? null : options.get(name.toLowerCase(Locale.ROOT));
    }

    /** 选项名是否是已知选项。 */
    public boolean isKnownOption(String name) {
        return SetOption.of(name) != null;
    }

    public void setOption(String name, String value) {
        options.put(name.toLowerCase(Locale.ROOT), value.toLowerCase(Locale.ROOT));
    }

    /**
     * {@code _SET FEEDBACK}：关闭时**状态行（摘要）与错误消息**都不打印（§4.3）。
     *
     * <p>两者是同一类"这次执行怎么样了"的信息；错误计数与退出码不受影响
     * （不说 ≠ 没发生）。要连 CLI 自身消息与第三方日志一起关，用 {@code --silent}。</p>
     */
    public boolean feedbackOn() {
        return !"off".equals(option("feedback"));
    }

    /** {@code _SET ECHO}：是否把脚本里执行的 SQL/命令原文写进日志（默认 on）。 */
    public boolean echoOn() {
        return !"off".equals(option("echo"));
    }

    /** {@code _SET TERMOUT}：是否输出结果集（关闭时连 logfile/spool 也不写——"这批结果不要"）。 */
    public boolean termOutOn() {
        return !"off".equals(option("termout"));
    }

    /** {@code _SET TIMING}（默认 off）：每条语句跑完后是否在 stdout 打一行耗时。 */
    public boolean timingOn() {
        return "on".equals(option("timing"));
    }

    /** {@code _SET TIME}（默认 off）：每条语句跑完后是否在 stdout 打一行当前系统时间。 */
    public boolean timeOn() {
        return "on".equals(option("time"));
    }

    /** {@code _SET DIFF_REPORT_HTML}（默认 off）：{@code _COMPARE} 有差异时是否另外写一份 HTML 报告。 */
    public boolean diffReportHtmlOn() {
        return "on".equals(option("diff_report_html"));
    }

    /** {@code _SET HEADER}（默认 on）：是否打印表头。 */
    public boolean headerOn() {
        return !"off".equals(option("header"));
    }

    /** {@code _SET LINEWIDTH}（默认 200）：一行最多多少显示列，超出换行。 */
    public int lineWidth() {
        return integerOption("linewidth");
    }

    /** {@code _SET COLWIDTH}（默认 40）：单元格最多多少显示列，超出用 {@code ...} 截断。 */
    public int colWidth() {
        return integerOption("colwidth");
    }

    /** 读一个整数选项（解析失败按 0 处理；{@code _SET} 已经校验过取值）。 */
    private int integerOption(String name) {
        try {
            return Integer.parseInt(option(name));
        } catch (RuntimeException unparsable) {
            return 0;
        }
    }

    // ------------------------------------------------------------- 子脚本（_START）

    /**
     * 一段正在执行的脚本（栈帧）。
     *
     * <p>为什么需要显式的栈帧：{@code _START} 会把"另一个脚本"压进同一个上下文，
     * 而"脚本会话/控制台会话"的区别（{@link #isScriptSession()}）与当前脚本的名字都要能回答。</p>
     */
    private static final class ScriptFrame {

        private final String name;
        private final Path file;
        /** 是否**脚本会话**（交互式控制台为 false；见 {@link #isScriptSession()}）。 */
        private final boolean scriptSession;

        private ScriptFrame(String name, Path file, boolean scriptSession) {
            this.name = name;
            this.file = file;
            this.scriptSession = scriptSession;
        }
    }

    /**
     * 进入一个脚本：压栈（深度即栈大小，由 {@link ScriptRunner} 限制上限）。
     *
     * @param displayName 显示名（交互式控制台是 {@code <console>}）
     * @param scriptFile  脚本文件（stdin/文本输入为 null）
     */
    public void enterScript(String displayName, Path scriptFile) {
        enterScript(displayName, scriptFile, true);
    }

    /**
     * 进入一个脚本并指定它是不是脚本会话。
     *
     * <p>交互式控制台用 {@code scriptSession=false}（它不该回显输入，见 {@link #isScriptSession()}）；
     * {@code --execute}/{@code --profile}/{@code _START} 子脚本/作业脚本都是 {@code true}。</p>
     */
    public void enterScript(String displayName, Path scriptFile, boolean scriptSession) {
        frames.add(new ScriptFrame(displayName == null ? "<script>" : displayName, scriptFile, scriptSession));
    }

    public void leaveScript() {
        frames.remove(frames.size() - 1);
    }

    /**
     * 当前会话是不是**脚本会话**（{@code --execute}/{@code --profile}/{@code _START}/作业脚本）。
     *
     * <p>交互式控制台返回 {@code false}。用途只有一个：{@code _SET ECHO} 只回显脚本里执行的语句
     * ——控制台里敲的那一行终端已经显示过了，再回显一遍只是重复（SQL*Plus 的 ECHO 同样只作用于脚本）。</p>
     */
    public boolean isScriptSession() {
        return !frames.isEmpty() && frames.get(frames.size() - 1).scriptSession;
    }

    public int depth() {
        return frames.size();
    }

    /** 当前脚本的显示名（错误文案用）。 */
    public String currentScriptName() {
        return frames.isEmpty() ? "<none>" : frames.get(frames.size() - 1).name;
    }

    /** 当前脚本文件（stdin 或文本输入时为 null）。 */
    public Path currentScriptFile() {
        return frames.isEmpty() ? null : frames.get(frames.size() - 1).file;
    }

    // ------------------------------------------------------------------ 断言取值

    /**
     * {@code _ASSERT} 用的结果模型：最近一条 SQL 的结果 + 变量快照 + 引擎注入的
     * {@code RUN.EXITCODE} 与 {@code JOB.<名>.STATUS/EXITCODE}。
     *
     * <p>{@code RUN.EXITCODE} 的语义按文档来：**最近一次 {@code _START} 子脚本的退出码**；
     * 没跑过子脚本时退回"本次会话到目前为止的退出码"（{@code _EXIT}/{@code _WHENEVER} 设定的那个）。</p>
     */
    public ResultModel assertModel() {
        Map<String, String> assertVariables = new LinkedHashMap<>(variables.snapshot());
        assertVariables.put("RUN.EXITCODE", String.valueOf(
                lastSubScriptExitCode != null ? lastSubScriptExitCode : exitCode));
        // 作业状态：只有真正用过 _JOB（管理器已存在）时才取，避免为一条断言创建一个管理器
        JobManager jobs = dispatcher.jobManager();
        if (jobs != null) {
            for (Job job : jobs.jobs()) {
                assertVariables.put("JOB." + job.name() + ".STATUS", job.state().name());
                assertVariables.put("JOB." + job.name() + ".EXITCODE", String.valueOf(job.exitCode()));
            }
        }
        ResultModel last = lastResult;
        return new ResultModel(last.errorCode(), last.rowCount(), last.status(), last.rows(),
                last.headers(), last.columnTypes(), assertVariables);
    }

    /** 记录一次 {@code _START} 子脚本的退出码（{@code RUN.EXITCODE} 取值）。 */
    public void setLastSubScriptExitCode(int exitCode) {
        this.lastSubScriptExitCode = exitCode;
    }

    /** 收尾：关闭 SQL 执行层、作业管理器、SSH 会话与输出（spool/tee 落盘）。 */
    @Override
    public void close() {
        output.close();
        dispatcher.close();
        sqlExecutor.close();
    }
}
