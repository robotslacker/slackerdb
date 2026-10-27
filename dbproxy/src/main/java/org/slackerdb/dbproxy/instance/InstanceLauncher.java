package org.slackerdb.dbproxy.instance;

import org.slackerdb.common.utils.OSUtil;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 启动实例进程。
 *
 * <p>代理不持有长期句柄：拉起之后只做"等 pid + 等 /health"，进程就与代理脱钩了
 * （stdio 已重定向到文件，代理被 kill 也不会影响它）。</p>
 *
 * <p>dbserver 运行时按固定顺序自动探测，<b>不引入新的配置项</b>：</p>
 * <ol>
 *   <li>系统属性 {@code slackerdb.dbserver.jar}（测试/应急用）；</li>
 *   <li>代理 jar 同目录的 {@code slackerdb-dbserver-*-standalone.jar}（发行包 jlib/）；</li>
 *   <li>{@code <代理目录>/../jlib}；</li>
 *   <li>仓库布局：{@code <root>/dbserver/target}；</li>
 *   <li>当前目录的 {@code dbserver/target}、{@code jlib}、{@code lib}；</li>
 *   <li>都找不到但 classpath 上有 dbserver 主类 → 用 classpath 模式（开发/测试）。</li>
 * </ol>
 */
public final class InstanceLauncher {

    public static final String DBSERVER_MAIN = "org.slackerdb.dbserver.Main";
    public static final String DBSERVER_JAR_PATTERN = "slackerdb-dbserver-*-standalone.jar";

    private volatile LaunchSpec cached;

    public LaunchSpec resolve() {
        LaunchSpec current = cached;
        if (current == null) {
            synchronized (this) {
                if (cached == null) {
                    cached = detect();
                }
                current = cached;
            }
        }
        return current;
    }

    /**
     * 启动实例进程。
     *
     * @return 子进程句柄（用于"启动失败时快速失败"，不用于长期托管）
     */
    public Process start(InstancePaths paths, InstanceConf conf) throws IOException {
        Files.createDirectories(paths.consoleLog().getParent());
        LaunchSpec spec = resolve();
        List<String> command = spec.command("start", paths.conf().toAbsolutePath());

        // 把实际命令行写进 launch.log：排查"起不来"时第一眼要看的就是它。
        // 单独一个文件，避免把超长的 classpath 混进 console.log 和错误信息里。
        Files.writeString(paths.launchLog(),
                "==== " + java.time.LocalDateTime.now() + " (" + spec.description() + ") ====\n"
                        + String.join(" ", command) + "\n",
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(paths.dir().toAbsolutePath().toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(paths.consoleLog().toFile()));
        // 子进程不会读 stdin；继承即可（DISCARD 是"写"方向的重定向，不能用于输入）
        builder.redirectInput(ProcessBuilder.Redirect.INHERIT);

        // 子进程不需要看到父进程的任何"启动上下文"，只继承环境变量
        builder.environment().putIfAbsent("SLACKERDB_INSTANCE", paths.alias());
        return builder.start();
    }

    private LaunchSpec detect() {
        String javaExecutable = javaExecutable();
        List<Path> candidates = jarCandidates();

        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return new LaunchSpec(javaExecutable, List.of("-jar", candidate.toString()),
                        true, "jar:" + candidate);
            }
        }

        if (classpathModeAvailable()) {
            return new LaunchSpec(javaExecutable,
                    List.of("-cp", absoluteClasspath(), DBSERVER_MAIN),
                    false, "classpath:" + DBSERVER_MAIN);
        }

        throw new InstanceException(InstanceException.INTERNAL,
                "dbserver runtime not found. Set -Dslackerdb.dbserver.jar=<path> or place "
                        + DBSERVER_JAR_PATTERN + " next to the proxy jar. Searched: " + candidates);
    }

    private List<Path> jarCandidates() {
        List<Path> candidates = new ArrayList<>();
        String explicit = System.getProperty("slackerdb.dbserver.jar");
        if (explicit != null && !explicit.isBlank()) {
            candidates.add(Path.of(explicit.trim()).toAbsolutePath());
        }

        Path codeLocation = codeLocation();
        if (codeLocation != null) {
            if (Files.isRegularFile(codeLocation)) {
                Path dir = codeLocation.toAbsolutePath().getParent();
                candidates.addAll(jarsIn(dir));
                candidates.addAll(jarsIn(dir.resolve("jlib")));
                if (dir.getParent() != null) {
                    candidates.addAll(jarsIn(dir.getParent().resolve("jlib")));
                }
            } else {
                Path repositoryRoot = findRepositoryRoot(codeLocation.toAbsolutePath());
                if (repositoryRoot != null) {
                    candidates.addAll(jarsIn(repositoryRoot.resolve("dbserver").resolve("target")));
                }
            }
        }

        Path workingDirectory = Path.of("").toAbsolutePath();
        candidates.addAll(jarsIn(workingDirectory.resolve("dbserver").resolve("target")));
        candidates.addAll(jarsIn(workingDirectory.resolve("jlib")));
        candidates.addAll(jarsIn(workingDirectory.resolve("lib")));
        if (workingDirectory.getParent() != null) {
            candidates.addAll(jarsIn(workingDirectory.getParent().resolve("dbserver").resolve("target")));
        }
        return candidates;
    }

    private static List<Path> jarsIn(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, DBSERVER_JAR_PATTERN)) {
            stream.forEach(jars::add);
        } catch (IOException ignored) {
            return List.of();
        }
        jars.sort(Comparator.comparing(Path::toString).reversed());
        return jars;
    }

    private static Path codeLocation() {
        try {
            var codeSource = InstanceLauncher.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return null;
            }
            return Path.of(codeSource.getLocation().toURI());
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    private static Path findRepositoryRoot(Path start) {
        Path current = start;
        for (int depth = 0; depth < 6 && current != null; depth++) {
            if (Files.isDirectory(current.resolve("dbserver"))
                    && Files.isDirectory(current.resolve("dbproxy"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static boolean classpathModeAvailable() {
        try {
            Class.forName(DBSERVER_MAIN, false, InstanceLauncher.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static String absoluteClasspath() {
        // surefire 用 "booter jar + manifest Class-Path" 时 java.class.path 只有 booter，
        // 但它同时提供了 surefire.test.class.path（完整测试类路径），开发/测试模式优先用它。
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) {
            classpath = System.getProperty("java.class.path", "");
        }
        Path base = Path.of("").toAbsolutePath();
        String[] entries = classpath.split(java.util.regex.Pattern.quote(File.pathSeparator));
        List<String> absolute = new ArrayList<>(entries.length);
        for (String entry : entries) {
            if (entry.isEmpty()) {
                continue;
            }
            Path path = Path.of(entry);
            absolute.add(path.isAbsolute() ? path.toString() : base.resolve(path).toString());
        }
        return String.join(File.pathSeparator, absolute);
    }

    private static String javaExecutable() {
        String suffix = OSUtil.isWindows() ? "java.exe" : "java";
        Path candidate = Path.of(System.getProperty("java.home"), "bin", suffix);
        if (Files.isRegularFile(candidate)) {
            return candidate.toString();
        }
        return suffix;
    }
}
