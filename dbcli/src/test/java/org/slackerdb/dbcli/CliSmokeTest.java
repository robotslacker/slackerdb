package org.slackerdb.dbcli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令行入口冒烟测试。
 *
 * <p>验证这些在后续所有步骤里都必须成立的事：</p>
 * <ol>
 *   <li>{@code --version} 打印**构建期生成**的项目版本号 + {@code Build Time} 两行，
 *       形态镜像服务端的 {@code [SERVER] VERSION: …}（这里是 {@code [CLIENT] VERSION: …}）
 *       （第一行只有版本号、构建时刻只在第二行；且没有漏出未替换的 {@code ${...}} 占位符）；</li>
 *   <li>{@code --help} 可用，且**不含**版本与构建时间（{@code --version} 是版本信息的唯一入口）；</li>
 *   <li><b>不带参数时打印用法帮助</b>（而不是任何占位/调试文案），退出码 0；</li>
 *   <li><b>流分离纪律</b>：解析错误只写 stderr，stdout 保持为空 —— 这是"stdout 只放数据、
 *       因为它要被 _COMPARE 与 golden 夹具逐字节比对"这条硬约束的最早一道防线。</li>
 * </ol>
 */
class CliSmokeTest {

    /**
     * 项目版本号的形状：{@code <major>.<minor>.<patch>}。
     *
     * <p>这里刻意不写死 {@code 0.2.2}：版本号来自 POM，改版本不该改测试；但**必须**是纯版本号
     * —— 第一行不得掺构建时刻，也不得掺 git 信息（{@code -<提交>} / {@code -dirty}）。</p>
     */
    private static final String PROJECT_VERSION = "\\d+\\.\\d+\\.\\d+";

    /** 历史口径的紧凑时刻形状（{@code +yyyyMMdd.HHmmss}）：任何一行都不得再出现。 */
    private static final String BUILD_STAMP_SHAPE = "\\+\\d{8}\\.\\d{6}";

    /**
     * 第二行 {@code Build Time} 的形状：**本地时间 + 数字偏移**。
     *
     * <p>存储的是 {@code build.info} 里的 UTC 瞬时（{@code ...Z}），展示时才转本地并带偏移，
     * 因此这里要求 {@code +08:00} 这样的偏移必须出现 —— 没有偏移的时刻串是不可解释的。</p>
     */
    private static final String BUILD_TIME_SHAPE =
            "\\[CLIENT\\] Build Time: \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} [+-]\\d{2}:\\d{2}";

    private CommandLine newCommandLine(StringWriter out, StringWriter err) {
        CommandLine commandLine = new CommandLine(new Cli());
        commandLine.setOut(new PrintWriter(out, true));
        commandLine.setErr(new PrintWriter(out, true)); // dbcli 从不写 stderr：参数错误也在 stdout
        return commandLine;
    }

    @Test
    @DisplayName("--version 打印 [CLIENT] VERSION/Build Time 两行：第一行只有版本号，构建时刻只在第二行")
    void versionPrintsProjectVersionAndBuildTime() {
        StringWriter out = new StringWriter();
        int exitCode = newCommandLine(out, new StringWriter()).execute("--version");

        assertEquals(0, exitCode, "打印版本应当成功退出");
        String printed = out.toString();
        String[] lines = printed.split("\\R");
        assertEquals(2, lines.length, "版本输出正好两行（VERSION 行 + Build Time 行）:\n" + printed);
        // 两行都带 [CLIENT] 前缀，日志里一眼看出是谁打印的；客户端名字不在这里
        // （横幅与 --help 抬头已经带着它）
        assertTrue(lines[0].startsWith("[CLIENT] VERSION: "),
                "第一行应是 [CLIENT] VERSION: + 项目版本号:\n" + printed);
        assertTrue(lines[0].trim().matches("\\[CLIENT\\] VERSION: " + PROJECT_VERSION),
                "第一行只能是项目版本号 <major>.<minor>.<patch>"
                        + "（不得掺构建时刻、不得掺 git 信息），实际:\n" + printed);
        // 构建时刻只在第二行：第一行里不得出现 +<yyyyMMdd.HHmmss> 这种时刻形状
        assertFalse(lines[0].matches("(?s).*" + BUILD_STAMP_SHAPE + ".*"),
                "构建时刻不该再混进第一行（它只出现在 Build Time 行）:\n" + printed);
        // 版本号里不再有 git 信息：既没有 -dirty，也没有 -<短提交>
        assertFalse(lines[0].contains("-dirty"), "版本号里不该再有 -dirty:\n" + printed);
        assertFalse(lines[0].matches(".*\\+\\d{8}\\.\\d{6}-.*"),
                "版本号里不该再追加 -<git 短提交>:\n" + printed);
        assertTrue(lines[1].matches(BUILD_TIME_SHAPE),
                "第二行应是 [CLIENT] Build Time: + 本地构建时间（带偏移）:\n" + printed);
        assertFalse(printed.contains("dbcli (SlackerDB command line client)"),
                "--version 里不该再出现客户端名字（名字归横幅与 --help 抬头）:\n" + printed);
        // 生成物读不到时的降级值也必须可读，绝不能漏出 Maven 占位符
        assertFalse(printed.contains("${"), "版本输出不得漏出未替换的占位符:\n" + printed);
        assertFalse(printed.contains("project.version"), printed);
    }

    @Test
    @DisplayName("--help 可用，且不含版本串/Build Time（--version 是版本信息的唯一入口）")
    void helpIsAvailableWithoutVersionInformation() {
        StringWriter out = new StringWriter();
        int exitCode = newCommandLine(out, new StringWriter()).execute("--help");

        assertEquals(0, exitCode, "帮助应当成功退出");
        String printed = out.toString();
        assertTrue(printed.contains("dbcli (SlackerDB command line client)"),
                "帮助抬头应只有客户端名字，实际输出: " + printed);
        assertFalse(printed.contains("--nologo"), "--nologo 已删除，帮助里不得再出现: " + printed);
        assertFalse(printed.contains("Build Time"),
                "帮助里不该出现 Build Time（只有 --version 打印它），实际输出: " + printed);
        assertFalse(printed.matches("(?s).*" + BUILD_STAMP_SHAPE + ".*"),
                "帮助里不该出现构建时刻形状的版本串，实际输出: " + printed);
        assertFalse(printed.contains("${project.version}"),
                "帮助里不得漏出未替换的 Maven 占位符: " + printed);
        assertFalse(printed.contains("${maven.build.timestamp}"),
                "帮助里不得漏出未替换的 Maven 占位符: " + printed);
        // 变量的唯一写法是 ${name}：--var 的说明要给出它，且不得再提双花括号写法
        assertTrue(printed.contains("--var"), "帮助里应列出 --var，实际输出: " + printed);
        assertTrue(printed.contains("${name}"),
                "--var 的说明里应给出 ${name} 写法（picocli 的 $$ 转义后应显示单个 $）: " + printed);
        assertFalse(printed.contains("{{"),
                "帮助里不得再出现双花括号的变量写法（已只支持 ${name}）: " + printed);
        assertFalse(printed.contains("--selftest"),
                "自检机制已删除，帮助里不得再宣传 --selftest: " + printed);
        // 已删除的选项/子命令不得再出现在帮助里
        assertFalse(printed.contains("--interactive"),
                "--interactive 已删除，帮助里不得再出现: " + printed);
        assertFalse(printed.contains("--logfile-timing"),
                "--logfile-timing 已删除，帮助里不得再出现: " + printed);
        for (String removed : new String[] {"start|stop|status", "Subcommand", "start --execute"}) {
            assertFalse(printed.contains(removed),
                    "子命令已删除，帮助里不得再出现 [" + removed + "]: " + printed);
        }
    }

    @Test
    @DisplayName("不带参数时打印用法帮助，且退出码为 0（不打印任何占位文案）")
    void noArgumentsPrintsUsage() {
        StringWriter out = new StringWriter();
        int exitCode = newCommandLine(out, new StringWriter()).execute();

        assertEquals(0, exitCode, "打印帮助应当成功退出");
        String printed = out.toString();
        assertTrue(printed.contains("Usage: dbcli"), "应当打印用法帮助，实际输出: " + printed);
        assertTrue(printed.contains("--help"), "帮助里应列出 --help，实际输出: " + printed);
        assertTrue(printed.contains("Documentation:"), "帮助里应给出文档指引，实际输出: " + printed);
        // 回归护栏：开发期的占位文案不得再出现在产物里
        assertFalse(printed.contains("M0 step"), "不得打印开发占位文案，实际输出: " + printed);
        assertFalse(printed.contains("skeleton is alive"), "不得打印开发占位文案，实际输出: " + printed);
        assertFalse(printed.contains("${project.version}"), "不得漏出未替换的占位符，实际输出: " + printed);
    }

    @Test
    @DisplayName("已删除的 --nologo 是未知选项：退出码 2，错误进 stdout（dbcli 从不写 stderr）")
    void nologoIsRejected() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exitCode = newCommandLine(out, err).execute("--nologo");

        assertEquals(2, exitCode);
        assertTrue(out.toString().contains("Unknown option"), "解析错误写在 stdout: " + out);
        assertEquals("", err.toString(), "dbcli 从不写 stderr");
    }

    @Test
    @DisplayName("解析错误：退出码非 0，错误进 stdout（stderr 始终为空）")
    void parseErrorGoesToStderrOnly() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exitCode = newCommandLine(out, err).execute("--no-such-option");

        assertNotEquals(0, exitCode, "未知选项应当以非 0 退出");
        assertTrue(out.toString().contains("Unknown option"), "解析错误写在 stdout: " + out);
        assertEquals("", err.toString(), "dbcli 从不写 stderr");
    }

    @Test
    @DisplayName("--selftest 已删除：按未知选项拒绝（stdout 报错、退出码非 0）")
    void selfTestOptionIsGone() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exitCode = newCommandLine(out, err).execute("--selftest");

        assertNotEquals(0, exitCode, "已删除的选项必须以非 0 退出");
        assertTrue(out.toString().contains("Unknown option"), "解析错误写在 stdout: " + out);
        assertTrue(out.toString().contains("'--selftest'"),
                "错误里应点到 --selftest，实际输出: " + out);
        assertEquals("", err.toString(), "dbcli 从不写 stderr");
    }
}
