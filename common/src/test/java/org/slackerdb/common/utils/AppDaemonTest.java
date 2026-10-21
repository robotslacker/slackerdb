package org.slackerdb.common.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --daemon} 的验收：取值解析、与配置文件的优先级、子进程命令行拼装、真的能拉起后台进程。
 *
 * <h3>为什么这样测</h3>
 * <p>
 * {@code --daemon} 属于"出错也不报错、只是行为不对"的选项，历史上踩过的坑都长这样：
 * </p>
 * <ul>
 *   <li><b>无限 fork</b>：父进程 fork 出的子进程重新读配置文件，又看到 {@code daemon=true}，
 *       于是再 fork 一次。日志里只剩一行行 "Daemon pid [...] has successful started"，
 *       机器上留一堆半死的 java 进程。所以这里既验证"命令行优先于配置文件"这条规则，
 *       也验证子进程命令行里<b>一定</b>带着 {@code --daemon false}；</li>
 *   <li><b>静默变形</b>：靠遍历 {@code HashMap} 复制参数会打乱顺序、丢掉"带值/不带值"的区别，
 *       后台副本可能少起一个服务。所以这里验证参数顺序和形态逐字保留；</li>
 *   <li><b>起不来 / 起来了但参数是错的</b>：{@code cmd /c start} 会把第一个带引号的参数
 *       当成窗口标题（java 路径几乎总是带空格），POSIX 下不转义则带空格的取值会被 shell 拆开。
 *       这两个都不是靠肉眼看代码能确认的，所以最后有一段<b>真的 fork 一个 JVM</b>的验收，
 *       让子进程把自己的参数原样写回文件再比对。</li>
 * </ul>
 *
 * <p><b>断言写在哪一层</b>：拼装结果分两步，一步平台无关、一步平台相关。
 * {@code AppDaemon.buildForegroundCommand} 给出 {@code java -jar <jar> <选项...> --daemon false <子命令>}，
 * 任何系统上都一样；{@code AppDaemon.buildDaemonCommand} 再把它包成后台启动命令——
 * Windows 上是 {@code cmd /c start "" /B <参数...>}（一个参数一个数组元素），
 * POSIX 上是 {@code <shell> -c "nohup ... &"}（整条命令被压进一个字符串）。
 * 因此<b>不能</b>拿 {@code buildDaemonCommand} 的返回值按数组元素去检查参数：
 * 那在 Linux 上必然全部失配（这组用例确实在 Linux 回归里红过一次，{@code child.indexOf("--daemon")}
 * 返回 -1、{@code child.get(0)} 是 {@code /bin/bash}）。现在"子进程收到哪些参数"一律断言
 * 平台无关的那一层，平台包装由 {@code daemonWrapperKeepsArguments} 单独覆盖。
 * </p>
 *
 * <p>断言消息刻意写成英文：surefire 在中文控制台上会把非 ASCII 打成乱码，
 * 而失败信息正是最需要看清的部分。</p>
 */
class AppDaemonTest {

    /** 夹具读取输出路径用的系统属性名；与 {@code DaemonArgsFixture} 里的常量必须一致。 */
    private static final String ARGS_FILE_PROPERTY = "daemon.args.file";

    /** 夹具的类名。它在默认包里，具名包无法 import，只能写字面量。 */
    private static final String FIXTURE_CLASS = "DaemonArgsFixture";

    // ------------------------------------------------------------------ 取值解析

    @Test
    @DisplayName("--daemon 取值：true/false/裸写，大小写不敏感")
    void parsesDaemonFlag() {
        assertTrue(AppDaemon.parseDaemonFlag(null), "bare --daemon means true");
        assertTrue(AppDaemon.parseDaemonFlag("  "));
        assertTrue(AppDaemon.parseDaemonFlag("true"));
        assertTrue(AppDaemon.parseDaemonFlag("TRUE"));
        assertFalse(AppDaemon.parseDaemonFlag("false"));
        assertFalse(AppDaemon.parseDaemonFlag("False"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AppDaemon.parseDaemonFlag("maybe"));
        assertTrue(e.getMessage().contains("true or false"), e.getMessage());
    }

    @Test
    @DisplayName("--daemon 取值非法时解析阶段就报错，而不是静默变成前台运行")
    void rejectsInvalidDaemonValue() {
        // 历史实现是 equalsIgnoreCase("true")，于是 "--daemon maybe" 静默变成前台运行
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AppDaemon.parse(new String[]{"--daemon", "maybe", "start"}));
        assertTrue(e.getMessage().contains("--daemon"), e.getMessage());
        assertTrue(e.getMessage().contains("maybe"), e.getMessage());
    }

    @Test
    @DisplayName("命令行解析：子命令、选项顺序、带值/不带值 都原样保留")
    void keepsOptionOrder() {
        AppDaemon.ParsedArguments parsed = AppDaemon.parse(new String[]{
                "--conf", "my conf/dbserver.conf",
                "--plugin.home", "/opt/a b",
                "--daemon", "true",
                "--locale", "zh_CN",
                "start"});

        assertEquals("start", parsed.subCommand());
        assertEquals(List.of(
                        "--conf", "my conf/dbserver.conf",
                        "--plugin.home", "/opt/a b",
                        "--locale", "zh_CN"),
                parsed.toArguments(false),
                "order must match what the user typed, and a value with spaces must stay one token");
        assertTrue(parsed.intent().requested());
        assertTrue(parsed.intent().fromCli());
    }

    @Test
    @DisplayName("--name=value 等号写法：拆成 name + value，父子进程保持同一套解读")
    void parsesEqualsSyntax() {
        AppDaemon.ParsedArguments parsed = AppDaemon.parse(new String[]{
                "--plugin.home=/opt/a b", "--port=5432", "start"});

        assertEquals(List.of("--plugin.home", "/opt/a b", "--port", "5432"),
                parsed.toArguments(false),
                "the old parser kept a literal option named 'port=5432',"
                        + " so the background copy never saw the port at all");
    }

    @Test
    @DisplayName("--name= 空取值：与 --name 一样能传给子进程")
    void parsesEmptyValueSyntax() {
        assertEquals(List.of("--data_dir", ""),
                AppDaemon.parse(new String[]{"--data_dir=", "start"}).toArguments(false));
    }

    @Test
    @DisplayName("裸写 --daemon 不会吞掉紧跟其后的选项；等号写法 --daemon=false 也认")
    void bareDaemonDoesNotSwallowNextOption() {
        AppDaemon.ParsedArguments bare = AppDaemon.parse(
                new String[]{"--daemon", "--conf", "a.conf", "start"});
        assertTrue(bare.intent().requested(), "bare --daemon means true");
        assertEquals(List.of("--conf", "a.conf"), bare.toArguments(false),
                "--conf must survive: it must not be taken as the value of --daemon");

        AppDaemon.ParsedArguments eq = AppDaemon.parse(new String[]{"--daemon=false", "start"});
        assertFalse(eq.intent().requested());
        assertTrue(eq.intent().fromCli(),
                "the equals form still counts as an explicit command line choice");
        assertEquals(List.of(), eq.toArguments(false),
                "--daemon is not carried to the child as a plain option");
    }

    @Test
    @DisplayName("命令行解析：最多一个子命令")
    void rejectsSecondSubCommand() {
        assertThrows(IllegalArgumentException.class,
                () -> AppDaemon.parse(new String[]{"start", "stop"}));
    }

    // ------------------------------------------------------------------ 与配置文件的优先级

    @Test
    @DisplayName("命令行优先于配置文件：这是无限 fork 的根因")
    void commandLineWinsOverConfiguration() {
        // 配置文件里写着 daemon=true，用户在命令行上明确要求"不要后台"
        AppDaemon.DaemonIntent off = AppDaemon.resolve(
                new AppDaemon.DaemonIntent(false, true), true);
        assertFalse(off.requested(),
                "an explicit --daemon false must beat daemon=true in the config file");

        AppDaemon.DaemonIntent on = AppDaemon.resolve(
                new AppDaemon.DaemonIntent(true, true), false);
        assertTrue(on.requested(),
                "an explicit --daemon true must beat daemon=false in the config file");
    }

    @Test
    @DisplayName("命令行没提 --daemon 时，才轮到配置文件说话")
    void configurationUsedWhenCommandLineIsSilent() {
        assertTrue(AppDaemon.resolve(new AppDaemon.DaemonIntent(false, false), true).requested(),
                "configuration daemon=true with a silent command line should fork");
        assertFalse(AppDaemon.resolve(new AppDaemon.DaemonIntent(false, false), false).requested());
    }

    @Test
    @DisplayName("只有 start 支持后台运行，其他子命令退化为前台并告警（告警只走 stderr）")
    void onlyStartSupportsDaemon() throws Exception {
        AppDaemon.DaemonIntent intent = new AppDaemon.DaemonIntent(true, true);
        boolean[] allowed = new boolean[5];
        Console console = captureConsole(() -> {
            allowed[0] = AppDaemon.applySubCommandRestriction(intent, "start", null);
            allowed[1] = AppDaemon.applySubCommandRestriction(intent, "START", null);
            allowed[2] = AppDaemon.applySubCommandRestriction(intent, "stop", null);
            allowed[3] = AppDaemon.applySubCommandRestriction(intent, "status", null);
            // 前台请求本来就不该被这条规则改变
            allowed[4] = AppDaemon.applySubCommandRestriction(
                    new AppDaemon.DaemonIntent(false, true), "stop", null);
        });

        assertTrue(allowed[0]);
        assertTrue(allowed[1], "subcommand comparison is case insensitive");
        assertFalse(allowed[2]);
        assertFalse(allowed[3]);
        assertFalse(allowed[4], "a foreground request must not be changed by this rule");

        // 告警是诊断信息：只许出现在 stderr（stdout 会被重定向、被逐字节比对，混一句就是污染）
        assertEquals("", console.out(), "a warning must never reach stdout");
        assertTrue(console.err().contains("Warn: [stop] does not support daemon mode, ignore it."),
                "the warning must go to stderr: " + console.err());
        assertTrue(console.err().contains("Warn: [status] does not support daemon mode, ignore it."),
                "the warning must go to stderr: " + console.err());
    }

    // ------------------------------------------------------------------ 子进程命令行

    @Test
    @DisplayName("子进程命令行：必须显式带 --daemon false，否则无限 fork")
    void childCommandAlwaysDisablesDaemon() throws Exception {
        AppDaemon.ParsedArguments parsed = AppDaemon.parse(new String[]{
                "--conf", "dbserver.conf", "--daemon", "true", "start"});

        // 断言的是"前景形态"（平台无关）：buildDaemonCommand 在 POSIX 下返回的是
        // <shell> -c "nohup ... &" 这样一条 shell 命令字符串，参数边界已被转义吃掉，
        // 不能按数组元素去检查。平台包装另有一条专门的用例（daemonWrapperKeepsArguments）。
        List<String> child = AppDaemon.buildForegroundCommand(
                fakeJar(), parsed.subCommand(), parsed.options());

        assertNotNull(child);
        int daemonIndex = child.indexOf("--daemon");
        assertTrue(daemonIndex >= 0, "child command must carry --daemon explicitly: " + child);
        assertEquals("false", child.get(daemonIndex + 1),
                "child must get --daemon false, otherwise daemon=true in the config file"
                        + " makes it fork again (infinite recursion): " + child);
        assertFalse(child.contains("--daemon=true"), "child command: " + child);

        assertEquals("start", child.get(child.size() - 1), "subcommand must come last: " + child);
    }

    @Test
    @DisplayName("子进程命令行：参数顺序与形态逐字保留，带空格的取值是一个整体")
    void childCommandKeepsArgumentsIntact() throws Exception {
        String jar = fakeJar();
        AppDaemon.ParsedArguments parsed = AppDaemon.parse(new String[]{
                "--data_dir", "C:\\Program Files\\slackerdb data",
                "--plugin.home", "/opt/a b",
                "--port", "5432",
                "--daemon", "true",
                "start"});

        List<String> child = AppDaemon.buildForegroundCommand(
                jar, parsed.subCommand(), parsed.options());

        assertEquals(jar, child.get(child.indexOf("-jar") + 1), "must re-execute the same jar");
        assertEquals(List.of(
                        "--data_dir", "C:\\Program Files\\slackerdb data",
                        "--plugin.home", "/opt/a b",
                        "--port", "5432",
                        "--daemon", "false",
                        "start"),
                child.subList(child.indexOf("--data_dir"), child.size()),
                "arguments must be carried over verbatim, in order,"
                        + " keeping a spaced value as a single token");
    }

    @Test
    @DisplayName("平台包装：后台命令行里原样保留前景命令的每个参数（一个不少、顺序不变）")
    void daemonWrapperKeepsArguments() throws Exception {
        String jar = fakeJar();
        AppDaemon.ParsedArguments parsed = AppDaemon.parse(new String[]{
                "--conf", "my conf/dbserver.conf",
                "--plugin.home", "/opt/a b",
                "--daemon", "true",
                "start"});

        List<String> foreground = AppDaemon.buildForegroundCommand(
                jar, parsed.subCommand(), parsed.options());
        assertNotNull(foreground);
        String[] wrapped = OSUtil.buildDaemonCommand(foreground.toArray(new String[0]));

        if (OSUtil.isWindows()) {
            // cmd /c start "" /B <前景命令...>：一个参数一个数组元素，不应有任何增删
            assertEquals(foreground, List.of(wrapped).subList(5, wrapped.length),
                    "cmd /c start \"\" /B must be followed by the foreground command verbatim: "
                            + List.of(wrapped));
        } else {
            // <shell> -c "nohup <前景命令...> >/dev/null 2>&1 &"：参数边界在 shell 行里，
            // 所以检查"逐个 shellQuote 之后连成一串"是否原样出现——这同时证明了顺序和个数
            assertEquals(3, wrapped.length, "POSIX daemon command is <shell> -c <line>: "
                    + List.of(wrapped));
            String expected = foreground.stream()
                    .map(OSUtil::shellQuote)
                    .collect(Collectors.joining(" "));
            int at = wrapped[2].indexOf(expected);
            assertTrue(at >= 0,
                    "the shell line must carry every foreground argument, quoted and in order\n"
                            + "  expected: " + expected + "\n  actual:   " + wrapped[2]);
            assertEquals("nohup " + expected + " >/dev/null 2>&1 &", wrapped[2],
                    "nothing may be added or dropped around the foreground command: " + wrapped[2]);
        }
    }

    @Test
    @DisplayName("开发模式（从 classes 启动）：不拼命令行，退化为前台运行")
    void devModeFallsBackToForeground() throws Exception {
        assertNull(AppDaemon.resolveJarPath(AppDaemonTest.class),
                "surefire starts from target/classes, so this must be detected as dev mode");
        assertNull(AppDaemon.buildDaemonCommand(
                        AppDaemonTest.class, "start",
                        AppDaemon.parse(new String[]{"--daemon", "true", "start"}).options()),
                "dev mode must return null so that the caller keeps running in the foreground,"
                        + " instead of building a command line that cannot work");

        // 调用方靠这个返回值决定"继续前台跑"，所以这里把它也钉住
        boolean[] started = new boolean[1];
        Console console = captureConsole(() -> started[0] = AppDaemon.startInBackground(
                AppDaemonTest.class, "start",
                AppDaemon.parse(new String[]{"--daemon", "true", "start"}).options(),
                null, "log", null));
        assertFalse(started[0],
                "startInBackground must report false in dev mode so the caller does not exit");

        // 这条警告同样是诊断信息：stdout 一个字符都不该多出来
        assertEquals("", console.out(), "the dev mode warning must never reach stdout");
        assertTrue(console.err().contains(
                        "Warn: --daemon is not supported in dev mode (not started from a jar);"
                                + " running in the foreground."),
                "the dev mode warning must go to stderr: " + console.err());
    }

    @Test
    @DisplayName("jar 路径解析：非 jar 的 location（classes 目录）一律视为开发模式")
    void resolveJarPathRejectsNonJar() {
        assertNull(AppDaemon.resolveJarPath(AppDaemonTest.class));
        assertNull(AppDaemon.resolveJarPath(String.class),
                "a JDK class has no jar-shaped code source");
    }

    // ------------------------------------------------------------------ 平台命令拼装

    @Test
    @DisplayName("Windows：cmd start 必须占住 title 位置，否则带空格的 java 路径会被当成标题")
    void windowsCommandReservesTitleSlot() {
        String[] command = {
                "C:\\Program Files\\Java\\bin\\java.exe", "-jar", "C:\\a b\\app.jar", "start"};
        String[] winCmd = OSUtil.buildWindowsDaemonCommand(command);

        assertEquals("cmd", winCmd[0]);
        assertEquals("/c", winCmd[1]);
        assertEquals("start", winCmd[2]);
        assertEquals("\"\"", winCmd[3],
                "an explicit empty title is required: start treats its first quoted argument"
                        + " (a java path containing spaces) as the window title");
        assertEquals("/B", winCmd[4]);
        assertEquals(command.length + 5, winCmd.length,
                "the foreground command must follow verbatim");
        for (int i = 0; i < command.length; i++) {
            assertEquals(command[i], winCmd[i + 5],
                    "each argument must stay its own array element so that ProcessBuilder"
                            + " does the Windows escaping; hand-rolled quotes break spaced values");
        }
    }

    @Test
    @DisplayName("POSIX：带空格的取值必须被 shell 引号包住，否则后台进程收到的是错的参数")
    void posixCommandQuotesEveryArgument() {
        String[] command = {"/usr/bin/java", "-jar", "/opt/slacker db/app.jar",
                "--log", "/var/log/my log.log", "start"};
        String[] posix = OSUtil.buildPosixDaemonCommand("/bin/sh", command);

        assertEquals("/bin/sh", posix[0]);
        assertEquals("-c", posix[1]);
        String line = posix[2];
        assertTrue(line.startsWith("nohup "), line);
        assertTrue(line.endsWith(" >/dev/null 2>&1 &"), line);
        assertTrue(line.contains("'/opt/slacker db/app.jar'"),
                "a path containing spaces must be passed as one argument,"
                        + " otherwise the shell splits it: " + line);
        assertTrue(line.contains("'/var/log/my log.log'"),
                "a log path containing spaces must be passed as one argument: " + line);
        assertTrue(line.contains("'start'"), line);
    }

    @Test
    @DisplayName("POSIX：单引号要按 shell 规则转义")
    void posixEscapesSingleQuotes() {
        assertEquals("'a b'", OSUtil.shellQuote("a b"));
        assertEquals("'it'\\''s'", OSUtil.shellQuote("it's"));
        assertEquals("''", OSUtil.shellQuote(""));
    }

    @Test
    @DisplayName("POSIX：找不到 shell 时明确报错，而不是静默拼出一个跑不起来的命令")
    void posixWithoutShellFailsLoudly() {
        assertThrows(RuntimeException.class,
                () -> OSUtil.buildPosixDaemonCommand(null, new String[]{"java", "-jar", "a.jar"}));
        assertThrows(RuntimeException.class,
                () -> OSUtil.buildPosixDaemonCommand("", new String[]{"java", "-jar", "a.jar"}));
    }

    // ------------------------------------------------------------------ 真的拉起一个后台进程

    /**
     * 端到端验收：真的 fork 一个 JVM，让它把 {@code main(String[])} 收到的参数原样写回文件，再比对。
     *
     * <p>这一段是必要的：{@code cmd /c start} 的参数重新拼装、POSIX 的 shell 转义都属于
     * "看起来对、跑起来错"的类型，只有让真正的子进程把收到的参数写回来才能确认。
     * 夹具写完就退出，不会在机器上留下后台进程。</p>
     */
    @Test
    @DisplayName("端到端：后台副本能起来，且收到的参数与命令行逐字一致")
    void realBackgroundProcessReceivesExactArguments() throws Exception {
        // 刻意不用 @TempDir：被 fork 出来的 JVM 会在 Windows 上短暂占住 jar 句柄，
        // JUnit 删除临时目录时会失败（表现为一个与断言无关的 "Failed to delete temp directory"）。
        //
        // 每次运行用**自己的**子目录，而不是共用 target/daemon-e2e：
        // 上一次运行留下的副本可能还在启动中（Windows 上它短暂占着那个 jar），共用同一个运行目录
        // 会让这一次"写 jar / 删目录"直接失败——那是两次运行之间的互相干扰，与被测行为无关。
        // 父目录尽力清一次（删不掉的留给 mvn clean），旧子目录不会再被这一次碰到。
        Path runRoot = Path.of("target", "daemon-e2e");
        deleteRecursively(runRoot);
        Path workDir = runRoot.resolve("run-" + Long.toHexString(System.nanoTime()));
        Files.createDirectories(workDir);

        Path jar = createFixtureJar(workDir);
        Path argsFile = workDir.resolve("child-args.txt");
        List<String> reported = new ArrayList<>();
        List<String> expected = List.of(
                "start",
                "--conf", "C:\\Program Files\\slacker db\\dbserver.conf",
                "--log", "C:\\Program Files\\slacker db\\logs\\dbserver.log",
                "--plugin.home", "/opt/a b",
                "--locale", "zh_CN",
                "--daemon", "false");
        long pid = -1;
        try {
            List<String> command = new ArrayList<>(List.of(
                    javaExecutable(),
                    "-D" + ARGS_FILE_PROPERTY + "=" + argsFile,
                    "-jar", jar.toString()));
            command.addAll(expected);

            pid = OSUtil.launchDaemon(OSUtil.buildDaemonCommand(command.toArray(new String[0])));

            // 等**内容**，不是等"文件存在"：文件先被创建、内容后写的写法会让这里读到空文件
            // （Windows 上红过一次：reported=[]）。夹具那边也已经改成原子改名，两边都堵上。
            reported.addAll(awaitArgs(argsFile, 30_000L));
        } finally {
            if (pid > 0) {
                ProcessHandle.of(pid).ifPresent(ProcessHandle::destroy);
            }
        }

        assertFalse(reported.isEmpty(),
                "the background copy did not write its arguments back within 30s");
        assertTrue(reported.size() > 1,
                "the background copy should report the jar path plus its arguments: " + reported);
        assertTrue(reported.get(0).replace('\\', '/').endsWith(jar.getFileName().toString()),
                "the background copy should have been started from this jar: " + reported.get(0));
        assertEquals(expected, reported.subList(1, reported.size()),
                "the background copy must receive exactly the arguments it was launched with");
    }

    // ------------------------------------------------------------------ 工具

    /** 控制台的两路输出：用于断言"告警走哪一路"。 */
    private record Console(String out, String err) {
    }

    /** 可以被捕获控制台输出的动作（允许抛受检异常——被测方法本身大多会抛）。 */
    @FunctionalInterface
    private interface ConsoleAction {
        void run() throws Exception;
    }

    /**
     * 在"换掉 {@code System.out}/{@code System.err}"的窗口里跑一段代码，把两路输出收拢回来。
     *
     * <p>{@code AppDaemon} 在日志系统初始化之前是直打控制台的（那时还没有 logger），
     * 要断言"诊断信息只走 stderr"就只能这样观察。窗口一结束立刻换成原来的流（{@code finally}），
     * 断言失败也不会把 surefire 的输出管道留在替换状态。</p>
     */
    private static Console captureConsole(ConsoleAction action) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (PrintStream capturedOut = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream capturedErr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            System.setErr(capturedErr);
            try {
                action.run();
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }
        return new Console(out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** 一个稳定存在的 jar 路径，只用于"命令行里带什么"的纯逻辑断言。 */
    private static String fakeJar() {
        return new File("target/cli-daemon-test/slackerdb-dbserver.jar").getAbsolutePath();
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                OSUtil.isWindows() ? "java.exe" : "java").toString();
    }

    /**
     * 就地打一个可执行 jar：里面只有夹具那一个类，它的 {@code main} 会把收到的每个参数
     * 按行写回文件然后退出。
     */
    private static Path createFixtureJar(Path workDir) throws Exception {
        Path jar = workDir.resolve("daemon-args-fixture.jar");

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", FIXTURE_CLASS);

        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry(FIXTURE_CLASS + ".class"));
            out.write(fixtureClassBytes());
            out.closeEntry();
        }
        return jar;
    }

    /** 夹具的字节码（源码见 src/test/java/DaemonArgsFixture.java）。 */
    private static byte[] fixtureClassBytes() throws Exception {
        try (var in = AppDaemonTest.class.getClassLoader()
                .getResourceAsStream(FIXTURE_CLASS + ".class")) {
            assertNotNull(in, "fixture class " + FIXTURE_CLASS + " is not on the test classpath");
            return in.readAllBytes();
        }
    }

    /**
     * 等夹具把参数写回来：等的是**内容**，不是"文件存在"。
     *
     * <p>{@code Files.write} 先创建/截断文件、再写内容，两步之间有窗口；只等 {@code Files.exists}
     * 会读到空文件——这就是 Windows 上偶发 {@code reported=[]} 的根因。夹具那边已改成
     * "写临时文件 + 原子改名"，这里再按内容等，两边把这一层竞态堵死。</p>
     *
     * @return 写回来的行；超时仍为空时返回空列表（调用方给一条能看懂的失败信息）
     */
    private static List<String> awaitArgs(Path argsFile, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        List<String> lines = List.of();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(argsFile)) {
                lines = Files.readAllLines(argsFile, StandardCharsets.UTF_8);
                if (!lines.isEmpty()) {
                    return lines;
                }
            }
            // 还没写出来（或还空着）：稍等再看。副本是另一个 JVM，冷启动通常几十毫秒
            Thread.sleep(20L);
        }
        return lines;
    }

    /**
     * 尽力删掉上一次运行留下的目录。
     *
     * <p>删不掉不算失败：上一次运行的副本可能还在退出过程中（Windows 上它短暂占着 jar 或输出文件，
     * 删除会抛 {@code AccessDeniedException}），而这一轮的运行目录是新的、不会碰到那些文件。
     * 真正的清理交给 {@code mvn clean}。</p>
     */
    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 被上一轮的副本攥着：留着，不影响这一轮（每轮用独立子目录）
                }
            }
        } catch (IOException ignored) {
            // 同上：清理是尽力而为
        }
    }
}
