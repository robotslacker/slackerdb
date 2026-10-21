package org.slackerdb.dbcli;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code _LOAD JDBCDRIVER <jar>} 的回归用例：加载、重复加载、以及几条可读错误。
 *
 * <p>为什么不去连一个真实的第三方库：回归里不能带外部驱动（PostgreSQL 驱动不在 classpath 上）。
 * 这里用**classpath 上现成的 sqlite-jdbc jar** 当"外部 jar"：它带着
 * {@code META-INF/services/java.sql.Driver}，足以验证"打开 jar → 实例化驱动 → 登记到
 * DriverManager"整条链路；真实 PG 驱动的一次性验证见交付说明。</p>
 *
 * <p>空 jar / 重复加载用的目录是 {@code dbcli/target/dbcli-load-test}：被加载的 jar 会被
 * classloader 一直打开（Windows 上删不掉），放在 {@code @TempDir} 里会让清理失败。</p>
 */
class CliLoadJdbcDriverTest {

    /** 放临时 jar 的目录（构建目录，测试自己清）。 */
    private static final Path WORK_DIR = Path.of("target", "dbcli-load-test");

    private record Result(String out, String err, int exitCode) {
    }

    private Result run(String stdinText, String... args) {
        Cli cli = new Cli();
        cli.setStdin(new ByteArrayInputStream(
                stdinText == null ? new byte[0] : stdinText.getBytes(StandardCharsets.UTF_8)));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = new CommandLine(cli);
        commandLine.setOut(new PrintWriter(out, true));
        commandLine.setErr(new PrintWriter(err, true));
        int exitCode = commandLine.execute(args);
        return new Result(out.toString(), err.toString(), exitCode);
    }

    @Test
    @DisplayName("_load jdbcdriver <真实 jar>：把里面的驱动登记进来；同一个 jar 再加载只提示 already loaded")
    void loadsARealDriverJarAndRemembersIt() {
        Path driverJar = findSqliteJar();
        Assumptions.assumeTrue(driverJar != null,
                "classpath 上找不到 sqlite-jdbc jar，跳过（Maven 下它一定在）");

        Result first = run("_load jdbcdriver " + driverJar + "\nselect 1;\n", "--execute", "-");

        assertEquals(0, first.exitCode, first.out);
        assertTrue(first.out.contains("Loaded JDBC driver from [" + driverJar + "]: org.sqlite.JDBC"),
                first.out);
        assertTrue(first.out.contains("Not connected."),
                "加载驱动不该顺带建立连接: " + first.out);

        Result again = run("_load jdbcdriver " + driverJar + "\nselect 1;\n", "--execute", "-");

        assertEquals(0, again.exitCode, again.out);
        assertTrue(again.out.contains("JDBC driver [" + driverJar + "] is already loaded"),
                again.out);
    }

    @Test
    @DisplayName("_load 的可读错误：缺目标 / 不支持的目标 / 缺 jar / 文件不存在 / jar 里没有驱动")
    void loadReportsReadableErrors() throws IOException {
        Files.createDirectories(WORK_DIR);
        // 命令按**脚本工作目录**解析相对路径，回显的是绝对路径：断言用同一个规范路径比较
        Path emptyJar = WORK_DIR.resolve("no-driver.jar").toAbsolutePath().normalize();
        try (OutputStream file = Files.newOutputStream(emptyJar);
             JarOutputStream jar = new JarOutputStream(file)) {
            jar.putNextEntry(new ZipEntry("readme.txt"));
            jar.write("no driver here".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }

        Path missing = WORK_DIR.resolve("missing.jar").toAbsolutePath().normalize();

        Result bare = run("_load\n", "--execute", "-");
        assertTrue(bare.out.contains("Load needs a target"), bare.out);

        Result unsupported = run("_load map rules.map\n", "--execute", "-");
        assertTrue(unsupported.out.contains(
                "Unsupported _LOAD target [map rules.map]"), unsupported.out);

        Result noFile = run("_load jdbcdriver\n", "--execute", "-");
        assertTrue(noFile.out.contains("_LOAD JDBCDRIVER needs a jar file."), noFile.out);

        Result absent = run("_load jdbcdriver " + missing + "\n", "--execute", "-");
        assertTrue(absent.out.contains("JDBC driver jar [" + missing + "] does not exist."), absent.out);

        Result noDriver = run("_load jdbcdriver " + emptyJar + "\n", "--execute", "-");
        assertTrue(noDriver.out.contains("No JDBC driver found in [" + emptyJar + "]"), noDriver.out);
        assertFalse(noDriver.out.contains("Exception"), noDriver.out);
    }

    /** classpath 上的 sqlite-jdbc jar（Maven 测试一定给得到）。 */
    private static Path findSqliteJar() {
        String classpath = System.getProperty("java.class.path", "");
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(entry);
            String name = candidate.getFileName() == null ? "" : candidate.getFileName().toString();
            if (name.startsWith("sqlite-jdbc") && name.endsWith(".jar") && Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }
}
