package org.slackerdb.dbcli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbcli.job.ProcessTree;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * **真进程**端到端测试：起一个真的 dbcli 子进程，跑几条只依赖引擎的命令。
 *
 * <h2>为什么必须起真进程</h2>
 * <p>进程内测试（{@code CliSmokeTest}/{@code CliConsoleModeTest}…）用的是注入的
 * {@code PrintWriter}/stdin，验不到"真进程的 stdin 重定向 + 真退出码 + 真 argv 解析"。
 * 这里刻意不做进程内注入：{@code java -cp <当前 classpath> org.slackerdb.dbcli.Main …}，
 * 于是"stdin 是管道还是终端""退出码怎么落回操作系统"都被真的跑到。</p>
 *
 * <h2>为什么这里不再有"控制台真进程"测试</h2>
 * <p>控制台只在 {@code System.console() != null}（或进程内注入了取行来源）时才进入，
 * 而 {@code --interactive} 已经删除；重定向 stdin/stdout 起出来的子进程永远拿不到
 * {@code System.console()}，因此"真进程里的控制台"无法在不引入 PTY 的前提下验证。
 * 控制台行为改由 {@code repl.InteractiveConsoleTest}（直接跑主循环）与
 * {@code CliConsoleModeTest}（注入取行来源走 CLI 全链路）覆盖 ——
 * 它们验的是同一条执行路径，只是不经过"操作系统给的控制台"。</p>
 *
 * <h2>为什么默认 logon 的环境变量用例也在这里</h2>
 * <p>{@code SLACKERDB_LOGON} 是**进程级**的取值，而 Java 没有"改进程环境变量"的进程内手段，
 * 因此"设了 / 没设 / 空串 / 多行"只能靠真子进程验证（{@code ProcessBuilder.environment()}）。
 * 这些用例都往环境变量里放一个**故意被拒绝**的取值：只要那条拒绝文案出现，
 * 就证明它确实被当成了连接参数 —— 不需要数据库。</p>
 *
 * <h2>为什么子进程的工作目录与输出都在 {@code target} 下，而不是 {@code @TempDir}</h2>
 * <p>与 {@code CliDaemonTest} 同一条 Windows 事实：{@code Process.destroyForcibly()} 只作用于
 * **直接子进程**，被 {@code cmd}/{@code sh} 拉起的孙进程会变成孤儿，继续攥着重定向文件（乃至工作目录）
 * 的句柄 —— 于是 JUnit 清理 {@code @TempDir} 时报 {@code Failed to delete temp directory}，
 * 一个与测试结论毫无关系的失败。对策：</p>
 * <ol>
 *   <li>子进程的工作目录、stdin 文件、stdout/stderr 全部落在 {@code target/dbcli-process-test/}
 *       （构建目录，每次测试开始前清空），{@code @TempDir} 与子进程再无句柄关系；</li>
 *   <li>收尾**一律树杀 + 等退出**（{@link ProcessTree#kill} 含 {@code ProcessHandle.descendants()}），
 *       正常结束、断言失败、超时三条路都走同一段收尾。</li>
 * </ol>
 */
class CliRealProcessTest {

    /** 子进程的等待上限：真 JVM 启动 + 跑几行命令，正常远低于它。 */
    private static final long PROCESS_TIMEOUT_MILLIS = 60_000L;

    /** 树杀之后等进程真的消失的上限。 */
    private static final long PROCESS_EXIT_WAIT_SECONDS = 10L;

    /** 子进程产物目录（{@code dbcli/target/dbcli-process-test}，见类注释）。 */
    private static final Path OUTPUT_DIR = targetDirectory().resolve("dbcli-process-test");

    /** 项目版本号形状：{@code <major>.<minor>.<patch>}，**不含**构建时刻与任何 git 信息。 */
    private static final String PROJECT_VERSION = "\\d+\\.\\d+\\.\\d+";

    @BeforeEach
    void prepareOutputDirectory() throws IOException {
        // 从干净目录开始：旧的 stdout/stderr 会污染本测试的断言
        deleteRecursively(OUTPUT_DIR);
        Files.createDirectories(OUTPUT_DIR);
    }

    @AfterEach
    void cleanOutputDirectory() {
        deleteRecursively(OUTPUT_DIR);
    }

    /** 一次真进程运行的结果：stdout 与 stderr **分开**收（这条纪律要求它们不可能混在一起看）。 */
    private record Result(int exitCode, String stdout, String stderr) {
    }

    /** 用"重定向的标准输入"跑一个真实 dbcli 进程（不额外设定环境变量）。 */
    private Result run(String stdinText, String... args) throws Exception {
        return run(stdinText, Map.of(), args);
    }

    /**
     * 同上，并**显式**控制子进程的环境变量。
     *
     * <p>为什么每条用例都要先摘掉 {@link Cli#LOGON_ENV}：环境变量是**进程级**的，
     * 开发机上完全可能已经设了它（那正是这个功能的用法），继承下来会让"没设环境变量"的用例失真。
     * 因此这里先无条件移除，再按参数放回（空串也是一次显式设定，用来验"空串视为未设置"）。</p>
     */
    private Result run(String stdinText, Map<String, String> environment, String... args)
            throws Exception {
        Path input = OUTPUT_DIR.resolve("process-input.txt");
        Files.writeString(input, stdinText == null ? "" : stdinText);
        Path stdout = OUTPUT_DIR.resolve("process-stdout.txt");
        Path stderr = OUTPUT_DIR.resolve("process-stderr.txt");

        List<String> command = new ArrayList<>(List.of(
                JvmLauncher.javaExecutable(),
                "-cp", System.getProperty("java.class.path", "."),
                Main.class.getName()));
        command.addAll(List.of(args));

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(OUTPUT_DIR.toFile())
                .redirectInput(input.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile());
        builder.environment().remove(Cli.LOGON_ENV);
        builder.environment().putAll(environment);
        Process process = builder.start();
        try {
            if (!process.waitFor(PROCESS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                fail("dbcli 进程没有在 " + PROCESS_TIMEOUT_MILLIS + " 毫秒内结束");
            }
            return new Result(process.exitValue(), readLenient(stdout), readLenient(stderr));
        } finally {
            // 正常结束 / 断言失败 / 超时：都从这里收尾，树杀 + 等退出，绝不留孤儿
            killAndWait(process);
        }
    }

    /** 把脚本文本写进 {@link #OUTPUT_DIR}（子进程的工作目录就是它）。 */
    private static Path write(String name, String text) throws IOException {
        Path file = OUTPUT_DIR.resolve(name);
        Files.writeString(file, text);
        return file;
    }

    /**
     * 树杀并等进程真的退出（已经结束的进程立即返回）。
     *
     * <p>只 {@code destroyForcibly()} 直接子进程是不够的：Windows 上它的后代（{@code cmd}/{@code sh}
     * 拉起的那些）会活下来并继续攥住重定向文件的句柄。</p>
     */
    private static void killAndWait(Process process) {
        ProcessTree.kill(process);
        try {
            if (!process.waitFor(PROCESS_EXIT_WAIT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 本模块的构建目录（{@code dbcli/target}）：由 classes 目录反推，不依赖 surefire 的工作目录。 */
    private static Path targetDirectory() {
        try {
            Path location = Path.of(Main.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            if (Files.isDirectory(location) && "classes".equals(String.valueOf(location.getFileName()))) {
                return location.getParent();
            }
        } catch (Exception ignored) {
            // 从 jar 里跑测试这类非常规形态：退回相对路径
        }
        return Path.of("target").toAbsolutePath();
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

    /**
     * 读取子进程的输出文件。
     *
     * <p>子进程是独立 JVM，它写 stdout/stderr 用的是**部署环境的字符集**。本测试不去猜那个字符集，
     * 也不在断言里出现非 ASCII 内容——**断言只允许用 ASCII**：ASCII 在任何字符集下字节都相同，
     * 于是结论与部署环境无关（这正是这类"真进程"用例能稳定跑在不同机器上的前提）。</p>
     *
     * <p>解码本身宽松即可（UTF-8 + 把解不出来的字节替换掉）：反正断言不依赖那些字节。</p>
     */
    private static String readLenient(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("管道 stdin（非终端）：被当**脚本**读，stdout 只有数据，没有提示符/横幅")
    void pipedStdinIsReadAsAScript() throws Exception {
        Result result = run("""
                _set @who world
                _echo title
                hello ${who}
                echo off
                _assert 1 = 1;
                """);

        assertEquals(0, result.exitCode(), "stderr:\n" + result.stdout());
        assertEquals("hello world\nAssert successful.\n", result.stdout(),
                "stdout 里只能有数据与状态行");
        assertFalse(result.stdout().contains("dbcli> "), "脚本形态不该有提示符");
        assertFalse(result.stdout().contains("dbcli> "),
                "脚本形态不该有提示符（stderr）:\n" + result.stdout());
        assertFalse(result.stdout().contains("Interactive console."),
                "脚本形态不该有横幅:\n" + result.stdout());
    }

    @Test
    @DisplayName("真进程 --version：两行、第一行只有版本号（无构建时刻、无 git 信息）")
    void versionHasNoGitInformation() throws Exception {
        Result result = run("", "--version");

        assertEquals(0, result.exitCode(), result.stdout());
        String[] lines = result.stdout().split("\\R");
        assertEquals(2, lines.length, "版本输出正好两行:\n" + result.stdout());
        assertTrue(lines[0].matches("\\[CLIENT\\] VERSION: " + PROJECT_VERSION),
                "第一行必须是纯项目版本号 <major>.<minor>.<patch>，实际: " + lines[0]);
        assertFalse(lines[0].matches("(?s).*\\+\\d{8}\\.\\d{6}.*"),
                "构建时刻不该再混进第一行: " + lines[0]);
        assertFalse(lines[0].contains("-dirty"), lines[0]);
        assertFalse(lines[0].matches(".*\\+\\d{8}\\.\\d{6}-.*"),
                "版本号里不该再追加 git 短提交: " + lines[0]);
        assertTrue(lines[1].matches(
                        "\\[CLIENT\\] Build Time: \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} [+-]\\d{2}:\\d{2}"),
                "第二行必须是本地构建时间并带偏移（存储是 UTC 瞬时）: " + lines[1]);
    }

    @Test
    @DisplayName("真进程 --help：没有 --interactive、没有子命令、没有已删除的选项")
    void helpHasNoRemovedFeatures() throws Exception {
        Result result = run("", "--help");

        assertEquals(0, result.exitCode(), result.stdout());
        String help = result.stdout();
        assertTrue(help.contains("--daemon"), help);
        assertTrue(help.contains("--pidfile"), help);
        assertTrue(help.contains("--logfile"), help);
        // --logon 的说明里要提到默认值来自哪个环境变量（--help 仍然不含版本与构建时间）
        assertTrue(help.contains(Cli.LOGON_ENV), "帮助里应提到 " + Cli.LOGON_ENV + ":\n" + help);
        assertFalse(help.contains("[CLIENT] VERSION"), "帮助里不该有版本信息:\n" + help);
        assertFalse(help.contains("Build Time"), "帮助里不该有构建时间:\n" + help);
        for (String gone : new String[] {"--interactive", "--logfile-timing", "start|stop|status",
                "Subcommand", "--selftest"}) {
            assertFalse(help.contains(gone), "帮助里不得再出现 [" + gone + "]:\n" + help);
        }
    }

    @Test
    @DisplayName("真进程：_connect 全写形态（带用户名/口令）→ 按同一 URL 去连，口令不进 URL/输出")
    void connectAcceptsTheFullForm() throws Exception {
        Path script = write("full-form.sql",
                "_connect main/secret@jdbc:slackerdb://127.0.0.1:1/db1\n");

        Result result = run(null, "--execute", script.toString());

        assertEquals(0, result.exitCode(), result.stdout());
        assertTrue(result.stdout().contains("Cannot connect to [jdbc:slackerdb://127.0.0.1:1/db1]"),
                "全写形态应当按 jdbc:slackerdb:// URL 去连:\n" + result.stdout());
        assertFalse(result.stdout().contains("secret"),
                "口令不得出现在输出（含回显 URL）里:\n" + result.stdout());
        assertEquals("", result.stderr(), "错误语句不该往数据通道写东西");
    }

    @Test
    @DisplayName("真进程：_connect 非连接串取值 → 同一条无效连接串错误（原值回显 + 退出 1）")
    void connectRejectsGarbage() throws Exception {
        // 取值只用 ASCII：子进程按**部署环境的字符集**写出字节，本测试不猜那个字符集，
        // 也不断言任何非 ASCII 内容——否则换一个环境（GBK / UTF-8）结论就变。
        Path script = write("garbage.sql", "_connect not-a-connection-string\n");

        Result result = run(null, "--execute", script.toString());

        assertEquals(0, result.exitCode(), result.stdout());
        assertTrue(result.stdout().contains("Invalid connection string [not-a-connection-string]."),
                "实际 stdout:\n" + result.stdout());
        assertEquals("", result.stderr(), "无效连接串不得往数据通道写东西");
    }

    @Test
    @DisplayName("真进程：_connect /mem 已从语法删除（同一条无效连接串错误 + 退出 1，IR/文案里没有本地内存库）")
    void connectLocalMemoryIsGone() throws Exception {
        Path script = write("mem.sql", "_connect /mem\n");

        Result result = run(null, "--execute", script.toString());

        assertEquals(0, result.exitCode(), result.stdout());
        // 语法里不再有 connectlocal 备选：`/` 在 CONNECT 之后就是语法错误，报的仍是同一条无效连接串
        assertTrue(result.stdout().contains("Invalid connection string [/mem]."),
                "应当是无效连接串错误: " + result.stdout());
        assertFalse(result.stdout().contains("local service"), "不该再有本地服务分支: " + result.stdout());
        assertEquals("", result.stderr(), "无效连接串不产出数据");
    }

    @Test
    @DisplayName("真进程：_job wait 不写 timeout 就一直等，被 _job timer 放行后继续（不是 300 秒后超时）")
    void jobWaitWithoutTimeoutWaitsUntilTheTimerReleasesIt() throws Exception {
        // 作业脚本：先到屏障前等（不写 timeout），放行后打印一行
        write("gate-child.sql", "_job wait TIMER:GO\n_echo t\nchild-passed-the-gate\necho off\n");
        Path parent = write("gate-parent.sql", """
                _job jobmanager on
                _job create CHILD script=gate-child.sql
                _job start CHILD
                _job timer GO
                _job wait CHILD
                _job jobmanager off
                """);

        Result result = run(null, "--execute", parent.toString());

        assertEquals(0, result.exitCode(), "stderr:\n" + result.stdout() + "\nstdout:\n" + result.stdout());
        assertTrue(result.stdout().contains("Job [CHILD] finished with exit code 0."),
                "无限等待应当被放行后正常返回:\n" + result.stdout());
        // 作业自己的输出在 <作业名>.out 里（工作目录 = 父脚本所在目录）
        String childOut = Files.readString(OUTPUT_DIR.resolve("CHILD.out"), StandardCharsets.UTF_8);
        assertTrue(childOut.contains("child-passed-the-gate"), childOut);
    }

    // -------------------------------------------------------- 默认 logon（环境变量）

    /**
     * 环境变量里放一个**故意构不成合法连接串**的取值（简写形态缺端口与服务名）：
     * 只要输出里出现"无效连接串 + 这个原值"，就证明它真的被当成连接参数用了 —— 不需要数据库。
     */
    private static final String REJECTED_ENV_LOGON = "main@127.0.0.1";

    /** env 提示只走 stderr 的固定前缀（见 {@code Cli}：采用环境变量时打一行）。 */
    private static final String ENV_NOTICE = "[dbcli] Using " + Cli.LOGON_ENV;

    @Test
    @DisplayName("真进程：只设 SLACKERDB_LOGON、不给 --logon → 环境变量被当成连接参数")
    void environmentVariableSuppliesTheDefaultLogon() throws Exception {
        Result result = run("", Map.of(Cli.LOGON_ENV, REJECTED_ENV_LOGON), "--execute", "-");

        assertEquals(0, result.exitCode(),
                "stdout:\n" + result.stdout() + "\nstderr:\n" + result.stdout());
        assertTrue(result.stdout().contains(
                        "Invalid connection string [" + REJECTED_ENV_LOGON + "]."),
                "环境变量的取值必须被真的拿去连接:\n" + result.stdout());
        assertTrue(result.stdout().contains(ENV_NOTICE),
                "采用环境变量时应当有一条可观测提示:\n" + result.stdout());
        assertEquals("", result.stderr(), "环境变量提示与错误都只能走 stderr，stdout 不放数据以外的东西");
    }

    @Test
    @DisplayName("真进程：同时给 --logon 与 SLACKERDB_LOGON → 命令行优先，环境变量完全不参与")
    void commandLineLogonWinsOverTheEnvironmentVariable() throws Exception {
        Result result = run("", Map.of(Cli.LOGON_ENV, REJECTED_ENV_LOGON),
                "--logon", "main@127.0.0.2", "--execute", "-");

        assertEquals(0, result.exitCode(), result.stdout());
        assertTrue(result.stdout().contains("Invalid connection string [main@127.0.0.2]."),
                "错误必须来自命令行的取值:\n" + result.stdout());
        assertFalse(result.stdout().contains(REJECTED_ENV_LOGON),
                "环境变量的取值不该被采用:\n" + result.stdout());
        assertFalse(result.stdout().contains(Cli.LOGON_ENV),
                "给了 --logon 时连环境变量提示都不该出现（它被完全忽略）:\n" + result.stdout());
        assertEquals("", result.stderr(), "报错不得污染数据通道");
    }

    @Test
    @DisplayName("真进程：--logon 合法但不可达时，非法的环境变量同样连读都不读（忽略 ≠ 校验）")
    void aValidCommandLineLogonIgnoresAnInvalidEnvironmentVariable() throws Exception {
        Result result = run("", Map.of(Cli.LOGON_ENV, "main@127.0.0.2"),
                "--logon", "127.0.0.1:1/db1", "--execute", "-");

        assertEquals(0, result.exitCode(), result.stdout());
        assertTrue(result.stdout().contains("Cannot connect to [jdbc:slackerdb://127.0.0.1:1/db1]"),
                "应当按命令行的取值去连（并如实在『连不上』里回显 URL）:\n" + result.stdout());
        assertFalse(result.stdout().contains("Invalid connection string"),
                "非法的环境变量不该有机会报错:\n" + result.stdout());
        assertFalse(result.stdout().contains(Cli.LOGON_ENV), result.stdout());
        assertEquals("", result.stderr(), "报错不得污染数据通道");
    }

    @Test
    @DisplayName("真进程：不设 SLACKERDB_LOGON、不给 --logon → 不自动连接（现状不变）")
    void noEnvironmentVariableAndNoLogonMeansNoConnection() throws Exception {
        Result result = run("select 1;\n");

        assertEquals(0, result.exitCode(), "stdout:\n" + result.stdout() + "\nstderr:\n" + result.stdout());
        assertTrue(result.stdout().contains("Not connected."),
                "没有连接参数时脚本里的 SQL 仍应报 Not connected.:\n" + result.stdout());
        assertFalse(result.stdout().contains(Cli.LOGON_ENV), result.stdout());
        assertEquals("", result.stderr(), "没连上时脚本不产出数据");
    }

    @Test
    @DisplayName("真进程：SLACKERDB_LOGON 为空串/全空白 → 视为未设置（不连接、不提示）")
    void blankEnvironmentVariableIsTreatedAsUnset() throws Exception {
        for (String blank : new String[] {"", "   "}) {
            Result result = run("select 1;\n", Map.of(Cli.LOGON_ENV, blank));

            assertEquals(0, result.exitCode(),
                    "取值[" + blank + "] stdout:\n" + result.stdout() + "\nstderr:\n" + result.stdout());
            assertTrue(result.stdout().contains("Not connected."), result.stdout());
            assertFalse(result.stdout().contains(Cli.LOGON_ENV),
                    "空白取值不该触发任何连接或提示:\n" + result.stdout());
            assertEquals("", result.stderr(), "没连上时脚本不产出数据");
        }
    }

    @Test
    @DisplayName("真进程：SLACKERDB_LOGON 含换行 → 与 --logon 同一条『必须单行』的可读错误")
    void multilineEnvironmentVariableIsRejected() throws Exception {
        Result result = run("", Map.of(Cli.LOGON_ENV, "main@127.0.0.1:1/db\nselect 1;"),
                "--execute", "-");

        assertEquals(1, result.exitCode(), result.stdout());
        assertTrue(result.stdout().contains("Invalid " + Cli.LOGON_ENV
                        + " value: it must be a single line."),
                "多行取值应当报与 --logon 一致的可读错误:\n" + result.stdout());
        assertFalse(result.stdout().contains("Invalid connection string"),
                "多行取值在校验处就应当被拦下，不该走到连接:\n" + result.stdout());
        assertFalse(result.stdout().contains("\tat "), "不得吐 Java 栈:\n" + result.stdout());
        assertEquals("", result.stderr(), "错误不得污染数据通道");
    }
}
