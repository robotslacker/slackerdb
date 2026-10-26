package org.slackerdb.dbcli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "其它 JDBC 驱动"的端到端用例：{@code _connect jdbc:sqlite:…} 原样交给随包的 SQLite 驱动。
 *
 * <p>为什么用 SQLite：驱动随包（自包含、含各平台原生库）、无服务端、无端口，因此这几条用例是
 * hermetic 的（库文件由 {@link TempDir} 管）。它验证的是**通用 JDBC URL** 这条路径：
 * {@code _connect} 不解释 URL，建表/插入/查询都真的落到那个驱动上。</p>
 */
class CliSqliteTest {

    @TempDir
    Path tempDir;

    private record Result(String out, String err, int exitCode) {
    }

    /** 跑一次 CLI；stdin 默认给一个空流，避免测试真的去读进程标准输入。 */
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
    @DisplayName("_session saveurl 不能指向当前会话名：报可读错误，连接保持可用")
    void saveUrlOnCurrentSessionIsRejected() throws Exception {
        Path database = tempDir.resolve("saveurl.db");
        Path script = tempDir.resolve("saveurl.sql");
        Files.writeString(script, """
                _connect jdbc:sqlite:%s
                _session saveurl NONAME
                select 1 as one;
                """.formatted(database));

        Result result = run(null, "--execute", script.toString());

        assertTrue(result.out.contains("is the current session"),
                "把当前会话改成'只有 URL'的 bookmark 会让后续 SQL 全部 Not connected: " + result.out);
        assertTrue(result.out.contains("one"),
                "报错之后连接必须仍然可用: " + result.out);
        assertFalse(result.out.contains("Not connected."), result.out);
    }

    @Test
    @DisplayName("_set autocommit off 之后重连：新连接同样关掉 autocommit（事务不会被静默提交）")
    void autoCommitSurvivesReconnect() throws Exception {
        Path database = tempDir.resolve("autocommit.db");
        Path script = tempDir.resolve("autocommit.sql");
        Files.writeString(script, """
                _connect jdbc:sqlite:%s
                create table t (id integer);
                _set autocommit off
                _connect jdbc:sqlite:%s
                insert into t values (1);
                rollback;
                select count(*) as remaining from t;
                _assert RESULT.ROWS[0][0] = 0, rollback-worked;
                """.formatted(database, database));

        Result result = run(null, "--execute", script.toString());

        assertEquals(0, result.exitCode, result.out);
        assertTrue(result.out.contains("Assert [rollback-worked] successful."),
                "rollback 必须真的回滚：新连接继承了 autocommit=false 才会如此: " + result.out);
    }

    @Test
    @DisplayName("_connect jdbc:sqlite:<file>：建表/插入/查询都走同一个 SQLite 文件")
    void connectAndRunSqlAgainstSqlite() throws Exception {
        Path database = tempDir.resolve("demo.db");
        Path script = tempDir.resolve("sqlite.sql");
        Files.writeString(script, """
                _connect jdbc:sqlite:%s
                create table t (id integer primary key, name text);
                insert into t values (1, 'alpha');
                insert into t values (2, 'beta');
                select id, name from t order by id;
                _disconnect
                """.formatted(database));

        Result result = run(null, "--execute", script.toString());

        assertEquals(0, result.exitCode, result.out);
        assertFalse(result.out.contains("Invalid connection string"), result.out);
        assertTrue(result.out.contains("Database connected."), result.out);
        assertTrue(result.out.contains("alpha"), "查询结果里应当有插入的数据: " + result.out);
        assertTrue(result.out.contains("beta"), result.out);
        assertTrue(result.out.contains("2 rows selected."), result.out);
    }

    @Test
    @DisplayName("--logon jdbc:sqlite::memory:：内存库也能连（不需要文件）")
    void logonWithInMemorySqlite() {
        Result result = run("select 42 as answer;\n",
                "--logon", "jdbc:sqlite::memory:", "--execute", "-");

        assertEquals(0, result.exitCode, result.out);
        assertTrue(result.out.contains("Database connected."), result.out);
        assertTrue(result.out.contains("42"), result.out);
    }

    @Test
    @DisplayName("用户名/口令照旧可写（走 JDBC properties），且不进 URL/输出")
    void credentialsArePassedAsProperties() {
        Result result = run("_connect main/secret@jdbc:sqlite::memory:\nselect 7 as seven;\n",
                "--execute", "-");

        assertEquals(0, result.exitCode, result.out);
        assertTrue(result.out.contains("Database connected."), result.out);
        assertTrue(result.out.contains("seven"), result.out);
        assertFalse(result.out.contains("secret"), "口令不得出现在输出里: " + result.out);
    }

    @Test
    @DisplayName("整段加引号的 URL：空格与 ';' 都留在 URL 里（脚本不会在 ';' 处截断）")
    void quotedUrlKeepsSpacesAndSemicolons() throws Exception {
        Path database = tempDir.resolve("quoted db;1.db");
        Path script = tempDir.resolve("quoted.sql");
        Files.writeString(script, """
                _connect "jdbc:sqlite:%s"
                select 1 as one;
                """.formatted(database));

        Result result = run(null, "--execute", script.toString());

        assertEquals(0, result.exitCode, result.out);
        assertTrue(result.out.contains("Database connected."), result.out);
        assertTrue(Files.isRegularFile(database),
                "URL 必须整段到达驱动（库文件不见了说明被 ';' 截断了）: " + result.out);
    }
}
