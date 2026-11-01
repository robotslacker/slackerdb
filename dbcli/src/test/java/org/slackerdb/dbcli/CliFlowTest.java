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

/** 控制流的 CLI 端到端：真实 SQLite 驱动、循环重放、LOOPTRACE 与 --logfile/--ref。 */
class CliFlowTest {

    @TempDir
    Path tempDir;

    private record Result(String out, String err, int exitCode) {
    }

    private Result run(String... args) {
        Cli cli = new Cli();
        cli.setStdin(new ByteArrayInputStream(new byte[0]));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = new CommandLine(cli);
        commandLine.setOut(new PrintWriter(out, true));
        commandLine.setErr(new PrintWriter(err, true));
        int exitCode = commandLine.execute(args);
        return new Result(out.toString(), err.toString(), exitCode);
    }

    @Test
    @DisplayName("SQLite 端到端：IF 读 RESULT、COUNT 循环、BREAK 与 LOOPTRACE")
    void flowAgainstSqlite() throws Exception {
        Path script = tempDir.resolve("flow.sql");
        Files.writeString(script, """
                _connect jdbc:sqlite:%s
                create table t (id integer);
                _loop 3
                  insert into t values (1);
                _endloop
                select count(*) as n from t;
                _if {%% RESULT.ROWS[0][0] = 3 %%}
                  _echo t
                  count-is-three
                  echo off
                _else
                  _assert 1 = 2, count-should-be-three
                _endif
                _loop 5
                  _loop break
                _endloop
                """.formatted(tempDir.resolve("flow.db")));

        Result result = run("--execute", script.toString());

        assertEquals(0, result.exitCode, result.out + result.err);
        assertTrue(result.out.contains("Loop iteration 1 of 3."), result.out);
        assertTrue(result.out.contains("Loop iteration 3 of 3."), result.out);
        assertTrue(result.out.contains("count-is-three"), result.out);
        assertTrue(result.out.contains("Loop iteration 1 of 5."), result.out);
        assertFalse(result.out.contains("Loop iteration 2 of 5."), result.out);
        assertFalse(result.out.contains("Assert fail."), result.out);
    }

    @Test
    @DisplayName("LOOPTRACE OFF：CLI 输出里没有进度行，但 SQL 仍执行")
    void loopTraceCanBeDisabled() throws Exception {
        Path script = tempDir.resolve("quiet.sql");
        Files.writeString(script, """
                _connect jdbc:sqlite:%s
                create table t (id integer);
                _set looptrace off
                _loop 2
                  insert into t values (1);
                _endloop
                select count(*) as n from t;
                """.formatted(tempDir.resolve("quiet.db")));

        Result result = run("--execute", script.toString());

        assertEquals(0, result.exitCode, result.out + result.err);
        assertFalse(result.out.contains("Loop iteration"), result.out);
        assertTrue(result.out.contains("|      1 | 2 |"), result.out);
    }

    @Test
    @DisplayName("--logfile 与 --ref：控制流输出可录制、再跑一致")
    void logfileAndReference() throws Exception {
        Path script = tempDir.resolve("ref.sql");
        Path log = tempDir.resolve("ref.log");
        Path ref = tempDir.resolve("ref.ref");
        Files.writeString(script, """
                _loop 2
                  _echo t
                  iteration
                  echo off
                _endloop
                """);

        Result recorded = run("--execute", script.toString(), "--logfile", log.toString());
        assertEquals(0, recorded.exitCode, recorded.out + recorded.err);
        assertTrue(Files.exists(log));
        String logText = Files.readString(log);
        assertEquals(1, logText.lines().filter("_loop 2"::equals).count(), logText);
        assertEquals(1, logText.lines().filter("_endloop"::equals).count(), logText);
        assertEquals(2, logText.lines().filter(line -> line.strip().equals("iteration")).count(), logText);
        assertEquals(2, occurrences(logText, "Loop iteration "), logText);

        Files.copy(log, ref);
        Result compared = run("--execute", script.toString(), "--logfile", log.toString(),
                "--ref", ref.toString());
        assertEquals(0, compared.exitCode, compared.out + compared.err);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
