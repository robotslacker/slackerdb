package org.slackerdb.common.utils;

import org.slf4j.Logger;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * dbserver / dbproxy 共用的 {@code --daemon}（后台运行）支撑。
 *
 * <h3>为什么单独抽一层</h3>
 * <p>
 * {@code --daemon} 属于"出错也不报错、只是行为不对"的选项：参数拼错会静默变成前台运行，
 * 参数复制不全会让后台副本少启动一个服务，子进程忘了带 {@code --daemon false} 会无限 fork。
 * 两个入口（{@code dbserver.Main} / {@code dbproxy.Main}）各写一份必然各自跑偏，
 * 因此这里集中处理"取值解析、与配置文件的优先级、子进程命令行拼装、拉起后台副本"。
 * </p>
 *
 * <h3>命令行是"有序"的，配置文件才是"键值"的</h3>
 * <p>
 * 调用方原先用 {@code HashMap<String, String>} 收集参数，再靠遍历 map 拼出后台命令行：
 * 参数顺序被 {@code HashMap} 打乱，且丢失了"带值/不带值"的区别。对 {@code --<pluginId>.<param>}
 * 这类插件参数、以及将来可能出现的位置相关选项来说，这是静默变形。这里改为
 * <b>按原始顺序</b>记录 {@code --name [value]}，拼装时原样搬运。
 * </p>
 *
 * <h3>命令行优先于配置文件</h3>
 * <p>
 * 历史实现只做单向覆盖（配置文件说 {@code daemon=true} 就把命令行按下的 {@code --daemon false}
 * 顶掉）。后果是：配置文件里写了 {@code daemon=true} 时，父进程 fork 出的子进程重新读同一个
 * 配置文件，又看到 {@code daemon=true}，于是<b>再 fork 一次</b>——无限递归，
 * 日志里只剩一行行 "Daemon pid [...] has successful started in background."。
 * 现在命令行显式给出 {@code --daemon} 时<b>以命令行为准</b>，配置文件只在命令行没提这件事时生效。
 * </p>
 */
public final class AppDaemon {

    /** 转后台运行开关；取值 {@code true}/{@code false}，裸写 {@code --daemon} 等价于 true。 */
    public static final String DAEMON_OPTION = "daemon";

    private AppDaemon() {
    }

    /**
     * 命令行里的一个选项：{@code --name} 或 {@code --name value}。
     *
     * @param name  选项名，<b>不含</b> {@code --} 前缀，保留用户原始大小写
     * @param value 选项取值；{@code null} 表示该选项在命令行上不带取值
     */
    public record Option(String name, String value) {

        /** @return 还原成命令行形态，例如 {@code --conf} / {@code --conf my.conf} */
        public List<String> toArguments() {
            List<String> args = new ArrayList<>(2);
            args.add("--" + name);
            if (value != null) {
                args.add(value);
            }
            return args;
        }
    }

    /**
     * {@code --daemon} 的解析结果。
     *
     * @param requested 最终是否要转后台（命令行优先，其次配置文件）
     * @param fromCli   命令行上是否显式出现了 {@code --daemon}（用于判定优先级，以及拦住无限 fork）
     */
    public record DaemonIntent(boolean requested, boolean fromCli) {
    }

    // ------------------------------------------------------------------ 命令行解析

    /**
     * 把原始命令行拆成<b>有序</b>的选项列表 + 子命令，并顺带解析 {@code --daemon}。
     *
     * <p>语义与 {@code dbserver.Main} / {@code dbproxy.Main} 里原有的循环一致，只补齐两个
     * 历史上会"静默做错事"的写法：</p>
     * <ul>
     *   <li>{@code --daemon} 后面紧跟另一个选项（如 {@code --daemon --conf x}）时，
     *       视为"裸写 {@code --daemon}"（等价 true），<b>不会</b>吞掉后面的选项；</li>
     *   <li>{@code --daemon=false} 这种等号写法按取值 {@code false} 处理，
     *       而不是当成一个名叫 {@code daemon=false} 的开关。</li>
     * </ul>
     *
     * @param args 原始命令行参数；可为 null
     * @return 解析结果（选项保持原始顺序，重复选项也会重复保留）
     * @throws IllegalArgumentException 出现了第二个子命令，或 {@code --daemon} 的取值既不是 true 也不是 false
     */
    public static ParsedArguments parse(String[] args) {
        List<Option> options = new ArrayList<>();
        String subCommand = null;
        boolean daemonSeen = false;
        boolean daemonValue = false;

        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg == null || arg.isEmpty()) {
                    continue;
                }
                if (!arg.startsWith("--")) {
                    if (subCommand == null) {
                        subCommand = arg;
                    } else {
                        throw new IllegalArgumentException(
                                "Only one subcommand is required. But you have more .. ["
                                        + subCommand + ", " + arg + " ...].");
                    }
                    continue;
                }

                String name = arg.substring(2);
                String value = null;
                int eq = name.indexOf('=');
                if (eq >= 0) {
                    value = name.substring(eq + 1);
                    name = name.substring(0, eq);
                } else if (i + 1 < args.length && !isOption(args[i + 1])) {
                    value = args[++i];
                }
                if (name.isEmpty()) {
                    continue;
                }

                if (DAEMON_OPTION.equalsIgnoreCase(name)) {
                    daemonSeen = true;
                    // 重复给出时以最后一次为准（与配置文件"后出现的覆盖先出现的"一致）
                    daemonValue = parseDaemonFlag(value);
                    // 取值已经解析进 intent，不再作为普通选项搬运给子进程（子进程固定带 --daemon false）
                    continue;
                }
                options.add(new Option(name, value));
            }
        }

        return new ParsedArguments(options, subCommand, new DaemonIntent(daemonValue, daemonSeen));
    }

    /** {@link #parse(String[])} 的结果。 */
    public record ParsedArguments(List<Option> options, String subCommand, DaemonIntent intent) {

        public ParsedArguments {
            options = Collections.unmodifiableList(options);
        }

        /**
         * 把有序选项还原成 {@code --name value} 形态的参数列表。
         *
         * @param skipDaemon 是否跳过 {@code --daemon}（父进程用它拼子进程命令行时跳过）
         */
        public List<String> toArguments(boolean skipDaemon) {
            List<String> flattened = new ArrayList<>();
            for (Option option : options) {
                if (skipDaemon && DAEMON_OPTION.equalsIgnoreCase(option.name())) {
                    continue;
                }
                flattened.addAll(option.toArguments());
            }
            return flattened;
        }
    }

    /**
     * 解析 {@code --daemon} 的取值。
     *
     * @param value 取值；null/空白表示"裸写 --daemon"，按 true 处理
     * @return 是否转后台
     * @throws IllegalArgumentException 取值既不是 true 也不是 false（历史实现会静默按 false 处理，
     *                                  于是 {@code --daemon maybe} 变成前台运行且没有任何提示）
     */
    public static boolean parseDaemonFlag(String value) {
        if (value == null || value.trim().isEmpty()) {
            return true;
        }
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("true")) {
            return true;
        }
        if (trimmed.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException(
                "--" + DAEMON_OPTION + " must be true or false (got: " + value + ")");
    }

    /**
     * 合并"命令行"与"配置文件"两处的 {@code daemon} 取值。
     *
     * @param intent        命令行解析结果（{@link #parse(String[])}）
     * @param configDaemon  配置文件里的 {@code daemon}（未配置传 false）
     * @return 最终意图；命令行显式给出 {@code --daemon} 时以命令行为准
     */
    public static DaemonIntent resolve(DaemonIntent intent, boolean configDaemon) {
        if (intent.fromCli()) {
            return intent;
        }
        return new DaemonIntent(configDaemon, false);
    }

    /**
     * 只有 {@code start} 子命令支持后台运行。
     *
     * @param intent     最终意图（{@link #resolve(DaemonIntent, boolean)} 的结果）
     * @param subCommand 子命令
     * @param logger     用于告警的 logger，可为 null（此时直打控制台，且只走 <b>stderr</b>）
     * @return 实际是否转后台
     */
    public static boolean applySubCommandRestriction(DaemonIntent intent, String subCommand, Logger logger) {
        if (!intent.requested() || subCommand == null || subCommand.equalsIgnoreCase("start")) {
            return intent.requested();
        }
        String message = "Warn: [" + subCommand + "] does not support daemon mode, ignore it.";
        if (logger != null) {
            logger.warn("[DAEMON] {}", message);
        } else {
            // 此时日志系统还没初始化（--log 的处理在配置解析之后），所以直打到控制台：
            // 只走 stderr —— 告警是**诊断信息**，而调用方的 stdout 纪律是"只放数据"
            // （它会被重定向、被逐字节比对），一句 Warn 混进去就是污染
            System.err.println(message);
        }
        return false;
    }

    // ------------------------------------------------------------------ 后台命令行拼装

    /**
     * 构造后台子进程的完整命令行；开发模式（不是从 jar 启动）返回 null。
     *
     * @param anchor     用于推断当前 jar 的类（{@code Main.class}）
     * @param subCommand 子命令，如 {@code start}
     * @param options    有序选项（已经剥掉 {@code --daemon}）
     * @throws Exception 找不到可用的 shell（仅 POSIX 路径）
     */
    public static List<String> buildDaemonCommand(Class<?> anchor, String subCommand,
                                                  List<Option> options) throws Exception {
        return buildDaemonCommand(resolveJarPath(anchor), subCommand, options);
    }

    /**
     * 命令行拼装本体：与"当前进程是否从 jar 启动"解耦，便于单测直接喂一个假 jar 路径。
     *
     * <p>返回值已经是<b>平台相关</b>的后台启动命令：Windows 上是
     * {@code cmd /c start "" /B <前景命令>}，POSIX 上是 {@code <shell> -c "nohup ... &"}。
     * 也就是说 POSIX 下整条前景命令被压进了 shell 行里，不再是一个一个的数组元素。
     * 想检查"子进程收到哪些参数"，用 {@link #buildForegroundCommand(String, String, List)}
     * （它是平台无关的），别去解包这里的返回值。</p>
     *
     * @param jarPath 可执行 jar 的路径；null 表示开发模式（无法转后台）
     * @throws Exception 找不到可用的 shell（仅 POSIX 路径）
     */
    public static List<String> buildDaemonCommand(String jarPath, String subCommand,
                                                  List<Option> options) throws Exception {
        List<String> foreground = buildForegroundCommand(jarPath, subCommand, options);
        if (foreground == null) {
            return null;
        }
        return List.of(OSUtil.buildDaemonCommand(foreground.toArray(new String[0])));
    }

    /**
     * 拼出子进程的"前景形态"命令行，<b>不做</b>平台包装：{@code java -jar <jar> <选项...> --daemon false <子命令>}。
     *
     * <p>抽出来是为了让"子进程到底收到哪些参数"这件事能被平台无关地断言：
     * {@link #buildDaemonCommand(String, String, List)} 的返回值在 POSIX 下是一条 shell 命令字符串，
     * 参数边界已经被 {@code OSUtil} 的转义吃掉，不能按数组元素检查。</p>
     *
     * @param jarPath 可执行 jar 的路径；null 表示开发模式（无法转后台）
     * @return 前景命令；开发模式返回 null
     */
    static List<String> buildForegroundCommand(String jarPath, String subCommand,
                                               List<Option> options) {
        if (jarPath == null) {
            return null;
        }

        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                OSUtil.isWindows() ? "java.exe" : "java").toString();

        List<String> foreground = new ArrayList<>();
        foreground.add(javaExecutable);
        foreground.add("-jar");
        foreground.add(jarPath);

        // 原样搬运用户的参数（顺序不变），但跳过 --daemon
        for (Option option : options) {
            if (DAEMON_OPTION.equalsIgnoreCase(option.name())) {
                continue;
            }
            foreground.add("--" + option.name());
            if (option.value() != null) {
                foreground.add(option.value());
            }
        }

        // 子进程绝不能再转后台，否则会无限 fork 下去。显式写成 false，
        // 这样即使配置文件里写着 daemon=true，子进程也不会再 fork（命令行优先）。
        foreground.add("--" + DAEMON_OPTION);
        foreground.add("false");

        if (subCommand != null && !subCommand.isBlank()) {
            foreground.add(subCommand);
        }

        return List.copyOf(foreground);
    }

    // ------------------------------------------------------------------ 拉起后台副本

    /**
     * 拉起后台副本。
     *
     * <p><b>输出去向</b>：开发模式（不是从 jar 启动，无法转后台）的那条警告走 <b>stderr</b>
     * ——警告是诊断信息，而调用方的 stdout 只放数据（见方法体里的注释）；
     * "副本已在后台起来"这类状态行仍走 stdout，它是这条命令的产物。</p>
     *
     * @param anchor        用于推断当前 jar 的类
     * @param subCommand    子命令
     * @param options       有序选项（已经剥掉 {@code --daemon}）
     * @param logTarget     日志落盘目标，仅用于提示；可为 null（表示只写控制台）
     * @param logOptionName 日志选项名（如 {@code log}），仅用于提示；可为 null
     * @param logger        用于记录启动信息的 logger；可为 null（此时只打印到控制台）
     * @return true 表示后台副本已启动（调用方应当立即退出）；false 表示开发模式，调用方应继续前台运行
     * @throws Exception 启动后台进程失败
     */
    public static boolean startInBackground(Class<?> anchor, String subCommand, List<Option> options,
                                            String logTarget, String logOptionName,
                                            Logger logger) throws Exception {
        List<String> command = buildDaemonCommand(anchor, subCommand, options);
        if (command == null) {
            String message = "Warn: --" + DAEMON_OPTION
                    + " is not supported in dev mode (not started from a jar); running in the foreground.";
            if (logger != null) {
                logger.warn("[DAEMON] {}", message);
            } else {
                // 走 stderr：这是**诊断信息**，不是命令的产物。调用方（dbcli 的 --execute、
                // dbserver 的启动输出）的 stdout 纪律是"只放数据"——它会被重定向、被逐字节比对，
                // 一句警告混进去就是污染（本条的日志系统此时还没初始化，所以只能直打控制台）。
                System.err.println(message);
            }
            return false;
        }

        OSUtil.launchDaemon(command.toArray(new String[0]));

        // 直接打到启动终端：日志可能已经只写文件了，但"父进程已退出、后台副本在跑"这件事用户必须看得见。
        //
        // 这里刻意<b>不打印 pid</b>：Windows 上后台副本是经 {@code cmd /c start} 拉起来的，
        // 拿到的是那个中间 shell 的 pid，不是真正干活的那个 java 进程；把它当"服务 pid"报出去
        // 只会误导（用它去 stop/kill 会打错目标）。真正的服务 pid 由 {@code --pid} 文件提供，
        // 由副本自己写入。
        String logDesc = logTarget == null || logTarget.isBlank() ? "CONSOLE" : logTarget;
        System.out.println("Daemon started in background (log: " + logDesc + ").");
        if (logTarget == null || logTarget.isBlank()) {
            // 这一条是"提醒"，属于诊断信息 -> stderr（"副本已经起来"那句状态行才是命令产物，留 stdout）
            System.err.println("Warn: --" + (logOptionName == null ? "log" : logOptionName)
                    + " was not given; background output stays on this console and may be lost when it closes.");
        }
        if (logger != null) {
            logger.info("[DAEMON] Daemon started in background (log:{})."
                    + " Check the --pid file for the service pid.", logDesc);
        }
        return true;
    }

    /**
     * 推断当前进程是从哪个 jar 启动的。
     *
     * <p>可执行 jar 的类来自 jar 内部，不同打包/加载方式给出的 CodeSource 形态并不一样
     * （Spring Boot 3.2+ 的 {@code jar:nested:/path/app.jar/!BOOT-INF/classes/!/}、
     * 之前的 {@code jar:file:/path/app.jar!/BOOT-INF/classes!/}、普通类路径的
     * {@code file:/path/app.jar}），这里把见过的几种都归一化掉。</p>
     *
     * @return jar 的规范路径；开发模式（从 classes 目录启动、或路径不是 jar）返回 null
     */
    static String resolveJarPath(Class<?> anchor) {
        try {
            java.security.CodeSource source = anchor.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            String path = URLDecoder.decode(source.getLocation().getPath(), StandardCharsets.UTF_8);

            // 砍掉 "!/xxx"（Boot < 3.2）或 "/!xxx"（Boot >= 3.2 nested 形式）之后的部分
            int cut = path.indexOf("/!");
            if (cut < 0) {
                cut = path.indexOf("!/");
            }
            if (cut >= 0) {
                path = path.substring(0, cut);
            }

            // 逐层剥掉协议前缀：jar:nested:file: ...
            boolean stripped = true;
            while (stripped) {
                stripped = false;
                for (String prefix : new String[]{"jar:", "nested:", "zip:", "wsjar:", "file:"}) {
                    if (path.regionMatches(true, 0, prefix, 0, prefix.length())) {
                        path = path.substring(prefix.length());
                        stripped = true;
                        break;
                    }
                }
            }

            // Windows 盘符前多出来的斜杠：/C:/x/app.jar -> C:/x/app.jar
            if (path.matches("^/[A-Za-z]:[/\\\\].*")) {
                path = path.substring(1);
            }
            if (!path.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return null;
            }
            File jar = new File(path);
            return jar.isFile() ? jar.getCanonicalPath() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isOption(String arg) {
        return arg != null && arg.startsWith("--");
    }
}
