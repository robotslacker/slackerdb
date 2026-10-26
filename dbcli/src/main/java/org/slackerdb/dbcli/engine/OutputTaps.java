package org.slackerdb.dbcli.engine;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 旁路文件（{@code _SPOOL} 与命令行 tee）的**写入器管理**：打开、追加写、关掉、失败处置。
 *
 * <p>从 {@link ScriptOutput} 抽出来的原因：那一层关心的是"这条消息该走哪个通道"
 * （数据 / 状态 / 错误 / 诊断 / 转录），这一层关心的是"同一行字节怎么同时落到 0..N 个文件里"。
 * 两者混在一个 500 行的类里时，"spool 写失败怎么办"和"错误要不要进 tee"读起来像同一件事。</p>
 *
 * <h2>两种旁路的区别</h2>
 * <ul>
 *   <li><b>spool</b>（{@code _SPOOL}）：脚本在运行中控制的，同一时刻最多一个；</li>
 *   <li><b>tee</b>（{@code --logfile} / {@code --reference} 的 log）：命令行在整次运行期间控制的，
 *       可以同时有两个（{@code --logfile} 与 {@code --ref} 各一个）。</li>
 * </ul>
 *
 * <p>两者行尾都固定 {@code "\n"}、UTF-8、打开即截断；写失败都**不改变脚本结果**
 * （交回 {@link FailureSink} 报一行错误并停掉该旁路），避免一个旁路文件把整次运行搞失败。</p>
 */
final class OutputTaps {

    /** 旁路写失败的去处（{@link ScriptOutput} 用它打一行可读错误）。 */
    interface FailureSink {

        /**
         * @param message 已经拼好的可读错误行（例如 {@code ERROR: cannot write the spool file [...]}）；
         *                收到时对应的旁路已经停掉，不会每行再报一次
         */
        void reportTapFailure(String message);
    }

    private final Object lock = new Object();
    private final FailureSink failures;

    private Path spoolFile;
    private Writer spoolWriter;

    private final List<Writer> teeWriters = new ArrayList<>();
    private Path teeFile;

    OutputTaps(FailureSink failures) {
        this.failures = failures;
    }

    // ------------------------------------------------------------------ spool

    /**
     * 开始 spool（截断写）。
     *
     * @throws IOException 文件打不开（引擎转成"可读错误 + 计错误数"）
     */
    void startSpool(Path file) throws IOException {
        synchronized (lock) {
            stopSpoolQuietly();
            spoolWriter = open(file);
            spoolFile = file;
        }
    }

    /** 停止 spool（幂等）。 */
    void stopSpool() {
        synchronized (lock) {
            stopSpoolQuietly();
        }
    }

    private void stopSpoolQuietly() {
        if (spoolWriter == null) {
            spoolFile = null;
            return;
        }
        try {
            spoolWriter.flush();
            spoolWriter.close();
        } catch (IOException ignored) {
            // 关不掉也无法补救；文件内容已经落盘的部分仍然可用
        }
        spoolWriter = null;
        spoolFile = null;
    }

    boolean isSpooling() {
        synchronized (lock) {
            return spoolWriter != null;
        }
    }

    /** 当前 spool 文件（未 spool 时为 null）。 */
    Path spoolFile() {
        synchronized (lock) {
            return spoolFile;
        }
    }

    /** 写一行 spool；失败时报一行可读错误并停掉 spool（避免每行都报一次）。 */
    void writeSpool(String line) {
        synchronized (lock) {
            if (spoolWriter == null) {
                return;
            }
            try {
                append(spoolWriter, line);
            } catch (IOException spoolFailed) {
                Path failedFile = spoolFile;
                stopSpoolQuietly();
                failures.reportTapFailure("ERROR: cannot write spool file [" + failedFile + "]: "
                        + spoolFailed.getMessage());
            }
        }
    }

    /** 写一行 spool，但**不回报**失败（错误行与回显行走这条，避免递归）。 */
    void writeSpoolQuietly(String line) {
        synchronized (lock) {
            if (spoolWriter == null) {
                return;
            }
            try {
                append(spoolWriter, line);
            } catch (IOException ignored) {
                stopSpoolQuietly();
            }
        }
    }

    // -------------------------------------------------------------------- tee

    /**
     * 开始把输出 tee 到文件；重复调用先停掉上一个。
     *
     * @throws IOException 文件打不开（命令行层转成"可读错误 + 退出 1"）
     */
    void startTee(Path file) throws IOException {
        synchronized (lock) {
            stopTeeQuietly();
            teeWriters.add(open(file));
            teeFile = file;
        }
    }

    /**
     * 再加一个 tee 目标（{@code --logfile} 与 {@code --reference} 同时给出时用）。
     *
     * @throws IOException 文件打不开
     */
    void addTee(Path file) throws IOException {
        synchronized (lock) {
            teeWriters.add(open(file));
            teeFile = file;
        }
    }

    /** 停止 tee 并落盘（幂等）。 */
    void stopTee() {
        synchronized (lock) {
            stopTeeQuietly();
        }
    }

    private void stopTeeQuietly() {
        for (Writer writer : teeWriters) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException ignored) {
                // 关不掉无法补救；已经落盘的部分仍然可用
            }
        }
        teeWriters.clear();
        teeFile = null;
    }

    boolean isTeeing() {
        synchronized (lock) {
            return !teeWriters.isEmpty();
        }
    }

    /** 当前 tee 文件（未 tee 时为 null）。 */
    Path teeFile() {
        synchronized (lock) {
            return teeFile;
        }
    }

    /** 写一行 tee；失败时报一行可读错误并停掉全部 tee。 */
    void writeTee(String line) {
        synchronized (lock) {
            if (teeWriters.isEmpty()) {
                return;
            }
            try {
                appendAll(line);
            } catch (IOException teeFailed) {
                Path failedFile = teeFile;
                stopTeeQuietly();
                failures.reportTapFailure("ERROR: cannot write the output file [" + failedFile + "]: "
                        + teeFailed.getMessage());
            }
        }
    }

    /** 写一行 tee，但不回报失败（错误/转录/回显走这条，避免递归）。 */
    void writeTeeQuietly(String line) {
        synchronized (lock) {
            if (teeWriters.isEmpty()) {
                return;
            }
            try {
                appendAll(line);
            } catch (IOException ignored) {
                stopTeeQuietly();
            }
        }
    }

    /** 收尾：关掉全部旁路（幂等）。 */
    void close() {
        synchronized (lock) {
            stopTeeQuietly();
            stopSpoolQuietly();
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 打开一个旁路文件（UTF-8、截断）。
     *
     * <p>先判父目录是否存在：不存在时 {@code newBufferedWriter} 抛出的消息因平台而异，
     * 这里的"directory [x] does not exist"对使用者更直接。</p>
     */
    private static Writer open(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            throw new IOException("directory [" + parent + "] does not exist");
        }
        return Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static void append(Writer writer, String line) throws IOException {
        writer.write(line);
        writer.write('\n');
        writer.flush();
    }

    private void appendAll(String line) throws IOException {
        for (Writer writer : teeWriters) {
            append(writer, line);
        }
    }
}
