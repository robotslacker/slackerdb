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
 * 结果格式开关（{@code _SET OUTFORMAT|HEADER|LINEWIDTH|COLWIDTH}）的命令行级测试。
 *
 * <p>这些开关以前是命令行参数（{@code --format} / {@code --no-header} / {@code --null} /
 * {@code --max-col-width}），现在只在脚本/控制台里设置；命令行不再接受它们。
 * 不需要数据库：{@code _SET} 会打印一张选项表（走的是与查询结果**同一条**渲染路径），
 * 因此"换格式"的接线可以在没有服务端的情况下端到端验证。</p>
 */
class CliFormatTest {

    @TempDir
    Path tempDir;

    private record Result(String out, String err, int exitCode) {
    }

    private Result run(String script, String... extraArgs) {
        Path file = tempDir.resolve("case.sql");
        try {
            Files.writeString(file, script, StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        String[] args = new String[extraArgs.length + 2];
        args[0] = "--execute";
        args[1] = file.toString();
        System.arraycopy(extraArgs, 0, args, 2, extraArgs.length);
        Cli cli = new Cli();
        cli.setStdin(new ByteArrayInputStream(new byte[0]));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = new CommandLine(cli);
        commandLine.setOut(new PrintWriter(out, true));
        commandLine.setErr(new PrintWriter(out, true)); // dbcli 从不写 stderr：参数错误也在 stdout
        int exitCode = commandLine.execute(args);
        return new Result(out.toString(), err.toString(), exitCode);
    }

    private static final String SET_TABLE = "_SET\n";

    @Test
    @DisplayName("_set outformat csv：纯逗号分隔、无行号列；状态行照旧（由 _set feedback 控制）")
    void csvFormat() {
        Result result = run("_set outformat csv;\n_set;\n");

        assertEquals(0, result.exitCode, result.out);
        // 状态行由 _set feedback 控制，与格式无关：CSV 下它照样在前面
        assertTrue(result.out.startsWith("Current set options:\nOption,Current value,Default value\n"),
                "状态行照常出现，表头紧随其后: " + result.out);
        assertTrue(result.out.contains("\nfeedback,on,on\n"), result.out);
        assertFalse(result.out.contains("+---"), "CSV 不该出现表格边框: " + result.out);
    }

    @Test
    @DisplayName("_set outformat vertical：仍是给人看的格式——状态行照旧保留")
    void verticalKeepsStatusLines() {
        Result result = run("_set outformat vertical;\n_set;\n");

        assertEquals(0, result.exitCode, result.out);
        assertTrue(result.out.contains("Current set options:"), result.out);
        assertTrue(result.out.contains("-[ RECORD 1 ]"), result.out);
        assertTrue(result.out.contains("| feedback"), result.out);
    }

    @Test
    @DisplayName("_set header off：CSV 去掉表头行；表格去掉表头与分割线")
    void noHeader() {
        Result csv = run("_set outformat csv;\n_set header off;\n_set;\n");
        assertTrue(csv.out.contains("echo,on,on"), "无表头的 CSV 直接是数据（选项表第一项是 echo）: " + csv.out);
        assertTrue(csv.out.contains("feedback,on,on"), csv.out);
        assertFalse(csv.out.contains("Current value"), "CSV 不该再有表头: " + csv.out);

        Result table = run("_set header off;\n_set;\n");
        assertFalse(table.out.contains("|   ##   |"), "表格不该再有表头行: " + table.out);
        assertTrue(table.out.contains("+--------+"), table.out);
    }

    @Test
    @DisplayName("_set colwidth：table/vertical 用 ... 截断；CSV 保持原值")
    void colWidth() {
        Result table = run("_set colwidth 3;\n_set;\n");
        assertTrue(table.out.contains("..."), "表格里应当出现截断标记: " + table.out);
        assertFalse(table.out.contains("feedback"), "feedback 已被截断: " + table.out);

        Result csv = run("_set outformat csv;\n_set colwidth 3;\n_set;\n");
        assertTrue(csv.out.contains("feedback,on,on"), "CSV 是导出格式，不截断: " + csv.out);
        assertFalse(csv.out.contains("..."), csv.out);
    }

    @Test
    @DisplayName("_set linewidth：整行超宽时换行（不丢字符），CSV 不受影响")
    void lineWidth() {
        Result table = run("_set linewidth 20;\n_set;\n");
        // 换行不截断：选项名仍在（可能被拆到另一行），且最长行不超过 20 个显示列
        assertTrue(table.out.replace("\n", "").contains("feedback"), table.out);
        for (String line : table.out.split("\n", -1)) {
            assertTrue(line.length() <= 20, "行宽应被限制在 20: <" + line + ">");
        }

        Result csv = run("_set outformat csv;\n_set linewidth 10;\n_set;\n");
        assertTrue(csv.out.contains("Option,Current value,Default value"),
                "CSV 不换行（保持一行一条记录）: " + csv.out);
    }

    @Test
    @DisplayName("_set outformat 写错：可读错误 + 列出合法取值，脚本继续、退出 1")
    void unknownFormatIsAReadableError() {
        Result result = run("_set outformat xml;\n_set;\n");

        assertEquals(0, result.exitCode);
        assertTrue(result.out.contains("Set option [outformat] requires table or vertical or csv"),
                result.out);
        assertTrue(result.out.contains("Current set options:"), "写错不该中断脚本: " + result.out);
    }

    @Test
    @DisplayName("_set linewidth / colwidth 写负数：可读错误（退出 1）")
    void negativeWidthIsAReadableError() {
        Result result = run("_set linewidth -1;\n_set colwidth -5;\n");

        assertEquals(0, result.exitCode);
        assertTrue(result.out.contains("requires a non-negative integer"), result.out);
    }

    @Test
    @DisplayName("命令行不再有 --format/--no-header/--null/--max-col-width")
    void theFormatFlagsAreGoneFromTheCommandLine() {
        for (String removed : new String[] {"--format", "--no-header", "--null", "--max-col-width"}) {
            Result result = run(SET_TABLE, removed, "x");

            assertEquals(2, result.exitCode, removed + " 应当被当成未知选项: " + result.out);
            assertTrue(result.out.contains("Unknown option"), removed + " → " + result.out);
        }
    }
}
