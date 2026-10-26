package org.slackerdb.dbcli.ssh;

/**
 * 一条远程命令的执行结果。
 *
 * @param command    实际执行的命令
 * @param stdout     标准输出（原样，含换行）
 * @param stderr     标准错误（原样，含换行）
 * @param exitStatus 远端退出码；{@code -1} 表示通道未给出退出码（例如连接中断），
 *                   {@code -2} 表示命令超时被中断
 * @param timedOut   是否因为超过 SSH 超时而被中断（此时 {@code stdout} 是已经读到的部分输出）
 */
public record SshCommandResult(String command, String stdout, String stderr, int exitStatus,
                               boolean timedOut) {

    /** 超时的退出码（与远端真实退出码、{@code -1} 都不冲突）。 */
    public static final int TIMEOUT_EXIT_STATUS = -2;

    /** 不带超时标记的结果（既有的假实现与测试用）。 */
    public SshCommandResult(String command, String stdout, String stderr, int exitStatus) {
        this(command, stdout, stderr, exitStatus, false);
    }

    public boolean success() {
        return exitStatus == 0 && !timedOut;
    }

    /** 输出到 dbcli 控制台时用的文本：只有 stdout 算"结果"，stderr 单独走错误路径。 */
    public String outputText() {
        return stdout == null ? "" : stdout;
    }

    /**
     * 合并输出：把 stderr 接在 stdout 之后一起输出（{@code _SSH execute} 就是用它逐行打印）。
     *
     * <p><b>已知限制</b>：这里只能做"stdout 之后接 stderr"，不能还原两者的**真实交织顺序**
     * （JSch 把两者分流到不同流）。对"逐行输出"的用途而言，差异只体现在"错误信息与正常输出交错"的
     * 场景；要拿到严格交织，需要改用 {@code setErrStream} 指向同一个缓冲并放弃 stdout 的流式读取。</p>
     */
    public String combinedOutput() {
        return outputText() + (stderr == null ? "" : stderr);
    }

    /** 出错提示（stderr 优先，空则给退出码），供引擎拼错误消息。 */
    public String errorText() {
        if (timedOut) {
            return stderr == null || stderr.isEmpty() ? "command timed out" : stderr;
        }
        if (stderr != null && !stderr.isEmpty()) {
            return stderr;
        }
        return exitStatus == 0 ? "" : ("command exited with status " + exitStatus);
    }
}
