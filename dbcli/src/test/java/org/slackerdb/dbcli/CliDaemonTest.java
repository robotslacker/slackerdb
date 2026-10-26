package org.slackerdb.dbcli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbcli.daemon.CliDaemon;
import org.slackerdb.dbcli.daemon.PidFile;
import org.slackerdb.dbcli.job.ProcessTree;
import org.slackerdb.dbcli.repl.LineSource;
import org.slackerdb.dbcli.repl.ScriptedLineSource;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --daemon/--pidfile} 的命令行级测试（**没有子命令**：不带 {@code --daemon} 就是前台运行）。
 *
 * <p>两类验证：</p>
 * <ol>
 *   <li><b>参数校验</b>：逐字断言消息与退出码（缺 {@code --pidfile}、{@code --daemon} 配控制台、
 *       {@code --daemon maybe}、{@code --daemon} 缺脚本、残留的 {@code start} 位置参数被拒）；</li>
 *   <li><b>pidfile 生命周期</b>：用**真实子进程**跑，断言"父进程立即返回 + 副本写 pidfile + pid 活着"，
 *       以及"前台/失败跑完不留 pidfile"。不 mock 进程，因为这一层要验的正是"进程真的在"。</li>
 * </ol>
 *
 * <h2>为什么真 daemon 用独立可执行 jar 起，而 dev 模式另有一个测试</h2>
 * <p>{@code --daemon} 只有在**从 jar 启动**时才真的转后台（{@link org.slackerdb.common.utils.AppDaemon}
 * 靠 {@code CodeSource} 判定）。用 {@code -cp <classes>} 起的话走的是<b>开发模式</b>：打印一条警告后
 * **当前台跑完**——那时 pidfile 是那个前台进程自己写的，验的根本不是后台化路径。因此这里分成两个测试，
 * 各自锁住一条语义：真 jar 测试验"父进程立即返回 + 副本写 pidfile"；dev 模式测试验
 * "**不后台化**：警告只进 stderr、脚本在本进程前台跑完、pidfile 里的 pid 就是本进程"。</p>
 *
 * <p>真 daemon 测试要的 jar 优先取 package 产物；{@code mvn clean test} 会把 target 下的产物删掉，
 * 那时由测试**自建**一个可 {@code java -jar} 启动的启动器 jar（见 {@link #launcherJar()}）：
 * 类来自 {@code target/classes}（于是 {@code Cli.class} 的 {@code CodeSource} 是 jar，判定为 jar 模式），
 * 依赖走清单里的 {@code Class-Path}。这样最常用的全量入口不会因为"没打包"而少跑这条路径。</p>
 *
 * <h2>为什么子进程的工作目录与输出都在 {@code target} 下，而不是 {@code @TempDir}</h2>
 * <p>Windows 上 {@code destroyForcibly()} 只作用于**直接子进程**：经 {@code cmd /c start} 拉起的孙进程
 * 会变成孤儿，继续攥着输出文件的句柄、并把 {@code @TempDir} 当作自己的工作目录，于是 JUnit 删不掉该目录，
 * 报成 {@code Failed to close extension context ... Failed to delete temp directory}——与测试本身的结论
 * 完全无关。因此本类的对策是两条：</p>
 * <ol>
 *   <li>子进程的工作目录与 stdout/stderr 一律落在 {@code target/dbcli-daemon-test/}（构建目录，
 *       每次测试开始前清空），不再让 {@code @TempDir} 与子进程有任何句柄关系；</li>
 *   <li>收尾**杀整棵进程树**：{@link ProcessTree#kill} 处理直接子进程，{@link #killProcessTree(long)}
 *       处理只拿到 pid 的后台副本（{@code ProcessHandle.descendants()}，与 {@code _HOST} 同一套逻辑）。</li>
 * </ol>
 */
class CliDaemonTest {

    /** 独立可执行 jar（package 阶段的产物）；优先用它，与生产形态逐字一致。 */
    private static final String STANDALONE_JAR = "slackerdb-dbcli-0.2.2-standalone.jar";

    /** 打包产物不存在时自建的启动器 jar 名前缀（见 {@link #launcherJar()}；实际文件名带唯一后缀）。 */
    private static final String LAUNCHER_JAR = "dbcli-daemon-launcher";

    /**
     * 子进程产物目录（{@code dbcli/target/dbcli-daemon-test}）。
     *
     * <p>刻意**不**用 {@code @TempDir}：孤儿进程攥着这里的文件句柄只会让这个目录删不掉，
     * 而它由 {@code mvn clean} 负责清，不会把 JUnit 的扩展上下文收尾拖下水（见类注释）。</p>
     */
    private static final Path OUTPUT_DIR = targetDirectory().resolve("dbcli-daemon-test");

    /** 子进程 stdout / stderr 落盘文件名前缀（每轮带唯一后缀，见 {@link #prepareOutputDirectory()}）。 */
    private static final String CHILD_OUT_PREFIX = "child-";

    /** 本轮子进程的 stdout 落盘文件；{@link #prepareOutputDirectory()} 里换新名字。 */
    private Path childOutFile;

    /** 本轮子进程的 stderr 落盘文件；与 stdout <b>分开</b>（早先两者写同一个文件，互相覆盖）。 */
    private Path childErrFile;

    /**
     * 等父进程返回的上限。
     *
     * <p>父进程自己最多会花 {@link CliDaemon#PID_WAIT_MILLIS} 等副本写 pidfile，这里再加 30 秒
     * 余量（父进程 JVM 冷启动 + 本机负载）。<b>必须跟着产品常量走</b>：测试侧写死一个固定秒数，
     * 一旦产品那边放宽等待，测试就会抢在父进程还在正常等待时判超时——两边各写一个数，迟早互相打架。</p>
     */
    private static final long PARENT_WAIT_MILLIS = CliDaemon.PID_WAIT_MILLIS + 30_000L;

    @TempDir
    Path tempDir;

    /** 本测试启动过的所有子进程：无论如何都在收尾时收掉（含整棵进程树），不留孤儿。 */
    private final List<Process> children = new ArrayList<>();

    /** 只拿到 pid 的后台副本（真 daemon 是经 {@code cmd /c start} 拉起的孙进程，没有 Process 对象）。 */
    private final Set<Long> daemonPids = new LinkedHashSet<>();

    @BeforeEach
    void prepareOutputDirectory() throws IOException {
        // 从干净目录开始：残留的旧输出会污染断言（上一次失败时留下的现场也在这里，便于事后翻看）
        deleteRecursively(OUTPUT_DIR);
        Files.createDirectories(OUTPUT_DIR);
        // 唯一后缀：上一轮留下的副本可能还占着旧的重定向文件（Windows 上会撞共享冲突），
        // 换个名字就彻底互不相干
        String tag = CHILD_OUT_PREFIX + Long.toHexString(System.nanoTime());
        childOutFile = OUTPUT_DIR.resolve(tag + ".out");
        childErrFile = OUTPUT_DIR.resolve(tag + ".err");
    }

    @AfterEach
    void killChildren() {
        // 先收直接子进程（它们可能还会拉起新的后代），再按 pid 收后台副本
        for (Process child : children) {
            // 树杀：只 destroyForcibly 直接子进程会留下孤儿（Windows 上实测攥住重定向文件的句柄）
            ProcessTree.kill(child);
        }
        children.clear();
        for (Long pid : daemonPids) {
            killProcessTree(pid);
        }
        daemonPids.clear();
    }

    private static final class Result {
        final String out;
        final String err;
        final int exitCode;

        Result(String out, String err, int exitCode) {
            this.out = out;
            this.err = err;
            this.exitCode = exitCode;
        }

        /** stdout 去掉行尾（平台换行符不进断言，消息本身仍逐字比对）。 */
        String outText() {
            return out.strip();
        }

        String errText() {
            return err.strip();
        }
    }

    /** 跑一次命令行；stdin 默认给空流（避免测试真去读进程标准输入），控制台形态用 {@link #runConsole}。 */
    private Result run(String stdinText, String... args) {
        return execute(null, stdinText, args);
    }

    /** 控制台形态：注入预置输入行，因此不需要 TTY。 */
    private Result runConsole(String... args) {
        return execute(new ScriptedLineSource(List.of()), null, args);
    }

    private Result execute(LineSource lineSource, String stdinText, String... args) {
        Cli cli = new Cli();
        if (lineSource != null) {
            cli.setLineSource(lineSource);
        }
        cli.setStdin(new ByteArrayInputStream(
                stdinText == null ? new byte[0] : stdinText.getBytes(StandardCharsets.UTF_8)));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = new CommandLine(cli);
        commandLine.setOut(new PrintWriter(out, true));
        commandLine.setErr(new PrintWriter(out, true)); // dbcli 从不写 stderr：参数错误也在 stdout
        int exitCode = commandLine.execute(args);
        return new Result(out.toString(), err.toString(), exitCode);
    }

    private Path script(String name, String text) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, text);
        return file;
    }

    // ---------------------------------------------------------------- 参数校验

    @Test
    @DisplayName("--daemon 可以不给 --pidfile：开发模式下照旧前台跑，不报缺参数")
    void daemonWithoutPidFileIsAllowed() throws Exception {
        Path script = script("ok.sql", "_echo t\nok\necho off\n");

        Result result = run(null, "--execute", script.toString(), "--daemon", "true");

        assertEquals(0, result.exitCode, result.out);
        assertTrue(result.out.contains("ok"), result.out);
        assertFalse(result.out.contains("Missing --pidfile"), result.out);
    }

    @Test
    @DisplayName("--daemon 不给 --pidfile（真 jar）：后台照跑，父进程只报『已在后台启动』，不额外提 pid")
    void backgroundWithoutPidFileStartsQuietly() throws Exception {
        Path jar = daemonJar();
        Path script = writeTo(OUTPUT_DIR, "nopid-bg.sql", "_echo t\nbg-nopid\necho off\n");
        Path logFile = OUTPUT_DIR.resolve("nopid-bg.out");

        Process parent = spawn(List.of(javaExecutable(), "-jar", jar.toString(),
                "--execute", script.toString(), "--daemon", "true",
                "--logfile", logFile.toString()));

        assertTrue(parent.waitFor(PARENT_WAIT_MILLIS, TimeUnit.MILLISECONDS), outputSummary());
        assertEquals(0, parent.exitValue(), outputSummary());
        assertTrue(childOut().contains("Daemon started in background"), outputSummary());
        assertFalse(childOut().contains("--pidfile"), "不给 pidfile 时不该再额外解释 pid: " + outputSummary());

        String logged = awaitFileText(logFile, "bg-nopid", 30_000L);
        assertTrue(logged.contains("bg-nopid"), "--logfile 应当由后台副本写出: " + outputSummary());
    }

    @Test
    @DisplayName("--daemon 取值写错（maybe）：直接报错，不静默前台运行")
    void daemonValueMustBeTrueOrFalse() throws Exception {
        Path pidFile = tempDir.resolve("maybe.pid");
        Path script = script("ok3.sql", "_echo t\nok\necho off\n");

        Result result = run(null, "--execute", script.toString(),
                "--pidfile", pidFile.toString(), "--daemon", "maybe");

        assertEquals(2, result.exitCode);
        assertEquals("--daemon must be true or false (got: maybe)", result.outText());
        assertFalse(Files.exists(pidFile));
    }

    @Test
    @DisplayName("残留的 start 位置参数：不再有子命令，位置参数被拒（退出 2）")
    void straySubCommandIsRejected() throws Exception {
        Path script = script("stray.sql", "_echo t\nok\necho off\n");

        Result result = run(null, "start", "--execute", script.toString());

        assertEquals(2, result.exitCode, "位置参数（旧子命令）必须以参数错误被拒");
        assertEquals("", result.err);
        assertTrue(result.out.contains("start"), "错误里应点到那个位置参数: " + result.out);
    }

    @Test
    @DisplayName("裸写 --daemon 等价 true，且不会吞掉紧跟其后的选项")
    void bareDaemonMeansTrue() throws Exception {
        Path pidFile = tempDir.resolve("bare.pid");
        Path script = script("bare.sql", "_echo t\nbare\necho off\n");

        // 测试 JVM 不是从 jar 启动的 -> AppDaemon 判定为开发模式：往 **stderr** 打一条警告后
        // **当前台**跑完脚本（"不后台化"这条语义由 devModeDoesNotBackground 用真子进程钉住）。
        // 这里验的正是"裸写被认成 true（否则会因为缺少 --pidfile/脚本而报错），
        // 且 --pidfile/--execute 没有被 --daemon 当成取值吞掉"。
        Result result = run(null, "--daemon", "--pidfile", pidFile.toString(),
                "--execute", script.toString());

        assertEquals(0, result.exitCode, "stderr: " + result.out);
        assertTrue(result.out.contains("bare"), result.out);
        assertFalse(result.out.contains("Warn:"), "警告不该出现在 stdout（数据流）：" + result.out);
        assertFalse(Files.exists(pidFile), "脚本跑完（进程退出）后 pidfile 应当被清掉");
    }

    @Test
    @DisplayName("--daemon 没脚本可跑（没有 --execute 也没有管道输入）：退出 2 并给出提示")
    void daemonRequiresAScript() {
        Path pidFile = tempDir.resolve("noscript.pid");

        Result result = run(null, "--daemon", "true", "--pidfile", pidFile.toString());

        assertEquals(2, result.exitCode);
        assertEquals("--daemon requires a script to run (use --execute <file> or pipe the script in).",
                result.outText());
        assertFalse(Files.exists(pidFile));
    }

    @Test
    @DisplayName("--daemon 配控制台模式：拒绝（控制台后台跑没有意义）")
    void daemonRejectsConsoleMode() {
        Path pidFile = tempDir.resolve("console.pid");

        Result result = runConsole("--daemon", "true", "--pidfile", pidFile.toString());

        assertEquals(2, result.exitCode);
        assertEquals("--daemon requires a script to run (use --execute <file> or pipe the script in).",
                result.outText());
    }

    @Test
    @DisplayName("--daemon false 与不写 --daemon 一样：前台跑完脚本，退出码就是脚本退出码")
    void explicitDaemonFalseRunsInForeground() throws Exception {
        Path pidFile = tempDir.resolve("foreground.pid");
        Path script = script("foreground.sql", "_echo t\nforeground\necho off\n");

        Result result = run(null, "--execute", script.toString(),
                "--pidfile", pidFile.toString(), "--daemon", "false");

        assertEquals(0, result.exitCode, "stderr: " + result.out);
        assertTrue(result.out.contains("foreground"), result.out);
        assertFalse(result.out.contains("Daemon started"), result.out);
        // 前台跑完后进程自己清掉 pidfile（异常退出也尽力清，见 Cli.call 的 finally）
        assertFalse(Files.exists(pidFile), "前台跑完不该留下 pidfile");
    }

    @Test
    @DisplayName("--daemon 配 stdin 管道：被认成『有脚本』（不是控制台），开发模式下当前台跑完")
    void daemonAcceptsAPipedScript() throws Exception {
        Path pidFile = tempDir.resolve("piped.pid");

        Result result = run("_echo t\npiped-in-the-background\necho off\n",
                "--daemon", "true", "--pidfile", pidFile.toString());

        assertEquals(0, result.exitCode, "stderr: " + result.out);
        assertTrue(result.out.contains("piped-in-the-background"), result.out);
        assertFalse(Files.exists(pidFile), "跑完（进程退出）后 pidfile 应当被清掉");
    }

    @Test
    @DisplayName("管道形态的副本命令行：新增/替换 --execute 指向临时脚本，并带上『跑完删掉』的内部开关")
    void pipedScriptIsMaterializedForTheBackgroundCopy() throws Exception {
        Path tempScript = tempDir.resolve("materialized.sql");
        List<org.slackerdb.common.utils.AppDaemon.Option> withExecute = List.of(
                new org.slackerdb.common.utils.AppDaemon.Option("pidfile", "p.pid"),
                new org.slackerdb.common.utils.AppDaemon.Option("execute", "-"));
        List<org.slackerdb.common.utils.AppDaemon.Option> withoutExecute = List.of(
                new org.slackerdb.common.utils.AppDaemon.Option("pidfile", "p.pid"));

        // --execute 已经在：只换取值（位置不变）
        List<String> replaced = Cli.withDaemonScriptTemp(withExecute, tempScript).stream()
                .flatMap(option -> option.toArguments().stream()).toList();
        assertEquals(List.of("--pidfile", "p.pid", "--execute", tempScript.toString(),
                "--daemon-script-temp"), replaced);

        // 管道形态（没有 --execute）：追加一对，副本才能知道脚本在哪
        List<String> appended = Cli.withDaemonScriptTemp(withoutExecute, tempScript).stream()
                .flatMap(option -> option.toArguments().stream()).toList();
        assertEquals(List.of("--pidfile", "p.pid", "--execute", tempScript.toString(),
                "--daemon-script-temp"), appended);
    }

    @Test
    @DisplayName("脚本失败（退出码 1）也要清掉 pidfile：异常退出同样尽力清理")
    void pidFileIsRemovedWhenTheRunFails() throws Exception {
        Path pidFile = tempDir.resolve("failed.pid");
        Path script = script("failed.sql", "_assert 1 = 2, bad\n");

        Result result = run(null, "--execute", script.toString(),
                "--pidfile", pidFile.toString());

        assertEquals(0, result.exitCode, "stderr: " + result.out);
        assertFalse(Files.exists(pidFile), "脚本失败退出时也要清掉 pidfile");
    }

    @Test
    @DisplayName("等号写法 --daemon=true 与 --daemon true 等价（取值校验同一条路径）")
    void daemonEqualsFormIsAccepted() throws Exception {
        Path pidFile = tempDir.resolve("equals.pid");
        Path script = script("equals.sql", "_echo t\nequals-form\necho off\n");

        Result result = run(null, "--execute", script.toString(),
                "--pidfile", pidFile.toString(), "--daemon=true");

        assertEquals(0, result.exitCode, "stderr: " + result.out);
        assertTrue(result.out.contains("equals-form"), result.out);
        assertFalse(Files.exists(pidFile), "前台跑完后 pidfile 应当被清掉");
    }

    @Test
    @DisplayName("--pidfile 在前台运行**期间**有效：控制台还在跑时文件里是当前进程的 pid，收尾时清掉")
    void pidFileIsWrittenWhileRunningInTheForeground() {
        Path pidFile = tempDir.resolve("running.pid");
        StringBuilder pidSeenWhileRunning = new StringBuilder();

        // 取行来源在"控制台还在跑"的那一刻回看 pidfile：没有 --daemon 也照旧写（这就是保留 --pidfile 的意义）
        LineSource probe = new LineSource() {
            @Override
            public String readLine(String prompt) {
                pidSeenWhileRunning.append(new PidFile(pidFile).read());
                return null;   // 立刻 EOF：控制台正常收尾
            }

            @Override
            public void close() {
                // 测试自己的来源，无需关闭
            }
        };

        Result result = execute(probe, null, "--pidfile", pidFile.toString());

        assertEquals(0, result.exitCode, "stderr: " + result.out);
        assertEquals(String.valueOf(ProcessHandle.current().pid()), pidSeenWhileRunning.toString(),
                "运行期间 pidfile 里应当是当前进程的 pid");
        assertFalse(Files.exists(pidFile), "收尾之后 pidfile 应当被清掉");
    }

    // ------------------------------------------------------- pidfile 生命周期

    @Test
    @DisplayName("真 jar + --daemon：父进程不等脚本就返回、副本写 pidfile 且 pid 活着（收尾时由测试杀掉）")
    void pidFileLifecycleWithARealProcess() throws Exception {
        // 真 daemon 路径只在"从 jar 启动"时才成立（-cp/classes 起的是开发模式，见类注释）。
        // jar 优先用 package 产物；`clean test` 会把它删掉，那时由测试自建一个（见 launcherJar）——
        // 这条路径在任何入口都**真的跑到**，而不是被跳过。
        Path jar = daemonJar();

        // 脚本要睡 90 秒：父进程若"等脚本跑完"就会在这里超时失败 —— 这就是"立即返回"的证明，
        // 不需要脆弱的耗时断言
        Path script = writeTo(OUTPUT_DIR, "soak.sql", "_sleep 90\n");
        Path pidFile = OUTPUT_DIR.resolve("soak.pid");

        Process parent = spawn(List.of(javaExecutable(), "-jar", jar.toString(),
                "--execute", script.toString(),
                "--pidfile", pidFile.toString(), "--daemon", "true"));

        assertTrue(parent.waitFor(PARENT_WAIT_MILLIS, TimeUnit.MILLISECONDS),
                "后台化之后父进程应当立即返回（不等脚本跑完）：" + outputSummary());
        assertEquals(0, parent.exitValue(), "父进程退出码：" + outputSummary());

        Long pid = waitForPidFile(pidFile);
        assertNotNull(pid, "后台副本应当把自己的 pid 写进 pidfile：" + outputSummary());
        daemonPids.add(pid);
        assertNotEquals(parent.pid(), pid.longValue(), "后台副本必须是另一个进程（父进程不跑脚本）");
        // 真的走了后台分支（而不是开发模式的"警告后当前台"）：pidfile 由副本自己写、且不是父进程
        // （"Daemon started in background (log: ...)" 那句由共享的 AppDaemon 直打控制台，
        //  在 Windows 上会落到 cmd start 那个中间 shell 的窗口里，所以这里以 pidfile + 自己那句为准）
        String out = childOut();
        assertTrue(out.contains("Daemon started (pid " + pid + ").") || out.contains("Daemon started in background"),
                "父进程应当是后台化的那条路径：" + outputSummary());
        // 父进程报出的 pid 取自副本自己写的 pidfile，不是 cmd start 那个中间 shell 的 pid
        // （比最后一行：副本继承着同一份 stdout，之后可能还有脚本自己的输出）
        assertTrue(out.contains("Daemon started (pid " + pid + ")."), outputSummary());
        assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "pidfile 里的 pid 应当是活的");

        // 没有任何 stop/status 子命令：处置后台进程由使用者自己来（这里由 @AfterEach 树杀）
        killProcessTree(pid);
        assertFalse(PidFile.isAlive(pid), "收尾之后 pid 不该还是活的");
        waitForProcessExit(pid);
    }

    @Test
    @DisplayName("dev 模式（-cp 起，不是 jar）**不后台化**：警告只进 stderr，脚本在本进程前台跑完")
    void devModeDoesNotBackground() throws Exception {
        Path script = writeTo(OUTPUT_DIR, "dev.sql", "_sleep 5\n_echo t\ndev-mode-ran\necho off\n");
        Path pidFile = OUTPUT_DIR.resolve("dev.pid");

        // 同一个 classpath 的 Main：CodeSource 是 classes 目录 -> AppDaemon 判定为开发模式
        Process child = spawn(List.of(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                Main.class.getName(), "--execute", script.toString(),
                "--pidfile", pidFile.toString(), "--daemon", "true"));

        Long pid = waitForPidFile(pidFile);
        assertNotNull(pid, "开发模式下 pidfile 照旧要写（起不来后台时使用者仍要能处置那个进程）："
                + outputSummary());
        daemonPids.add(pid);
        // 关键语义：没有副本。写 pidfile 的就是被拉起的那一个前台进程本身
        assertEquals(child.pid(), pid.longValue(),
                "dev 模式不该有第二个进程（--daemon 没后台化）：" + outputSummary());

        // 前台 = 父进程不立即返回：脚本跑完才退出（脚本本身要睡 5 秒）
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "开发模式下应当前台跑完脚本再退出");
        assertEquals(0, child.exitValue(), outputSummary());

        // 唯一的 stderr 出口是共享的 AppDaemon 告警（它发生在 dbcli 自己的输出层建立之前）。
        // 两路输出现在分开落盘（child-*.out / child-*.err），因此可以直接断言"只进 stderr"。
        assertTrue(childErr().contains("is not supported in dev mode (not started from a jar);"),
                "开发模式要说清没后台化：" + outputSummary());
        assertFalse(childOut().contains("is not supported in dev mode"),
                "告警是诊断信息，不该出现在 stdout：" + outputSummary());
        String out = childOut();
        assertFalse(out.contains("Daemon started"), "开发模式没有副本，不该报副本已启动：" + outputSummary());
        assertTrue(out.contains("dev-mode-ran"), "脚本应当在前台真的跑过：" + outputSummary());

        // 前台跑完，进程自己把 pidfile 清掉
        assertFalse(Files.exists(pidFile), "前台跑完后 pidfile 应当被清掉");
    }

    @Test
    @DisplayName("--daemon + --logfile：后台副本把数据输出写进那个文件（与前台同一套 tee）")
    void backgroundCopyWritesTheLogFile() throws Exception {
        Path jar = daemonJar();
        Path script = writeTo(OUTPUT_DIR, "logged.sql", "_echo t\nbackground-log-line\necho off\n");
        Path pidFile = OUTPUT_DIR.resolve("logged.pid");
        Path logFile = OUTPUT_DIR.resolve("logged.out");

        Process parent = spawn(List.of(javaExecutable(), "-jar", jar.toString(),
                "--execute", script.toString(), "--pidfile", pidFile.toString(),
                "--daemon", "true", "--logfile", logFile.toString()));

        assertTrue(parent.waitFor(PARENT_WAIT_MILLIS, TimeUnit.MILLISECONDS), outputSummary());
        assertEquals(0, parent.exitValue(), outputSummary());
        Long pid = waitForPidFile(pidFile);
        assertNotNull(pid, outputSummary());
        daemonPids.add(pid);

        // 副本还在跑，等它把 logfile 写出来（进程退出时也会 flush）
        String logged = awaitFileText(logFile, "background-log-line", 30_000L);
        assertTrue(logged.contains("background-log-line"),
                "--logfile 应当由后台副本写出数据输出：" + outputSummary());
        killProcessTree(pid);
    }

    @Test
    @DisplayName("副本跑完**不删** pidfile：快脚本跑完了父进程也一定能读到 pid（否则会误报没写出 pid）")
    void backgroundCopyKeepsItsPidFileAfterItExits() throws Exception {
        Path jar = daemonJar();
        // 这份脚本几毫秒就跑完：副本会在父进程两次轮询（50ms）之间写完并退出，
        // 若副本退出时把 pidfile 删掉，父进程就再也读不到它了（Linux/Windows 上都实测过误报）
        Path script = writeTo(OUTPUT_DIR, "quick.sql", "quick-daemon-line\necho off\n");
        Path pidFile = OUTPUT_DIR.resolve("quick.pid");

        Process parent = spawn(List.of(javaExecutable(), "-jar", jar.toString(),
                "--execute", script.toString(), "--pidfile", pidFile.toString(),
                "--daemon", "true"));

        assertTrue(parent.waitFor(PARENT_WAIT_MILLIS, TimeUnit.MILLISECONDS), outputSummary());
        assertEquals(0, parent.exitValue(), "父进程必须报成功（副本已经跑完也是成功）：" + outputSummary());
        Long pid = waitForPidFile(pidFile);
        assertNotNull(pid, outputSummary());
        daemonPids.add(pid);

        // 等副本自己退出（脚本很短），然后断言 pidfile 仍在：留下的是"已死的 pid"，不是"没有 pid"
        killProcessTree(pid);
        waitForProcessExit(pid);
        assertTrue(Files.exists(pidFile), "副本退出后 pidfile 应当留着（父进程/使用者都要能读到 pid）："
                + outputSummary());
        assertEquals(pid, new PidFile(pidFile).read(), "文件里应当仍是那个副本的 pid");
        assertFalse(PidFile.isAlive(pid), "那个 pid 这时已经死了");
    }

    // ------------------------------------------------------------------ 小工具

    /**
     * 真 daemon 测试要用的 jar：优先 package 产物（与生产形态逐字一致），
     * 缺失时（{@code mvn clean test}）就地自建一个。
     */
    private static Path daemonJar() throws IOException {
        Path packaged = targetDirectory().resolve(STANDALONE_JAR);
        return Files.isRegularFile(packaged) ? packaged : launcherJar();
    }

    /**
     * 就地自建一个"可 {@code java -jar} 启动"的启动器 jar（只在缺打包产物时用）。
     *
     * <p>{@code --daemon} 是不是"从 jar 启动"完全取决于 {@code Cli.class} 的 {@code CodeSource}，
     * 所以本 jar 里放 {@code target/classes} 的全部内容（类从 jar 里来 → 判定为 jar 模式），
     * 依赖则通过清单里的 {@code Class-Path} 指向当前测试 JVM 的 classpath 各条目。
     * 后台副本被 {@code cmd /c start} 拉起后会重新执行 {@code java -jar <本 jar>}，
     * 因此 {@code Class-Path} 也必须是**绝对 URL**（副本的工作目录与父进程不同，相对路径会解析错）。</p>
     *
     * <p>打包只是本测试的夹具：不用 assembly、不改构建，产物落在 {@link #OUTPUT_DIR} 下。</p>
     */
    private static Path launcherJar() throws IOException {
        Path classes = classDirectory();
        // **每次用新文件名**，不复用固定路径：上一次运行留下的后台副本可能还在退出过程中，
        // Windows 上它短暂占着那个 jar 的句柄，覆盖写会直接抛 AccessDeniedException
        // （表现为一条与被测行为无关的失败）。新名字让两次运行彻底互不相干，
        // 旧文件删不掉也无所谓——@BeforeEach 尽力清一次，剩下的交给 mvn clean。
        Path jar = OUTPUT_DIR.resolve(LAUNCHER_JAR + "-" + Long.toHexString(System.nanoTime()) + ".jar");

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", Main.class.getName());
        // 值整体交给 Manifest/JarOutputStream 写：它会按规范折成 <=72 字节的行
        // （手写长行会被 manifest 解析器拒绝："line too long"）
        manifest.getMainAttributes().putValue("Class-Path", dependencyClassPath());

        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest);
             Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String entry = classes.relativize(file).toString().replace(File.separatorChar, '/');
                out.putNextEntry(new JarEntry(entry));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
        return jar;
    }

    /**
     * 自建 jar 的 {@code Class-Path}：当前测试 JVM classpath 的各项（绝对 {@code file:} URL，空格由
     * {@link Path#toUri()} 编码成 {@code %20}）。
     *
     * <p>surefire 默认用一个**只带清单的引导 jar** 当 classpath（真正的条目写在它的
     * {@code Class-Path} 里），因此遇到这种 jar 要继续展开，不能只把引导 jar 自己写进去。</p>
     */
    private static String dependencyClassPath() throws IOException {
        Set<String> urls = new LinkedHashSet<>();
        Set<Path> visited = new LinkedHashSet<>();
        Deque<Path> pending = new ArrayDeque<>();
        for (String raw : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!raw.isBlank()) {
                pending.add(Path.of(raw).toAbsolutePath().normalize());
            }
        }
        while (!pending.isEmpty()) {
            Path entry = pending.poll();
            if (!visited.add(entry) || !Files.exists(entry)) {
                continue;
            }
            urls.add(entry.toUri().toString());
            if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".jar")) {
                for (String nested : manifestClassPath(entry)) {
                    Path resolved = resolveClassPathEntry(entry.getParent(), nested);
                    if (resolved != null) {
                        pending.add(resolved);   // 不存在的条目会在下一轮被 Files.exists 过滤掉
                    }
                }
            }
        }
        return String.join(" ", urls);
    }

    /** 读一个 jar 清单里的 {@code Class-Path}（没有就返回空表）。 */
    private static List<String> manifestClassPath(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            if (manifest == null) {
                return List.of();
            }
            String value = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            return value == null || value.isBlank() ? List.of() : List.of(value.split("\\s+"));
        } catch (IOException unreadable) {
            // 不是 zip / 清单坏了：当作没有 Class-Path（它自己仍然会作为条目被写进去）
            return List.of();
        }
    }

    /** 清单里的 {@code Class-Path} 项既可能是绝对 URL，也可能是相对该 jar 的路径。 */
    private static Path resolveClassPathEntry(Path baseDirectory, String entry) {
        try {
            URI uri = new URI(entry);
            if (uri.isAbsolute()) {
                return Path.of(uri);
            }
        } catch (Exception notAUri) {
            // 老式写法（带空格之类）不是合法 URI：按路径处理，%20 之类自己解回来
        }
        return baseDirectory.resolve(URLDecoder.decode(entry, StandardCharsets.UTF_8)).normalize();
    }

    /**
     * 起一个子进程：工作目录与 stdout/stderr 都在 {@link #OUTPUT_DIR} 下。
     *
     * <p>刻意不碰 {@code @TempDir}：子进程（尤其是它拉起的孙进程）会把工作目录与重定向文件
     * 一直攥在手里，那正是 JUnit 删不掉临时目录的原因（见类注释）。</p>
     */
    private Process spawn(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(OUTPUT_DIR.toFile());
        builder.redirectOutput(childOutFile.toFile());
        builder.redirectError(childErrFile.toFile());
        Process child = builder.start();
        children.add(child);
        return child;
    }

    private static Path writeTo(Path directory, String name, String text) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, text);
        return file;
    }

    /** 子进程的 stdout（读不出来时给占位串，避免断言路径上再抛一个无关异常）。 */
    private String childOut() {
        return readQuietly(childOutFile);
    }

    /** 子进程的 stderr。 */
    private String childErr() {
        return readQuietly(childErrFile);
    }

    /** 失败信息里带上子进程的两路输出：出问题时不用再手工复现一次。 */
    private String outputSummary() {
        return "\n--- child stdout ---\n" + childOut() + "\n--- child stderr ---\n" + childErr();
    }

    /**
     * 取最后一个非空行。
     *
     * <p>后台副本会**继承**父进程重定向出去的 stdout 句柄（Windows 上 {@code cmd /c start /B}
     * 不新建控制台），所以父进程退出后这个文件还可能被副本继续写；"父进程报出的 pid"是它退出前
     * 打出的最后一行。</p>
     */
    private static String lastNonBlankLine(String text) {
        String last = "";
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                last = line.strip();
            }
        }
        return last;
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return "<cannot read " + file + ": " + unreadable.getMessage() + ">";
        }
    }

    /** 等 pidfile 出现（最多 30 秒：冷启动一个 13 MB 的 jar 通常不到 1 秒，给足余量）。 */
    private Long waitForPidFile(Path pidFile) throws InterruptedException {
        PidFile file = new PidFile(pidFile);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Long pid = file.read();
            if (pid != null) {
                return pid;
            }
            Thread.sleep(50);
        }
        return null;
    }

    /** 等一个文件里出现某段文本（子进程在自己的进程里写，读之前要等它落盘）。 */
    private static String awaitFileText(Path file, String needle, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        String content = "";
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(file)) {
                content = readQuietly(file);
                if (content.contains(needle)) {
                    return content;
                }
            }
            Thread.sleep(50);
        }
        return content;
    }

    /**
     * 按 pid 杀整棵进程树（后台副本只留下 pid，没有 {@link Process} 对象）。
     *
     * <p>顺序与 {@link ProcessTree} 一致：<b>先杀后代再杀本体，最后再兜一次</b>——直接子进程一死，
     * 它的后代会被重新挂到别的父进程上，之后就再也查不到了（Windows 上 {@code destroyForcibly}
     * 只作用于直接子进程，正是"杀不干净 → 句柄不释放 → 临时目录删不掉"的根源）。</p>
     */
    private static void killProcessTree(long pid) {
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle == null) {
            return;
        }
        List<ProcessHandle> descendants = handle.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        handle.destroyForcibly();
        waitForProcessExit(pid);
        // 兜底：本体死掉之前可能还有刚被它拉起来的后代
        ProcessHandle.of(pid).ifPresent(self -> self.descendants().forEach(ProcessHandle::destroyForcibly));
    }

    /** 等进程真的消失（最多 10 秒）；进程已经不在了立即返回。 */
    private static void waitForProcessExit(long pid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (PidFile.isAlive(pid) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 本模块的编译产物目录（{@code dbcli/target/classes}）：自建 jar 的类从它来。 */
    private static Path classDirectory() {
        Path location = codeSourceOfMain();
        if (location != null && Files.isDirectory(location)) {
            return location;
        }
        return targetDirectory().resolve("classes");
    }

    /** 本模块的构建目录（{@code dbcli/target}）：由 classes 目录反推，不依赖 surefire 的工作目录。 */
    private static Path targetDirectory() {
        Path location = codeSourceOfMain();
        if (location != null && "classes".equals(String.valueOf(location.getFileName()))) {
            return location.getParent();
        }
        // 从 jar 里跑测试这类非常规形态：退回相对路径（只在"跑测试"时用得到它）
        return Path.of("target").toAbsolutePath();
    }

    /** {@code Main} 的 CodeSource 位置（classes 目录或 jar）；拿不到返回 null。 */
    private static Path codeSourceOfMain() {
        try {
            return Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception notALocalPath) {
            return null;
        }
    }

    /** 递归删目录（幂等；删不掉不算失败——它只是构建目录里的中间产物）。 */
    private static void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 有进程还攥着句柄时会走到这里：不影响测试结论（mvn clean 会收拾）
                }
            }
        } catch (IOException ignored) {
            // 同上
        }
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }
}
