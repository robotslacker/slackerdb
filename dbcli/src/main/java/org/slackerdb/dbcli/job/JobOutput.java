package org.slackerdb.dbcli.job;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 一个作业的**输出目标**：只知道一个文件 {@code <日志目录>/<作业名>.out}。
 *
 * <h2>为什么必须是文件而不是父进程的 stdout</h2>
 * <p>并发作业共享一个 JVM，如果都往父脚本的 stdout 写，行就会交错，父脚本的输出也不再是"父脚本的产出"。
 * 因此每个作业的输出落到 {@code <日志目录>/<作业名>.out}。</p>
 *
 * <h2>为什么只有 .out，没有 .err</h2>
 * <p>作业的 stdout 与 stderr **写进同一个文件**（按发生顺序逐行追加）：一个作业的"输出"是一份记录，
 * 拆成两个文件只会让人来回翻。父脚本仍然完全看不到它们——隔离靠"写到文件"，不靠"分两个文件"。</p>
 *
 * <p>打开即截断（与 {@code ProcessBuilder.redirectOutput} 的语义一致）：重复启动同名作业不会看到上次的残留。</p>
 */
public final class JobOutput implements AutoCloseable {

    private final PrintWriter stdout;
    private final PrintWriter stderr;
    private final Path stdoutFile;

    private JobOutput(PrintWriter stdout, PrintWriter stderr, Path stdoutFile) {
        this.stdout = stdout;
        this.stderr = stderr;
        this.stdoutFile = stdoutFile;
    }

    /**
     * 打开作业的唯一输出文件（UTF-8、截断）。
     *
     * @throws IOException 目录不存在或文件打不开（调用方转成"作业启动失败"）
     */
    public static JobOutput open(Path stdoutFile) throws IOException {
        PrintWriter out = new PrintWriter(Files.newBufferedWriter(stdoutFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
        // 两路写同一个 writer：作业的 stdout 与 stderr 都进 <作业名>.out
        return new JobOutput(out, out, stdoutFile);
    }

    /**
     * 丢弃型输出（**进程模式**用）：什么也不写。
     *
     * <p>为什么需要它：进程模式里 {@code <作业名>.out} 由**子进程自己**重定向写入，
     * 父进程如果也打开这个文件就会同时持有写句柄——Windows 上子进程的重定向会打不开（共享冲突）。
     * 于是父进程拿一个丢弃型目标：它只用来承接"作业线程抛异常"这类可读错误，而那些错误
     * 在进程模式下由执行体自己追加到 {@code .out}（见 {@code ProcessJobExecutor}）。</p>
     */
    public static JobOutput discard(Path stdoutFile) {
        PrintWriter sink = new PrintWriter(Writer.nullWriter(), true);
        return new JobOutput(sink, sink, stdoutFile);
    }

    /** 作业的数据/状态行目标（{@code <作业名>.out}）。 */
    public PrintWriter stdout() {
        return stdout;
    }

    /** 作业的错误目标——**同一个文件**（{@code <作业名>.out}）。 */
    public PrintWriter stderr() {
        return stderr;
    }

    public Path stdoutFile() {
        return stdoutFile;
    }

    /** 落盘并关闭（幂等；关不掉不抛：作业的收尾不该因为文件问题而改变结论）。 */
    @Override
    public void close() {
        closeQuietly(stdout);
        if (stderr != stdout) {
            closeQuietly(stderr);
        }
    }

    private static void closeQuietly(PrintWriter writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
            writer.close();
        } catch (RuntimeException ignored) {
            // 见方法注释
        }
    }
}
