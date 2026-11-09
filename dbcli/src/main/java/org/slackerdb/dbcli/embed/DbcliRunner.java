package org.slackerdb.dbcli.embed;

import org.slackerdb.dbcli.compare.LineRules;
import org.slackerdb.dbcli.engine.ScriptContext;
import org.slackerdb.dbcli.engine.ScriptOutput;
import org.slackerdb.dbcli.engine.ScriptRunner;
import org.slackerdb.dbcli.engine.ScriptStoppedException;
import org.slackerdb.dbcli.engine.StopSignal;
import org.slackerdb.dbcli.engine.VariableResolver;
import org.slackerdb.dbcli.sql.JdbcSqlExecutor;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * dbcli 脚本的**嵌入入口**：给"在别的进程里跑一段 dbcli 脚本"的宿主用（dbserver 的
 * {@code DBCLI} 调度任务就是这样一个宿主）。
 *
 * <p>它与 {@code dbcli --execute <file>} 走同一条执行路径：同一套语句切分、
 * {@code _SET}/{@code _WHENEVER}/{@code _START}/{@code _ASSERT} 语义与输出格式；
 * 区别只在"谁来提供输入输出、谁来要求停止"。因此这里是 dbcli 唯一承诺稳定的编程接口，
 * {@code engine}/{@code sql} 包里的类仍然是内部实现。</p>
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li>本类只负责"跑脚本"：连接（{@code logon}，等价 {@code --logon}）、变量、工作目录、
 *       输出、可选的数据输出文件（{@code --logfile} 的 tee）、准备脚本（{@code --profile}）、
 *       协作式停止；</li>
 *   <li><b>不包含</b>调度概念（重试、任务历史、超时判定）与 CLI 概念（daemon、pidfile、
 *       {@code --ref} 的 golden 比对收尾）——那些留在调用方；</li>
 *   <li>输出只写调用方给的 writer，不碰 {@code System.out}/{@code System.err}。</li>
 * </ul>
 *
 * <h2>退出码</h2>
 * <p>与 CLI 完全一致：只有脚本里的显式 {@code _EXIT n} / {@code _WHENEVER ERROR EXIT n} 会改变退出码；
 * 脚本内部发生过的错误只计数（见 {@link Result#errorCount()}）。</p>
 *
 * <h2>停止</h2>
 * <p>停止是协作式的：{@code stopRequested} 变真后，引擎在下一条语句前停下（{@code _SLEEP}/{@code _HOST}
 * 会提前醒来），并通过 {@link Request#onCancelReady} 注册的回调取消正在执行的 SQL。
 * 被停掉时抛出 {@link StoppedException}（与"脚本失败"区分开）。</p>
 */
public final class DbcliRunner {

    private DbcliRunner() {
    }

    /**
     * 一次脚本运行的输入。
     *
     * <p>{@code script} 与 {@code scriptText} 二选一：文件用于 {@code --execute <file>}，
     * 文本用于 stdin / 内联脚本（{@code scriptName} 作为报错与断言里的显示名）。</p>
     */
    public record Request(
            Path script,
            String scriptText,
            String scriptName,
            Path workingDirectory,
            String logon,
            Map<String, String> variables,
            List<Path> profiles,
            Path logFile,
            PrintWriter out,
            PrintWriter err,
            BooleanSupplier stopRequested,
            Consumer<Runnable> onCancelReady,
            boolean abortOnLogonFailure) {

        public static Builder builder() {
            return new Builder();
        }

        /** 链式构造器：字段多，且大部分是可选的。 */
        public static final class Builder {
            private Path script;
            private String scriptText;
            private String scriptName;
            private Path workingDirectory;
            private String logon;
            private Map<String, String> variables = Map.of();
            private final List<Path> profiles = new ArrayList<>();
            private Path logFile;
            private PrintWriter out;
            private PrintWriter err;
            private BooleanSupplier stopRequested;
            private Consumer<Runnable> onCancelReady;
            private boolean abortOnLogonFailure;

            public Builder script(Path script) {
                this.script = script;
                return this;
            }

            public Builder scriptText(String scriptText, String scriptName) {
                this.scriptText = scriptText;
                this.scriptName = scriptName;
                return this;
            }

            public Builder workingDirectory(Path workingDirectory) {
                this.workingDirectory = workingDirectory;
                return this;
            }

            public Builder logon(String logon) {
                this.logon = logon;
                return this;
            }

            public Builder variables(Map<String, String> variables) {
                this.variables = variables == null ? Map.of() : new LinkedHashMap<>(variables);
                return this;
            }

            public Builder profile(Path profile) {
                if (profile != null) {
                    this.profiles.add(profile);
                }
                return this;
            }

            public Builder logFile(Path logFile) {
                this.logFile = logFile;
                return this;
            }

            public Builder out(PrintWriter out) {
                this.out = out;
                return this;
            }

            public Builder err(PrintWriter err) {
                this.err = err;
                return this;
            }

            public Builder stopRequested(BooleanSupplier stopRequested) {
                this.stopRequested = stopRequested;
                return this;
            }

            public Builder onCancelReady(Consumer<Runnable> onCancelReady) {
                this.onCancelReady = onCancelReady;
                return this;
            }

            /**
             * {@code logon} 失败时是否立即结束（不继续跑脚本）。
             *
             * <p>CLI 的 {@code --logon} 语义是"失败只计数、退出码仍是 0"，因此默认 {@code false}；
             * 调度任务把它设为 {@code true}：连不上库的脚本跑下去只会产生一串 "Not connected."。</p>
             */
            public Builder abortOnLogonFailure(boolean abortOnLogonFailure) {
                this.abortOnLogonFailure = abortOnLogonFailure;
                return this;
            }

            public Request build() {
                return new Request(script, scriptText, scriptName, workingDirectory, logon, variables,
                        List.copyOf(profiles), logFile, out, err, stopRequested, onCancelReady,
                        abortOnLogonFailure);
            }
        }
    }

    /**
     * 运行结果。
     *
     * @param exitCode      dbcli 语义的退出码
     * @param errorCount    脚本内部累计的错误数（不改退出码；调用方可用它做告警）
     * @param compareRules  {@code _COMPARE} 的 mask/skip 规则（{@code --ref} 的收尾要用）
     * @param logonFailed   预连接（{@code logon}）是否失败；CLI 不据此改变退出码，
     *                      但嵌入方（调度任务）应把它当失败
     */
    public record Result(int exitCode, int errorCount, LineRules compareRules, boolean logonFailed) {
    }

    /** 用法/环境错误（脚本不存在、连接串非法、日志文件不可写等）。脚本业务失败只走退出码。 */
    public static class DbcliException extends Exception {
        private static final long serialVersionUID = 1L;

        public DbcliException(String message) {
            super(message);
        }

        public DbcliException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 脚本被 {@link Request#stopRequested} 停掉（不是失败，也不是脚本自己的退出码）。 */
    public static final class StoppedException extends DbcliException {
        private static final long serialVersionUID = 1L;

        public StoppedException(Throwable cause) {
            super("script stopped", cause);
        }
    }

    /**
     * 运行一次脚本。
     *
     * @return 退出码与统计
     * @throws DbcliException   用法/环境错误，或被 {@link Request#stopRequested} 停掉
     */
    public static Result run(Request request) throws DbcliException {
        validate(request);

        Path script = request.script();
        Path workingDirectory = request.workingDirectory() != null
                ? request.workingDirectory()
                : (script != null && script.getParent() != null
                        ? script.getParent() : Path.of("").toAbsolutePath());
        String scriptName = request.scriptName() != null && !request.scriptName().isBlank()
                ? request.scriptName()
                : (script != null && script.getFileName() != null
                        ? script.getFileName().toString() : "<script>");
        BooleanSupplier stop = request.stopRequested() == null ? () -> false : request.stopRequested();
        StopSignal signal = stop::getAsBoolean;

        try (JdbcSqlExecutor sql = new JdbcSqlExecutor();
             ScriptOutput output = new ScriptOutput(request.out(), request.err())) {
            if (request.onCancelReady() != null) {
                request.onCancelReady().accept(sql::cancelCurrentStatement);
            }
            if (request.logFile() != null) {
                try {
                    output.startTee(request.logFile());
                } catch (IOException teeFailed) {
                    throw new DbcliException("Cannot write the log file [" + request.logFile() + "]: "
                            + teeFailed.getMessage(), teeFailed);
                }
            }
            try (ScriptContext context = new ScriptContext(sql, output,
                    new VariableResolver(request.variables()), workingDirectory)) {
                ScriptRunner runner = new ScriptRunner(context, signal);

                // 与 --logon 同一条路径：一行 _connect（多行在 validate 里已拒掉）
                if (request.logon() != null) {
                    int errorsBefore = context.errorCount();
                    int connectCode = runner.runText("_connect " + request.logon(), "<logon>", List.of());
                    boolean logonFailed = context.errorCount() > errorsBefore;
                    if (logonFailed && request.abortOnLogonFailure()) {
                        return new Result(connectCode, context.errorCount(),
                                context.dispatcher().compareRules(), true);
                    }
                    if (connectCode != 0) {
                        return new Result(connectCode, context.errorCount(),
                                context.dispatcher().compareRules(), logonFailed);
                    }
                }
                for (Path profile : request.profiles()) {
                    int errorsBefore = context.errorCount();
                    runner.runFile(profile, List.of());
                    if (context.exitRequested() || context.errorCount() > errorsBefore) {
                        return result(context, context.finalExitCode());
                    }
                }
                if (context.exitRequested()) {
                    return result(context, context.finalExitCode());
                }
                int code = script != null
                        ? runner.runFile(script, List.of())
                        : runner.runText(request.scriptText(), scriptName, List.of());
                return result(context, code);
            } finally {
                output.stopTee();
            }
        } catch (ScriptStoppedException stopped) {
            throw new StoppedException(stopped);
        }
    }

    private static Result result(ScriptContext context, int exitCode) {
        return new Result(exitCode, context.errorCount(), context.dispatcher().compareRules(), false);
    }

    private static void validate(Request request) throws DbcliException {
        if (request.out() == null || request.err() == null) {
            throw new DbcliException("out/err writers are required");
        }
        boolean hasScript = request.script() != null;
        boolean hasText = request.scriptText() != null;
        if (hasScript == hasText) {
            throw new DbcliException("exactly one of script / scriptText must be provided");
        }
        if (hasScript) {
            if (!Files.isRegularFile(request.script())) {
                throw new DbcliException("Script file [" + request.script() + "] does not exist.");
            }
        }
        if (request.logon() != null && (request.logon().contains("\n") || request.logon().contains("\r"))) {
            throw new DbcliException("Invalid --logon value: it must be a single line.");
        }
        for (Path profile : request.profiles()) {
            if (!Files.isRegularFile(profile)) {
                throw new DbcliException("Profile script [" + profile + "] does not exist.");
            }
        }
    }
}
