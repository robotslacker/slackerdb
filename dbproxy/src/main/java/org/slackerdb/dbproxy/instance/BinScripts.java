package org.slackerdb.dbproxy.instance;

import org.slackerdb.common.utils.OSUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/**
 * 生成实例目录下的运维脚本。
 *
 * <p>脚本只是 dbserver 自带 CLI 的薄包装（{@code start}/{@code start --daemon true}/
 * {@code stop}/{@code status}），所以"用户手工运维"和"代理发起操作"走的是同一套协议，
 * 不会出现两套行为。</p>
 */
public final class BinScripts {

    private BinScripts() {
    }

    public static void write(InstancePaths paths, LaunchSpec spec) throws IOException {
        Files.createDirectories(paths.binDir());
        Path conf = Path.of(InstancePaths.CONF_FILE);

        writePair(paths, "start", spec.command("start", conf), "Start the instance in the foreground.");
        writePair(paths, "start-daemon", spec.daemonCommand(conf), "Start the instance in the background.");
        writePair(paths, "stop", spec.command("stop", conf), "Ask the instance to stop (graceful, keeps data).");
        writePair(paths, "status", spec.command("status", conf), "Print the instance status from its own CLI.");
    }

    private static void writePair(InstancePaths paths, String name, List<String> command, String description)
            throws IOException {
        writeShell(paths.binDir().resolve(name + ".sh"), command, description);
        writeBatch(paths.binDir().resolve(name + ".bat"), command, description);
    }

    private static void writeShell(Path file, List<String> command, String description) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/sh\n");
        sb.append("# ").append(description).append('\n');
        sb.append("cd \"$(dirname \"$0\")/..\" || exit 1\n");
        sb.append("exec");
        for (String argument : command) {
            sb.append(' ').append(shellQuote(argument));
        }
        sb.append(" \"$@\"\n");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        setExecutable(file);
    }

    private static void writeBatch(Path file, List<String> command, String description) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\r\n");
        sb.append("rem ").append(description).append("\r\n");
        sb.append("cd /d \"%~dp0..\"\r\n");
        for (int index = 0; index < command.size(); index++) {
            sb.append(index == 0 ? "" : " ");
            sb.append(batchQuote(command.get(index)));
        }
        sb.append(" %*\r\n");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void setExecutable(Path file) {
        if (OSUtil.isWindows()) {
            return;
        }
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统上忽略；脚本仍可直接用 sh 调用
        }
    }

    private static String shellQuote(String value) {
        if (value.isEmpty()) {
            return "\"\"";
        }
        boolean simple = value.matches("[A-Za-z0-9_./:=+-]+");
        if (simple) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("$", "\\$").replace("`", "\\`") + "\"";
    }

    private static String batchQuote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
