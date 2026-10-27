package org.slackerdb.dbproxy.instance;

import java.nio.file.Path;
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

    /** 代理管理口自己的路径段，避免和实例别名抢路由。 */
    public static final Set<String> RESERVED_ALIASES =
            Set.of("admin", "web", "status", "capabilities", "health", "favicon.ico");

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

    public Path binDir() {
        return dir.resolve("bin");
    }

    public Path dataDir() {
        return dir.resolve("data");
    }

    public Path logsDir() {
        return dir.resolve("logs");
    }

    /** dbserver 自己的 logback 日志（路径也写在 conf 的 log= 里）。 */
    public Path serverLog() {
        return logsDir().resolve("server.log");
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
