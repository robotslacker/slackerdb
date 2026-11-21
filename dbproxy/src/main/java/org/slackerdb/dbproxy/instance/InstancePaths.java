package org.slackerdb.dbproxy.instance;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 实例目录的路径约定。
 *
 * <p>没有 catalog、没有 instance.json：<b>目录结构本身就是约定</b>。
 * 只要一个目录里有 {@code instance.conf}，它就是一个实例；别名就是目录名。</p>
 */
public record InstancePaths(String alias, Path dir) {

    public static final String CONF_FILE = "instance.conf";
    public static final String PID_FILE = "instance.pid";

    /** 创建中的临时目录前缀（扫描时忽略，启动时 GC）。 */
    public static final String TEMP_PREFIX = ".tmp-";
    /** 删除中的目录前缀（扫描时忽略，启动时 GC）。 */
    public static final String TRASH_PREFIX = ".trash-";

    /**
     * 代理管理口自己的路径段，避免和实例别名抢路由。
     *
     * <p>代理只提供 API（{@code /status}、{@code /capabilities}、{@code /admin/**} 与
     * {@code /<别名>/**} 转发），不提供任何界面；{@code web} / {@code favicon.ico} 因此不再是保留名。</p>
     */
    public static final Set<String> RESERVED_ALIASES =
            Set.of("admin", "status", "capabilities", "health");

    /**
     * 别名规则：小写字母开头，后续为小写字母/数字/下划线/连字符，最长 63。
     *
     * <p>限定小写是刻意的：Windows 文件系统大小写不敏感，Linux 敏感；
     * 如果允许大小写混用，同一个名字在两套系统上的行为会不同。</p>
     */
    private static final Pattern ALIAS_PATTERN = Pattern.compile("[a-z][a-z0-9_-]{0,62}");

    public static InstancePaths of(Path root, String alias) {
        return new InstancePaths(alias, root.resolve(alias));
    }

    /** 规范化用户输入：去空白 + 转小写。 */
    public static String normalizeAlias(String alias) {
        return alias == null ? "" : alias.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValidAlias(String alias) {
        return alias != null && ALIAS_PATTERN.matcher(alias).matches();
    }

    public Path conf() {
        return dir.resolve(CONF_FILE);
    }

    public Path pid() {
        return dir.resolve(PID_FILE);
    }

    /**
     * 实例实际使用的 pid 文件。
     *
     * <p>{@code instance.conf} 里的 {@code pid=} 是 dbserver 真正写入的文件；用户通过 key/value
     * 覆盖它时可能不叫 {@code instance.pid}，也可能指向实例目录之外。相对路径按实例目录解析
     * （与 dbserver 的进程工作目录一致），未配置时退回 {@link #pid()}。</p>
     */
    public Path pid(InstanceConf conf) {
        if (conf != null) {
            String configured = conf.pidFile();
            if (configured != null && !configured.isBlank()) {
                try {
                    Path resolved = Path.of(configured.trim());
                    return (resolved.isAbsolute() ? resolved : dir.resolve(resolved)).normalize();
                } catch (RuntimeException ignored) {
                    // 非法路径不在探测层兜底：按默认位置处理，启动失败会由 dbserver 报出来
                }
            }
        }
        return pid();
    }

    public Path binDir() {
        return dir.resolve("bin");
    }

    public Path dataDir() {
        return dir.resolve("data");
    }

    /**
     * 实例实际使用的数据目录。
     *
     * <p>取 {@code instance.conf} 里的 {@code data_dir=}；相对路径按实例目录解析，未配置时退回
     * {@link #dataDir()}。{@code :memory:} / {@code :ducklake:} 这类标志值不是真实目录，返回 {@code null}。</p>
     */
    public Path dataDir(InstanceConf conf) {
        String configured = conf == null ? null : conf.get("DATA_DIR");
        if (configured == null || configured.isBlank()) {
            return dataDir();
        }
        String value = configured.trim();
        if (value.startsWith(":")) {
            return null;
        }
        try {
            Path resolved = Path.of(value);
            return (resolved.isAbsolute() ? resolved : dir.resolve(resolved)).normalize();
        } catch (RuntimeException invalidPath) {
            return null;
        }
    }

    public Path logsDir() {
        return dir.resolve("logs");
    }

    /**
     * dbserver 日志的默认位置。
     *
     * <p>这个路径只是代理创建实例时写进 {@code log=} 的默认值；用户可以用 {@code log=} 覆盖它，
     * 所以读日志必须走 {@link #logFiles(InstanceConf)}，不能写死这里。</p>
     */
    public Path serverLog() {
        return logsDir().resolve("server.log");
    }

    /**
     * 实例实际写入的 dbserver 日志文件（可能有多个）。
     *
     * <p>{@code instance.conf} 里的 {@code log=}（或它的别名 {@code logfile=}）才是 dbserver 真正
     * 写入的目标，按逗号分隔；{@code CONSOLE} 表示标准输出（代理已经把它收进
     * {@link #consoleLog()}），不是文件。相对路径按实例目录解析（与 dbserver 的进程工作目录一致），
     * {@code ${data}} 按 dbserver 的规则替换成库名。</p>
     *
     * <p>没有配置 {@code log=} 时退回约定位置 {@link #serverLog()}；显式配置成 {@code CONSOLE}
     * 时返回空列表（确实没有文件）。</p>
     */
    public List<Path> logFiles(InstanceConf conf) {
        String configured = conf == null ? null : conf.get("LOG");
        if (configured == null || configured.isBlank()) {
            configured = conf == null ? null : conf.get("LOGFILE");
        }
        if (configured == null || configured.isBlank()) {
            return List.of(serverLog());
        }

        String dataName = conf.dataName();
        List<Path> files = new ArrayList<>();
        for (String part : configured.split(",")) {
            String value = part.trim();
            if (value.isEmpty() || "CONSOLE".equalsIgnoreCase(value)) {
                continue;
            }
            // dbserver 加载配置时会把 ${data} 换成库名，代理读文件时要按同样的规则解析。
            value = value.replace("${data}", dataName);
            if (value.isEmpty()) {
                continue;
            }
            try {
                Path resolved = Path.of(value);
                Path file = (resolved.isAbsolute() ? resolved : dir.resolve(resolved)).normalize();
                if (!files.contains(file)) {
                    files.add(file);
                }
            } catch (RuntimeException invalidPath) {
                // 非法路径不在探测层兜底：日志接口忽略它，启动失败会由 dbserver 报出来
            }
        }
        return files;
    }

    /** 子进程 stdout/stderr：启动失败时唯一的持久证据。 */
    public Path consoleLog() {
        return logsDir().resolve("console.log");
    }

    /** 启动器写下的实际命令行（排障用，不属于 dbserver 的输出）。 */
    public Path launchLog() {
        return logsDir().resolve("launch.log");
    }
}
