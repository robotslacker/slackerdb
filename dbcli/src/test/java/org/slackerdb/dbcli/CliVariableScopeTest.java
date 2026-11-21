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
 * {@code _SET GLOBAL|SESSION|LOCAL} 的作用域：
 * LOCAL 只当前 job；SESSION 随 fork 传给子 job，不回传父/并行 job；GLOBAL 在同一 WORK 内共享，
 * 子进程写 GLOBAL 父进程可见。默认 {@code _SET @x} 等价 LOCAL。
 */
class CliVariableScopeTest {

    @TempDir
    Path tempDir;

    private record Result(String out, int exitCode) {
    }

    private Result run(String... args) {
        Cli cli = new Cli();
        cli.setStdin(new ByteArrayInputStream(new byte[0]));
        StringWriter out = new StringWriter();
        CommandLine commandLine = new CommandLine(cli);
        commandLine.setOut(new PrintWriter(out, true));
        commandLine.setErr(new PrintWriter(out, true));
        String[] withDirs = new String[args.length + 6];
        withDirs[0] = "--home_dir";
        withDirs[1] = tempDir.toString();
        withDirs[2] = "--work_dir";
        withDirs[3] = tempDir.resolve("work").toString();
        withDirs[4] = "--output_dir";
        withDirs[5] = tempDir.resolve("output").toString();
        System.arraycopy(args, 0, withDirs, 6, args.length);
        int exitCode = commandLine.execute(withDirs);
        return new Result(out.toString(), exitCode);
    }

    private Path script(String name, String text) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("默认 _SET 是 LOCAL；LOCAL > SESSION > GLOBAL > --var")
    void defaultSetIsLocalAndScopePriorityWins() throws Exception {
        Path script = script("priority.sql", """
                _SET GLOBAL @v global
                _SET SESSION @v session
                _ECHO t
                S:${v}
                echo off
                _SET @v local
                _ECHO t
                L:${v}
                echo off
                """);

        Result result = run("--execute", script.toString(), "--var", "v=command-line");

        assertEquals(0, result.exitCode, result.out);
        assertTrue(result.out.contains("S:session"), result.out);
        assertTrue(result.out.contains("L:local"), result.out);
    }

    @Test
    @DisplayName("thread job 继承 SESSION/GLOBAL，不继承父 LOCAL；子 job 的 SESSION 不回传")
    void threadJobInheritsSessionAndGlobalNotLocal() throws Exception {
        script("child.sql", """
                _ECHO t
                CHILD:${g}/${s}/${l}
                echo off
                _SET SESSION @childSession child
                _ECHO t
                CS:${childSession}
                echo off
                """);
        Path parent = script("parent.sql", """
                _SET GLOBAL @g global
                _SET SESSION @s session
                _SET LOCAL @l local
                _JOB JOBMANAGER ON
                _JOB CREATE J script=child.sql mode=thread
                _JOB START J
                _JOB WAIT J
                _ECHO t
                P:${childSession}
                echo off
                """);

        Result result = run("--execute", parent.toString());

        assertEquals(0, result.exitCode, result.out);
        String childOut = Files.readString(
                tempDir.resolve("output").resolve("jobs").resolve("J.out"),
                StandardCharsets.UTF_8);
        assertTrue(childOut.contains("CHILD:global/session/${l}"), childOut);
        assertTrue(childOut.contains("CS:child"), childOut);
        assertTrue(result.out.contains("P:${childSession}"),
                "子 job 的 SESSION 不能回传父 job: " + result.out);
    }

    @Test
    @DisplayName("process job：SESSION 经子进程继承；子进程写 GLOBAL 父进程可见；LOCAL/SESSION 不泄漏")
    void processJobKeepsScopesAndWritesGlobalBack() throws Exception {
        script("child.sql", """
                _SET GLOBAL @fromChild child-global
                _SET SESSION @childSession child-session
                _SET LOCAL @childLocal child-local
                _ECHO t
                CHILD:${s}/${l}
                echo off
                """);
        Path parent = script("parent.sql", """
                _SET SESSION @s parent-session
                _SET LOCAL @l parent-local
                _JOB JOBMANAGER ON
                _JOB CREATE J script=child.sql mode=process
                _JOB START J
                _JOB WAIT J
                _ECHO t
                PARENT:${fromChild}/${childSession}/${childLocal}
                echo off
                """);

        Result result = run("--execute", parent.toString());

        assertEquals(0, result.exitCode, result.out);
        String childOut = Files.readString(
                tempDir.resolve("output").resolve("jobs").resolve("J.out"),
                StandardCharsets.UTF_8);
        assertTrue(childOut.contains("CHILD:parent-session/${l}"), childOut);
        assertTrue(result.out.contains(
                        "PARENT:child-global/${childSession}/${childLocal}"),
                "GLOBAL 应可见，SESSION/LOCAL 不应回传: " + result.out);
    }

    @Test
    @DisplayName("SESSION 沿孙 job 链传递；LOCAL 不越过当前 job")
    void sessionFlowsToGrandChild() throws Exception {
        script("grand.sql", """
                _ECHO t
                GRAND:${s}/${l}
                echo off
                """);
        script("child.sql", """
                _JOB JOBMANAGER ON
                _JOB CREATE G script=grand.sql mode=thread
                _JOB START G
                _JOB WAIT G
                _ECHO t
                CHILD:${s}/${l}
                echo off
                """);
        Path parent = script("parent.sql", """
                _SET SESSION @s session
                _SET LOCAL @l local
                _JOB JOBMANAGER ON
                _JOB CREATE C script=child.sql mode=thread
                _JOB START C
                _JOB WAIT C
                """);

        Result result = run("--execute", parent.toString());

        assertEquals(0, result.exitCode, result.out);
        String childOut = Files.readString(
                tempDir.resolve("output").resolve("jobs").resolve("C.out"),
                StandardCharsets.UTF_8);
        String grandOut = Files.readString(
                tempDir.resolve("output").resolve("jobs").resolve("G.out"),
                StandardCharsets.UTF_8);
        assertTrue(childOut.contains("CHILD:session/${l}"), childOut);
        assertTrue(grandOut.contains("GRAND:session/${l}"), grandOut);
    }

    @Test
    @DisplayName("其他 job 能读到 GLOBAL（顺序 job 验证）")
    void anotherJobSeesGlobal() throws Exception {
        script("writer.sql", """
                _SET GLOBAL @fromA A
                """);
        script("reader.sql", """
                _ECHO t
                B:${fromA}
                echo off
                """);
        Path parent = script("parent.sql", """
                _JOB JOBMANAGER ON
                _JOB CREATE A script=writer.sql mode=thread
                _JOB START A
                _JOB WAIT A
                _JOB CREATE B script=reader.sql mode=thread
                _JOB START B
                _JOB WAIT B
                """);

        Result result = run("--execute", parent.toString());

        assertEquals(0, result.exitCode, result.out);
        String readerOut = Files.readString(
                tempDir.resolve("output").resolve("jobs").resolve("B.out"),
                StandardCharsets.UTF_8);
        assertTrue(readerOut.contains("B:A"), readerOut);
    }

    @Test
    @DisplayName("作业参数是子 job 的 LOCAL：覆盖继承的 SESSION")
    void jobParameterOverridesInheritedSession() throws Exception {
        script("child.sql", """
                _ECHO t
                V:${v}
                echo off
                """);
        Path parent = script("parent.sql", """
                _SET SESSION @v parent-session
                _JOB JOBMANAGER ON
                _JOB CREATE J script=child.sql v=job-param mode=thread
                _JOB START J
                _JOB WAIT J
                """);

        Result result = run("--execute", parent.toString());

        assertEquals(0, result.exitCode, result.out);
        String childOut = Files.readString(
                tempDir.resolve("output").resolve("jobs").resolve("J.out"),
                StandardCharsets.UTF_8);
        assertTrue(childOut.contains("V:job-param"), childOut);
    }

    @Test
    @DisplayName("作用域只适用于变量：_SET GLOBAL ECHO OFF 给可读错误")
    void scopeOnOptionsIsRejected() throws Exception {
        Path script = script("bad-scope.sql", """
                _SET GLOBAL ECHO OFF
                _ECHO t
                still-runs
                echo off
                """);

        Result result = run("--execute", script.toString());

        assertEquals(0, result.exitCode, result.out);
        assertTrue(result.out.contains("only supported for variables"), result.out);
        assertTrue(result.out.contains("still-runs"), "错误不应中断脚本: " + result.out);
    }

    @Test
    @DisplayName("_SET GLOBAL 子进程写入后父进程立即读到（同一 WORK 的 SQLite）")
    void globalIsSharedAcrossProcesses() throws Exception {
        script("writer.sql", """
                _SET GLOBAL @shared written-by-child
                _ECHO t
                W
                echo off
                """);
        Path parent = script("reader.sql", """
                _JOB JOBMANAGER ON
                _JOB CREATE W script=writer.sql mode=process
                _JOB START W
                _JOB WAIT W
                _ECHO t
                READ:${shared}
                echo off
                """);

        Result result = run("--execute", parent.toString());

        assertEquals(0, result.exitCode, result.out);
        assertTrue(result.out.contains("READ:written-by-child"), result.out);
    }
}
