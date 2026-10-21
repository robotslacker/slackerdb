package org.slackerdb.dbcli.engine;

import org.slackerdb.dbcli.assertion.ResultModel;
import org.slackerdb.dbcli.common.CommandOutcome;
import org.slackerdb.dbcli.engine.command.CommandCall;
import org.slackerdb.dbcli.engine.command.CommandCatalog;
import org.slackerdb.dbcli.engine.command.CommandHost;
import org.slackerdb.dbcli.engine.command.ScriptCommand;
import org.slackerdb.dbcli.compare.CompareReporter;
import org.slackerdb.dbcli.script.SqlAnalyzer;
import org.slackerdb.dbcli.sql.SqlExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 脚本执行引擎：**逐行累积 → 分析 → 分派 → 输出**，一条直线。
 *
 * <h2>语句怎么被切出来（唯一的控制流判据）</h2>
 * <p>把累积缓冲交给 {@link SqlAnalyzer}；{@code isFinished=false} 表示"语句还没写完"
 * （{@code select 1} 缺 {@code ;}、{@code create procedure …} 缺行首 {@code /}、
 * {@code _echo} 缺 {@code echo off} 都是这一类），于是继续读下一行——控制台的续行提示
 * {@code "   > "} 就是这个判据；{@code isFinished=true} 才执行。</p>
 *
 * <h2>解析层认不下来怎么办（dbcli 不解释语法）</h2>
 * <p>分析器认不下来的输入**绝不**把解析器的位置信息拿给使用者：{@code line…:… …}、
 * {@code missing SQL_END}、{@code extraneous input} 这一类文案一律不打印。
 * 分流看**首词**与**"是不是还没写完"**（{@link SqlAnalyzer.Result#incompleteInput()}：报错是否都落在
 * 输入末尾），见 {@link #handleUnparsable}：</p>
 * <ul>
 *   <li><b>{@code _} 开头、还没写完</b>（{@code _echo t} 缺 {@code echo off}、光秃秃的 {@code _help}）→
 *       继续读下一行（控制台给续行提示 {@code "   > "}）；脚本读到 EOF 还没写完 →
 *       一条 {@code Incomplete command [...]}（stdout、清错误计数），**不送库**；</li>
 *   <li><b>{@code _} 开头、写错了/不认识</b>（{@code _bogus}、{@code _use sql}）→
 *       {@code Unknown command [...]}（命令层兜底文案，**不送库**）；</li>
 *   <li><b>其余一切</b>（真正的 SQL：{@code selec 1}、{@code select * frm t}）→ 把**原始文本**原样交给
 *       执行层：成功则照常输出，失败就打印**数据库返回的错误原文**（该多行就多行），未连接时就是
 *       {@code Not connected.}；</li>
 *   <li>脚本读到 EOF 仍没写完的那一段（{@code select 1,}）走同一条分流。</li>
 * </ul>
 *
 * <p><b>不送库的两类错误</b>（它们不是"语法看不懂"，而是命令自己的判定，保持原样）：
 * 访问器（IR 层）给出的可读业务错误（{@code _ssh sftp chmod /f abc} → {@code Invalid SFTP file mode [abc]…}），
 * 以及 {@code _connect} 的 {@code Invalid connection string [...]}。</p>
 *
 * <p><b>一行多条语句</b>（{@code select 1; select 2;}）由 {@link StatementSplitter} 按顶层
 * {@code ;} 给出切分点（引号内 / 注释内 / {@code {% %}} 内的 {@code ;} 不算），
 * 本类只负责"把一段候选文本当作完整语句处理一次"；切分点的取舍与缓冲推进在
 * {@link ScriptSession} 里（批处理与控制台共用同一段状态机，因此两种跑法行为一致）。</p>
 *
 * <p>空行与以 {@code --}/{@code #} 开头的行在**缓冲为空**时被跳过（注释只在语句之外生效，
 * 语句内部的行原样喂给分析器）。</p>
 *
 * <h2>输出（{@code --logfile} / {@code --reference}）</h2>
 * <p>本类**不**自己写任何旁路文件：所有数据/状态行都走 {@link ScriptOutput}，
 * 命令行层在那里挂 tee（{@code --logfile} = 把与 stdout 相同的内容写进文件；
 * {@code --reference} = 拿这份字节做 golden 比对）。因此"跑一次脚本得几份文件"这件事
 * 只有一处实现，控制台与批处理也天然一致。</p>
 *
 * <h2>错误、{@code _WHENEVER} 与退出码（§3.7）</h2>
 * <ul>
 *   <li>错误 = 可读消息（打到 stdout，同时也进 {@code --logfile}/ref 日志）+ 错误计数 1 次；默认行为是 <b>继续</b>；</li>
 *   <li>{@code _WHENEVER ERROR EXIT n}：第一次出错就**立刻**结束整次运行并返回 {@code n}；</li>
 *   <li>{@code _WHENEVER ERROR CONTINUE}：显式回到默认行为；</li>
 *   <li>{@code _EXIT n}/{@code _QUIT n}：立刻以 {@code n} 结束；</li>
 *   <li>最终退出码由 {@link ScriptContext#finalExitCode()} 统一计算：显式 {@code _EXIT n}（含 0）压过一切，
 *       否则"有错误 → 1"（这条同时覆盖 {@code _COMPARE} 失败 1 与断言失败），否则 0；</li>
 *   <li>未捕获异常由 {@code Main} 收敛成 255（本类内部不放任异常外溢）。</li>
 * </ul>
 *
 * <h2>{@code _COMPARE} 失败怎么算</h2>
 * <p>{@code CompareCommandDispatcher} 的契约里"比对失败"是**正常结果**（状态行
 * {@code Compare failed.}、{@code errorCode=0}）。
 * 而退出码纪律要求"{@code _COMPARE} 失败 1"，因此引擎按**冻结文案**识别该状态行并计一次错误：
 * 它不改变比对本身的输出，只影响 {@code _WHENEVER ERROR} 与退出码。</p>
 *
 * <h2>作业线程里的协作式停止（{@link StopSignal}）</h2>
 * <p>作业线程用的是带信号的构造器（见 {@code ScriptJobExecutor}）：{@code stopRequested()} 为真时，
 * 引擎在下一条语句之前抛 {@link ScriptStoppedException}，并且 {@code _SLEEP} 分段睡、{@code _HOST}
 * 分段等（停止请求一到就销毁本地子进程）。JDBC 长语句由 {@code JdbcSqlExecutor#cancelCurrentStatement()}
 * 通过作业的取消回调取消。父脚本与控制台拿的是 {@link StopSignal#NEVER}，因此以上分支对它们不生效。</p>
 */
public final class ScriptRunner implements CommandHost {

    /** {@code _START} 的递归深度上限（防止脚本互相调用把栈撑爆）。 */
    public static final int MAX_SCRIPT_DEPTH = 16;

    /** SQL 语句的 IR {@code name} 集合。 */
    private static final Set<String> SQL_STATEMENTS = Set.of(
            "SELECT", "CREATE", "INSERT", "UPDATE", "DELETE", "DROP", "REPLACE",
            "DECLARE", "BEGIN", "PROCEDURE", "COMMIT", "ROLLBACK",
            // 通用 SQL（WITH / ALTER / TRUNCATE / VACUUM / ANALYZE / EXPLAIN / SET / SHOW /
            // GRANT / REVOKE / CALL / COMMENT / REFRESH / COPY / DO）统一用这个 IR 名
            "SQL");

    /**
     * {@code COPY … FROM STDIN} / {@code COPY … TO STDOUT}：数据流要占用 COPY 协议通道，
     * 本客户端没有这条通道，因此执行前就拒绝（否则服务端会等数据，客户端卡在 execute()）。
     */
    private static final Pattern COPY_STREAM =
            Pattern.compile("(?is)\\bcopy\\b.*?\\b(from\\s+stdin|to\\s+stdout)\\b");

    /** {@code _SPOOL OFF} 里的 {@code off}：按用户本意当"停 spool"。 */
    private static final String STOP_SPOOL_WORD = "off";

    /** 作业被停止时，{@code _SLEEP} 与 {@code _HOST} 的等待分片（毫秒）：停止请求最多晚这么久被看见。 */
    private static final long STOP_POLL_MILLIS = 50L;

    private final ScriptContext context;

    /** 协作式停止信号（作业线程用；父脚本与控制台是 {@link StopSignal#NEVER}）。 */
    private final StopSignal stopSignal;

    /**
     * 这条语句的 elapsed 行是否已经打过。
     *
     * <p>SQL 语句在 {@link #executeSqlText} 里按"真正执行那段"计一次时（更贴近服务端耗时），
     * 收尾的 {@link #reportStatementEnd} 因此不能再补一条——否则 {@code _SET TIMING ON} 会让每条
     * SQL 打出两行 {@code ... elapsed.}。</p>
     */
    private boolean elapsedReported;

    public ScriptRunner(ScriptContext context) {
        this(context, StopSignal.NEVER);
    }

    /**
     * @param stopSignal 停止信号：{@code stopRequested()} 为真时不再执行下一条语句，并尽快唤醒
     *                   {@code _SLEEP}/{@code _HOST}（见 {@link StopSignal}）
     */
    public ScriptRunner(ScriptContext context, StopSignal stopSignal) {
        this.context = context;
        this.stopSignal = stopSignal == null ? StopSignal.NEVER : stopSignal;
    }

    // ------------------------------------------------------------------ CommandHost

    @Override
    public ScriptContext context() {
        return context;
    }

    @Override
    public boolean stopRequested() {
        return stopSignal.stopRequested();
    }

    @Override
    public int runChildScript(Path scriptFile, List<String> argv) {
        return runFile(scriptFile, argv);
    }
    // ---------------------------------------------------------------------- 入口

    /**
     * 执行一个脚本文件。
     *
     * @param scriptFile 脚本文件
     * @param argv       {@code _START} 传进来的参数（顶层运行为空表），可用 {@code ${ARGV1}} 取
     * @return 进程退出码（§3.7）
     */
    public int runFile(Path scriptFile, List<String> argv) {
        Path file = scriptFile == null ? null : scriptFile.toAbsolutePath().normalize();
        if (file == null || !Files.isRegularFile(file)) {
            reportError("Script file [" + scriptFile + "] does not exist.");
            return context.finalExitCode();
        }
        String text;
        try {
            text = Utf8Text.read(file);
        } catch (IOException readFailed) {
            reportError("Cannot read script file [" + file + "]: " + readFailed.getMessage());
            return context.finalExitCode();
        }
        // 显示名用文件名而不是全路径：错误定位形如 `a.sql:3: …`，全路径会把前缀撑得看不清
        String displayName = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        return runScript(text, displayName, file, argv);
    }

    /**
     * 执行一段脚本文本（stdin 管道、{@code --logon} 的单行脚本、测试都用它）。
     *
     * @param scriptName 显示名（错误文案里用）
     */
    public int runText(String text, String scriptName, List<String> argv) {
        return runScript(text, scriptName, null, argv);
    }

    /**
     * 打开一个"逐行喂入"的会话（交互式控制台与批处理共用）。
     *
     * <p><b>嵌套深度检查不在这里</b>：{@code _START} 递归一律走 {@link #runFile}/{@link #runText}
     * （那里检查深度），控制台始终在深度 0 打开会话。</p>
     *
     * @param scriptName 显示名（错误文案里用）
     * @param scriptFile 脚本文件（{@code _START} 的相对路径回退用；控制台/stdin 为 null）
     */
    public ScriptSession openSession(String scriptName, Path scriptFile, List<String> argv) {
        return openSession(scriptName, scriptFile, argv, true);
    }

    /**
     * 打开一个"逐行喂入"的会话，并指定它是不是脚本会话。
     *
     * @param scriptSession {@code false} 表示这是交互式控制台会话（它不该回显输入）
     */
    public ScriptSession openSession(String scriptName, Path scriptFile, List<String> argv,
                                     boolean scriptSession) {
        return new ScriptSession(this, context, scriptName, scriptFile, argv, scriptSession);
    }

    private int runScript(String text, String scriptName, Path scriptFile, List<String> argv) {
        checkStopRequested();
        boolean topLevel = context.depth() == 0;
        if (context.depth() >= MAX_SCRIPT_DEPTH) {
            reportError("_START nesting is too deep (limit " + MAX_SCRIPT_DEPTH + "): ["
                    + scriptName + "]");
            return context.finalExitCode();
        }
        int errorsBefore = context.errorCount();
        int exitCodeBefore = context.exitCode();
        try (ScriptSession session = openSession(scriptName, scriptFile, argv)) {
            session.feedText(text);
            session.finish();
        }
        if (topLevel) {
            // 只在整个运行的**最外层**收尾：_START 子脚本返回时作业可能还要被父脚本 WAIT
            reportUnacknowledgedJobFailures();
        }
        if (topLevel) {
            return context.finalExitCode();
        }
        // 子脚本（_START）：返回**它自己的**有效退出码——显式 _EXIT 优先，否则"这个子脚本里出过错就是 1"。
        // 父脚本的错误计数是共享的（子脚本报错同样让整次运行失败），但 RUN.EXITCODE 需要的是"子脚本的"。
        int explicit = context.exitCode();
        if (explicit != exitCodeBefore) {
            return explicit;
        }
        return context.errorCount() > errorsBefore ? 1 : 0;
    }

    /**
     * 收尾补报：把"跑失败了但脚本一次都没检查过"的作业报成错误。
     *
     * <p>为什么需要这一步：脚本可能启动作业之后直接结束（漏写 {@code _JOB WAIT}，或者以为
     * 作业失败不影响结论）。那种情况下没有任何一处会把失败反映到退出码上，CI 就会把一次失败
     * 判成通过。这里在顶层脚本结束时统一收口：每个没被认领的失败作业一条错误（stdout、计错误数），
     * 然后认领它们，避免同一件事被算两次。</p>
     */
    private void reportUnacknowledgedJobFailures() {
        if (!context.jobFailureReporting()) {
            // 作业线程：汇总归父脚本，别把兄弟作业的失败写进自己的 .err 并替它认领
            // （见 ScriptContext.jobFailureReporting）
            return;
        }
        List<String> messages = context.dispatcher().unacknowledgedJobFailureMessages();
        if (messages.isEmpty()) {
            return;
        }
        for (String message : messages) {
            reportError(message);
        }
        context.dispatcher().acknowledgeJobFailures();
    }

    // ---------------------------------------------------------------- 语句累积/分派

    /**
     * 把一段候选文本当作**一条**语句处理：变量替换 → 分析 → 执行（或交给执行层）。
     *
     * <p>这是"语句 → 结果"的唯一入口，也是 {@link ScriptSession} 判断"前缀是否可消费"的判据。</p>
     *
     * <p>分析器给不出可执行的 IR 时（{@code parsedObject==null}，或词法/语法监听器报过错），
     * 这条候选就按"解析失败"处理：绝不打印解析器文案，而是走 {@link #handleUnparsable}。
     * 而**访问器**给出的可读业务错误（{@code parserError=false}，如 SFTP 权限位写错）保持原样：
     * 它不是解析层文案，照旧一行进 stdout。</p>
     *
     * @param rawCandidate 候选语句原文（可能只是累积缓冲的前缀）
     * @return {@code true} 表示这条候选**已定性**（已执行或已报错，调用方可从缓冲消费掉）；
     *         {@code false} 表示"还没写完"，调用方应继续累积或尝试更长的前缀
     */
    boolean executeStatement(String rawCandidate) {
        long startedAt = System.nanoTime();
        elapsedReported = false;
        boolean handled = handleStatement(rawCandidate);
        if (handled) {
            // `_SET TIMING` / `_SET TIME`：这条语句跑完之后补一行（打到 stdout，见 reportStatementEnd）
            reportStatementEnd(startedAt);
        }
        return handled;
    }

    /** {@link #executeStatement} 的本体：判定 + 分派，返回"这条候选是否已定性"。 */
    private boolean handleStatement(String rawCandidate) {
        // 作业被请求停止（_JOB ABORT/SHUTDOWN/管理器关闭）时，下一条语句不再执行：让作业线程尽快收尾
        checkStopRequested();
        String resolved;
        try {
            resolved = context.variables().resolve(rawCandidate);
        } catch (VariableExpressionException variableError) {
            // 替换目标非法（含表达式/函数调用）：该语句不执行，按错误语句处理
            reportError(variableError.getMessage());
            return true;
        }
        SqlAnalyzer.Result result = SqlAnalyzer.analyze(resolved);
        if (!result.isFinished()) {
            // 语句还没写完（缺 ';' / 缺行首 '/' / _echo 缺 echo off…）：继续读下一行
            return false;
        }
        Map<String, Object> ir = result.parsedObject();
        if (!turnsEchoOff(ir)) {
            // `_SET ECHO OFF` 这一行自己不回显：这样"关掉 echo 之后日志里只剩结果"从那一行起就是真的
            echoInput(rawCandidate);
        }
        if (ir != null && result.errorCode() == 0) {
            execute(ir);
            return true;
        }
        if (ir != null && !result.parserError() && reportReadableIrError(resolved, ir, result.errorMsg())) {
            // 访问器的可读错误已经报过（不是解析层文案，因此不能送去数据库）
            return true;
        }
        return handleUnparsable(resolved, result.incompleteInput());
    }

    /**
     * 访问器（IR 层）给出的 {@code errorCode != 0}：{@code errorMsg} 是一条**可读**提示，
     * 按旧口径原样报一行即可（{@code _connect} 仍统一成"无效连接串"）。
     *
     * @param statement 使用者写的那条命令（{@code _connect} 的原文回显用）
     * @return {@code false} 表示没有可报的消息（交给调用方按"解析失败"处理）
     */
    private boolean reportReadableIrError(String statement, Map<String, Object> ir, String errorMsg) {
        if (errorMsg == null || errorMsg.isBlank()) {
            return false;
        }
        if ("CONNECT".equalsIgnoreCase(String.valueOf(ir.get("name")))) {
            reportError(SqlExecutor.invalidConnectionStringMessage(connectStringOf(statement)));
            return true;
        }
        reportError(errorMsg);
        return true;
    }

    /**
     * 分析器**没有**认下这条命令时的唯一出口（dbcli 不解释语法）。
     *
     * <p>分流只看**首词**与"是不是还没写完"（{@code incomplete}，来自
     * {@link SqlAnalyzer.Result#incompleteInput()}：报错是否都落在输入末尾）：</p>
     * <ol>
     *   <li>{@code _connect …}：命令名认得出来、但取值不成句 → 沿用
     *       {@code Invalid connection string [...]}（见 {@link SqlExecutor#invalidConnectionStringMessage}）；</li>
     *   <li>其余以 {@code _} 开头、**还没写完**的（{@code _echo t} 缺 {@code echo off}、光秃秃的
     *       {@code _help}）→ 返回 {@code false}：继续读下一行（控制台给续行提示）；
     *       脚本读到 EOF 还没写完 → {@link #finishPending} 报一条 {@code Incomplete command [...]}；</li>
     *   <li>其余以 {@code _} 开头、**写错了/不认识**的（{@code _bogus}、{@code _use sql}）→
     *       {@code Unknown command [...]}（命令层兜底文案，**不送库**）；</li>
     *   <li>其它一切（真正的 SQL）→ 把**原始文本**原样交给执行层，成功照常输出、失败打印数据库的错误。</li>
     * </ol>
     *
     * @return {@code true} 表示这条命令已定性（已执行或已报错，调用方可从缓冲消费掉）；
     *         {@code false} 表示"还没写完"，调用方应继续累积
     */
    private boolean handleUnparsable(String text, boolean incomplete) {
        String firstWord = firstWord(text);
        if (firstWord != null && "_connect".equalsIgnoreCase(firstWord)) {
            // 命令名认得出来、但后面的取值不成句：仍然是"无效连接串"，不是未知命令
            reportError(SqlExecutor.invalidConnectionStringMessage(connectStringOf(text)));
            return true;
        }
        if (firstWord != null && firstWord.startsWith("_")) {
            if (incomplete) {
                return false;
            }
            reportError("Unknown command [" + firstWord + "]. Run _HELP for the command list.");
            return true;
        }
        executeRaw(text);
        return true;
    }

    /**
     * 输入结束时缓冲里还剩一段没写完的文本：按"解析失败"处理（分流规则同 {@link #handleUnparsable}）。
     *
     * <p>与 {@link #executeStatement} 的区别只有两点：变量替换的时机（这里的文本还没替换过），
     * 以及**此时已经没有下一行了** —— 所以 {@code _} 命令不再返回"继续累积"，而是如实报一条
     * {@code Incomplete command [...]}（不打印任何解析器文案）；SQL 仍然送库。</p>
     */
    void finishPending(String raw) {
        long startedAt = System.nanoTime();
        elapsedReported = false;
        handlePending(raw);
        reportStatementEnd(startedAt);
    }

    /** {@link #finishPending} 的本体。 */
    private void handlePending(String raw) {
        String resolved;
        try {
            resolved = context.variables().resolve(raw);
        } catch (VariableExpressionException variableError) {
            reportError(variableError.getMessage());
            return;
        }
        echoInput(raw);
        String firstWord = firstWord(resolved);
        if (firstWord != null && "_connect".equalsIgnoreCase(firstWord)) {
            reportError(SqlExecutor.invalidConnectionStringMessage(connectStringOf(resolved)));
            return;
        }
        if (firstWord != null && firstWord.startsWith("_")) {
            reportError("Incomplete command [" + firstWord + "]: the command is not finished yet.");
            return;
        }
        executeRaw(resolved);
    }

    /**
     * 把一段**原始文本**原样交给执行层。
     *
     * <p>关键点：这里**不再过分析器** —— 否则又回到"解析错误"那条老路。变量替换已经做过，
     * 送出去的就是使用者写的那句话（只去掉首尾空白/行尾换行，内部换行原样保留）。</p>
     *
     * <p>成功则像普通 SQL 一样输出结果；失败则打印执行层给出的那一行错误
     * （{@link SqlExecutor#lastError()}；未连接时就是 {@code Not connected.}）。</p>
     */
    private void executeRaw(String statement) {
        executeSqlText(statement == null ? "" : statement.strip());
    }

    /**
     * 这条语句是不是 {@code _SET ECHO OFF}（或 {@code _SET ECHO} 之类的变体）。
     *
     * <p>用途：它自己**不**回显——否则"关掉 echo 之后日志里只剩结果"永远差这一行。</p>
     */
    private static boolean turnsEchoOff(Map<String, Object> ir) {
        if (ir == null || !"SET".equalsIgnoreCase(String.valueOf(ir.get("name")))) {
            return false;
        }
        Object option = ir.get("optionName");
        Object value = ir.get("optionValue");
        return option != null && "echo".equalsIgnoreCase(String.valueOf(option))
                && value != null && "off".equalsIgnoreCase(String.valueOf(value).strip());
    }

    /**
     * {@code _SET ECHO}（默认 on）：把这条 SQL/命令的**原文**写进日志（{@code --logfile}/{@code --ref}）。
     *
     * <p>目标是**可复现、可回看的运行记录**：日志里既有"跑了什么"也有"出来什么"，所以
     * {@code _connect} 与 {@code _ssh} 同样回显（连到哪台机器、用什么参数，必须能在日志里看到；
     * 这里不考虑口令泄漏——想藏就别写进脚本）。</p>
     *
     * <p><b>只管脚本会话</b>（{@code --execute}/{@code --profile}/{@code _START}/作业脚本）：它们没有
     * "屏幕"，所以日志里补上语句原文。**控制台**不用这条路径——它的日志是
     * {@link org.slackerdb.dbcli.engine.ScriptOutput#printTranscript 转录}（横幅 + 提示符 + 敲进去的行 +
     * 输出 + 错误），比"只补语句原文"更忠实；两条路径都只写日志、不改 stdout。</p>
     *
     * <p>例外是 {@code _ECHO} 文本块：它的正文本身就是数据，回显等于把同一批数据打两遍；
     * 以及 {@code _SET ECHO OFF} 自己（见 {@link #turnsEchoOff}）。</p>
     */
    private void echoInput(String raw) {
        if (raw == null || raw.isBlank() || !context.isScriptSession()) {
            return;
        }
        String first = firstWord(raw);
        if (first != null && "_echo".equalsIgnoreCase(first)) {
            return;
        }
        // 一行多条语句时，第二条候选会带着分隔符后面的空格（`select 1; select 2;` → ` select 2;`）：
        // 回显按使用者写的语句回显，不要那道分隔用的空白
        context.output().printInput(raw.strip());
    }

    /**
     * 按 IR 的 {@code name} 分派（SQL 语句 → 执行层；命令 → 本引擎或既有分派器）。
     */
    private void execute(Map<String, Object> ir) {
        String name = ir.get("name") == null ? "" : String.valueOf(ir.get("name")).toUpperCase(Locale.ROOT);
        if (SQL_STATEMENTS.contains(name)) {
            executeSql(ir);
        } else if (CommandDispatcher.supports(name)) {
            dispatch(ir);
        } else {
            // 命令实现登记在 CommandCatalog（一命令一类）；这里只做"找人/报不认识"
            ScriptCommand command = CommandCatalog.find(name);
            if (command == null) {
                reportError("Unknown command [" + name + "]. Run _HELP for the command list.");
                return;
            }
            command.execute(new CommandCall(context, this), ir);
        }
    }
    // ------------------------------------------------------------------- SQL 语句

    private void executeSql(Map<String, Object> ir) {
        executeSqlText(ir.get("statement") == null ? "" : String.valueOf(ir.get("statement")));
    }

    /**
     * 一段 SQL 文本的**唯一**执行/输出路径：IR 解析出来的语句与"原样送库"的原始文本都走这里。
     *
     * <p>成功 → 表格 + 状态行（{@code _SET FEEDBACK OFF} 时只有表格）；失败 → 执行层给出的那一行
     * 错误进 stdout 并计一次错误（未连接时就是 {@code Not connected.}）。</p>
     */
    private void executeSqlText(String statement) {
        // COPY 的 STDIN/STDOUT 数据流要在协议层来回送数据（COPY_IN/COPY_OUT），本客户端没有这条数据通道。
        // 这种语句一旦发出去，服务端会进入等待数据的状态，客户端就卡在 execute() 上；
        // 因此在这里明确拒绝并给出可读错误，而不是把一条会挂住的语句发给服务端。
        if (COPY_STREAM.matcher(statement).find()) {
            reportError("COPY ... FROM STDIN / TO STDOUT is not supported: the data stream needs the "
                    + "COPY protocol; use COPY ... FROM '<file>' with the file on the server side.");
            return;
        }
        long startedAt = System.nanoTime();
        // 一条语句可能产生多个结果（CALL/DO）：逐个输出，最后那个结果成为 _ASSERT 看到的"最近结果"
        List<ResultModel> models = context.sqlExecutor().executeAll(statement);
        long elapsedNanos = System.nanoTime() - startedAt;
        ResultModel last = null;
        for (ResultModel model : models) {
            last = model;
            if (model.errorCode() != 0) {
                context.setLastResult(model);
                reportError(context.sqlExecutor().lastError());
                reportSqlTiming(elapsedNanos);
                return;
            }
            if (!model.headers().isEmpty()) {
                context.output().printTable(model.headers(), model.columnTypes(), model.rows());
            }
            if (context.feedbackOn()) {
                context.output().printStatus(model.status());
            }
        }
        if (last != null) {
            context.setLastResult(last);
        }
        reportSqlTiming(elapsedNanos);
    }

    // ------------------------------------------------------------ 时机与耗时（诊断）

    /** {@code Current time:} 的固定格式（毫秒三位，本地时区）。 */
    private static final DateTimeFormatter CURRENT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    /**
     * 一条语句跑完之后补的诊断行：{@code _SET TIMING} 的耗时与 {@code _SET TIME} 的系统时间。
     *
     * <p><b>走 stdout（诊断行）</b>：与数据/状态行同一条通道，因此也进 {@code --logfile}/{@code --ref} 的 log
     * 与 {@code _SPOOL}；{@code --silent} 时静默。代价是它们每次都不同，要逐字节比对就保持默认的 OFF。</p>
     *
     * <p>{@code elapsed} 一行只打一次：SQL 语句已经在 {@link #executeSqlText} 里打过（见
     * {@link #elapsedReported}），这里只补它没打的那种。</p>
     */
    private void reportStatementEnd(long startedAt) {
        if (!elapsedReported) {
            reportSqlTiming(System.nanoTime() - startedAt);
        }
        if (context.timeOn()) {
            context.output().printDiagnostic("Current time: "
                    + LocalDateTime.now().format(CURRENT_TIME_FORMAT));
        }
    }

    /**
     * {@code _SET TIMING ON}：这条语句的耗时，格式 {@code 3 minutes 25 seconds 222 ms elapsed.}
     * （三段总是都给，便于 grep 与逐字节比对）。
     */
    private void reportSqlTiming(long elapsedNanos) {
        if (!context.timingOn()) {
            return;
        }
        // 记住"这条语句已经有过 elapsed 行"：收尾时不再补第二行（见 reportStatementEnd）
        elapsedReported = true;
        long totalMillis = elapsedNanos / 1_000_000L;
        context.output().printDiagnostic(String.format(Locale.ROOT,
                "%d minutes %d seconds %d ms elapsed.",
                totalMillis / 60_000L, (totalMillis / 1000L) % 60L, totalMillis % 1000L));
    }

    // ------------------------------------------------------------------ 既有分派器

    private void dispatch(Map<String, Object> ir) {
        for (CommandOutcome outcome : context.dispatcher().dispatch(ir)) {
            if (outcome instanceof CommandOutcome.Failure failure) {
                // "同时是状态行与失败"：状态行已经在 stdout 上（输出契约），错误只**计数**，
                // 不再打印第二遍——stdout 同时也是错误通道，重复 printError 会让同一句话出现两次
                // （_JOB WAIT 等到的失败作业、COMPARE 的 Compare failed. 都走这条）
                if (failure.text() != null) {
                    countFailure(failure.text());
                }
            } else if (outcome instanceof CommandOutcome.Status status) {
                if (status.text() != null) {
                    if (CompareReporter.STATUS_FAILED.equals(status.text())) {
                        // 见类注释：比对失败是"正常结果"，但按 §3.7 要计入错误数与 _WHENEVER
                        countFailure(status.text());
                    } else {
                        context.output().printStatus(status.text());
                    }
                }
            } else if (outcome instanceof CommandOutcome.Table table) {
                // 分派器的表格没有列类型信息（CommandOutcome 里没有这个字段）：按字符串全左对齐渲染
                context.output().printTable(table.headers(), null, table.rows());
            } else if (outcome instanceof CommandOutcome.Error error) {
                reportError(error.message());
            }
        }
    }

    // ------------------------------------------------------------------ 错误与工具

    /**
     * 协作式停止的唯一判据点：被请求停止就抛 {@link ScriptStoppedException}，由作业侧收尾。
     *
     * <p>父脚本与控制台的信号是 {@link StopSignal#NEVER}，因此这条判断对它们恒为假——执行路径不变。</p>
     */
    @Override
    public void checkStopRequested() {
        if (stopSignal.stopRequested()) {
            throw new ScriptStoppedException();
        }
    }

    /**
     * 报一次错误：stderr（同时进 --logfile/--ref 的日志）+ 错误计数 + {@code _WHENEVER ERROR} 判定。
     *
     * <p>文案**就是消息本身**，不加位置前缀：日志/ref 是给人 review、也常常要手工改的产物，
     * 行号会让它一改脚本就得跟着调整；要定位就看消息本身与它前后输出的数据。</p>
     */
    @Override
    public void reportError(String message) {
        countError(message, false);
    }

    /**
     * 一条"既是状态行又是失败"的消息：打印一次（见 {@link ScriptOutput#printFailure}）+ 计一次错误。
     *
     * <p>为什么不直接 {@code printStatus + reportError}：dbcli 的错误通道就是 stdout，
     * 那样会把同一句话打印两遍（{@code Assert fail.}、{@code Compare failed.}、
     * {@code Job [x] finished with exit code n.}、{@code ... wait timed out.}）。</p>
     */
    @Override
    public void countFailure(String message) {
        context.output().printFailure(message == null || message.isBlank()
                ? "ERROR: unknown failure." : message);
        countError(message, true);
    }

    /**
     * 计一次错误并执行 {@code _WHENEVER} 判定。
     *
     * @param alreadyPrinted 消息是否已经打印过（{@code true} 时不再走 {@link ScriptOutput#printError}）
     */
    private void countError(String message, boolean alreadyPrinted) {
        if (!alreadyPrinted) {
            String text = message == null || message.isBlank() ? "ERROR: unknown failure." : message;
            context.output().printError(text);
        }
        context.countError();
        ScriptContext.Whenever whenever = context.whenever();
        if ("exit".equalsIgnoreCase(whenever.action())) {
            context.requestExit(whenever.exitCode());
        }
    }

    private static String firstWord(String text) {
        String stripped = text.strip();
        int end = 0;
        // 命令字在空白、行尾或语句终结符处结束：`_bogus;` 的"命令字"是 `_bogus`，
        // 否则报错文案会写成 `Unknown command [_bogus;]`
        while (end < stripped.length()) {
            char character = stripped.charAt(end);
            if (Character.isWhitespace(character) || character == ';' || character == '\n') {
                break;
            }
            end++;
        }
        return end == 0 ? null : stripped.substring(0, end);
    }

    /**
     * 从一条 {@code _connect} 语句里取出使用者写的连接串原文：去掉命令字、首尾空白与结尾的 {@code ';'}。
     *
     * <p>只在**解析阶段**就要报"无效连接串"时用：那时 IR 里没有完整原文
     * （解析器只为成功匹配的 token 建节点，见 {@code SqlIrVisitor} 的 {@code connectString}），
     * 而错误文案必须回显使用者写的那一段。</p>
     */
    private static String connectStringOf(String statement) {
        String text = statement.strip();
        String command = firstWord(text);
        if (command != null) {
            text = text.substring(command.length()).strip();
        }
        if (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).strip();
        }
        return text;
    }

    private static String messageOf(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
