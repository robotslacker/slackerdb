package org.slackerdb.dbcli.engine.command;

import org.slackerdb.common.utils.OSUtil;
import org.slackerdb.dbcli.job.ProcessTree;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@code _HOST <命令>}：在脚本的工作目录里跑一条本地命令。
 *
 * <p>stdout 逐行当**数据**、stderr 逐行当**错误**输出（两路不交织）；退出码非 0 计一次错误。
 * 多行块（{@code _HOST """…"""}）在 IR 里是 {@code " & "} 连接的多条命令，拆分必须
 * **按引号感知**：直接 split 会把 {@code echo "a & b"} 切成两条，等于替 shell 解析命令行。</p>
 *
 * <p><b>可中断</b>：两侧输出都由 daemon 抽水线程读走（管道写满会把子进程和我们一起卡死），
 * 主流程只阻塞在可中断的 {@code waitFor}/{@code get} 上；停止请求一到（或线程被 interrupt）就销毁
 * 本地子进程并如实报告，绝不留孤儿进程。</p>
 */
public final class HostCommand implements ScriptCommand {

    @Override
    public Set<String> names() {
        return Set.of("HOST");
    }

    @Override
    public void execute(CommandCall call, Map<String, Object> ir) {
        String script = ir.get("script") == null ? "" : String.valueOf(ir.get("script"));
        if (script.isBlank()) {
            call.reportError("Missing command for _HOST.");
            return;
        }
        for (String command : splitHostCommands(script)) {
            String single = command.strip();
            if (single.isEmpty()) {
                continue;
            }
            Integer exitCode = runHostCommand(call, single);
            if (exitCode == null) {
                return;
            }
            if (exitCode != 0) {
                call.reportError("Command [" + single + "] finished with exit code [" + exitCode + "].");
            }
            if (call.context().exitRequested()) {
                return;
            }
        }
    }

    /**
     * 按 {@code " & "} 拆分多条命令，**跳过引号里的内容**（引号里的 {@code &} 是数据）。
     *
     * <p>反斜杠转义按"吞掉下一个字符"处理，与脚本其它地方（行切分、语句边界）的引号规则一致。</p>
     */
    static List<String> splitHostCommands(String script) {
        List<String> commands = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int index = 0; index < script.length(); index++) {
            char character = script.charAt(index);
            if (inSingleQuote || inDoubleQuote) {
                char quote = inSingleQuote ? '\'' : '"';
                if (character == '\\' && index + 1 < script.length()) {
                    current.append(character).append(script.charAt(index + 1));
                    index++;
                    continue;
                }
                if (character == quote) {
                    inSingleQuote = false;
                    inDoubleQuote = false;
                }
                current.append(character);
                continue;
            }
            if (character == '\'') {
                inSingleQuote = true;
            } else if (character == '"') {
                inDoubleQuote = true;
            } else if (character == ' ' && index + 2 < script.length()
                    && script.charAt(index + 1) == '&' && script.charAt(index + 2) == ' ') {
                commands.add(current.toString());
                current.setLength(0);
                index += 2;
                continue;
            }
            current.append(character);
        }
        commands.add(current.toString());
        return commands;
    }

    /**
     * 跑一条本地命令。
     *
     * @return 退出码；启动/等待失败或中途被停掉返回 null（错误已报）
     */
    private Integer runHostCommand(CommandCall call, String command) {
        List<String> argv = OSUtil.isWindows()
                ? List.of("cmd.exe", "/c", command)
                : List.of("/bin/sh", "-c", command);
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(call.context().workingDirectory().toFile());
        builder.redirectErrorStream(false);
        Process process = null;
        try {
            process = builder.start();
            CompletableFuture<List<String>> stdoutLines = pump(process.getInputStream(), "dbcli-host-stdout");
            CompletableFuture<List<String>> stderrLines = pump(process.getErrorStream(), "dbcli-host-stderr");
            while (!process.waitFor(CommandHost.STOP_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (call.host().stopRequested()) {
                    destroyProcess(process);
                    call.reportError("Host command stopped [" + command + "].");
                    return null;
                }
            }
            int exitCode = process.exitValue();
            for (String line : stdoutLines.get(30, TimeUnit.SECONDS)) {
                call.context().output().printData(line);
            }
            for (String line : stderrLines.get(30, TimeUnit.SECONDS)) {
                call.context().output().printError(line);
            }
            return exitCode;
        } catch (IOException startFailed) {
            call.reportError("Cannot run host command [" + command + "]: " + startFailed.getMessage());
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            destroyProcess(process);
            call.reportError("Host command interrupted [" + command + "].");
            return null;
        } catch (Exception pumpFailed) {
            destroyProcess(process);
            call.reportError("Host command failed [" + command + "]: " + messageOf(pumpFailed));
            return null;
        }
    }

    /** 起一条 daemon 抽水线程读走一路输出（线程名固定，便于线程卫生排查）。 */
    private static CompletableFuture<List<String>> pump(InputStream stream, String threadName) {
        CompletableFuture<List<String>> lines = new CompletableFuture<>();
        Thread pump = new Thread(() -> lines.complete(readLines(stream)), threadName);
        pump.setDaemon(true);
        pump.start();
        return lines;
    }

    /**
     * 销毁本地子进程**及其整棵进程树**（先优雅，再强杀），失败不报错：调用方已经在收尾，
     * 报错只会盖住真正的原因（实现在 {@link ProcessTree}，与 {@code mode=process} 的作业共用）。
     */
    private static void destroyProcess(Process process) {
        ProcessTree.destroy(process, CommandHost.STOP_POLL_MILLIS);
    }

    /**
     * 读子进程的一路输出。
     *
     * <p>解码用 **JVM 默认字符集**：本地命令按平台编码输出（中文 Windows 的 {@code cmd.exe} 是 GBK），
     * 硬按 UTF-8 解会把中文命令输出变成乱码；这与"脚本/期望文件只支持 UTF-8"不冲突。</p>
     */
    private static List<String> readLines(InputStream stream) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, Charset.defaultCharset()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException readFailed) {
            // 进程输出读不完时保留已读到的行（异常不冒出去）
        }
        return lines;
    }

    private static String messageOf(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
