package org.slackerdb.dbcli;

import org.slackerdb.common.utils.AppDaemon;
import org.slackerdb.dbcli.common.PathArguments;
import org.slackerdb.dbcli.daemon.CliDaemon;
import org.slackerdb.dbcli.daemon.PidFile;
import org.slackerdb.dbcli.engine.ScriptContext;
import org.slackerdb.dbcli.engine.ScriptOutput;
import org.slackerdb.dbcli.engine.ScriptRunner;
import org.slackerdb.dbcli.engine.VariableResolver;
import org.slackerdb.dbcli.repl.InteractiveConsole;
import org.slackerdb.dbcli.repl.JLineLineSource;
import org.slackerdb.dbcli.repl.LineSource;
import org.slackerdb.dbcli.report.ReferenceCheck;
import org.slackerdb.dbcli.sql.JdbcSqlExecutor;
import org.slackerdb.dbcli.sql.SqlExecutor;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * dbcli 顶层命令：参数解析 + **批处理入口**（{@code --execute} / stdin 管道）。
 *
 * <p><b>为什么没有配置文件</b>：dbcli 只用项目自带的 {@code org.slackerdb.jdbc.Driver}，
 * 没有"多数据库驱动注册表"（每个库的 jar 名、驱动类、URL 模板）可配，因此不引入配置文件；
 * 字符集/超时/输出格式这些运行期选项走命令行参数与脚本内的 {@code _SET}，
 * 避免"配置散落在文件里"这种命令行工具不该有的行为。</p>
 *
 * <p>为什么用 picocli：需要自动 help、类型校验、可重复选项这几项能力。
 * 本仓原有的 {@code AppDaemon.parse} 只支持 {@code --k v} 且"第二个位置参数直接抛异常"
 * （common/.../AppDaemon.java:112-115），无法承载 {@code dbcli --execute x.sql --var a=1} 这类形态，
 * 因此参数解析独立用 picocli；{@code AppDaemon} 负责守护进程那部分职责
 * （取值解析、子进程命令行拼装、拉起后台副本），两边的取值规则只有一份实现。</p>
 *
 * <h2>运行形态</h2>
 * <ul>
 *   <li>{@code dbcli}（无参数、{@code System.console() != null}）：进入 <b>JLine 交互式控制台</b>；
 *       输入就是脚本行，逐行喂给 {@code engine.ScriptRunner}（与批处理同一条执行路径）；</li>
 *   <li>{@code dbcli --execute a.sql [--profile p.sql] [--var k=v] [--logon <connect-string>]
 *       [--logfile x.txt] [--silent]}：跑完脚本退出，<b>脚本退出码就是进程退出码</b>（§3.7）；</li>
 *   <li>{@code dbcli --execute -}：从 stdin 读脚本（管道形态）；不带 {@code --execute} 但 stdin
 *       <b>非交互且有内容</b>时也按脚本执行（{@code type a.sql | dbcli}）；</li>
 *   <li>{@code dbcli --daemon true --pidfile p --execute soak.sql}：把脚本放到后台跑，
 *       父进程立即返回并打印副本的 pid；<b>没有子命令</b> —— 不带 {@code --daemon} 就是前台运行；</li>
 *   <li>{@code --profile} 在主脚本/控制台<b>之前</b>执行（可重复，按给定顺序），
 *       它设置的变量与打开的连接对后续可见；</li>
 *   <li>{@code --logfile} 把本次运行的<b>完整记录</b>（被执行语句 + 与 stdout 相同的数据输出 + 错误行）写进文件，
 *       交互式形态下记的就是<b>当前会话</b>的操作结果；它同时是 {@code --reference} 的
 *       "实际输出文件"（两者一起用时比对的就是它）；</li>
 *   <li>无参数但 stdin 不是终端且没有内容时：打印用法帮助并退出 0（{@code --help} 也走这条）；</li>
 *   <li>工作目录：{@code --execute <file>} 时是该脚本所在目录（**脚本里**的相对路径都以它为基准），
 *       stdin 与控制台形态下是进程当前目录。<b>命令行选项一律按进程当前目录解析</b>
 *       （{@code --profile}/{@code --logfile}/{@code --reference}/{@code --report-dir}/{@code --pidfile}），
 *       与脚本放在哪里无关。</li>
 *   <li>连接参数：{@code --logon} 优先；没给它时才用环境变量 {@code SLACKERDB_LOGON}
 *       （同义、同格式，三种 {@code _connect} 写法都认）；两者都没有就不自动连接。</li>
 * </ul>
 *
 * <p><b>怎么判断"stdin 是交互式终端"</b>：用 {@code System.console() != null}。已知边界：
 * 某些 Windows 终端（mintty / Git Bash）与 IDE 控制台里 {@code System.console()} 返回 null，
 * 这时 dbcli 会按"非交互"处理（stdin 没有内容就打印帮助）——
 * 要在那种环境里跑控制台可以用 {@link #setLineSource(LineSource)} 注入取行来源（测试与嵌入走这条路），
 * 或者用真实支持控制台的终端。</p>
 *
 * <p><b>版本与构建时间只从 {@code --version} 出</b>（见 {@link BuildInfo}）：横幅只打印客户端名字，
 * {@code --help} 里不出现版本信息 —— 版本串含构建时刻，放进每次启动都出现的横幅
 * 或纯用法帮助里只会变成噪音。</p>
 *
 * @see BuildInfo 版本信息的来源与打印口径（{@code --version} 是唯一入口）
 * @see CliVersionProvider
 */
@Command(
        name = "dbcli",
        header = "dbcli (SlackerDB command line client)",
        description = "Interactive console, batch script runner (SQL + commands + assertions) and "
                + "file comparer for SlackerDB.",
        footer = {
                "",
                "Without --execute: an interactive stdin starts the console, a piped stdin is read",
                "as a script, and neither prints this help.",
                "With --daemon true the script runs in the background and the parent returns at once.",
                "",
                "Documentation:",
                "  Inside a session, _HELP lists every command and _HELP <command> describes one.",
                "  The user manual (README.adoc / README-CN.adoc, also shipped as PDF) covers the",
                "  same ground."
        },
        mixinStandardHelpOptions = true,
        versionProvider = CliVersionProvider.class,
        sortOptions = false
)
public class Cli implements Callable<Integer> {

    /** 提供**默认** logon 参数的环境变量名（与 {@code --logon} 同义、同格式；命令行优先）。 */
    static final String LOGON_ENV = "SLACKERDB_LOGON";

    /** 探测"stdin 里有没有脚本"的最长等待；超时就当作没有（见 {@link #readStdinIfAvailable()}）。 */
    private static final long STDIN_PROBE_TIMEOUT_MILLIS = 1500;

    /** 轮询 stdin 的间隔（不阻塞、不起线程）。 */
    private static final long STDIN_POLL_INTERVAL_MILLIS = 20;

    /** 批处理脚本；{@code -} 表示从 stdin 读。 */
    @Option(
            names = "--execute",
            paramLabel = "<script>",
            description = "Run the script file and exit; use '-' to read the script from stdin."
    )
    private String execute;

    /**
     * 启动时连接（与 {@code _connect} 的三种写法同义）：简写的 {@code <host>:<port>/<service>[?k=v]}、
     * {@code [<user>[/<password>]@]jdbc:slackerdb://<host>:<port>/[<database>]}，
     * 以及任意其它驱动的 {@code [<user>[/<password>]@]jdbc:<scheme>:<url>}。
     *
     * <p>简写形态没有缺省值：端口与服务名都得写出来，也不接受用户名/口令（客户端不替使用者补任何东西）。</p>
     */
    @Option(
            names = "--logon",
            paramLabel = "<connect-string>",
            description = "Connect before running the script; same shapes as _connect: "
                    + "host:port/service[?k=v] (no defaults, no user/password), "
                    + "[user[/password]@]jdbc:slackerdb://host:port[/database], or any other "
                    + "[user[/password]@]jdbc:<scheme>:<url> whose driver is on the classpath. "
                    + "Defaults to the " + LOGON_ENV + " environment variable, which this option overrides."
    )
    private String logon;

    /**
     * 变量（可重复）。
     *
     * <p>脚本里的占位符**只有** {@code ${name}} 一种写法（历史文档里出现过的双花括号写法已不支持，
     * 它现在是普通文本）。解析顺序只有三步：已定义的变量（{@code _SET} / {@code --var} / {@code ARGVn}）
     * → 同名环境变量 → 都没有则原样保留。</p>
     *
     * <p>注意 {@code $${name}}：picocli 会把帮助文本里的 {@code ${...}} 当**变量**插值
     * （不转义就被替换成 null），双写美元符才是显示一个真实的 {@code ${name}}。</p>
     */
    @Option(
            names = "--var",
            paramLabel = "NAME=VALUE",
            description = "Define a script variable; repeatable. Used by $${name}. An undefined name "
                    + "falls back to the same-named environment variable, and is left as written otherwise."
    )
    private List<String> variables = new ArrayList<>();

    /**
     * 完全静默：**终端上什么都不打印**——数据、状态行、错误、诊断、横幅一概不出现在
     * 终端（stdout 与 stderr；dbcli 实际只写 stdout），这次运行唯一留下的结果是**进程退出码**。
     *
     * <p>实现是一条"总闸"：进 {@link #call()} 就把两个输出流换成丢弃流（{@link Writer#nullWriter()}），
     * 再加上 {@link ScriptOutput} 自己的 silent 判断与日志后端降级 —— 三处一起保证
     * "除了退出码，屏幕上什么都没有"。</p>
     *
     * <p><b>文件产物不受影响</b>：{@code --logfile} / {@code --reference} 的 log 与 {@code _SPOOL}
     * 照常写（它们不是"打印"）。因此"同一次运行的 log"与是否 {@code --silent} 无关，
     * 静默跑 CI 时证据仍然完整。</p>
     */
    @Option(
            names = "--silent",
            description = "Print nothing at all (stdout, and stderr which dbcli never uses): only the exit code is left. "
                    + "Files written by the run (--logfile, the --reference log, _SPOOL) are unaffected."
    )
    private boolean silent;

    /**
     * 把本次运行的输出写成文件：默认 {@code _SET ECHO ON}，所以文件里先是被执行语句的原文，
     * 再是与 stdout **相同**的数据/状态行（同一份 tee，见 {@link ScriptOutput}）。
     *
     * <p>它有两个用途：给人留下"这次跑了什么、出来什么"的证据；
     * 以及作为 {@code --reference} 的"实际输出文件"（两者同时给出时，比对的对象就是这个文件）。
     * 交互式形态下它记的是**当前会话**的操作结果（控制台里敲的输入不回显）。</p>
     *
     * <p>相对路径按**进程当前目录**解析（命令行选项一律如此，与脚本放在哪里无关）。</p>
     */
    @Option(
            names = "--logfile",
            paramLabel = "<file>",
            description = "Write this run's full record to <file>: the executed statements (while _SET ECHO "
                    + "is on, the default), the same data output as stdout, and every error line (the "
                    + "file:line text that also goes to stderr); in the console it records the current "
                    + "session. Also the actual output file used by --reference."
    )
    private String logFile;

    /**
     * golden 比对：脚本/控制台结束后把这次运行真实的数据输出与参考文件比一遍，
     * 并在参考文件旁边写 {@code .suc} / {@code .dif}（见 {@link ReferenceReport}）。
     *
     * <p>与 {@code _COMPARE} 不同，这条路径**固定用严格选项**（大小写敏感、不 trim、不开 mask/skip），
     * 理由见 {@link ReferenceReport} 的类注释：golden 比对不能"帮输出抹平差异"。</p>
     */
    /**
     * golden 比对（{@code --reference} / {@code --ref}）：给了它才进入"一条龙"——
     * 这次运行的数据输出会写到 {@code <脚本>.log}（除非另给 {@code --logfile}），收尾再与这份 ref 比对。
     *
     * <p>ref 文件不存在时**直接报错**（{@code Reference file [<path>] does not exist.}，退出 1）：
 * 没有"待录制"这种默认放行的形态——要录制就把这次运行的 log 复制成 ref。
     * 比对口径固定严格（大小写敏感、不剥空白），**唯一的放宽手段是脚本里声明的
     * {@code _COMPARE MASKLINE/SKIPLINE}** —— 内置比对继承这些规则，不做任何自动识别。</p>
     */
    @Option(
            names = {"--reference", "--ref"},
            paramLabel = "<file>",
            description = "Compare this run's data output with this golden file; the run log is "
                    + "written next to the script. The reference file must already exist (the client never "
                    + "creates it): a missing one exits with 1, a mismatch exits with 1."
    )
    private String reference;

    /**
     * {@code --reference} 产物的目录（{@code --report-dir <dir>}）。
     *
     * <p>默认写在参考文件旁边。参考文件通常位于只读夹具或受版本控制的目录，往里写
     * {@code .suc/.dif} 会污染工作区；给一个目录之后 CI 也能集中收集报告。</p>
     */
    @Option(
            names = "--report-dir",
            paramLabel = "<dir>",
            description = "Write the --reference artifacts (.suc/.dif) into this directory instead "
                    + "of next to the golden file. Requires --reference."
    )
    private String reportDir;

    /**
     * 启动脚本（可重复，按给定顺序）：在主脚本/控制台**之前**执行。
     *
     * <p>用途是统一的初始化：{@code _connect}、{@code _SET} 写在 profile 里，
     * 后续脚本与控制台直接可见（同一个 {@code ScriptContext}）。需要预置一批变量时，
     * 用一个先把变量 {@code _SET} 好的 profile 脚本正是推荐做法（代替以前的映射文件）。</p>
     */
    @Option(
            names = "--profile",
            paramLabel = "<script>",
            description = "Run this script before the main script / console; repeatable."
    )
    private List<String> profiles = new ArrayList<>();

    /**
     * 转后台运行：{@code --daemon true|false}，裸写 {@code --daemon} 等价于 true。
     *
     * <p>取值判定交给 {@code AppDaemon.parseDaemonFlag}（写错直接报错，不静默当前台运行）；
     * 后台形态必须与 {@code --execute <file>}/stdin 管道一起用。{@code --pidfile} 是**可选**的：
     * 给了就能拿到并记住那个后台进程的 pid，不给就是"起了就不管"（父进程不报 pid）。</p>
     */
    @Option(
            names = "--daemon",
            arity = "0..1",
            fallbackValue = "true",
            paramLabel = "true|false",
            description = "Run the script in the background: true|false; a bare --daemon means true. "
                    + "--pidfile is optional. Default: false."
    )
    private String daemon;

    /**
     * pidfile（可选）：后台副本把自己的 pid 写进去。
     *
     * <p>相对路径按**进程当前目录**解析（与其它命令行选项同一条规则：
     * pidfile 是控制面文件，不该随脚本位置变化）。</p>
     */
    @Option(
            names = "--pidfile",
            paramLabel = "<file>",
            description = "PID file: the background copy writes its own pid into it. Relative paths are "
                    + "resolved against the process working directory."
    )
    private String pidFile;

    /**
     * 内部选项：这次运行结束后删掉 {@code --execute} 指向的脚本文件。
     *
     * <p>只有一种用法：{@code --daemon} + stdin 管道时，父进程把读到的脚本落成一个临时文件交给副本
     * （管道里的字节只能被读一次，副本自己读只会拿到 EOF），副本跑完把这个临时脚本删掉。
     * 因此它对用户隐藏（help 里不出现），也不该手工使用。</p>
     */
    @Option(names = "--daemon-script-temp", hidden = true)
    private boolean daemonScriptTemp;

    /**
     * 内部选项：本进程是<b>父进程拉起的后台副本</b>（父进程在命令行上显式加上）。
     *
     * <p>只有一件事依赖它：<b>pidfile 的收尾</b>。副本跑完不再删自己的 pidfile——
     * 父进程要读它才能报出"副本的 pid"，而一份跑得很快的脚本（脚本本身只花几毫秒）会在父进程
     * 两次轮询（50ms）之间写完又删掉，父进程于是报"pidfile 没写出来"，尽管副本已经跑完并且成功了
     * （Linux 上实测约 3% 概率，Windows 同理）。留着这份文件，子进程的"我来过"就一定能被看到；
     * 里面的 pid 是否是活的由 {@code PidFile.isAlive} 回答，下一轮启动时父进程也会先删旧文件。</p>
     *
     * <p>前台运行（含开发模式）没有这个问题：写 pidfile 的就是使用者眼前那个进程，
     * 退出时照旧清理。与 {@code --daemon-script-temp} 一样，它对用户隐藏、不该手工使用。</p>
     */
    @Option(names = "--daemon-copy", hidden = true)
    private boolean daemonCopy;

    @Spec
    private CommandSpec spec;

    /** 脚本来源；默认 {@code System.in}。 */
    private InputStream stdin = System.in;

    /** stdin 读取失败的原因（null 表示没出错）；用于区分"没有内容"与"读失败"。 */
    private String stdinError;

    /**
     * 交互式控制台的取行来源（null 表示"按需创建 JLine 终端"）。
     *
     * <p>公开的原因与 {@link #setStdin(InputStream)} 一致：把 dbcli 当库嵌进别的程序时要能换掉终端；
     * 测试正是靠它注入"预置输入行"，从而在**没有 TTY** 的环境里验证控制台行为
     * （控制台与批处理必须行为一致，这条注入点是验证手段）。</p>
     */
    private LineSource lineSource;

    /**
     * 替换脚本来源（管道形态的 {@code --execute -} 与"非交互 stdin 有内容"都读它）。
     *
     * <p>公开的原因：把 dbcli 当库嵌进别的程序时要能换掉 stdin；测试也用它避免真的去读进程标准输入。</p>
     */
    public void setStdin(InputStream stdin) {
        this.stdin = stdin;
    }

    /**
     * 替换交互式控制台的取行来源（测试注入"预置输入行"用；见 {@link LineSource}）。
     *
     * <p>注入之后即视为"要进控制台"，不再探测 stdin——这正是无 TTY 环境下的单测入口。
     * 该来源的所有权仍属于调用方，dbcli 不会关闭它。</p>
     */
    public void setLineSource(LineSource lineSource) {
        this.lineSource = lineSource;
    }

    @Override
    public Integer call() {
        Map<String, String> variableTable = parseVariables();
        // 输出策略只有一处（见 CliOutput）：--silent 时装丢弃流并降级第三方日志，否则 stderr 指向 stdout。
        CliOutput terminal = CliOutput.attach(spec.commandLine(), silent);
        PrintWriter out = terminal.out();
        PrintWriter err = terminal.err();

        // 输出格式不在这里选：`_SET OUTFORMAT|HEADER|LINEWIDTH|COLWIDTH` 是脚本/控制台里的开关
        // （见 §7.5）。命令行只负责"跑哪个脚本、连哪里"，不再有"怎么打印"的参数。

        // ------------------------------------------------------------ 守护进程参数
        AppDaemon.DaemonIntent daemonIntent;
        try {
            // 取值判定走 AppDaemon：--daemon maybe 这类**直接报错**，不静默当前台运行
            daemonIntent = CliDaemon.intent(daemon);
        } catch (IllegalArgumentException invalidDaemon) {
            err.println(invalidDaemon.getMessage());
            err.flush();
            return CliDaemon.EXIT_USAGE;
        }
        boolean daemonMode = daemonIntent.requested();

        String stdinScript = null;
        if ("-".equals(execute)) {
            stdinScript = readAllStdin();
            if (stdinScript == null) {
                return 1;
            }
        } else if (execute == null && lineSource == null) {
            stdinScript = readStdinIfAvailable();
            if (stdinScript == null && stdinError != null) {
                return 1;
            }
        }

        // 默认行为：控制台可用（System.console() != null）就进交互式控制台；注入了取行来源（嵌入/测试）同理
        boolean consoleMode = execute == null && stdinScript == null
                && (lineSource != null || System.console() != null);
        if (daemonMode && execute == null && stdinScript == null) {
            // --daemon 只允许配合脚本执行：控制台（stdin 是终端）与"没有输入"都不行 ——
            // 后台跑一个没人看的控制台没有意义，而且它的输出去向也说不清
            err.println(CliDaemon.REQUIRES_SCRIPT);
            err.flush();
            return CliDaemon.EXIT_USAGE;
        }
        if (execute == null && stdinScript == null && !consoleMode) {
            // 没有可执行动作（无参数、stdin 不是终端也没有内容）时打印用法帮助。
            // 不用 System.out 直接打印：picocli 的 usage 写到注入的 out 流，测试才能拿到。
            spec.commandLine().usage(out);
            out.flush();
            return 0;
        }
        RunPlan runPlan;
        try {
            runPlan = RunPlan.resolve(execute);
        } catch (RunPlan.MissingScriptException missingScript) {
            err.println(missingScript.getMessage());
            err.flush();
            return 1;
        }
        Path scriptFile = runPlan.scriptFile();

        if (daemonMode) {
            // 先在本进程把"脚本在不在"这类能立刻判断的事判掉，再 fork：否则错误只能落在后台副本的
            // 输出里（POSIX 上那份输出是 /dev/null），使用者看到的是"父进程等 pidfile 等到超时"。
            Integer daemonExitCode = startDaemon(stdinScript, out, err);
            if (daemonExitCode != null) {
                return daemonExitCode;
            }
            // 返回 null = 开发模式（不是从 jar 启动）：脱离不了当前进程，继续**前台**跑，
            // --pidfile 照旧会写，这样"起不来后台"时使用者仍然拿得到那个 pid
        }

        // 工作目录/命令行基准目录的规则见 RunPlan（脚本里的相对路径跟脚本，命令行选项跟进程当前目录）
        Path workingDirectory = runPlan.workingDirectory();
        Path processDirectory = runPlan.processDirectory();

        ReportPlan reportPlan;
        try {
            // --ref（--reference 的别名）：**给了它才进入"一条龙"**——隐含 log + 收尾比对；
            // 不给就是普通客户端（不落 log、不读 ref、不比对）。路径与校验规则见 ReportPlan。
            reportPlan = ReportPlan.resolve(reference, logFile, reportDir, processDirectory, scriptFile);
        } catch (ReportPlan.InvalidUsageException invalidUsage) {
            err.println(invalidUsage.getMessage());
            err.flush();
            return invalidUsage.exitCode();
        }
        Path referenceFile = reportPlan.referenceFile();
        Path logFileTarget = reportPlan.logFileTarget();
        Path reportDirectory = reportPlan.reportDirectory();
        Path actualOutputFile = reportPlan.actualOutputFile();

        VariableResolver resolver = new VariableResolver(variableTable);

        // --pidfile：真正在跑脚本的进程把自己的 pid 写进去（后台副本就是靠这一步被使用者找到）。
        // 放在这里而不是更早：前面的每一处早退都是"这次运行没跑起来"，那时不该留下一个 pidfile。
        PidFile pidFileHolder = null;
        if (pidFile != null && !pidFile.isBlank()) {
            try {
                pidFileHolder = new PidFile(resolvePidFile(pidFile));
                pidFileHolder.writeCurrent();
            } catch (PathArguments.InvalidPathException invalidPath) {
                err.println(invalidPath.getMessage());
                err.flush();
                return CliDaemon.EXIT_ERROR;
            } catch (IOException pidFailed) {
                err.println(CliDaemon.cannotWritePidFile(pidFile, messageOf(pidFailed)));
                err.flush();
                return CliDaemon.EXIT_ERROR;
            }
        }

        try (SqlExecutor executor = new JdbcSqlExecutor();
             ScriptOutput scriptOutput = terminal.scriptOutput();
             ScriptContext context = new ScriptContext(executor, scriptOutput, resolver, workingDirectory)) {
            // 输出格式走默认值（table / 带表头 / 单格 40 列 / 整行 200 列）；
            // 脚本或 profile 里的 `_SET OUTFORMAT|HEADER|LINEWIDTH|COLWIDTH` 会按需改它
            // --logfile（或 --ref 隐含的 <脚本>.log）：把这次运行的数据输出写进文件
            if (logFileTarget != null) {
                try {
                    scriptOutput.startTee(logFileTarget);
                } catch (IOException logFailed) {
                    err.println("Cannot write the log file [" + logFileTarget + "]: "
                            + messageOf(logFailed));
                    err.flush();
                    return 1;
                }
            }
            String scriptName = runPlan.scriptName(stdinScript);
            ScriptRunner runner = new ScriptRunner(context);
            // 连接参数：命令行 --logon 优先，没给才用环境变量 SLACKERDB_LOGON；两者都没有就不自动连接
            LogonRequest logonRequest = resolveLogon();
            if (logonRequest != null) {
                if (logonRequest.hasLineBreak()) {
                    // 多行校验只有这一份：命令行与环境变量走同一条判定
                    err.println("Invalid " + logonRequest.label()
                            + " value: it must be a single line.");
                    err.flush();
                    return 1;
                }
                if (logonRequest.fromEnvironment()) {
                    // 可观测：说明这次用的是环境变量（走 stdout，dbcli 从不写 stderr）
                    err.println("[dbcli] Using " + LOGON_ENV + " for the initial connection.");
                    err.flush();
                }
                // 与 --logon 同一条路径：就是一行 `_connect`，复用同一段分派/报错，退出码语义也一致
                int connectCode = runner.runText("_connect " + logonRequest.value(), "<logon>", List.of());
                if (connectCode != 0) {
                    return finish(connectCode, scriptOutput, scriptName, actualOutputFile,
                            referenceFile, reportDirectory, context, err);
                }
            }
            Integer profileCode = runProfiles(runner, context, err, processDirectory);
            if (profileCode != null) {
                // profile 是"环境准备"：它出错就不再往下跑
                // （脚本内错误 ⇒ 退出码仍是 0；profile 文件不存在是命令行层面的错 ⇒ 1）
                return finish(profileCode, scriptOutput, scriptName, actualOutputFile,
                        referenceFile, reportDirectory, context, err);
            }
            if (context.exitRequested()) {
                // profile 里的 _EXIT/_QUIT 是"整次运行到此为止"（与控制台/批处理同一语义）
                return finish(context.finalExitCode(), scriptOutput, scriptName, actualOutputFile,
                        referenceFile, reportDirectory, context, err);
            }
            int runCode;
            if (consoleMode) {
                runCode = runConsole(runner, context, err);
            } else if (scriptFile != null) {
                runCode = runner.runFile(scriptFile, List.of());
            } else {
                runCode = runner.runText(stdinScript, "<stdin>", List.of());
            }
            return finish(runCode, scriptOutput, scriptName, actualOutputFile, referenceFile,
                    reportDirectory, context, err);
        } finally {
            // pidfile：前台运行（含开发模式）正常/异常退出都尽力清理（被强杀时没机会走到这里，
            // 由使用者自行处理）。**后台副本例外**：它留着这份文件，否则一份跑得很快的脚本会在
            // 父进程两次轮询之间写完又删掉，父进程只能报"没写出 pid"（见 --daemon-copy）。
            if (pidFileHolder != null && !daemonCopy) {
                pidFileHolder.deleteQuietly();
            }
            // stdin 管道形态的临时脚本（见 --daemon-script-temp）：副本是本进程，跑完就删
            if (daemonScriptTemp && scriptFile != null) {
                deleteQuietly(scriptFile);
            }
        }
    }

    /**
     * 这次运行真正要用的 logon 参数：{@code --logon} 优先，其次环境变量 {@link #LOGON_ENV}。
     *
     * <p>环境变量只在**没给** {@code --logon} 时才看；两者都没有（或环境变量是空白）返回 null，
     * 于是不自动连接 —— 与没有这个环境变量时的行为完全一致。</p>
     *
     * <p>为什么只在这里归一化一次：连接动作只有"一行 {@code _connect}"这一条实现，
     * 两种来源只是取值不同，多行校验与分派/报错路径都必须共用，
     * 否则环境变量迟早长出一套自己的连接规则。</p>
     *
     * @return 连接请求；null 表示既不连接也不报错（现状）
     */
    private LogonRequest resolveLogon() {
        if (logon != null) {
            return new LogonRequest(logon, false);
        }
        String fromEnvironment = System.getenv(LOGON_ENV);
        if (fromEnvironment == null || fromEnvironment.isBlank()) {
            return null;
        }
        return new LogonRequest(fromEnvironment, true);
    }

    /**
     * 一次连接请求：取值 + 它从哪来（命令行还是环境变量）。
     *
     * @param value           与 {@code --logon} 同格式的取值（三种 {@code _connect} 写法都认，
     *                        见 {@link org.slackerdb.dbcli.sql.SqlExecutor#connect(Map)}）
     * @param fromEnvironment true 表示来自 {@link #LOGON_ENV}
     */
    private record LogonRequest(String value, boolean fromEnvironment) {

        /** 出错时按来源报：使用者一眼能看出这次用的是命令行还是环境变量。 */
        String label() {
            return fromEnvironment ? LOGON_ENV : "--logon";
        }

        /** 多行取值会拼出多行脚本：两种来源共用这一条校验（见 {@code call()}）。 */
        boolean hasLineBreak() {
            return value.contains("\n") || value.contains("\r");
        }
    }

    /**
     * {@code dbcli … --daemon true --pidfile p}：拉起后台副本并等它把 pidfile 写出来。
     *
     * <p>父进程在这里**立即返回**（脚本在副本里跑）：打印的 pid 取自副本自己写的 pidfile，
     * 而不是 {@code cmd start}/{@code nohup} 那个中间 shell 的 pid。</p>
     *
     * @param stdinScript stdin 管道形态下的脚本文本（null 表示脚本来自 {@code --execute <file>}）
     * @return 已拉起 → 0；开发模式（不是从 jar 启动，无法脱离当前进程）→ null 表示"当前台继续跑"
     */
    private Integer startDaemon(String stdinScript, PrintWriter out, PrintWriter err) {
        AppDaemon.ParsedArguments parsed;
        try {
            // 保留选项的**原始顺序**：后台副本的命令行就是"用户写的这一份 + --daemon false"，
            // 不另拼一套（拼两套必然有一天不一致）
            parsed = AppDaemon.parse(spec.commandLine().getParseResult().originalArgs()
                    .toArray(new String[0]));
        } catch (IllegalArgumentException badArguments) {
            err.println(badArguments.getMessage());
            err.flush();
            return CliDaemon.EXIT_USAGE;
        }

        List<AppDaemon.Option> options = parsed.options();
        Path tempScript = null;
        if (stdinScript != null) {
            // 管道里的字节只能被读一次：父进程已经读进内存了，副本再去读只会拿到 EOF（静默跑空脚本）。
            // 因此把它落成一个临时脚本文件，让副本按 --execute <临时文件> 跑，并由副本跑完自己删
            // （管道形态下"用户没写过 --execute"，所以这里可能要新增这个选项）。
            try {
                tempScript = Files.createTempFile("dbcli-daemon-", ".sql");
                Files.writeString(tempScript, stdinScript, StandardCharsets.UTF_8);
            } catch (IOException cannotMaterialize) {
                err.println(CliDaemon.cannotStart(
                        "cannot materialize the stdin script: " + messageOf(cannotMaterialize)));
                err.flush();
                return CliDaemon.EXIT_ERROR;
            }
            options = withDaemonScriptTemp(options, tempScript);
        }

        boolean hasPidFile = pidFile != null && !pidFile.isBlank();
        Path pidFilePath = hasPidFile ? resolvePidFile(pidFile) : null;
        // 告诉副本"你是副本"：它跑完不删自己的 pidfile，父进程才总能读到那个 pid
        // （快脚本会在两次轮询之间写完又删掉，见 --daemon-copy）
        options = withDaemonCopy(options);
        try {
            CliDaemon.StartResult started = CliDaemon.startInBackground(Cli.class, options,
                    logFile, pidFile, pidFilePath);
            if (!started.background()) {
                // 开发模式：AppDaemon 已经打了提示，这里继续前台运行（--pidfile 仍会被写）。
                // 没有副本去消费那个临时脚本，所以由本进程删掉（否则等于每跑一次漏一个临时文件）。
                deleteQuietly(tempScript);
                return null;
            }
            if (!hasPidFile) {
                // 没给 --pidfile：后台确实起来了，但父进程拿不到它的 pid，也不必多打一行——
                // AppDaemon 已经报过"已在后台启动"，pid 的事交给使用者自己安排
                return 0;
            }
            if (started.pid() == null) {
                err.println(CliDaemon.pidFileNotWritten(pidFile, logFile));
                err.flush();
                return CliDaemon.EXIT_ERROR;
            }
            out.println(CliDaemon.started(started.pid()));
            out.flush();
            return 0;
        } catch (Exception startFailed) {
            err.println(CliDaemon.cannotStart(messageOf(startFailed)));
            err.flush();
            return CliDaemon.EXIT_ERROR;
        }
    }

    /**
     * 把副本的命令行替换为"跑这个临时脚本，跑完删掉它"。
     *
     * <p>{@code --execute} 已经存在时只换它的取值（位置不变），不存在时追加一对
     * （管道形态就是这样：用户没有写过 {@code --execute}，副本必须被明确告知脚本在哪）。</p>
     */
    static List<AppDaemon.Option> withDaemonScriptTemp(List<AppDaemon.Option> options,
                                                       Path tempScript) {
        List<AppDaemon.Option> rewritten = new ArrayList<>(options.size() + 2);
        boolean executeReplaced = false;
        for (AppDaemon.Option option : options) {
            if ("execute".equalsIgnoreCase(option.name())) {
                rewritten.add(new AppDaemon.Option(option.name(), tempScript.toString()));
                executeReplaced = true;
            } else {
                rewritten.add(option);
            }
        }
        if (!executeReplaced) {
            rewritten.add(new AppDaemon.Option("execute", tempScript.toString()));
        }
        rewritten.add(new AppDaemon.Option("daemon-script-temp", null));
        return rewritten;
    }

    /**
     * 给副本的命令行加上"我是副本"这个内部开关（见 {@link #daemonCopy}）。
     *
     * <p>与其它选项一样按原始顺序搬运，只是末尾多一个不带取值的开关；用户看不到它（hidden）。</p>
     */
    static List<AppDaemon.Option> withDaemonCopy(List<AppDaemon.Option> options) {
        List<AppDaemon.Option> rewritten = new ArrayList<>(options.size() + 1);
        rewritten.addAll(options);
        rewritten.add(new AppDaemon.Option("daemon-copy", null));
        return rewritten;
    }

    /** 删文件但不报错（临时文件的收尾不该改变结论）。 */
    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 临时目录里的文件删不掉不影响结论（下次系统清理）
        }
    }

    /** {@code --pidfile} 的相对路径基准是**进程当前目录**（控制面文件，不跟脚本走）。 */
    private static Path resolvePidFile(String pidFile) {
        return resolve(Path.of("").toAbsolutePath(), pidFile);
    }

    /**
     * 收尾：停 tee（保证 log 就是"这次运行的全部数据输出"），然后按 {@code --ref} 的三种情形收口：
     * 没有 ref → 什么都不做；ref 不存在 → **报错**（指错文件就是错误，没有"待录制"这回事）；
     * ref 存在 → 比对（严格口径 + 脚本里声明的 mask/skip），不一致时给"重录"提示。
     *
     * <p>退出码规则：脚本自己的退出码优先；脚本为 0 而比对不一致、或 ref 文件不存在时给 1
     * （与 {@code _COMPARE} 一致）。stdout 全程不受影响。</p>
     */
    private int finish(int runCode, ScriptOutput scriptOutput, String scriptName,
                       Path actualOutputFile, Path referenceFile, Path reportDirectory,
                       ScriptContext context, PrintWriter err) {
        if (referenceFile == null) {
            scriptOutput.stopTee();
            return runCode;
        }
        // 与作业的 reference 收尾共用同一份实现（见 ReferenceCheck）：退出码规则只有一处
        ReferenceCheck.Outcome outcome = ReferenceCheck.finish(runCode, scriptName, actualOutputFile,
                referenceFile, reportDirectory, scriptOutput,
                context.dispatcher().compareRules(), err::println);
        err.flush();
        return outcome.exitCode();
    }

    /** 相对路径按给定的基准目录解析（命令行选项传的是**进程当前目录**）。 */
    private static Path resolve(Path workingDirectory, String path) {
        return PathArguments.resolve(workingDirectory, path);
    }

    /**
     * 进入交互式控制台。
     *
     * <p>取行来源：注入了就用注入的（测试；所有权属于调用方，不关闭），否则建一个 JLine 终端
     * （由本方法负责关闭）。</p>
     */
    private int runConsole(ScriptRunner runner, ScriptContext context, PrintWriter err) {
        if (lineSource != null) {
            return new InteractiveConsole(runner, context, lineSource, err).run();
        }
        try (LineSource jline = JLineLineSource.system()) {
            return new InteractiveConsole(runner, context, jline, err).run();
        } catch (IOException cannotStart) {
            err.println("Cannot start the interactive console: " + messageOf(cannotStart));
            err.flush();
            return 1;
        }
    }

    /**
     * 按给定顺序执行 {@code --profile <script>}（在主脚本/控制台之前）。
     *
     * <p>任一个 profile **出错**（或里面有 {@code _EXIT}）就**不再继续**：profile 是"环境准备"，
     * 准备失败还往下跑只会把错误放大成更难解释的失败。注意"出错"不等于"进程失败"：
     * 退出码仍由 {@link ScriptContext#finalExitCode()} 决定（默认 0）。</p>
     *
     * @return {@code null} 表示全部跑完、可以继续；否则是要作为退出码返回的值（profile 文件不存在 ⇒ 1，
     *         脚本内出错 ⇒ {@link ScriptContext#finalExitCode()}，通常就是 0）
     */
    private Integer runProfiles(ScriptRunner runner, ScriptContext context, PrintWriter err,
                                Path processDirectory) {
        for (String profile : profiles) {
            Path resolved;
            try {
                resolved = PathArguments.resolve(processDirectory, profile);
            } catch (PathArguments.InvalidPathException invalidPath) {
                err.println(invalidPath.getMessage());
                err.flush();
                return 1;
            }
            if (!Files.isRegularFile(resolved)) {
                err.println("Profile script [" + profile + "] does not exist.");
                err.flush();
                return 1;
            }
            int errorsBefore = context.errorCount();
            runner.runFile(resolved, List.of());
            if (context.exitRequested() || context.errorCount() > errorsBefore) {
                return context.finalExitCode();
            }
        }
        return null;
    }

    /** {@code --var NAME=VALUE}（可重复）→ 变量表；格式不对给 picocli 的参数错误（stderr + 退出 2）。 */
    private Map<String, String> parseVariables() {
        Map<String, String> table = new LinkedHashMap<>();
        for (String variable : variables) {
            int separator = variable.indexOf('=');
            if (separator <= 0) {
                throw new CommandLine.ParameterException(spec.commandLine(),
                        "Invalid --var [" + variable + "]: expected NAME=VALUE.");
            }
            table.put(variable.substring(0, separator), variable.substring(separator + 1));
        }
        return table;
    }

    /**
     * 非交互 stdin 且**已经有内容**时按脚本执行（{@code type a.sql | dbcli}）。
     *
     * <p>为什么要用"轮询 + 超时"来探测，而不是直接读或查 {@code available()}：</p>
     * <ul>
     *   <li>直接读会在交互终端上**挂住**，而"无参数时打印用法帮助"是既有行为（{@code CliSmokeTest} 锁着）；</li>
     *   <li>{@code available()} 在 Windows 的控制台/某些管道句柄上实测要**数秒**才返回
     *       （本机 surefire 下 8.7 秒），会让"跑个帮助"变成等好几秒；</li>
     *   <li>因此在有限窗口内轮询 {@code available()}：有数据就继续读完（管道形态）；
     *       没有数据/超时 → 视为没有脚本。既不阻塞也不留线程。</li>
     * </ul>
     *
     * @return 脚本文本；"没有脚本"返回 null（并保持 {@link #stdinError} 为 null）
     */
    private String readStdinIfAvailable() {
        if (System.console() != null) {
            // 交互式终端：不把键盘输入当脚本（无参数时应该进控制台）
            return null;
        }
        if (!stdinHasData()) {
            return null;
        }
        try {
            return decodeStdin(stdin.readAllBytes());
        } catch (IOException readFailed) {
            reportStdinError("Cannot read the script from stdin: " + messageOf(readFailed));
            return null;
        }
    }

    /**
     * 在有限窗口内轮询"stdin 是否已有数据"。
     *
     * <p><b>为什么不起一个后台线程去阻塞读</b>：{@code System.in} 的阻塞读**无法被中断**
     * （{@code FileInputStream} 的 native 读不响应 interrupt）。用"守护线程读首字节"的写法会让该线程
     * 永久挂在 {@code read} 上：测试 JVM（surefire fork）里 stdin 是永不 EOF 的管道，于是每个进程都留下一条
     * 卡住的线程，surefire 还会因此判定 fork 退出超时而写 dump 并强杀。轮询 {@code available()}
     * 既不阻塞也不留线程，代价是"stdin 已打开但暂无数据"时最多等一个窗口（{@value #STDIN_PROBE_TIMEOUT_MILLIS} 毫秒）。</p>
     */
    private boolean stdinHasData() {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(STDIN_PROBE_TIMEOUT_MILLIS);
        while (System.nanoTime() < deadline) {
            try {
                if (stdin.available() > 0) {
                    return true;
                }
            } catch (IOException unavailable) {
                return false;
            }
            try {
                Thread.sleep(STDIN_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 阻塞读 stdin（{@code --execute -} 是显式要求，等 EOF 是正确行为）。 */
    private String readAllStdin() {
        try {
            return decodeStdin(stdin.readAllBytes());
        } catch (IOException readFailed) {
            reportStdinError("Cannot read the script from stdin: " + readFailed.getMessage());
            return null;
        }
    }

    /** 严格 UTF-8 解码；失败时报可读错误并返回 null。 */
    private String decodeStdin(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notUtf8) {
            reportStdinError("Cannot read the script from stdin: dbcli only supports UTF-8: "
                    + notUtf8.getMessage());
            return null;
        }
    }

    private void reportStdinError(String message) {
        stdinError = message;
        if (silent) {
            // --silent：连"读不了 stdin"这种 CLI 级错误也不打印，只剩退出码
            return;
        }
        spec.commandLine().getErr().println(message);
        spec.commandLine().getErr().flush();
    }


    private static String messageOf(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message == null || message.isBlank() ? String.valueOf(failure) : message;
    }
}
