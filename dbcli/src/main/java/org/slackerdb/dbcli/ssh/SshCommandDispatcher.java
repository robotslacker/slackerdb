package org.slackerdb.dbcli.ssh;

import org.slackerdb.dbcli.common.CommandOutcome;
import org.slackerdb.dbcli.common.PathArguments;
import org.slackerdb.dbcli.common.UserNumber;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code _SSH} 的动作分派器：把 IR（{@code SqlIrVisitor.visitSsh} 的输出）变成"要打印的结果"。
 *
 * <p><b>文案已冻结</b>（测试逐字节断言），包括 {@code Unknown} 被写成 {@code Unkonwn} 的那处拼写
 * —— 它是既有脚本与 golden 夹具比对时依赖的输出形态，改动会让比对失败。</p>
 *
 * <h2>动作与文案</h2>
 * <pre>
 *   connect      → "SSH connected"                （认证失败 → "AuthenticationException failed."）
 *   disconnect   → "SSH disconnected"
 *   execute      → 输出逐行，最后 "&lt; Command finished with [&lt;rc&gt;]"
 *   save         → "SSH session saved."
 *   restore      → "SSH session restored."
 *   set encoding → 无输出                          （未知选项 → "Unkonwn ssh option [x]."）
 *   sftp_cwd     → 当前目录字符串
 *   sftp_listdir → 结果集，表头 ["fileName"]
 *   其余 sftp_*  → 无输出（失败 → "&lt;动作&gt; failed. OSError(...)"）
 *   未连接       → "SSH not connected." / "SFTP not connected."
 * </pre>
 *
 * <p>构造函数注入 {@link SshOperationsFactory}：生产传 {@code SshClient::new}，测试传假实现。</p>
 */
public final class SshCommandDispatcher {

    /** 造连接的方式（生产：{@code SshClient::new}）。 */
    public interface SshOperationsFactory {
        SshOperations create();
    }

    // 结果类型（Status/Table/Error）已抽到 org.slackerdb.dbcli.common.CommandOutcome：
    // COMPARE 分派器（org.slackerdb.dbcli.compare.CompareCommandDispatcher）要产出同一种结果，
    // 抽出去之后两个分派器共用一套"状态行/结果集/错误"三路输出。

    /** 内部信号：SFTP 动作失败，消息已经按冻结文案拼好。 */
    private static final class SftpFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SftpFailure(String message) {
            super(message);
        }
    }

    private final SshSessionRegistry registry;
    private final SshOperationsFactory factory;

    public SshCommandDispatcher(SshSessionRegistry registry, SshOperationsFactory factory) {
        this.registry = registry;
        this.factory = factory;
    }

    public SshSessionRegistry registry() {
        return registry;
    }

    /**
     * 执行一条 {@code _SSH} 命令。
     *
     * @param ir {@code visitSsh} 产出的 IR（必须有 {@code action} 键）
     */
    public List<CommandOutcome> execute(Map<String, Object> ir) {
        String action = String.valueOf(ir.get("action"));
        List<CommandOutcome> outcomes = new ArrayList<>();
        try {
            switch (action) {
                case "connect" -> connect(ir, outcomes);
                case "disconnect" -> {
                    registry.disconnectCurrent();
                    outcomes.add(new CommandOutcome.Status("SSH disconnected"));
                }
                case "execute" -> executeCommand(ir, outcomes);
                case "save" -> {
                    registry.save(text(ir, "sessionName"));
                    outcomes.add(new CommandOutcome.Status("SSH session saved."));
                }
                case "restore" -> {
                    registry.restore(text(ir, "sessionName"));
                    outcomes.add(new CommandOutcome.Status("SSH session restored."));
                }
                case "set" -> setOption(ir, outcomes);
                case "sftp_cwd" -> withSftp(outcomes, "cwd",
                        operations -> new CommandOutcome.Status(operations.sftpCwd()));
                case "sftp_chmod" -> withSftp(outcomes, "chmod", operations -> {
                    // mode 是八进制字符串
                    operations.sftpChmod(text(ir, "fileName"), sftpMode(ir, "fileMod", "SFTP file mode"));
                    return noOutput();
                });
                case "sftp_chdir" -> withSftp(outcomes, "chdir", operations -> {
                    operations.sftpChdir(text(ir, "dir"));
                    return noOutput();
                });
                case "sftp_mkdir" -> withSftp(outcomes, "mkdir", operations -> {
                    operations.sftpMkdir(text(ir, "dir"), sftpMode(ir, "dirMod", "SFTP directory mode"));
                    return noOutput();
                });
                case "sftp_chown" -> withSftp(outcomes, "chown", operations -> {
                    operations.sftpChown(text(ir, "fileName"), intValue(ir, "uid"), intValue(ir, "gid"));
                    return noOutput();
                });
                case "sftp_get" -> withSftp(outcomes, "get", operations -> {
                    operations.sftpGet(text(ir, "remoteFile"), localPath(ir, "localFile"));
                    return noOutput();
                });
                case "sftp_put" -> withSftp(outcomes, "put", operations -> {
                    operations.sftpPut(localPath(ir, "localFile"), text(ir, "remoteFile"));
                    return noOutput();
                });
                case "sftp_remove" -> withSftp(outcomes, "remove", operations -> {
                    operations.sftpRemove(text(ir, "file"));
                    return noOutput();
                });
                case "sftp_rename" -> withSftp(outcomes, "rename", operations -> {
                    operations.sftpRename(text(ir, "oldFile"), text(ir, "newFile"));
                    return noOutput();
                });
                case "sftp_listdir" -> withSftp(outcomes, "listdir", operations -> {
                    List<List<Object>> rows = new ArrayList<>();
                    for (String name : operations.sftpListDir(text(ir, "dir"))) {
                        List<Object> row = new ArrayList<>();
                        row.add(name);
                        rows.add(row);
                    }
                    return new CommandOutcome.Table(List.of("fileName"), rows);
                });
                case "sftp_truncate" -> withSftp(outcomes, "truncate", operations -> {
                    operations.sftpTruncate(text(ir, "file"), intValue(ir, "fileSize"));
                    return noOutput();
                });
                default -> outcomes.add(new CommandOutcome.Error("Unsupported ssh action [" + action + "]."));
            }
        } catch (SftpFailure failure) {
            outcomes.add(new CommandOutcome.Error(failure.getMessage()));
        } catch (PathArguments.InvalidPathException invalidPath) {
            // 路径参数写错：给 PathArguments 的可读文案（与数值参数同一风格）
            outcomes.add(new CommandOutcome.Error(invalidPath.getMessage()));
        } catch (UserNumber.InvalidNumberException invalidNumber) {
            // 数值参数写错：直接给 UserNumber 的可读文案（与 _SET/_JOB/_SLEEP 同一句式）
            outcomes.add(new CommandOutcome.Error(invalidNumber.getMessage()));
        } catch (Exception unexpected) {
            // 任何未预期的异常都收敛成一条错误输出："SSH Exception :" + 异常的可读表示
            outcomes.add(new CommandOutcome.Error("SSH Exception :" + errorRepr(unexpected)));
        }
        return outcomes;
    }

    // ------------------------------------------------------------------ 各动作

    private void connect(Map<String, Object> ir, List<CommandOutcome> outcomes) {
        int port;
        try {
            port = port(ir);
        } catch (UserNumber.InvalidNumberException invalidPort) {
            // 端口是"数值参数"：错误文案由 UserNumber 统一给（与 _JOB 的 timeout 同一口径）
            outcomes.add(new CommandOutcome.Error(invalidPort.getMessage()));
            return;
        }
        SshOperations operations = factory.create();
        SshConnectOptions options = new SshConnectOptions(
                text(ir, "host"),
                port,
                text(ir, "user"),
                ir.get("password") == null ? null : String.valueOf(ir.get("password")),
                ir.get("keyFile") == null ? null : String.valueOf(ir.get("keyFile")));
        try {
            operations.connect(options);
            // connect 时就打开 SFTP 并 chdir('.')：
            // 一是让 sftp_cwd 立刻可用，二是把 SFTP 的问题暴露在 connect 阶段。
            operations.sftpChdir(".");
        } catch (Exception connectError) {
            operations.close();
            outcomes.add(new CommandOutcome.Error(isAuthenticationFailure(connectError)
                    ? "AuthenticationException failed."
                    : "SSH Exception :" + errorRepr(connectError)));
            return;
        }
        registry.registerCurrent(operations);
        outcomes.add(new CommandOutcome.Status("SSH connected"));
    }

    /**
     * IR 的 {@code port} → 端口号；**没写 PORT 子句时用默认 22**。
     *
     * @throws UserNumber.InvalidNumberException 值不是整数或超出 1..65535
     *         （文案 {@code Invalid SSH port [...]}，由 {@link #connect} 转成可读错误）
     */
    private static int port(Map<String, Object> ir) {
        Object value = ir.get("port");
        if (value == null) {
            return SshConnectOptions.DEFAULT_PORT;
        }
        return UserNumber.port("SSH port", String.valueOf(value));
    }

    private void executeCommand(Map<String, Object> ir, List<CommandOutcome> outcomes) {
        if (!registry.hasCurrent()) {
            outcomes.add(new CommandOutcome.Error("SSH not connected."));
            return;
        }
        SshCommandResult result;
        try {
            result = registry.current().execute(text(ir, "command"));
        } catch (Exception executeError) {
            outcomes.add(new CommandOutcome.Error("SSH Exception :" + errorRepr(executeError)));
            return;
        }
        // stderr 已并进 stdout，**逐行**作为结果输出
        for (String line : result.combinedOutput().split("\n", -1)) {
            if (!line.isEmpty()) {
                outcomes.add(new CommandOutcome.Status(stripTrailingCr(line)));
            }
        }
        outcomes.add(new CommandOutcome.Status("< Command finished with [" + result.exitStatus() + "]"));
    }

    private void setOption(Map<String, Object> ir, List<CommandOutcome> outcomes) {
        if (!registry.hasCurrent()) {
            outcomes.add(new CommandOutcome.Error("SSH not connected."));
            return;
        }
        String option = text(ir, "option");
        if ("encoding".equalsIgnoreCase(option)) {
            String value = text(ir, "value");
            // 项目只支持 UTF-8。接受 UTF-8（含 utf8 简写）并当作无操作；
            // 其它编码明确拒绝，避免"按错误字符集解码出乱码"这种静默错误。
            String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT).replace("-", "");
            if ("UTF8".equals(normalized)) {
                outcomes.add(noOutput());
                return;
            }
            outcomes.add(new CommandOutcome.Error("dbcli only supports UTF-8, but ssh encoding [" + value
                    + "] was requested."));
            return;
        }
        // "Unkonwn" 是冻结文案里的拼写，不能"顺手改对"（否则既有夹具比对失败）
        outcomes.add(new CommandOutcome.Error("Unkonwn ssh option [" + option + "]."));
    }

    // ------------------------------------------------------------------ 工具

    /** SFTP 动作的执行壳：统一做"未连接"检查、把异常转成冻结的 OSError 文案。 */
    private void withSftp(List<CommandOutcome> outcomes, String actionLabel, SftpAction action) {
        if (!registry.hasCurrent()) {
            outcomes.add(new CommandOutcome.Error("SFTP not connected."));
            return;
        }
        try {
            outcomes.add(action.run(registry.current()));
        } catch (PathArguments.InvalidPathException invalidPath) {
            // 路径参数写错不是"SFTP 动作失败"：交给命令层直接给 PathArguments 的可读文案
            throw invalidPath;
        } catch (UserNumber.InvalidNumberException invalidNumber) {
            // 数值参数写错不是"SFTP 动作失败"：交给命令层直接给 UserNumber 的可读文案
            throw invalidNumber;
        } catch (Exception error) {
            // mkdir 的文案在数字前多一个空格（冻结形态），其余动作没有
            String separator = "mkdir".equals(actionLabel) ? " (" : "(";
            throw new SftpFailure(actionLabel + " failed. OSError" + separator + messageOf(error) + ")");
        }
    }

    /** 单个 SFTP 动作。 */
    private interface SftpAction {
        CommandOutcome run(SshOperations operations) throws Exception;
    }

    private static CommandOutcome noOutput() {
        return new CommandOutcome.Status(null);
    }

    private static String messageOf(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static String text(Map<String, Object> ir, String key) {
        Object value = ir.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 本地文件路径（sftp get/put）：相对路径按进程当前目录解析，非法字符给可读错误。 */
    private static Path localPath(Map<String, Object> ir, String key) {
        return PathArguments.parse(text(ir, key));
    }

    /** IR 里的数值字段（uid/gid/fileSize…）→ int；解析与文案都走 {@link UserNumber}。 */
    private static int intValue(Map<String, Object> ir, String key) {
        Object value = ir.get(key);
        return UserNumber.integer(labelOf(key), String.valueOf(value),
                UserNumber.Rule.NON_NEGATIVE_INTEGER, 10);
    }

    /** SFTP 权限位：IR 里是**八进制原文**，这里按 8 进制校验并给出统一文案。 */
    private static int sftpMode(Map<String, Object> ir, String key, String label) {
        return UserNumber.integer(label, text(ir, key), UserNumber.Rule.FILE_MODE, 8);
    }

    /** IR 字段名 → 错误文案里的参数名。 */
    private static String labelOf(String key) {
        return switch (key) {
            case "uid" -> "SFTP uid";
            case "gid" -> "SFTP gid";
            case "fileSize" -> "SFTP file size";
            default -> "SFTP " + key;
        };
    }

    private static String stripTrailingCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /** 认证失败判定：JSch 的 {@code Auth fail}/{@code Auth cancel} 与 {@code AuthenticationException}。 */
    private static boolean isAuthenticationFailure(Exception error) {
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("auth fail")
                || message.contains("auth cancel")
                || message.contains("authentication")
                || error.getClass().getSimpleName().contains("Auth");
    }

    /** 异常的可读表示：{@code ClassName('message')}（错误文案依赖这个形态）。 */
    private static String errorRepr(Exception error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        return error.getClass().getSimpleName() + "('" + message + "')";
    }
}
