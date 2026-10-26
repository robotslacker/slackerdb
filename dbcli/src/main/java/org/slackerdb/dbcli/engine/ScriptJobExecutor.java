package org.slackerdb.dbcli.engine;

import org.slackerdb.dbcli.common.PathArguments;
import org.slackerdb.dbcli.job.JobContext;
import org.slackerdb.dbcli.job.JobExecutor;
import org.slackerdb.dbcli.job.JobSpec;
import org.slackerdb.dbcli.report.ReferenceCheck;
import org.slackerdb.dbcli.sql.JdbcSqlExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 作业执行体（引擎侧）：在**作业线程**里新建一份会话状态，把 {@link JobSpec#script()} 跑完并返回退出码。
 *
 * <h2>为什么"另建一份上下文"而不是复用父脚本的</h2>
 * <p>作业本来就要改连接、变量、spool 目标这些会话级状态；复用父脚本的那一份会把并发作业搅在一起。
 * 因此每个作业都有**自己的**：</p>
 * <table border="1">
 *   <caption>作业自己的东西 / 作业共享的东西</caption>
 *   <tr><th>自己的</th><th>共享的（必须共享）</th></tr>
 *   <tr>
 *     <td>{@link JdbcSqlExecutor}（独立连接）、{@link VariableResolver}（独立变量）、
 *         {@link ScriptOutput}（写 {@code <作业名>.out}，并在给了 {@code logfile} 时 tee 一份给
 *         数据输出文件）、{@link CommandDispatcher}（独立 {@code _SSH} 会话）</td>
 *     <td>{@code job.JobManager}（**同一个**：定时点/屏障因此跨父脚本与任意深度的作业都成立，
 *         作业里再起的孙作业也登记在同一个管理器上）</td>
 *   </tr>
 * </table>
 *
 * <h2>选项（{@code JobSpec.options()}）</h2>
 * <p>与命令行同名选项一致：{@code logon} 先连一次库（等价于脚本第一行 {@code _connect}）、
 * {@code profile} 先跑一遍准备脚本、{@code logfile} 把作业的数据输出
 * （与它的 {@code .out} 相同的内容）写一份到指定文件、
 * {@code reference} 把作业的数据输出与 golden 文件比对（不一致 → 作业退出码 1）。</p>
 *
 * <h2>可中断（协作式中止）</h2>
 * <ul>
 *   <li>{@link ScriptRunner} 拿到 {@code control::stopRequested}：停止请求一到，下一条语句不再执行
 *       （{@link ScriptStoppedException}），{@code _SLEEP}/{@code _HOST} 也会提前醒来；</li>
 *   <li>{@link JdbcSqlExecutor#cancelCurrentStatement()} 与
 *       {@link CommandDispatcher#closeSshSessions()} 登记为"停止时取消"回调：正在跑的 SQL 被
 *       {@code Statement.cancel()} 取消，正在跑的远端命令因为 channel 断开而立刻失败。</li>
 * </ul>
 *
 * <h2>异常不出作业线程</h2>
 * <p>任何异常都由 {@code job.JobManager} 兜住：写进 {@code <作业名>.out} 并把作业置为 {@code FAILED}，
 * 绝不会冒到父脚本线程或杀掉整次运行。{@link ScriptStoppedException}（被停掉）在这里就地转成退出码，
 * 不写错误行——它不是失败，是中止。</p>
 */
public final class ScriptJobExecutor implements JobExecutor {

    /** {@code --reference} 的中间文件（作业 stdout 的镜像）前缀。 */
    private static final String REFERENCE_TEMP_PREFIX = "dbcli-job-reference-";

    @Override
    // cancelStatements/cancelSsh 只为"作业被停止时取消长操作"而存在（用的是 close() 的副作用），
    // 正文里不读它们；-Xlint:try 因此会提示"资源未被引用"，这里显式豁免并保留原因。
    @SuppressWarnings("try")
    public int execute(JobContext job) throws Exception {
        JobSpec spec = job.spec();
        Path base = spec.workingDirectory() == null ? Path.of("").toAbsolutePath() : spec.workingDirectory();
        Path script = resolve(base, spec.script());
        // 与命令行一致：--execute <文件> 的工作目录是**脚本所在目录**（脚本里的相对路径以它为准）
        Path workingDirectory = script.getParent() == null ? base : script.getParent();
        Map<String, String> options = spec.options();

        VariableResolver variables = new VariableResolver(spec.variables());
        try (JdbcSqlExecutor sqlExecutor = new JdbcSqlExecutor();
             ScriptOutput output = new ScriptOutput(job.output().stdout(), job.output().stderr());
             CommandDispatcher dispatcher = new CommandDispatcher(sqlExecutor, workingDirectory, job.manager());
             // 作业会话**不**负责作业失败传播：它共享父脚本的作业管理器，如果在这里补报/认领
             // "失败的兄弟作业"，失败信息会落进别的作业的 .err，而且父脚本收尾时不再报它
             ScriptContext context = new ScriptContext(sqlExecutor, output, variables, workingDirectory,
                     dispatcher, false);
             AutoCloseable cancelStatements = job.control().onStop(sqlExecutor::cancelCurrentStatement);
             AutoCloseable cancelSsh = job.control().onStop(dispatcher::closeSshSessions)) {
            return runScript(job, context, script, workingDirectory, options);
        } catch (ScriptStoppedException stopped) {
            // 被 abort/shutdown/管理器关闭停掉：终态由请求方落地（见 JobManager），这里只给一个非 0 退出码
            return 1;
        }
    }

    // ------------------------------------------------------------------ 主体

    private int runScript(JobContext job, ScriptContext context, Path script, Path workingDirectory,
                          Map<String, String> options) {
        ScriptRunner runner = new ScriptRunner(context, job.control()::stopRequested);

        // 数据输出文件：与命令行同一个语义（内容与作业 stdout 相同）。
        // 给了 reference 时它同时是"实际输出文件"，比对的就是它。
        // 这两件事都放在跑脚本之前：准备好再跑，且脚本没跑起来时不会留下半个文件。
        String reference = options.get("reference");
        String logTarget = options.get("logfile");
        Path referenceFile = null;
        Path tempOutputFile = null;
        if (reference != null) {
            referenceFile = resolve(workingDirectory, reference);
            if (!Files.isRegularFile(referenceFile)) {
                context.output().printError("Reference file [" + reference + "] does not exist.");
                return 1;
            }
        }
        Path logFileTarget = logTarget == null ? null : resolve(workingDirectory, logTarget);
        if (logFileTarget != null) {
            try {
                context.output().startTee(logFileTarget);
            } catch (IOException logFailed) {
                context.output().printError("Cannot write the log file [" + logTarget + "]: "
                        + logFailed.getMessage());
                return 1;
            }
        }
        if (referenceFile != null && logFileTarget == null) {
            try {
                tempOutputFile = Files.createTempFile(REFERENCE_TEMP_PREFIX, ".out");
                context.output().addTee(tempOutputFile);
            } catch (IOException teeFailed) {
                context.output().printError("Cannot write the reference output file: "
                        + teeFailed.getMessage());
                deleteQuietly(tempOutputFile);
                return 1;
            }
        }
        Path actualOutputFile = referenceFile == null ? null
                : (logFileTarget != null ? logFileTarget : tempOutputFile);

        try {
            String logon = options.get("logon");
            if (logon != null) {
                if (logon.contains("\n") || logon.contains("\r")) {
                    context.output().printError("Invalid --logon value: it must be a single line.");
                    return 1;
                }
                // 与命令行一致：--logon 就是一行 `_connect`（同一条分派/报错路径）
                int connectCode = runner.runText("_connect " + logon, "<logon>", List.of());
                if (connectCode != 0) {
                    return connectCode;
                }
            }
            String profile = options.get("profile");
            if (profile != null) {
                Path profileFile = resolve(workingDirectory, profile);
                if (!Files.isRegularFile(profileFile)) {
                    context.output().printError("Profile script [" + profile + "] does not exist.");
                    return 1;
                }
                int profileCode = runner.runFile(profileFile, List.of());
                if (profileCode != 0) {
                    return profileCode;
                }
                if (context.exitRequested()) {
                    return context.finalExitCode();
                }
            }

            int code = runner.runFile(script, List.of());
            if (referenceFile != null) {
                code = finishReference(context, script, actualOutputFile, referenceFile, code);
            } else {
                context.output().stopTee();
            }
            return code;
        } finally {
            deleteQuietly(tempOutputFile);
        }
    }

    /**
     * {@code reference} 选项的收尾：停 tee → 严格 golden 比对 → 写 {@code .dif/.suc}。
     *
     * <p>退出码规则与命令行一致：脚本自己的退出码优先；脚本为 0 而不一致时给 1。</p>
     */
    /**
     * {@code reference} 选项的收尾：与命令行 {@code --ref} 共用同一份实现（见 {@link ReferenceCheck}）。
     *
     * <p>作业与命令行的差别只在"消息去哪儿"：作业写自己的 {@code <作业名>.out}。</p>
     */
    private int finishReference(ScriptContext context, Path script, Path actualOutputFile,
                                Path referenceFile, int runCode) {
        String scriptName = script.getFileName() == null ? script.toString()
                : script.getFileName().toString();
        ReferenceCheck.Outcome outcome = ReferenceCheck.finish(runCode, scriptName, actualOutputFile,
                referenceFile, actualOutputFile == null ? null : actualOutputFile.toAbsolutePath().getParent(),
                context.output(), context.dispatcher().compareRules(),
                line -> context.output().printError(line));
        return outcome.exitCode();
    }

    // ------------------------------------------------------------------ 小工具

    private static Path resolve(Path base, String name) {
        return PathArguments.resolve(base, name);
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 临时目录里的文件删不掉不影响结论
        }
    }
}
