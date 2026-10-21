package org.slackerdb.dbcli.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbcli.Cli;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行引擎的**集成测试**（默认跳过）：需要真实服务端。
 *
 * <p>运行方式（Windows 上用 TCP + 服务端内存模式）：</p>
 * <pre>
 *   mvn -B -pl dbcli test "-Ddbcli.it.url=jdbc:slackerdb://127.0.0.1:43175/db1"
 * </pre>
 *
 * <p>{@code dbcli.it.url} 既可以写成驱动口径的 {@code jdbc:slackerdb://host:port/db}
 * （{@code JdbcChannelIntegrationTest} 直接拿它去 {@code DriverManager}），
 * 也可以写成 {@code _connect} 口径的 {@code host:port/db}；这里的脚本用的是**后者**
 * （{@code _CONNECT} 不接受 {@code jdbc:} 前缀），两种写法在本测试里都认。</p>
 *
 * <p>覆盖一整条真实脚本：{@code _CONNECT} → 建表 → 插入 → 查询（表格 + 状态行）→
 * {@code _ASSERT}（行数/单元格/列类型）→ {@code _COMPARE} → {@code DROP}，
 * 并且走的是**批处理入口**（{@code --execute}），因此顺带验证了 CLI → 引擎 → 驱动这条链路。</p>
 */
@EnabledIfSystemProperty(named = "dbcli.it.url", matches = ".+")
class ScriptEngineIntegrationTest {

    @TempDir
    Path tempDir;

    /** 驱动口径的 URL → {@code _connect} 口径的 {@code host:port/service}（没有缺省值、没有用户名）。 */
    private static String connectTarget(String url) {
        String target = url;
        int scheme = target.indexOf("://");
        if (scheme >= 0) {
            target = target.substring(scheme + 3);
        }
        return target;
    }

    @Test
    @DisplayName("真实脚本：连接/建表/插入/查询/断言/比对/清理，一次跑通")
    void runsRealScript() throws Exception {
        String url = System.getProperty("dbcli.it.url");
        Files.writeString(tempDir.resolve("work.txt"), "alpha\nbeta\n");
        Files.writeString(tempDir.resolve("ref.txt"), "alpha\nbeta\n");

        String script = String.join("\n",
                "_connect " + connectTarget(url),
                "drop table if exists dbcli_engine_it;",
                "create table dbcli_engine_it(id int, name varchar(20));",
                "insert into dbcli_engine_it values (1, 'alpha');",
                "insert into dbcli_engine_it values (2, 'beta');",
                "select id, name from dbcli_engine_it order by id;",
                "_assert RESULT.ROWCOUNT = 2, two-rows;",
                "_assert RESULT.ROWS[0][1] = 'alpha';",
                "_assert RESULT.COLUMNS[0] = 'INTEGER';",
                "_compare work.txt ref.txt;",
                "drop table dbcli_engine_it;",
                "_exit 0",
                "") ;
        Path scriptFile = tempDir.resolve("engine-it.sql");
        Files.writeString(scriptFile, script);

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Cli cli = new Cli();
        cli.setStdin(new ByteArrayInputStream(new byte[0]));
        CommandLine commandLine = new CommandLine(cli);
        commandLine.setOut(new PrintWriter(out, true));
        commandLine.setErr(new PrintWriter(out, true)); // dbcli 从不写 stderr：参数错误也在 stdout

        int exitCode = commandLine.execute("--execute", scriptFile.toString());

        assertEquals(0, exitCode, "stderr: " + err);
        String stdout = out.toString();
        assertTrue(stdout.contains("Database connected."), stdout);
        assertTrue(stdout.contains("2 rows selected."), stdout);
        // 表格是逐字节契约（§4.3）：表头居中、数值列右对齐、字符串列左对齐、行号列 7 宽
        assertTrue(stdout.contains("|   ##   | id |  name |"), stdout);
        assertTrue(stdout.contains("|      1 |  1 | alpha |"), stdout);
        assertTrue(stdout.contains("Assert [two-rows] successful."), stdout);
        assertTrue(stdout.contains("Compare successful."), stdout);
    }
}
