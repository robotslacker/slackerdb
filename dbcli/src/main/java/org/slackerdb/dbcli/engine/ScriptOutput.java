package org.slackerdb.dbcli.engine;

import org.slackerdb.dbcli.sql.RenderOptions;
import org.slackerdb.dbcli.sql.ResultRenderers;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 脚本输出的**唯一**出口：脚本执行期间所有字节都从这里出去，别处不再碰 {@code System.out}/{@code System.err}。
 *
 * <h2>流分离纪律</h2>
 * <ul>
 *   <li>{@link #printData(String)} → <b>stdout</b>（数据行：查询结果、{@code _ECHO} 的文本、表头边框…），
 *       同时写 spool；stdout 是要被 {@code _COMPARE} 与 golden 夹具**逐字节比对**的产物，
 *       所以状态行、错误、日志都不得混进来；</li>
 *   <li>{@link #printStatus(String)} → <b>stdout</b>（状态行也是输出契约的一部分，例如
 *       {@code Assert successful.}/{@code Compare failed.}/{@code 2 rows selected.}），同时写 spool；</li>
 *   <li>{@link #printError(String)} → <b>stdout</b>、tee（{@code --logfile} / {@code --reference} 的 log）
 *       与 spool（{@code _SPOOL}）：三者内容一致。日志与 spool 都是"这次运行发生过什么"的记录，
 *       少了错误行就等于少了一半事实；stdout 才是那份"只放数据与状态行"的比对产物。</li>
 *   <li>{@link #printTable} 用 {@link SqlTableRenderer} 渲染后按数据行输出（表格是数据）。</li>
 * </ul>
 *
 * <h2>{@code --silent}（完全静默）</h2>
 * <p>上面四条**终端**通道一起关掉：stdout（数据/状态行/错误/诊断）不出字节，
 * 这次运行唯一的结果是退出码。spool 与 tee（{@code --logfile} / {@code --reference} 的 log）
 * <b>照常写</b>：它们是文件产物，不是"打印"；同一次运行的 log 因此与是否 {@code --silent} 无关。</p>
 *
 * <h2>spool（{@code _SPOOL}）</h2>
 * <p>tee 语义：数据行、状态行、诊断行（{@code _SET TIMING/TIME}）、错误行与被执行语句的原文
 * **同时**写 stdout（错误与回显除外——它们只进文件）与 spool 文件，因此 <b>spool 的内容与
 * {@code --logfile} 的 log 完全一致</b>，只差"谁是 tee 目标"。spool 行尾固定 {@code "\n"}
 * （不随平台变化，便于比对）；文件按 UTF-8 写出、打开时截断。</p>
 *
 * <h2>输出 tee（{@code --logfile} 与 {@code --reference}）</h2>
 * <p>与 spool 同一条 tee 路径、但由**命令行选项**在整次运行期间打开（见 {@link #startTee} /
 * {@link #addTee}）：{@code --logfile} 因此拿到"与 stdout 相同的内容"，外加
 * {@link #printInput} 写的被执行语句原文（{@code _SET ECHO} 默认 on，见
 * {@code ScriptRunner.echoInput}），以及 {@link #printError} 的错误行
 * ——所以日志 = 回显的语句 + stdout 的数据/状态行/诊断行 + 错误行（与 {@code _SPOOL} 同一份内容）。
 * {@code --reference} 把这份字节留给 {@code report.ReferenceReport} 做 golden 比对。
 * 它**不改动 stdout 的任何字节** —— 不给这两个选项时的输出逐字节不变。</p>
 */
public final class ScriptOutput implements AutoCloseable {

    private final PrintWriter out;
    private final PrintWriter err;

    /**
     * {@code --silent}：**终端上什么都不打印**——数据、状态行、错误、诊断一概不出现在 stdout。
     *
     * <p>唯一留下的结果是进程退出码。文件产物（{@code _SPOOL}、{@code --logfile}、
     * {@code --reference} 的 log）**不受影响**：它们不是"打印"，而且 {@code --ref} 的证据不能因为
     * 终端安静就消失（同一次运行的 log 因此与是否 {@code --silent} 无关）。</p>
     */
    private final boolean silent;

    /** 全部输出操作的串行化锁：主线程与控制台/子脚本的所有写入都从这里过。 */
    private final Object writeLock = new Object();

    private Path spoolFile;
    private Writer spoolWriter;

    /** spool 之外额外的 tee 目标（{@code --reference}；通常是 0 或 1 个）。 */
    private final List<Writer> teeWriters = new ArrayList<>();
    private Path teeFile;

    /** 结果集的输出格式（{@code --format} 家族）；默认就是今天的表格。 */
    private RenderOptions renderOptions = RenderOptions.defaults();

    /**
     * {@code _SET ECHO}：是否把每条 SQL/命令的**原文**写进 {@code --logfile}（默认 on）。
     *
     * <p>两种会话各用各的形态：**脚本会话**（{@code --execute}/{@code _START}/作业脚本）写语句原文，
     * 因为脚本本身没有"屏幕"；**交互式控制台**写的是转录（提示符 + 敲进去的行，见
     * {@link #setTranscript}），这样日志才和屏幕上看到的一致。两者都只进日志，不改变 stdout——
     * 控制台与 {@code --execute} 的 stdout 因此仍然逐字节一致。{@code OFF} 之后日志里只剩输出。</p>
     */
    private boolean echo = true;

    /** 控制台转录模式（见 {@link #setTranscript}）：错误/诊断/提示符行也要进日志。 */
    private boolean transcript;

    /**
     * {@code _SET FEEDBACK}：**状态行（摘要）与错误消息**是否打印（默认 on）。
     *
     * <p>两者是同一类东西："这次执行怎么样了"——{@code N rows selected.}、{@code 1 row affected.}、
     * {@code ORA-…} 都是。关掉之后它们既不上屏，也不进 {@code --logfile}/{@code --ref} 的 log；
     * 错误计数与退出码**不受影响**（关它只是不说，不会把失败变成通过）。</p>
     */
    private boolean feedback = true;

    /**
     * {@code _SET TERMOUT}：结果集（数据行/表格）是否输出（默认 on）。
     *
     * <p>{@code OFF} 时**什么都不写**：不上屏，也不进 {@code --logfile}/{@code --ref} 的 log 与
     * {@code _SPOOL}——"这批结果不要"，用于结果不稳定、不适合参与比对的场景。比 SQL*Plus 的 TERMOUT
     * 更彻底（那边只是不显示），这是刻意的。</p>
     */
    private boolean termOut = true;

    public ScriptOutput(PrintWriter out, PrintWriter err) {
        this(out, err, false);
    }

    public ScriptOutput(PrintWriter out, PrintWriter err, boolean silent) {
        this.out = out;
        this.err = err;
        this.silent = silent;
    }

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

    /**
     * 诊断行：走 <b>stdout</b>（与数据/状态行同一条通道），因此也进 {@code --logfile}/{@code --ref} 的 log
     * 与 {@code _SPOOL}。
     *
     * <p>目前用于 {@code _SET TIMING} 的耗时与 {@code _SET TIME} 的系统时间。它们<b>不是</b>错误：
     * 放在 stdout 是为了"跑一遍就能看到、也能留在日志里"（排查慢语句时最常看的就是这两行）。
     * 代价是它们每次都不同：需要逐字节比对的场合用 {@code _set timing off} / {@code _set time off}
     * 关掉（默认就是 off）。</p>
     *
     * <p>与数据行的区别只有一点：不受 {@code _SET TERMOUT} 影响——那是"这批结果不要"，
     * 而耗时/时间是"这次跑得怎么样"。</p>
     */
    public void printDiagnostic(String line) {
        if (line == null) {
            return;
        }
        synchronized (writeLock) {
            if (!silent) {
                writeLine(out, line);
            }
            writeSpool(line);
            writeTee(line);
        }
    }

    /**
     * 控制台转录模式：日志（{@code --logfile} / {@code --ref} 的 log）记的是**屏幕上出现过的东西**——
     * 横幅、提示符、敲进去的行、结果、错误、诊断，按发生顺序逐行写。
     *
     * <p>只有交互式控制台会打开它（{@code --execute} 保持"回显语句 + 数据输出"的老口径：
     * 批处理本来就没有提示符可言）。转录行与其它输出一样只进日志：终端已经显示过，
     * 不再重复；{@code --silent} 与 {@code _SET ECHO OFF} 都能把它关掉。</p>
     */
    public void setTranscript(boolean transcript) {
        this.transcript = transcript;
    }

    /**
     * 转录行：横幅 / {@code 提示符 + 敲进去的那一行}。写日志（{@code --logfile}/{@code --ref}）与 {@code _SPOOL}，
     * 不写 stdout——终端上这些字节已经出现过，重复打印会污染数据通道。
     */
    public void printTranscript(String line) {
        if (line == null || line.isEmpty() || silent || !echo || !transcript) {
            return;
        }
        synchronized (writeLock) {
            writeTee(line);
            writeSpoolQuietly(line);
        }
    }

    /** 是否处于 {@code --silent}（终端上什么都不打印，只有退出码）。 */
    public boolean isSilent() {
        return silent;
    }

    /** {@code _SET ECHO}：输入回显开关（见字段注释）。 */
    public void setEcho(boolean echo) {
        this.echo = echo;
    }

    /** {@code _SET FEEDBACK}：状态行与错误消息开关。 */
    public void setFeedback(boolean feedback) {
        this.feedback = feedback;
    }

    /** {@code _SET TERMOUT}：结果集输出开关（连 logfile/spool 一起关）。 */
    public void setTermOut(boolean termOut) {
        this.termOut = termOut;
    }

    /**
     * 输入回显：把一条 SQL/命令的**原文**写进 {@code --logfile}（{@code --reference} 的 log 同一份）。
     *
     * <p>按 SQL*Plus 的 ECHO 口径：它只说明"这次运行跑了什么"，属于**日志**内容，不是运行结果，
     * 因此<b>不进 stdout、也不进 {@code _SPOOL}</b>——stdout 仍然只有数据与状态行，控制台与
     * {@code --execute} 的输出因此仍然逐字节一致。</p>
     *
     * <p>不看 {@code --silent}：那条开关只静默终端，回显本来就只在文件里，日志不该因此缺半截。</p>
     *
     * <p>与日志一样，回显也进 {@code _SPOOL}：spool 与 log 内容一致（只差"谁是 tee 目标"）。</p>
     */
    public void printInput(String text) {
        if (text == null || text.isEmpty() || !echo) {
            return;
        }
        synchronized (writeLock) {
            writeTee(text);
            writeSpoolQuietly(text);
        }
    }

    /**
     * 数据行：stdout + spool（{@code --silent} 只关终端；{@code _SET TERMOUT OFF} 什么都不写）。
     *
     * <p>{@code TERMOUT OFF} 是"这一批结果**不要**"：既不显示，也不进 {@code --logfile}/{@code --ref}
     * 与 {@code _SPOOL}。用途是结果不稳定、不适合参与比对时，让日志只留语句原文与摘要（见 §7.5）。</p>
     */
    public void printData(String line) {
        if (line == null || !termOut) {
            return;
        }
        synchronized (writeLock) {
            if (!silent) {
                writeLine(out, line);
            }
            writeSpool(line);
            writeTee(line);
        }
    }

    /**
     * 状态行：stdout + spool。**格式与它无关**——{@code _set outformat csv} 只改"结果集怎么渲染"，
     * 状态行是否出现由 {@code _SET FEEDBACK}（以及 {@code --silent}）决定。
     *
     * <p>（早期实现让 CSV 顺带吞掉状态行，理由是"导出文件要能被 CSV 解析"；那让一个格式开关偷偷改了
     * 另一类输出。现在想只留数据就显式 {@code _set feedback off}。）</p>
     *
     * @param line 可为 null（表示"本命令无输出"，见 {@code CommandOutcome.Status}）
     */
    public void printStatus(String line) {
        if (line == null || !feedback) {
            return;
        }
        synchronized (writeLock) {
            if (!silent) {
                writeLine(out, line);
            }
            writeSpool(line);
            writeTee(line);
        }
    }

    /**
     * "同时是状态行与失败"的一行（{@code Assert fail.}、{@code Compare failed.}、
     * {@code Job [x] finished with exit code n.}、{@code ... wait timed out.}）。
     *
     * <p>它属于输出契约（stdout 上必须有这一行），同时又是一次错误。若错误流与 stdout 是**同一个流**
     * （dbcli 就是这样：它从不写 stderr，{@code Cli} 把 err 指向 out），那就只打一次；
     * 只有两者是不同流时（测试夹具、把 dbcli 当库嵌入的调用方）才在错误流上再记一行。
     * 否则同一句失败会在终端、{@code --logfile} 与 {@code --ref} 的日志里各出现两遍。</p>
     */
    public void printFailure(String line) {
        if (line == null || !feedback) {
            return;
        }
        synchronized (writeLock) {
            if (!silent) {
                writeLine(out, line);
            }
            writeSpool(line);
            writeTee(line);
            if (err != out && !silent) {
                writeLine(err, line);
            }
        }
    }

    /**
     * 结果集：用 {@link SqlTableRenderer} 渲染成数据行。
     *
     * @param columnTypes 列类型名，可为 null（全部按字符串左对齐；{@code _COMPARE}/{@code _SSH}
     *                    的表格就是这种）
     */
    public void printTable(List<String> headers, List<String> columnTypes, List<List<Object>> rows) {
        if (headers == null) {
            return;
        }
        for (String line : ResultRenderers.render(renderOptions, headers, columnTypes,
                rows == null ? List.of() : rows)) {
            printData(line);
        }
    }

    /**
     * 错误行：**同时**写 stdout、tee（{@code --logfile} / {@code --reference} 的 log）与 spool。
     *
     * <p>为什么错误也要进文件：日志与 spool 都是"这次运行发生过什么"的记录。只写 stdout 的话，
     * 一个失败的脚本留下的日志/spool 看起来就像一次干净的运行——恰恰缺少最要紧的那几行
     * （{@code Not connected.}、{@code Assert [x] fail.} 这类消息本身，不带行号）。</p>
     *
     * <p>它属于 {@code _SET FEEDBACK} 管的那一类：关掉之后**摘要与错误都不打印**（日志里也没有，
     * 错误计数与退出码不受影响——不说，不等于没发生）。{@code --silent} 只静默**终端**：
     * stdout 不出字节，但日志照常写（文件产物与 {@code --silent} 无关，见类注释）。</p>
     */
    public void printError(String message) {
        if (message == null || !feedback) {
            return;
        }
        if (!silent) {
            writeLine(err, message);
        }
        // 错误同时进日志（--logfile / --ref 的 tee）与 _SPOOL：三者内容一致，
        // 这样 spool 出来的文件就是"这次运行发生过什么"的完整记录。
        // 用 quietly 版本：日志/spool 写不进去时会调 printError，直接复用会无限递归。
        // 这里刻意不看 silent：--silent 只关终端，文件产物"照常写"。
        synchronized (writeLock) {
            writeTeeQuietly(message);
            writeSpoolQuietly(message);
        }
    }

    /**
     * 写一行：行尾固定 {@code "\n"}。
     *
     * <p>为什么不用 {@code println}：平台的换行符（Windows 是 {@code \r\n}）会让"同一脚本在两个
     * 平台上跑出的 stdout"不一样，而 stdout 是要被逐字节比对的产物；固定 LF 之后 spool 文件与
     * stdout 也逐字节一致。</p>
     */
    private static void writeLine(PrintWriter writer, String line) {
        writer.print(line);
        writer.print('\n');
        writer.flush();
    }

    /**
     * 开始 spool（tee）。
     *
     * @throws IOException 文件打不开（引擎转成"可读错误 + 计错误数"）
     */
    public void startSpool(Path file) throws IOException {
        stopSpool();
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            throw new IOException("directory [" + parent + "] does not exist");
        }
        spoolWriter = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        spoolFile = file;
    }

    /** 停止 spool（幂等）。 */
    public void stopSpool() {
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

    public boolean isSpooling() {
        return spoolWriter != null;
    }

    /** 当前 spool 文件（未 spool 时为 null）。 */
    public Path spoolFile() {
        return spoolFile;
    }

    /**
     * 开始把 stdout 的数据/状态行 tee 到文件（{@code --logfile} / {@code --reference} 用；
     * 重复调用先停掉上一个）。
     *
     * <p>与 spool 的区别：spool 是**脚本**在运行中控制的，tee 是**命令行**在整次运行期间控制的；
     * 两者的写入口是同一条（见类注释）。行尾同样固定 {@code "\n"}、UTF-8、打开即截断。</p>
     *
     * @throws IOException 文件打不开（命令行层转成"可读错误 + 退出 1"）
     */
    public void startTee(Path file) throws IOException {
        synchronized (writeLock) {
            stopTee();
            openTee(file);
        }
    }

    /**
     * 再加一个 tee 目标（{@code --logfile} 与 {@code --reference} 同时给出时用）：
     * 两个文件都会拿到与 stdout 相同的字节。
     *
     * @throws IOException 文件打不开
     */
    public void addTee(Path file) throws IOException {
        synchronized (writeLock) {
            openTee(file);
        }
    }

    private void openTee(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            throw new IOException("directory [" + parent + "] does not exist");
        }
        Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        teeWriters.add(writer);
        teeFile = file;
    }

    /**
     * 停止 tee 并落盘（幂等）：调用返回之后文件内容就是"这次运行已产出的全部 stdout 字节"，
     * 可以安全地读回来做比对。
     */
    public void stopTee() {
        synchronized (writeLock) {
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
    }

    public boolean isTeeing() {
        synchronized (writeLock) {
            return !teeWriters.isEmpty();
        }
    }

    /** 当前 tee 文件（未 tee 时为 null）。 */
    public Path teeFile() {
        synchronized (writeLock) {
            return teeFile;
        }
    }

    /** 收尾：停 spool/tee 并刷新两个流（进程退出前必须调用，否则最后几行可能没落盘）。 */
    @Override
    public void close() {
        stopTee();
        stopSpool();
        out.flush();
        err.flush();
    }

    private void writeSpool(String line) {
        if (spoolWriter == null) {
            return;
        }
        try {
            spoolWriter.write(line);
            spoolWriter.write('\n');
            spoolWriter.flush();
        } catch (IOException spoolFailed) {
            // spool 失败不改变脚本结果：把它当错误打到 stdout，并停掉 spool（避免每行都报一次）
            printError("ERROR: cannot write spool file [" + spoolFile + "]: " + spoolFailed.getMessage());
            stopSpool();
        }
    }

    /**
     * 写 spool，但**不回报**失败（错误行与回显行走这条）。
     *
     * <p>与 {@code writeTeeQuietly} 同一取舍：{@link #writeSpool} 失败时会调 {@link #printError}，
     * 而错误也要写 spool——直接复用会无限递归，所以这里"写不进去就停掉 spool，不再多说一句"。</p>
     */
    private void writeSpoolQuietly(String line) {
        if (spoolWriter == null) {
            return;
        }
        try {
            spoolWriter.write(line);
            spoolWriter.write('\n');
            spoolWriter.flush();
        } catch (IOException ignored) {
            stopSpool();
        }
    }

    /**
     * 写 tee 目标（{@code --logfile} / {@code --reference}）。
     *
     * <p>写失败**不改变脚本结果**（与 spool 同一取舍）：把错误打到 stdout 并停掉全部 tee，
     * 让命令行层在收尾时报"拿不到实际输出"，而不是让脚本因为一个旁路文件失败。</p>
     */
    private void writeTee(String line) {
        if (teeWriters.isEmpty()) {
            return;
        }
        try {
            for (Writer writer : teeWriters) {
                writer.write(line);
                writer.write('\n');
                writer.flush();
            }
        } catch (IOException teeFailed) {
            printError("ERROR: cannot write the output file [" + teeFile + "]: "
                    + teeFailed.getMessage());
            stopTee();
        }
    }

    /**
     * 写 tee，但**不回报**失败（用于"错误/诊断/转录"这几条路径）。
     *
     * <p>为什么需要它：{@link #writeTee} 失败时会调 {@link #printError}，而错误与转录也要写 tee——
     * 直接复用会变成无限递归。这里的取舍是"日志写不进去就停掉 tee，不再多说一句"，
     * 与 {@link #writeTee} 的取舍一致（旁路文件失败不改变脚本结果）。</p>
     */
    private void writeTeeQuietly(String line) {
        if (teeWriters.isEmpty()) {
            return;
        }
        try {
            for (Writer writer : teeWriters) {
                writer.write(line);
                writer.write('\n');
                writer.flush();
            }
        } catch (IOException ignored) {
            stopTee();
        }
    }
}
