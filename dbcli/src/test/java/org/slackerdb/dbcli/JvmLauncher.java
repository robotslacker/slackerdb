package org.slackerdb.dbcli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 测试用的"起一个真实子 JVM"小工具（跨进程会合与 {@code mode=process} 的测试需要**真进程**）。
 *
 * <p>只做两件事：拼出 {@code java -cp <当前 classpath> <主类> <参数…>}，以及"等它结束并收拢输出"。
 * classpath 直接沿用当前 JVM 的（surefire 下是一个带 {@code Class-Path} 清单的引导 jar，
 * 子 JVM 用 {@code -cp} 指过去同样能解析）。</p>
 */
public final class JvmLauncher {

    private JvmLauncher() {
    }

    /** 当前 JVM 的 java 可执行文件（拿不到就从 {@code java.home} 推）。 */
    public static String javaExecutable() {
        String command = ProcessHandle.current().info().command().orElse(null);
        if (command != null && !command.isBlank()) {
            return command;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home", "."), "bin", windows ? "java.exe" : "java").toString();
    }

    /** 起一个子 JVM（stderr 合并进 stdout，便于诊断）。 */
    public static Process start(String mainClass, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(System.getProperty("java.class.path", "."));
        command.add(mainClass);
        command.addAll(List.of(args));
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    /**
     * 等子进程结束并收拢它的全部输出。
     *
     * @param timeoutMillis 超时后**强杀整棵进程树**并返回已读到的输出
     */
    public static String awaitOutput(Process process, long timeoutMillis) throws IOException {
        StringBuilder output = new StringBuilder();
        Thread pump = new Thread(() -> {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // 进程被杀时读会失败：保留已读到的部分
            }
        }, "test-jvm-pump");
        pump.setDaemon(true);
        pump.start();
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            pump.join(2_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        synchronized (output) {
            return output.toString();
        }
    }
}
