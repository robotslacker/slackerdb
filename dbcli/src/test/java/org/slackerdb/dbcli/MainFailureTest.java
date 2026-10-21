package org.slackerdb.dbcli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Main} 对未捕获 {@link Error} 的收敛（OOM / StackOverflow）。
 *
 * <p>为什么值得单测：picocli 的执行异常处理器只接收 {@link Exception}，{@code OutOfMemoryError}
 * 会绕过它直接打 Java 栈退出——那样 CI 看到的是"退出码 1 + 一段栈"，
 * 与"脚本里有断言失败"无法区分。本节锁住三条：可读的一行、专用退出码 70、stdout 不受污染。</p>
 */
class MainFailureTest {

    private static final class Harness {
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final CommandLine commandLine = new CommandLine(new Cli());

        Harness() {
            commandLine.setOut(new PrintWriter(out, true));
            commandLine.setErr(new PrintWriter(out, true)); // dbcli 从不写 stderr：参数错误也在 stdout
        }
    }

    @Test
    @DisplayName("OutOfMemoryError：一行可读错误 + 退出码 70 + 给 OOM 的两条建议 + 不打印栈")
    void outOfMemoryBecomesReadable() {
        Harness harness = new Harness();

        int code = Main.reportFatal(harness.commandLine, new OutOfMemoryError("Java heap space"));

        assertEquals(Main.EXIT_INTERNAL_ERROR, code);
        assertEquals(70, code, "专用退出码不能和'脚本失败=1'混在一起");
        String stderr = harness.out.toString();
        assertTrue(stderr.contains("dbcli aborted by java.lang.OutOfMemoryError: Java heap space"),
                stderr);
        assertTrue(stderr.contains("this run is incomplete"), stderr);
        assertTrue(stderr.contains("-Xmx"), "OOM 要给可执行的建议（加堆）: " + stderr);
        assertFalse(stderr.contains("\tat "), "只给一行结论，不打印 Java 栈: " + stderr);
        assertEquals("", harness.err.toString(), "stdout 是数据通道，这里一个字都不能多");
    }

    @Test
    @DisplayName("其它 Error（StackOverflowError）：同样收敛成一行 + 70，但不给 OOM 专属建议")
    void otherErrorsUseTheSamePathWithoutHeapHint() {
        Harness harness = new Harness();

        int code = Main.reportFatal(harness.commandLine, new StackOverflowError());

        assertEquals(70, code);
        String stderr = harness.out.toString();
        assertTrue(stderr.contains("java.lang.StackOverflowError"), stderr);
        assertFalse(stderr.contains("-Xmx"), "只有 OOM 才给堆相关的建议: " + stderr);
        assertFalse(stderr.contains("\tat "), stderr);
    }

    @Test
    @DisplayName("无消息的 Error：只报类名，不出现 'null'")
    void errorWithoutMessageDoesNotPrintNull() {
        Harness harness = new Harness();

        Main.reportFatal(harness.commandLine, new InternalError());

        String stderr = harness.out.toString();
        assertTrue(stderr.contains("java.lang.InternalError"), stderr);
        assertFalse(stderr.contains("null"), stderr);
    }
}
