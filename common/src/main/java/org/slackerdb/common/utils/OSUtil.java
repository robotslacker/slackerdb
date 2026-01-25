package org.slackerdb.common.utils;

import java.io.*;

public class OSUtil {
    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

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
