package org.slackerdb.dbcli.engine;

import org.slackerdb.dbcli.common.CommandOutcome;
import org.slackerdb.dbcli.engine.command.CommandCall;
import org.slackerdb.dbcli.engine.command.CommandCatalog;
import org.slackerdb.dbcli.engine.command.CommandHost;
import org.slackerdb.dbcli.engine.command.ScriptCommand;
import org.slackerdb.dbcli.script.SqlAnalyzer;
import org.slackerdb.dbcli.sql.SqlExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 脚本执行引擎：**逐行累积 → 分析 → 分派 → 输出**。
 *
 * <p>本类只做"推进"：一行文本进来之后决定它是"还没写完"、"一条 SQL"、"一条命令"还是"解析失败"。
 * 具体职责分给了几个协作对象，改一处不必读整个引擎：</p>
 * <table border="1">
 *   <caption>协作对象</caption>
 *   <tr><th>类</th><th>职责</th></tr>
 *   <tr><td>{@link ScriptSession}</td><td>累积缓冲、按顶层 {@code ;} / 行首 {@code /} / {@code echo off} 切分</td></tr>
 *   <tr><td>{@link SqlStatementExecutor}</td><td>一段 SQL 文本的执行与结果输出（含 COPY 流拒绝）</td></tr>
 *   <tr><td>{@link CommandOutcomeRenderer}</td><td>{@code CommandOutcome} → 状态行/表格/错误</td></tr>
 *   <tr><td>{@link ErrorReporter}</td><td>错误打印、错误计数、{@code _WHENEVER} 判定</td></tr>
 *   <tr><td>{@link StatementDiagnostics}</td><td>{@code _SET TIMING/TIME} 的诊断行</td></tr>
 *   <tr><td>{@link StatementText}</td><td>命令字/连接串原文等纯文本判断</td></tr>
 * </table>
 *
 * <h2>解析失败怎么分流（dbcli 不解释语法）</h2>
 * <p>分析器认不下来的输入**绝不**把解析器文案（{@code line…:…}、{@code missing SQL_END}）给使用者：</p>
 * <ul>
 *   <li>{@code _} 开头、还没写完 → 继续读下一行；脚本读到 EOF 仍是 {@code Incomplete command [...]}；</li>
 *   <li>{@code _} 开头、写错了/不认识 → {@code Unknown command [...]}（不送库）；</li>
 *   <li>其余一切 → **原文**交给执行层：成功照常输出，失败打印数据库返回的错误（未连接时 {@code Not connected.}）。</li>
 * </ul>
 *
 * <h2>退出码</h2>
 * <p>进程退出码由 {@link ScriptContext#finalExitCode()} 唯一裁决：显式 {@code _EXIT}/{@code _WHENEVER ERROR EXIT}
 * 优先，否则 <b>0</b>——脚本内部错误只打印与计数，不改变退出码（README §9.3）。子脚本（{@code _START}）
 * 的返回值另算，见 {@link #runScript}。</p>
 *
 * <h2>协作式停止</h2>
 * <p>作业线程用的是带信号的构造器：{@code stopRequested()} 为真时在下一条语句之前抛
 * {@link ScriptStoppedException}，并让 {@code _SLEEP}/{@code _HOST} 提前醒来。父脚本与控制台拿的是
 * {@link StopSignal#NEVER}，因此这条分支对它们不生效。</p>
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

    private final ScriptContext context;

    /** 协作式停止信号（作业线程用；父脚本与控制台是 {@link StopSignal#NEVER}）。 */
    private final StopSignal stopSignal;

    private final ErrorReporter errors;
    private final StatementDiagnostics diagnostics;
    private final SqlStatementExecutor sqlExecutor;
    private final CommandOutcomeRenderer outcomes;

    public ScriptRunner(ScriptContext context) {
        this(context, StopSignal.NEVER);
    }

    /**
     * @param stopSignal 停止信号：{@code stopRequested()} 为真时不再执行下一条语句，并尽快唤醒
     *                   {@code _SLEEP}/{@code _HOST}
     */
    public ScriptRunner(ScriptContext context, StopSignal stopSignal) {
        this.context = context;
        this.stopSignal = stopSignal == null ? StopSignal.NEVER : stopSignal;
        this.errors = new ErrorReporter(context);
        this.diagnostics = new StatementDiagnostics(context);
        this.sqlExecutor = new SqlStatementExecutor(context, errors, diagnostics);
        this.outcomes = new CommandOutcomeRenderer(context, errors);
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

    @Override
    public void checkStopRequested() {
        if (stopSignal.stopRequested()) {
            throw new ScriptStoppedException();
        }
    }

    @Override
    public void reportError(String message) {
        errors.report(message);
    }

    @Override
    public void countFailure(String message) {
        errors.fail(message);
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
     * <p>脚本可能启动作业之后直接结束（漏写 {@code _JOB WAIT}）。那种情况下没有任何一处会把失败反映到
     * 退出码上，CI 就会把一次失败判成通过；这里在顶层脚本结束时统一收口。</p>
     */
    private void reportUnacknowledgedJobFailures() {
        if (!context.jobFailureReporting()) {
            // 作业线程：汇总归父脚本，别把兄弟作业的失败写进自己的 .err 并替它认领
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
     * @param rawCandidate 候选语句原文（可能只是累积缓冲的前缀）
     * @return {@code true} 表示这条候选**已定性**（已执行或已报错，调用方可从缓冲消费掉）；
     *         {@code false} 表示"还没写完"，调用方应继续累积或尝试更长的前缀
     */
    boolean executeStatement(String rawCandidate) {
        long startedAt = System.nanoTime();
        diagnostics.begin();
        boolean handled = handleStatement(rawCandidate);
        if (handled) {
            diagnostics.finish(startedAt);
        }
        return handled;
    }

    /** {@link #executeStatement} 的本体：判定 + 分派，返回"这条候选是否已定性"。 */
    private boolean handleStatement(String rawCandidate) {
        // 作业被请求停止时，下一条语句不再执行：让作业线程尽快收尾
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
        if (!StatementText.turnsEchoOff(ir)) {
            // `_SET ECHO OFF` 这一行自己不回显
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
     * @return {@code false} 表示没有可报的消息（交给调用方按"解析失败"处理）
     */
    private boolean reportReadableIrError(String statement, Map<String, Object> ir, String errorMsg) {
        if (errorMsg == null || errorMsg.isBlank()) {
            return false;
        }
        if ("CONNECT".equalsIgnoreCase(StatementText.irName(ir))) {
            reportError(SqlExecutor.invalidConnectionStringMessage(
                    StatementText.connectStringOf(statement)));
            return true;
        }
        reportError(errorMsg);
        return true;
    }

    /**
     * 分析器**没有**认下这条命令时的唯一出口（dbcli 不解释语法）。
     *
     * @return {@code true} 表示这条命令已定性；{@code false} 表示"还没写完"，调用方应继续累积
     */
    private boolean handleUnparsable(String text, boolean incomplete) {
        String firstWord = StatementText.firstWord(text);
        if (firstWord != null && "_connect".equalsIgnoreCase(firstWord)) {
            // 命令名认得出来、但后面的取值不成句：仍然是"无效连接串"，不是未知命令
            reportError(SqlExecutor.invalidConnectionStringMessage(
                    StatementText.connectStringOf(text)));
            return true;
        }
        if (firstWord != null && firstWord.startsWith("_")) {
            if (incomplete) {
                return false;
            }
            reportError("Unknown command [" + firstWord + "]. Run _HELP for the command list.");
            return true;
        }
        executeSqlText(text);
        return true;
    }

    /**
     * 输入结束时缓冲里还剩一段没写完的文本：按"解析失败"处理（分流规则同 {@link #handleUnparsable}）。
     *
     * <p>区别只有两点：变量替换的时机（这里的文本还没替换过），以及**此时已经没有下一行了** ——
     * 所以 {@code _} 命令不再返回"继续累积"，而是如实报一条 {@code Incomplete command [...]}。</p>
     */
    void finishPending(String raw) {
        long startedAt = System.nanoTime();
        diagnostics.begin();
        handlePending(raw);
        diagnostics.finish(startedAt);
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
        String firstWord = StatementText.firstWord(resolved);
        if (firstWord != null && "_connect".equalsIgnoreCase(firstWord)) {
            reportError(SqlExecutor.invalidConnectionStringMessage(
                    StatementText.connectStringOf(resolved)));
            return;
        }
        if (firstWord != null && firstWord.startsWith("_")) {
            reportError("Incomplete command [" + firstWord + "]: the command is not finished yet.");
            return;
        }
        executeSqlText(resolved);
    }

    /** 一段 SQL 文本：只去掉首尾空白，内部换行原样保留（执行层看到的就是使用者写的那句话）。 */
    private void executeSqlText(String statement) {
        sqlExecutor.execute(statement == null ? "" : statement.strip());
    }

    /**
     * {@code _SET ECHO}（默认 on）：把这条 SQL/命令的**原文**写进日志（{@code --logfile}/{@code --ref}）。
     *
     * <p>只管脚本会话：它们没有"屏幕"，所以日志里补上语句原文。控制台用转录
     * （见 {@link ScriptOutput#printTranscript}），日志里已经有敲进去的那一行。</p>
     */
    private void echoInput(String raw) {
        if (raw == null || raw.isBlank() || !context.isScriptSession()) {
            return;
        }
        if (StatementText.isEchoBlock(raw)) {
            return;
        }
        // 一行多条语句时，第二条候选会带着分隔符后面的空格（`select 1; select 2;` → ` select 2;`）
        context.output().printInput(raw.strip());
    }

    /** 按 IR 的 {@code name} 分派（SQL 语句 → 执行层；命令 → 本引擎或既有分派器）。 */
    private void execute(Map<String, Object> ir) {
        String name = StatementText.irName(ir);
        if (SQL_STATEMENTS.contains(name)) {
            Object statement = ir.get("statement");
            executeSqlText(statement == null ? "" : String.valueOf(statement));
        } else if (CommandDispatcher.supports(name)) {
            List<CommandOutcome> outcomes = context.dispatcher().dispatch(ir);
            this.outcomes.render(outcomes);
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
}
