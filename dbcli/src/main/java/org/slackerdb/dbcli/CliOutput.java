package org.slackerdb.dbcli;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;
import org.slackerdb.dbcli.engine.ScriptOutput;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.Writer;

/**
 * 终端输出的**唯一**策略对象：stdout/stderr 指向哪、这次是不是 {@code --silent}、要不要顺带关掉第三方日志。
 *
 * <h2>为什么要一个对象</h2>
 * <p>以前 {@code --silent} 分散在四处：{@link Main} 在解析参数前换流（为了压住 picocli 自己的报错）、
 * {@code Cli.call()} 再换一次、{@link ScriptOutput} 自己还有一份 {@code silent} 判断、logback 再单独降级。
 * 四处各自为政的后果是"某个新出口忘了看 silent"——它写得进终端，静默就漏了。
 * 现在只有这一处决定流与位：解析前（{@link #attach}）与运行期（{@code Cli.call()}）拿的是同一个策略，
 * {@link #scriptOutput()} 也把 {@code silent} 从同一处传给输出层。</p>
 *
 * <h2>dbcli 从不写 stderr</h2>
 * <p>错误、提示、横幅、提示符统统走 stdout；{@code err} 这个名字在内部保留，但指向的就是同一个流。
 * 测试与嵌入方可以用 {@link #of(PrintWriter, PrintWriter, boolean)} 给两个独立的流。</p>
 */
public final class CliOutput {

    private final PrintWriter out;
    private final PrintWriter err;
    private final boolean silent;

    private CliOutput(PrintWriter out, PrintWriter err, boolean silent) {
        this.out = out;
        this.err = err;
        this.silent = silent;
    }

    /**
     * 命令行里是否出现了 {@code --silent}（**参数解析之前**的判定，{@link Main} 用它装总闸）。
     *
     * <p>只看独立出现的 {@code --silent}（它是无参选项，写不出别的形态）；放在值里（{@code --var K=--silent}）
     * 不算。</p>
     */
    public static boolean silentRequested(String[] args) {
        if (args == null) {
            return false;
        }
        for (String argument : args) {
            if ("--silent".equals(argument)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把 picocli 的输出流换成这次运行该用的，并返回策略对象。
     *
     * <p>{@code --silent} 时装上丢弃流（连 picocli 自己的"未知选项"都压掉）并降级第三方日志；
     * 否则 stderr 指向 stdout（dbcli 从不写 stderr）。可以重复调用：第二次看到的已经是配好的流。</p>
     */
    public static CliOutput attach(CommandLine commandLine, boolean silent) {
        if (silent) {
            PrintWriter discard = discard();
            commandLine.setOut(discard);
            commandLine.setErr(discard);
            CliOutput output = new CliOutput(discard, discard, true);
            output.silenceThirdPartyLogging();
            return output;
        }
        PrintWriter shared = commandLine.getOut();
        commandLine.setErr(shared);
        return new CliOutput(shared, shared, false);
    }

    /** 直接给定两个流（测试/嵌入；不碰 picocli、不动第三方日志）。 */
    public static CliOutput of(PrintWriter out, PrintWriter err, boolean silent) {
        return new CliOutput(out, err, silent);
    }

    /** 这次运行唯一的 {@link ScriptOutput}：{@code silent} 只有这一个来源。 */
    public ScriptOutput scriptOutput() {
        return new ScriptOutput(out, err, silent);
    }

    public PrintWriter out() {
        return out;
    }

    public PrintWriter err() {
        return err;
    }

    public boolean silent() {
        return silent;
    }

    /** {@code --silent} 用的丢弃流：写入即丢弃（不攒内存，也不落任何字节）。 */
    private static PrintWriter discard() {
        return new PrintWriter(Writer.nullWriter(), true);
    }

    /**
     * 把日志后端也关掉（{@code logback.xml} 里 WARN 以上写 stdout）。
     *
     * <p>本模块自己不打日志，但驱动 / JLine / SQLite 这些第三方库会打；"完全静默"要求连它们的告警也不出现。
     * 没有 logback 实现时什么都不做（静默只保证本程序自己不打字）。</p>
     */
    private void silenceThirdPartyLogging() {
        try {
            if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
                context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.OFF);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 换了 SLF4J 实现或没有 logback：静默仍然成立，只是少了"顺带关第三方日志"这一层
        }
    }
}
