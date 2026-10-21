package org.slackerdb.dbcli.repl;

import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link JLineLineSource} 的"无 TTY"测试：用 JLine 的 dumb 终端（数据来自内存流）
 * 走一遍真实的 {@code LineReader}，证明取行链路本身可用，而不是只有测试替身在跑。
 *
 * <p>生产路径（{@code TerminalBuilder.system(true)}）需要真终端，CI 里不该去要；
 * 这里喂的是同一套 JLine 读取实现，因此覆盖了"读到 EOF 返回 null"与"逐行返回"这两条契约。</p>
 *
 * <p><b>终端直接 new，不要交给 {@code TerminalBuilder.dumb(true)}</b>：
 * 在 Linux 上 {@code TerminalBuilder.builder().streams(in, out).dumb(true).build()} 给的是
 * {@code PosixPtyTerminal}（类型 {@code ansi}，实测），它把内存流当成 pty 去读，
 * 第一次 {@code readLine} 就抛 {@code ClosedException} → 这里看到的是 {@code null}（EOF），
 * 于是整组用例在 Linux 上红。{@link DumbTerminal} 正是生产代码在"没有控制台"时用的实现
 * （见 {@link JLineLineSource#system()}），直接 new 它既真实又跨平台一致
 * （{@link JLineLineSourceHistoryTest} 也是这么做的）。</p>
 */
class JLineLineSourceTest {

    private static DumbTerminal dumbTerminal(String input) throws Exception {
        return new DumbTerminal("dbcli", "dumb",
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("逐行读取：按顺序返回每一行，输入耗尽后返回 null（EOF）")
    void readsLinesFromADumbTerminal() throws Exception {
        // JLineLineSource.close() 连终端一起关，所以只让 source 进 try-with-resources
        try (JLineLineSource source = new JLineLineSource(dumbTerminal("select 1;\n_exit\n"))) {
            assertEquals("select 1;", source.readLine("dbcli> "));
            assertEquals("_exit", source.readLine("   > "));
            assertNull(source.readLine("dbcli> "), "输入耗尽后必须返回 null（EOF）");
        }
    }

    @Test
    @DisplayName("空输入：第一次读取就是 EOF（控制台因此立刻收尾）")
    void emptyInputIsEof() throws Exception {
        try (JLineLineSource source = new JLineLineSource(dumbTerminal(""))) {
            assertNull(source.readLine("dbcli> "));
        }
    }
}
