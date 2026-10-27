package org.slackerdb.dbproxy.instance;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次实例启动所需的"启动器描述"：java 可执行文件 + 一段固定前缀参数。
 *
 * <p>支持两种形态：</p>
 * <ul>
 *   <li>{@code -jar <dbserver-standalone.jar>} —— 发行包形态；</li>
 *   <li>{@code -cp <classpath> org.slackerdb.dbserver.Main} —— 开发/测试形态
 *       （dbserver 只在本工程的 test classpath 上时使用）。</li>
 * </ul>
 */
public record LaunchSpec(String javaExecutable, List<String> prefixArgs, boolean jarMode, String description) {

    public LaunchSpec {
        prefixArgs = List.copyOf(prefixArgs);
    }

    /** 拼出"启动/停止/查看状态"的完整命令行。 */
    public List<String> command(String action, Path confFile) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        command.add("-Dfile.encoding=UTF-8");
        command.addAll(prefixArgs);
        command.add(action);
        command.add("--conf");
        command.add(confFile.toString());
        return command;
    }

    /** 在 {@code start} 之后追加后台运行参数（给 bin/start-daemon 用）。 */
    public List<String> daemonCommand(Path confFile) {
        List<String> command = command("start", confFile);
        command.add("--daemon");
        command.add("true");
        return command;
    }
}
