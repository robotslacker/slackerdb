package org.slackerdb.dbcli.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProcessTree} 的真实进程测试：**杀一个子进程必须连它的后代一起杀**。
 *
 * <p>为什么值得单独测：Windows 上 {@code Process.destroy()} 只杀直接子进程（{@code cmd.exe}），
 * 命令真正的进程会变成**孤儿继续运行**，还攥住"以脚本工作目录为当前目录"的句柄，
 * 导致目录删不掉、资源泄漏。</p>
 *
 * <p>做法是跨平台可判定的：先起一个"必然带后代"的 shell 命令，记下它当时的所有后代 pid，
 * 销毁它，然后断言**这些后代一个都不剩**。</p>
 */
class ProcessTreeTest {

    /** 起一个会挂住几十秒、并且自身还会再拉一个子进程的命令。 */
    private static Process startTree() throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        // POSIX 上不能只写 "sleep 60"：单条命令时 sh 会直接 exec 成 sleep 本身（省一次 fork），
        // 于是这个"父进程"根本没有后代，用例的前提（必然带后代）不成立——在 Linux 上是这么红的。
        // 后面再接一条命令，sh 就只能 fork 出 sleep 再自己留下收尾，父子关系稳定存在。
        List<String> command = windows
                ? List.of("cmd.exe", "/c", "ping -n 60 127.0.0.1 > nul")
                : List.of("/bin/sh", "-c", "sleep 60; exit 0");
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    /** 等后代出现（拉起真正干活的进程需要一点时间），返回观测到的后代。 */
    private static List<ProcessHandle> awaitDescendants(Process process, long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        List<ProcessHandle> descendants = List.of();
        while (System.nanoTime() < deadline) {
            descendants = process.descendants().toList();
            if (!descendants.isEmpty()) {
                return descendants;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return descendants;
            }
        }
        return descendants;
    }

    @Test
    @DisplayName("kill：直接子进程与它的全部后代都被强杀（不留孤儿）")
    void killTakesDownTheWholeTree() throws Exception {
        Process process = startTree();
        try {
            List<ProcessHandle> descendants = awaitDescendants(process, 10_000L);
            assertFalse(descendants.isEmpty(), "测试前提不成立：这个命令应当会拉一个子进程出来");
            assertTrue(descendants.stream().anyMatch(ProcessHandle::isAlive),
                    "取到后代快照时它应当还在跑");

            ProcessTree.kill(process);

            assertFalse(process.isAlive(), "直接子进程必须已经被杀掉");
            for (ProcessHandle descendant : descendants) {
                assertFalse(descendant.isAlive(), "后代进程 " + descendant.pid() + " 变成了孤儿（没被杀掉）");
            }
        } finally {
            ProcessTree.kill(process);
        }
    }

    @Test
    @DisplayName("destroy：先优雅再强杀，同样收掉整棵树")
    void destroyTakesDownTheWholeTree() throws Exception {
        Process process = startTree();
        try {
            List<ProcessHandle> descendants = awaitDescendants(process, 10_000L);
            assertFalse(descendants.isEmpty(), "测试前提不成立：这个命令应当会拉一个子进程出来");

            ProcessTree.destroy(process, 200L);

            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "直接子进程必须已经退出");
            for (ProcessHandle descendant : descendants) {
                assertFalse(descendant.isAlive(), "后代进程 " + descendant.pid() + " 变成了孤儿（没被杀掉）");
            }
        } finally {
            ProcessTree.kill(process);
        }
    }
}
