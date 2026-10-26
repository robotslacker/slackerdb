package org.slackerdb.dbcli.engine;

import org.slackerdb.dbcli.sql.RenderOptions;
import org.slackerdb.dbcli.sql.ResultRenderers;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

/**
 * 脚本输出的**唯一**出口：脚本执行期间所有字节都从这里出去，别处不碰 {@code System.out}/{@code System.err}。
 *
 * <h2>通道纪律（谁写哪里）</h2>
 * <table border="1">
 *   <caption>五种内容与它们的去向</caption>
 *   <tr><th>方法</th><th>stdout</th><th>日志/tee</th><th>{@code _SPOOL}</th><th>用途</th></tr>
 *   <tr><td>{@link #printData}</td><td>✓</td><td>✓</td><td>✓</td><td>查询结果、{@code _ECHO} 正文、表格</td></tr>
 *   <tr><td>{@link #printStatus}</td><td>✓</td><td>✓</td><td>✓</td><td>{@code 2 rows selected.} 这类摘要</td></tr>
 *   <tr><td>{@link #printDiagnostic}</td><td>✓</td><td>✓</td><td>✓</td><td>{@code _SET TIMING/TIME}</td></tr>
 *   <tr><td>{@link #printError}</td><td>✓（经 err）</td><td>✓</td><td>✓</td><td>可读错误</td></tr>
 *   <tr><td>{@link #printInput} / {@link #printTranscript}</td><td>—</td><td>✓</td><td>✓</td><td>回显的语句 / 控制台转录</td></tr>
 * </table>
 *
 * <p>stdout 是要被 {@code _COMPARE} 与 golden 夹具**逐字节比对**的产物：状态行属于契约，
 * 而"这次跑了什么"（语句回显）与"这次怎么跑的"（转录）只进文件。行尾固定 {@code "\n"}，
 * 因此同一脚本在 Windows/Linux 上的 stdout 逐字节一致。</p>
 *
 * <h2>{@code --silent}</h2>
 * <p>终端通道一起关掉，这次运行唯一的结果是退出码；文件产物（spool、{@code --logfile}、
 * {@code --ref} 的 log）**照常写**——它们是文件，不是"打印"。</p>
 *
 * <h2>开关归谁</h2>
 * <p>{@code _SET} 的选项由 {@link SetOption} 落到本类：{@code ECHO}/{@code FEEDBACK}/{@code TERMOUT}
 * 各管一类内容；{@code OUTFORMAT}/{@code HEADER}/{@code LINEWIDTH}/{@code COLWIDTH} 只管表格渲染。
 * 旁路文件（spool/tee）的打开与失败处置在 {@link OutputTaps}。</p>
 */
public final class ScriptOutput implements AutoCloseable {

    private final PrintWriter out;
    private final PrintWriter err;

    /** {@code --silent}：终端上什么都不打印，只留退出码（文件产物照常写）。 */
    private final boolean silent;

    /** 全部输出操作的串行化锁：主线程与控制台/子脚本的所有写入都从这里过。 */
    private final Object writeLock = new Object();

    /** spool 与 tee 的写入器（旁路文件的生命周期与失败处置都在那里）。 */
    private final OutputTaps taps;

    /** 结果集的输出格式（{@code _SET OUTFORMAT|HEADER|LINEWIDTH|COLWIDTH}）。 */
    private RenderOptions renderOptions = RenderOptions.defaults();

    /** {@code _SET ECHO}：语句回显（只写日志/spool，不改 stdout）。 */
    private boolean echo = true;

    /** 控制台转录模式：日志记的是"屏幕上出现过的东西"（横幅、提示符、敲进去的行）。 */
    private boolean transcript;

    /** {@code _SET FEEDBACK}：状态行与错误消息是否打印。 */
    private boolean feedback = true;

    /** {@code _SET TERMOUT}：结果集是否输出（连日志与 spool 一起关）。 */
    private boolean termOut = true;

    public ScriptOutput(PrintWriter out, PrintWriter err) {
        this(out, err, false);
    }

    public ScriptOutput(PrintWriter out, PrintWriter err, boolean silent) {
        this.out = out;
        this.err = err;
        this.silent = silent;
        this.taps = new OutputTaps(message -> printError(message));
    }

    // ------------------------------------------------------------------ 开关

    /** 设置结果集的输出格式（{@code _SET OUTFORMAT|HEADER|LINEWIDTH|COLWIDTH}）。 */
    public void setRenderOptions(RenderOptions renderOptions) {
        this.renderOptions = renderOptions == null ? RenderOptions.defaults() : renderOptions;
    }

    /** 当前输出格式（供引擎判断是否需要在表格外补空行等）。 */
    public RenderOptions renderOptions() {
        return renderOptions;
    }

    /** {@code _SET OUTFORMAT}：换结果集的渲染格式（table / vertical / csv；不影响状态行）。 */
    public void setOutputFormat(RenderOptions.Format format) {
        this.renderOptions = renderOptions.withFormat(format);
    }

    /** {@code _SET HEADER}：表头开关。 */
    public void setHeader(boolean header) {
        this.renderOptions = renderOptions.withHeader(header);
    }

    /** {@code _SET COLWIDTH}：单元格宽度上限（{@code <= 0} = 不截断）。 */
    public void setColWidth(int colWidth) {
        this.renderOptions = renderOptions.withColWidth(colWidth);
    }

    /** {@code _SET LINEWIDTH}：整行宽度上限（{@code <= 0} = 不换行）。 */
    public void setLineWidth(int lineWidth) {
        this.renderOptions = renderOptions.withLineWidth(lineWidth);
    }

    /** 控制台转录模式：日志（{@code --logfile} / {@code --ref} 的 log）记屏幕上的内容。 */
    public void setTranscript(boolean transcript) {
        this.transcript = transcript;
    }

    /** {@code _SET ECHO}：输入回显开关。 */
    public void setEcho(boolean echo) {
        this.echo = echo;
    }

    /** {@code _SET FEEDBACK}：状态行与错误消息开关。 */
    public void setFeedback(boolean feedback) {
        this.feedback = feedback;
    }

    /** {@code _SET TERMOUT}：结果集输出开关（连日志/spool 一起关）。 */
    public void setTermOut(boolean termOut) {
        this.termOut = termOut;
    }

    /** 是否处于 {@code --silent}。 */
    public boolean isSilent() {
        return silent;
    }

    // ------------------------------------------------------------------ 输出

    /** 数据行：stdout + 日志 + spool（{@code TERMOUT OFF} 时什么都不写）。 */
    public void printData(String line) {
        if (line == null || !termOut) {
            return;
        }
        synchronized (writeLock) {
            writeTerminal(out, line);
            taps.writeSpool(line);
            taps.writeTee(line);
        }
    }

    /** 状态行：stdout + 日志 + spool（受 {@code FEEDBACK} 管）。 */
    public void printStatus(String line) {
        if (line == null || !feedback) {
            return;
        }
        synchronized (writeLock) {
            writeTerminal(out, line);
            taps.writeSpool(line);
            taps.writeTee(line);
        }
    }

    /**
     * 诊断行（{@code _SET TIMING/TIME}）：走 stdout，因此也进日志与 spool。
     *
     * <p>不受 {@code TERMOUT} 影响：那是"这批结果不要"，而耗时/时间是"这次跑得怎么样"。</p>
     */
    public void printDiagnostic(String line) {
        if (line == null) {
            return;
        }
        synchronized (writeLock) {
            writeTerminal(out, line);
            taps.writeSpool(line);
            taps.writeTee(line);
        }
    }

    /**
     * "同时是状态行与失败"的一行（{@code Assert fail.}、{@code Compare failed.}、
     * {@code Job [x] finished with exit code n.}）。
     *
     * <p>若错误流与 stdout 是**同一个流**（dbcli 就是这样），只打一次；只有两者是不同流时
     * （测试夹具、把 dbcli 当库嵌入的调用方）才在错误流上再记一行——否则同一句失败会出现两遍。</p>
     */
    public void printFailure(String line) {
        if (line == null || !feedback) {
            return;
        }
        synchronized (writeLock) {
            writeTerminal(out, line);
            taps.writeSpool(line);
            taps.writeTee(line);
            if (err != out && !silent) {
                writeLine(err, line);
            }
        }
    }

    /**
     * 错误行：stdout（经 err）+ 日志 + spool。
     *
     * <p>错误也进文件：只写 stdout 的话，一个失败的脚本留下的日志看起来就像一次干净的运行。
     * 它受 {@code FEEDBACK} 管（关掉之后摘要与错误都不打印），{@code --silent} 只静默**终端**。</p>
     */
    public void printError(String message) {
        if (message == null || !feedback) {
            return;
        }
        if (!silent) {
            writeLine(err, message);
        }
        // quietly 版本：日志/spool 写不进去时会回调 printError，直接复用会无限递归
        synchronized (writeLock) {
            taps.writeTeeQuietly(message);
            taps.writeSpoolQuietly(message);
        }
    }

    /** 结果集：用 {@link ResultRenderers} 渲染成数据行。 */
    public void printTable(List<String> headers, List<String> columnTypes, List<List<Object>> rows) {
        if (headers == null) {
            return;
        }
        for (String line : ResultRenderers.render(renderOptions, headers, columnTypes,
                rows == null ? List.of() : rows)) {
            printData(line);
        }
    }

    /** 输入回显：把一条 SQL/命令的**原文**写进日志与 spool（不进 stdout）。 */
    public void printInput(String text) {
        if (text == null || text.isEmpty() || !echo) {
            return;
        }
        synchronized (writeLock) {
            taps.writeTeeQuietly(text);
            taps.writeSpoolQuietly(text);
        }
    }

    /** 转录行（横幅 / 提示符 + 敲进去的那一行）：只写日志与 spool。 */
    public void printTranscript(String line) {
        if (line == null || line.isEmpty() || silent || !echo || !transcript) {
            return;
        }
        synchronized (writeLock) {
            taps.writeTeeQuietly(line);
            taps.writeSpoolQuietly(line);
        }
    }

    // -------------------------------------------------------------- 旁路文件

    /**
     * 开始 spool（{@code _SPOOL <文件>}）。
     *
     * @throws IOException 文件打不开（引擎转成"可读错误 + 计错误数"）
     */
    public void startSpool(Path file) throws IOException {
        taps.startSpool(file);
    }

    /** 停止 spool（幂等）。 */
    public void stopSpool() {
        taps.stopSpool();
    }

    public boolean isSpooling() {
        return taps.isSpooling();
    }

    /** 当前 spool 文件（未 spool 时为 null）。 */
    public Path spoolFile() {
        return taps.spoolFile();
    }

    /**
     * 开始把输出 tee 到文件（{@code --logfile} / {@code --reference}）；重复调用先停掉上一个。
     *
     * @throws IOException 文件打不开（命令行层转成"可读错误 + 退出 1"）
     */
    public void startTee(Path file) throws IOException {
        taps.startTee(file);
    }

    /**
     * 再加一个 tee 目标（{@code --logfile} 与 {@code --reference} 同时给出时用）。
     *
     * @throws IOException 文件打不开
     */
    public void addTee(Path file) throws IOException {
        taps.addTee(file);
    }

    /** 停止 tee 并落盘（幂等）：返回后文件内容就是"已产出的全部字节"。 */
    public void stopTee() {
        taps.stopTee();
    }

    public boolean isTeeing() {
        return taps.isTeeing();
    }

    /** 当前 tee 文件（未 tee 时为 null）。 */
    public Path teeFile() {
        return taps.teeFile();
    }

    /** 收尾：停 spool/tee 并刷新两个流（进程退出前必须调用，否则最后几行可能没落盘）。 */
    @Override
    public void close() {
        taps.close();
        out.flush();
        err.flush();
    }

    // ------------------------------------------------------------------ 内部

    /** 写终端（{@code --silent} 时写丢弃流：字节不出现，但调用点不必各自判断）。 */
    private void writeTerminal(PrintWriter terminal, String line) {
        if (!silent) {
            writeLine(terminal, line);
        }
    }

    /** 写一行：行尾固定 {@code "\n"}（平台换行会让两平台的 stdout 不一致）。 */
    private static void writeLine(PrintWriter writer, String line) {
        writer.print(line);
        writer.print('\n');
        writer.flush();
    }
}
