package org.slackerdb.common.utils;

public class OSUtil {
    private static boolean isWindows() {
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
        String[] linuxCmd = new String[command.length + 3];
        linuxCmd[0] = "nohup";
        System.arraycopy(command, 0, linuxCmd, 1, command.length);
        linuxCmd[linuxCmd.length - 2] = "&";
        linuxCmd[linuxCmd.length - 1] = ">/dev/null";
        Process process = new ProcessBuilder(linuxCmd).start();
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
}
