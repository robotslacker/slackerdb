package org.slackerdb.dbcli.repl;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.DumbTerminal;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * {@link LineSource} 的生产实现：JLine 终端上的行编辑 + 历史。
 *
 * <p>够用即可：上下键翻历史、行编辑与 Ctrl-C 中断；不做补全、语法高亮与 bottom toolbar。</p>
 *
 * <h2>历史是跨会话的</h2>
 * <p>翻的是 {@code ~/slackerdb/dbcli.history}（见 {@link #defaultHistoryFile()}）：启动时读进来，
 * 退出时写回去，所以"上次敲过的语句"下一次还能上下键翻到。目录建不出来或不可写时**安静放弃**——
 * 历史只是顺手的事，不能因为它让控制台起不来，也不该为此打警告。</p>
 *
 * <p><b>Ctrl-C 与 Ctrl-D</b>：JLine 分别抛 {@link UserInterruptException} 与
 * {@link EndOfFileException}，这里映射成"中断"与"EOF 返回 null"——这是两种不同的控制流，
 * 控制台必须区别对待（丢缓冲 vs 收尾退出）。</p>
 *
 * <p><b>终端创建</b>：有真控制台时用聚合件 {@code org.jline:jline} 自带的系统终端实现（Windows 走
 * jansi/jna/jni，任一可用即得真终端）；没有控制台时（CI / 管道 / 重定向）由 {@link #system()}
 * 显式建一个 dumb 终端，**输出接 stderr**——见该方法的注释：JLine 自己的 dumb 回退会把提示符写进
 * stdout，那正是 dbcli 的数据通道。</p>
 */
public final class JLineLineSource implements LineSource {

    /** 内存里保留多少条历史（上下键能翻到的上限；同时也是写回文件的上限）。 */
    static final int HISTORY_SIZE = 5_000;

    /** 历史文件最多保留多少行（超出时 JLine 会把最老的裁掉）。 */
    static final int HISTORY_FILE_SIZE = 5_000;

    private final Terminal terminal;
    private final LineReader reader;

    public JLineLineSource(Terminal terminal) {
        this(terminal, defaultHistoryFile());
    }

    /**
     * @param historyFile 历史文件；{@code null} 表示"只用内存历史"（不可写时的安静降级）
     */
    JLineLineSource(Terminal terminal, Path historyFile) {
        this.terminal = terminal;
        this.reader = buildReader(terminal, historyFile);
    }

    /**
     * 建 LineReader：{@code brcketed paste} 与历史文件都挂上。
     *
     * <p>{@code BRACKETED_PASTE} 显式打开：粘贴多行时 JLine 把整段作为一次输入返回（含换行），
     * 控制台按行喂进会话（语句仍只在 ';' / 顶格 '/' 处收尾），因此整段不会被当成"半行一条语句"。</p>
     *
     * <p>历史文件通过 {@link LineReader#HISTORY_FILE} 变量交给 JLine：它在构造时读入（{@code attach}
     * 里调 {@code load()}），由 {@link #close()} 负责写回。{@code historyFile == null} 时什么都不设，
     * LineReader 自己挂一个内存 {@code DefaultHistory}。</p>
     */
    static LineReader buildReader(Terminal terminal, Path historyFile) {
        LineReaderBuilder builder = LineReaderBuilder.builder()
                .terminal(terminal)
                .option(LineReader.Option.BRACKETED_PASTE, true)
                // 时间戳（`<epochMillis>:<行>`）是 JLine 的默认格式；历史文件给人看/grep，
                // 所以关掉它，写成纯文本一行一条
                .option(LineReader.Option.HISTORY_TIMESTAMPED, false);
        if (historyFile != null) {
            builder.variable(LineReader.HISTORY_FILE, historyFile)
                    .variable(LineReader.HISTORY_SIZE, HISTORY_SIZE)
                    .variable(LineReader.HISTORY_FILE_SIZE, HISTORY_FILE_SIZE);
        }
        LineReader reader = builder.build();
        if (historyFile != null) {
            // JLine 3.25 的 LineReaderImpl 构造时并不 attach 历史（实测 history 的 reader 字段为 null，
            // 于是 load()/read() 全抛 NPE、历史永远是空的）。这里显式 attach：它内部就是 load()，
            // 而此刻 HISTORY_FILE 变量已经就位，文件能正常读进来。attach 对同一 reader 是幂等的
            // （`if (this.reader != reader)`），后续 JLine 自己再 attach 不会重复加载。
            try {
                reader.getHistory().attach(reader);
            } catch (RuntimeException | Error unreadable) {
                // 读不出来（格式坏、权限）就当没有历史，安静继续
            }
        }
        return reader;
    }

    /**
     * 历史文件：{@code ~/slackerdb/dbcli.history}。
     *
     * <p>{@code user.home} 拿不到、目录建不出来、文件打不开（不可写/被占用）或读不出来（格式坏、权限）
     * 时返回 <b>null</b>：调用方据此只用内存历史。这条路径上**不打印任何东西**——历史保存失败不是
     * 使用者需要处理的问题，也不能让控制台起不来。</p>
     *
     * <p>可写性用"试着以 append 打开一次"来判定：{@code Files.isWritable} 在 Windows 上对只读 ACL
     * 并不可靠，而打开一次就是真正会发生的那步操作（顺带创建空文件，代价可忽略）。可读性同理先读一遍，
     * 这样后面 JLine 加载时不会因为 IO 失败而往 stderr 打它自己的 warning。</p>
     */
    static Path defaultHistoryFile() {
        try {
            String home = System.getProperty("user.home");
            if (home == null || home.isBlank()) {
                return null;
            }
            Path directory = Path.of(home, "slackerdb");
            Files.createDirectories(directory);
            Path historyFile = directory.resolve("dbcli.history");
            try (OutputStream probe = Files.newOutputStream(historyFile,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                probe.flush();
            }
            Files.readAllLines(historyFile, StandardCharsets.UTF_8);
            return historyFile;
        } catch (IOException | RuntimeException | Error unusable) {
            return null;
        }
    }

    /**
     * 建一个系统终端上的取行器。
     *
     * <p><b>有真控制台</b>（{@code System.console() != null}，与 {@code Cli} 进控制台的判据一致）时用
     * JLine 的系统终端：行编辑、历史、原始模式信号都由它提供。输出优先指向 stderr
     * （{@code systemOutput(SysErr)}）：提示符与行回显是"给人看的东西"，而 stdout 在 dbcli 里是
     * <b>数据通道</b>（要被 {@code _COMPARE} 与期望文件逐字节比对，§3.7），
     * {@code dbcli > out.txt} 时提示符绝不能跟着进去。用 {@code SysErr} 而不是 {@code ForcedSysErr}：
     * 终端实现不支持 stderr 时会自己回退，不会因为这一项让控制台起不来。</p>
     *
     * <p><b>没有真控制台</b>时（CI、IDE 控制台、stdin 被重定向的管道形态、
     * stdout/stderr 被重定向）**直接**建一个"输入 = 进程 stdin、输出 = 进程 **stderr**"的
     * {@link DumbTerminal}，不交给 JLine 自己退化。原因是 JLine 的 dumb 回退会：</p>
     * <ol>
     *   <li>把终端输出接在 <b>stdout</b> 上（它在 {@code computeSystemOutput()/select()} 里挑一个
     *       "可用的系统流"，stderr 是文件时它挑 stdout）——提示符与行回显就会污染数据通道；</li>
     *   <li>也可能挑中真正的控制台（{@code CONIN$}/{@code /dev/tty}）——那时管道里的输入会被无视，
     *       控制台卡在键盘上。</li>
     * </ol>
     * <p>自己建 {@code DumbTerminal}（类型 {@code dumb}）两件事都不会发生，
     * 且 {@code LineReaderImpl} 正是按 {@code getType()} 以 {@code dumb} 开头来选"逐行读取"那条路，
     * 所以这仍是**真的 JLine 取行链路**，只是显示面在 stderr、没有行编辑能力。
     * 字符集显式给 UTF-8（dbcli 只支持 UTF-8），不跟平台默认字符集走。</p>
     */
    public static JLineLineSource system() throws IOException {
        if (System.console() != null) {
            return new JLineLineSource(TerminalBuilder.builder()
                    .system(true)
                    .systemOutput(TerminalBuilder.SystemOutput.SysErr)
                    .build());
        }
        return new JLineLineSource(new DumbTerminal("dbcli", "dumb", System.in, System.err,
                StandardCharsets.UTF_8));
    }

    @Override
    public String readLine(String prompt) throws IOException, InputInterruptedException {
        try {
            return reader.readLine(prompt);
        } catch (UserInterruptException interrupted) {
            throw new InputInterruptedException("Input interrupted by the user (Ctrl-C).", interrupted);
        } catch (EndOfFileException endOfInput) {
            return null;
        }
    }

    @Override
    public Runnable captureInterrupt(Runnable action) {
        Terminal.SignalHandler previous;
        try {
            previous = terminal.handle(Terminal.Signal.INT, signal -> action.run());
        } catch (RuntimeException | Error unsupported) {
            // 没有信号能力（Dumb 终端、安全策略）：执行期取消不可用，其它功能不受影响
            return () -> {
            };
        }
        return () -> {
            try {
                terminal.handle(Terminal.Signal.INT, previous);
            } catch (RuntimeException | Error ignored) {
                // 恢复失败没有补救手段：进程随后退出，已执行的语句不受影响
            }
        };
    }

    @Override
    public void close() throws IOException {
        saveHistoryQuietly();
        // LineReader 接口本身不带 close()（实现类才有），因此按能力关闭；
        // 终端关掉之后 JLine 自己也收尾了，这里不必再判断状态
        if (reader instanceof java.io.Closeable closeable) {
            closeable.close();
        }
        terminal.close();
    }

    /** 把这次会话的历史写回文件；写不进去就算了（没有历史文件、磁盘满、只读目录……都不该影响退出）。 */
    private void saveHistoryQuietly() {
        try {
            reader.getHistory().save();
        } catch (Exception | Error ignored) {
            // 安静放弃：历史是顺手功能
        }
    }
}
