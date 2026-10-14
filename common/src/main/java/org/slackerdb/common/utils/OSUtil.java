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
 *   <li>跨平台的后台进程启动（{@link #buildDaemonCommand(String[])} 负责拼装，
 *       {@link #launchDaemon(String[])} 负责拉起）</li>
 *   <li>磁盘剩余空间查询</li>
 * </ul>
 *
 * <p>注意：后台进程启动方法依赖于系统命令（Windows: cmd /c start, Linux: nohup），
 * 在某些受限环境中可能无法正常工作。</p>
 *
 * <p>{@code --daemon} 的取值解析、与配置文件的优先级、子进程参数复制见
 * {@link AppDaemon}；本类只负责"给定命令，怎么脱离当前进程"这一层。</p>
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
     * 把"前台命令"包装成"后台启动命令"，不真正启动进程（便于单测直接检查拼装结果）。
     *
     * <ul>
     *   <li>Windows：{@code cmd /c start "" /B <command...>}</li>
     *   <li>Linux：{@code <shell> -c "nohup <command...> >/dev/null 2>&1 &"}</li>
     * </ul>
     *
     * <p>与历史实现相比修掉了两个"静默出错"的坑：</p>
     * <ol>
     *   <li><b>Windows 上显式给出 {@code start} 的标题参数。</b>
     *       {@code cmd /c start} 会把第一个带引号的参数当成窗口标题，而 java 可执行文件路径
     *       （{@code C:\Program Files\...\java.exe}）几乎总是带空格、必然被引号包住：
     *       {@code start /B "C:\Program Files\...\java.exe" -jar app.jar} 会被解释成
     *       "标题 = java 路径，命令 = -jar"，直接失败。写一个空标题 {@code ""} 占位即可。</li>
     *   <li><b>Linux 上对参数做 shell 转义。</b>
     *       {@code --log "/var/log/slackerdb/my log.log"} 这类带空格的取值如果直接拼进
     *       {@code sh -c} 的字符串，会被 shell 拆成多个参数，后台进程收到的日志路径是错的
     *       （而且是静默出错）。</li>
     * </ol>
     *
     * @param command 前台命令及其参数
     * @return 可直接交给 {@link ProcessBuilder} 的后台启动命令
     * @throws Exception 找不到可用的 shell（仅 POSIX 路径）
     */
    public static String[] buildDaemonCommand(String[] command) throws Exception {
        if (isWindows()) {
            return buildWindowsDaemonCommand(command);
        }
        String shellPath = System.getenv("SHELL");
        if (shellPath == null || shellPath.isEmpty()) {
            shellPath = new File("/bin/sh").exists() ? "/bin/sh" : null;
        }
        return buildPosixDaemonCommand(shellPath, command);
    }

    /**
     * Windows 分支：{@code cmd /c start "" /B <command...>}。
     *
     * <p>注意这里<b>刻意不做任何引号加工</b>：命令和参数原样交给 {@code ProcessBuilder}，
     * 由它按 Windows 的参数规则转义。{@code cmd start} 在重新拼装命令行时会保留引号，
     * 下游 java 进程拿到的参数与前台启动逐字符一致——包括带空格的取值。</p>
     *
     * <p>（另一个看似更"稳"的写法是把整条命令塞进一个字符串里自己加引号，例如
     * {@code cmd /c start "" /B "java.exe -jar a.jar --conf my conf"}。但那会让
     * {@code cmd} 在解析 "/c" 之后的内容时需要再剥一层引号，反而更容易把参数拆错，
     * 且无法用单元测试覆盖。保持"一个参数一个数组元素"最简单也最可验证。）</p>
     */
    static String[] buildWindowsDaemonCommand(String[] command) {
        String[] winCmd = new String[command.length + 5];
        winCmd[0] = "cmd";
        winCmd[1] = "/c";
        winCmd[2] = "start";
        // 占住 start 的 title 位置，否则带引号的 java 路径会被当成窗口标题
        winCmd[3] = "\"\"";
        winCmd[4] = "/B";
        System.arraycopy(command, 0, winCmd, 5, command.length);
        return winCmd;
    }

    /**
     * POSIX 分支：{@code <shell> -c "nohup <command...> >/dev/null 2>&1 &"}。
     *
     * @param shellPath 可用的 shell；null 表示找不到（抛异常）
     */
    static String[] buildPosixDaemonCommand(String shellPath, String[] command) {
        if (shellPath == null || shellPath.isEmpty()) {
            throw new RuntimeException("[OS] Daemon start fail. Shell path [" + shellPath + "] is not available]");
        }
        StringBuilder line = new StringBuilder("nohup");
        for (String part : command) {
            // 必须逐参数转义：直接拼字符串会让带空格的取值被 shell 拆成多个参数（静默出错）
            line.append(' ').append(shellQuote(part));
        }
        line.append(" >/dev/null 2>&1 &");
        return new String[]{shellPath, "-c", line.toString()};
    }

    /** POSIX shell 单引号转义：{@code it's} -> {@code 'it'\''s'}。 */
    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Windows平台的后台进程启动实现。
     *
     * <p>通过 {@code cmd /c start /B} 命令在后台启动进程，不显示命令窗口。
     * 该方法会立即返回进程ID，不等待进程执行完成。</p>
     *
     * <p>实现原理见 {@link #buildDaemonCommand(String[])}。</p>
     *
     * @param command 要执行的命令及其参数数组
     * @return 启动的进程ID
     * @throws Exception 如果进程启动失败
     */
    // Windows特殊处理：通过cmd /c start分离进程
    private static long launchDaemonWindows(String[] command) throws Exception {
        Process process = new ProcessBuilder(buildDaemonCommand(command)).start();

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
        String[] linuxCmd = buildDaemonCommand(command);
        Process process = new ProcessBuilder(linuxCmd).start();
        // 等待进程启动完毕，等待最多10秒
        int waitForTimes = 10;
        while (process.isAlive()) {
            if (waitForTimes < 0) {
                throw new RuntimeException("[OS] Daemon start fail, something panic. \n" +
                        " Command: [" + String.join(" ", linuxCmd) + "]");
            }
            waitForTimes = waitForTimes - 1;
            Sleeper.sleep(1000);
        }
        if (process.exitValue() != 0)
        {
            throw new RuntimeException("[OS] Daemon start fail, exit with [" + process.exitValue() + "]. \n" +
                    " Command: [" + String.join(" ", linuxCmd) + "]");
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
