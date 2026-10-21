package org.slackerdb.dbcli.job;

import org.slackerdb.common.utils.OSUtil;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 生产用的子进程命令行：**当前 JVM 的 java + 当前 dbcli 主程序**。
 *
 * <h2>两种启动形态</h2>
 * <ul>
 *   <li><b>发行形态</b>（本类来自一个 jar，例如 {@code slackerdb-dbcli-<版本>-standalone.jar}）：
 *       {@code java -jar <那个 jar> --execute <脚本> …}；</li>
 *   <li><b>开发形态</b>（本类来自 {@code target/classes} 这类目录）：
 *       {@code java -cp <当前 classpath> org.slackerdb.dbcli.Main --execute <脚本> …}。</li>
 * </ul>
 * <p>两种形态都由"我自己在哪儿"推出来，不需要使用者配置任何路径，也不会把版本号硬编进代码。</p>
 *
 * <h2>参数映射</h2>
 * <p>与脚本可见语义一致：{@code script} → {@code --execute}；{@code logon}/{@code profile}/
 * {@code logfile}/{@code reference} → 同名命令行选项；其余参数 → {@code --var 名=值}。
 * <b>编排参数（{@code mode}/{@code timeout}）不透传</b>：它们说的是"父进程怎么管这个作业"，
 * 不是子进程的选项（透传过去会被当成未知选项而以退出码 2 失败）。</p>
 */
public final class SelfJobProcessLauncher implements JobProcessLauncher {

    /** 子进程要跑的主类（开发形态用；发行形态由 {@code -jar} 的清单指定同一个类）。 */
    public static final String MAIN_CLASS = "org.slackerdb.dbcli.Main";

    /** 会按命令行同名选项透传给子进程的运行选项（顺序固定，便于比对命令行）。 */
    private static final List<String> CLI_OPTIONS =
            List.of("logon", "profile", "logfile", "reference");

    @Override
    public List<String> commandFor(JobSpec spec) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        Path jar = selfJar();
        if (jar != null) {
            command.add("-jar");
            command.add(jar.toString());
        } else {
            // 开发形态：把当前 JVM 的 classpath 原样交下去，并显式给出主类
            command.add("-cp");
            command.add(System.getProperty("java.class.path", "."));
            command.add(MAIN_CLASS);
        }
        command.add("--execute");
        command.add(spec.script());
        for (String option : CLI_OPTIONS) {
            String value = spec.options().get(option);
            if (value != null && !value.isBlank()) {
                command.add("--" + option);
                command.add(value);
            }
        }
        for (Map.Entry<String, String> variable : spec.variables().entrySet()) {
            command.add("--var");
            command.add(variable.getKey() + "=" + variable.getValue());
        }
        return command;
    }

    // ------------------------------------------------------------------ 内部

    /** 当前 JVM 的 java 可执行文件（拿不到就从 {@code java.home} 推）。 */
    static String javaExecutable() {
        String command = ProcessHandle.current().info().command().orElse(null);
        if (command != null && !command.isBlank()) {
            return command;
        }
        return Path.of(System.getProperty("java.home", "."), "bin",
                OSUtil.isWindows() ? "java.exe" : "java").toString();
    }

    /** 本类所在的 jar；开发形态（类目录）返回 {@code null}。 */
    static Path selfJar() {
        try {
            var location = SelfJobProcessLauncher.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            Path path = Path.of(location.toURI());
            return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar") ? path : null;
        } catch (URISyntaxException | RuntimeException unknownLocation) {
            // 定位不到自己的位置时退回"开发形态"（用 classpath 启动），比直接失败有用
            return null;
        }
    }
}
