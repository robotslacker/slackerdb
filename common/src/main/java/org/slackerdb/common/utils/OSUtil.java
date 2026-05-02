package org.slackerdb.common.utils;

import java.io.*;

/**
 * 操作系统工具类。
 * 提供跨平台的操作系统相关功能，包括操作系统检测、后台进程启动和磁盘空间检查。
 *
 * <p>该类封装了Windows和Linux系统的差异，为上层提供统一的API。</p>
 *
 * <p>功能包括：</p>
 * <ul>
 *   <li>操作系统类型检测（Windows/Linux）</li>
 *   <li>跨平台的后台进程启动</li>
 *   <li>磁盘剩余空间查询</li>
 * </ul>
 *
 * <p>注意：后台进程启动方法依赖于系统命令（Windows: cmd /c start, Linux: nohup），
 * 在某些受限环境中可能无法正常工作。</p>
 */
public class OSUtil {
    /**
     * 检测当前操作系统是否为Windows。
     *
     * <p>该方法通过检查系统属性"os.name"是否包含"win"（不区分大小写）来判断是否为Windows系统。</p>
     *
     * @return 如果当前操作系统是Windows则返回true，否则返回false
     */
    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * Windows平台的后台进程启动实现。
     *
     * <p>通过 {@code cmd /c start /B} 命令在后台启动进程，不显示命令窗口。
     * 该方法会立即返回进程ID，不等待进程执行完成。</p>
     *
     * <p>实现原理：将原始命令包装为 {@code cmd /c start /B [command]} 的形式执行。</p>
     *
     * @param command 要执行的命令及其参数数组
     * @return 启动的进程ID
     * @throws Exception 如果进程启动失败
     */
    // Windows特殊处理：通过cmd /c start分离进程
    private static long launchDaemonWindows(String[] command) throws Exception {
        String[] winCmd = new String[command.length + 4];
        winCmd[0] = "cmd";
        winCmd[1] = "/c";
        winCmd[2] = "start";
        winCmd[3] = "/B";
        System.arraycopy(command, 0, winCmd, 4, command.length);

        Process process = new ProcessBuilder(winCmd).start();

        process.getInputStream().close();
        return process.pid();
    }

    /**
     * Linux平台的后台进程启动实现。
     *
     * <p>通过 {@code nohup} 命令启动进程，并将输出重定向到 {@code /dev/null} 实现后台运行。
     * 该方法会等待进程启动完成（最多10秒），确保进程成功启动后返回进程ID。</p>
     *
     * <p>实现原理：</p>
     * <ol>
     *   <li>检测可用的shell（SHELL环境变量或/bin/sh）</li>
     *   <li>将原始命令包装为 {@code nohup [command] >/dev/null 2>&1 &}</li>
     *   <li>通过shell执行包装后的命令</li>
     *   <li>等待启动进程退出（nohup进程本身）</li>
     * </ol>
     *
     * <p>注意：该方法依赖于 {@code Sleeper.sleep()} 进行等待，需要确保Sleeper类可用。</p>
     *
     * @param command 要执行的命令及其参数数组
     * @return 启动的进程ID
     * @throws Exception 如果进程启动失败或超时
     */
    // Linux处理：使用nohup+&实现后台运行
    private static long launchDaemonLinux(String[] command) throws Exception {
        String shellPath = System.getenv("SHELL");
        if (shellPath == null || shellPath.isEmpty()) {
            if (new File("/bin/sh").exists()) {
                shellPath = "/bin/sh";
            }
            else
            {
                throw new RuntimeException("[OS] Daemon start fail. Shell path [" + shellPath + "] is not available]");
            }
        }

        String[] linuxCmd = new String[command.length + 2];
        linuxCmd[0] = "nohup";
        System.arraycopy(command, 0, linuxCmd, 1, command.length);
        linuxCmd[linuxCmd.length - 1] = ">/dev/null 2>&1 &";
        Process process = new ProcessBuilder(
                shellPath,
                "-c",
                String.join(" ", linuxCmd)).start();
        // 等待进程启动完毕，等待最多10秒
        int waitForTimes = 10;
        while (process.isAlive()) {
            if (waitForTimes < 0) {
                throw new RuntimeException("[OS] Daemon start fail, something panic. \n" +
                        " Command: [" + shellPath + "-c" + String.join(" ", linuxCmd) + "]");
            }
            waitForTimes = waitForTimes - 1;
            Sleeper.sleep(1000);
        }
        if (process.exitValue() != 0)
        {
            throw new RuntimeException("[OS] Daemon start fail, exit with [" + process.exitValue() + "]. \n" +
                    " Command: [" + shellPath + "-c" + String.join(" ", linuxCmd) + "]");
        }
        process.getInputStream().close();
        return process.pid();
    }

    /**
     * 启动后台守护进程。
     *
     * <p>该方法根据操作系统类型调用相应的后台进程启动实现：</p>
     * <ul>
     *   <li>Windows: 使用 {@code cmd /c start /B} 在后台启动进程</li>
     *   <li>Linux: 使用 {@code nohup} 和重定向到 {@code /dev/null} 在后台启动进程</li>
     * </ul>
     *
     * <p>注意：该方法会阻塞直到确认进程成功启动（Linux）或立即返回进程ID（Windows）。</p>
     *
     * @param command 要执行的命令及其参数数组
     * @return 启动的进程ID
     * @throws Exception 如果进程启动失败或发生其他错误
     * @see #launchDaemonWindows(String[])
     * @see #launchDaemonLinux(String[])
     */
    public static long launchDaemon(String[] command) throws Exception {
        if (isWindows()) {
            return launchDaemonWindows(command);
        } else {
            return launchDaemonLinux(command);
        }
    }

    /**
     * 检查指定路径所在文件系统的剩余空间
     * @param path 文件路径
     * @return 剩余空间字节数，如果无法获取则返回-1
     */
    public static long getFreeDiskSpace(String path) {
        try {
            File file = new File(path);
            return file.getFreeSpace();
        } catch (Exception e) {
            return -1;
        }
    }
}
