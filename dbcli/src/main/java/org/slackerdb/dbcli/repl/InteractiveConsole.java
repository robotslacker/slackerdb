package org.slackerdb.dbcli.repl;

import org.slackerdb.dbcli.BuildInfo;
import org.slackerdb.dbcli.engine.ScriptContext;
import org.slackerdb.dbcli.engine.ScriptRunner;
import org.slackerdb.dbcli.engine.ScriptSession;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

/**
 * 交互式控制台：**取行 → 交给引擎 → 打印引擎产出**，仅此而已。
 *
 * <h2>为什么它这么薄</h2>
 * <p>控制台自己不做语句切分、不做变量替换、不打印结果：那些都在
 * {@link ScriptSession}（累积与切分）与 {@link ScriptRunner}（执行与输出）里，
 * 与 {@code --execute} 批处理**完全同一段代码**。于是"同一脚本用 {@code --execute} 跑"与
 * "逐行粘进控制台跑"的 stdout、{@code --logfile} 内容天然一致；控制台只多出两样东西：
 * 提示符与"语句没写完时的续行提示"。</p>
 *
 * <h2>提示符与横幅去哪了（§3.7）</h2>
 * <ul>
 *   <li><b>提示符</b>：写给 {@link LineSource}（生产实现是 JLine 终端），不经过 stdout 数据通道；</li>
 *   <li><b>横幅</b>：走 <b>stderr</b>（它既不是数据也不是错误，但绝不能污染 stdout），
 *       内容只有**客户端名字**一行（用法查 {@code _HELP}，连接状态由语句自己回答）——
 *       版本与构建时间只在 {@code --version} 里出现（避免横幅每次构建都变）；</li>
 *   <li><b>提示符形状</b>：主提示符 {@code "dbcli> "}；缓冲里还有没写完的语句时用
 *       {@code "   > "}，与批处理里"语句跨行"是同一件事的两种表现。</li>
 * </ul>
 *
 * <h2>退出</h2>
 * <p>{@code _EXIT n}/{@code _QUIT n}、Ctrl-D（EOF）都会结束控制台；
 * 退出码与批处理同一口径（{@link ScriptContext#finalExitCode()}：显式 {@code _EXIT n}（含 0）优先，
 * 否则有错误为 1）。Ctrl-C 分两种情况：提示符处取消当前输入（丢掉没写完的缓冲），
 * **执行期**取消正在跑的语句（{@code Statement.cancel()}），都不退出。</p>
 *
 * <h2>收尾规则（与批处理同一条）</h2>
 * <p>控制台**没有自己的输入语法**：一条语句只在顶层 {@code ;} 或顶格的 {@code /} 处收尾并执行
 * （见 {@link ScriptSession#feedConsoleLine(String)}）。回车只是"这一行输入完了"，
 * 没写完就继续给续行提示 {@code "   > "}，与批处理读到下一行是同一件事。
 * {@code _} 命令按行收尾，不需要 {@code ;}（它们不是 SQL 语句）。</p>
 *
 * <p><b>粘贴不需要特殊处理</b>：取行实现一次返回多行（JLine 的 bracketed paste）时，整段按行喂进
 * {@link org.slackerdb.dbcli.engine.ScriptSession#feedConsoleLine(String)}——段内的 {@code ;}
 * 照常切分语句，与手敲这几行逐字节相同。</p>
 */
public final class InteractiveConsole {

    /** 主提示符。 */
    public static final String PROMPT = "dbcli> ";

    /** 续行提示符（语句还没写完时）。 */
    public static final String CONTINUATION_PROMPT = "   > ";

    private final ScriptRunner runner;
    private final ScriptContext context;
    private final LineSource lines;
    private final PrintWriter banner;


    /**
     * @param banner 横幅/控制台级提示的去处（生产是 stderr；绝不是 stdout）
     */
    public InteractiveConsole(ScriptRunner runner, ScriptContext context, LineSource lines,
                              PrintWriter banner) {
        this.runner = runner;
        this.context = context;
        this.lines = lines;
        this.banner = banner;
    }

    /** 跑控制台直到 EOF / {@code _EXIT}，返回进程退出码。 */
    public int run() {
        // 控制台的日志是"屏幕上出现过什么"的转录（横幅 + 提示符 + 输入 + 输出 + 错误）
        context.output().setTranscript(true);
        printBanner();
        try (ScriptSession session = runner.openSession("<console>", null, List.of(), false)) {
            while (!context.exitRequested()) {
                String prompt = session.isIncomplete() ? CONTINUATION_PROMPT : PROMPT;
                String line;
                try {
                    line = lines.readLine(prompt);
                } catch (InputInterruptedException interrupted) {
                    // Ctrl-C：取消当前这条输入（丢掉没写完的缓冲），回到主提示符
                    session.reset();
                    continue;
                } catch (IOException readFailed) {
                    banner.println("Cannot read from the console: " + messageOf(readFailed));
                    banner.flush();
                    return 1;
                }
                if (line == null) {
                    break;
                }
                // 转录：把"提示符 + 这一行"原样记进日志（终端已经显示过，不再上屏）
                context.output().printTranscript(prompt + line);
                // 执行期 Ctrl-C：只在这一行（可能包含一条长语句）执行期间接管 SIGINT；
                // 提示符处不碰它，仍然是 JLine 自己的"取消当前输入"。
                // 执行期 Ctrl-C：只在这一行（可能包含一条长语句）执行期间接管 SIGINT；
                // 提示符处不碰它，仍然是取行实现自己的"取消当前输入"。
                Runnable restore = lines.captureInterrupt(this::cancelRunningStatement);
                try {
                    // 一行或多行（bracketed paste 会把整段作为一次输入返回）：都按行喂，
                    // 语句仍只在 ';' / 顶格 '/' 处收尾，因此粘贴与手敲的行为逐字节相同
                    session.feedConsoleLine(line);
                } finally {
                    restore.run();
                }
            }
            session.finish();
        }
        return context.finalExitCode();
    }

    /**
     * 执行期 Ctrl-C 的动作：取消当前语句并在 stderr 留一行痕迹。
     *
     * <p>信号本身由取行实现接管（{@link LineSource#captureInterrupt}，生产实现走 JLine 的终端信号），
     * 这里只保留"取消 + 说一句"这个可被单测直接调用的动作。</p>
     */
    void cancelRunningStatement() {
        context.sqlExecutor().cancelCurrentStatement();
        banner.println("^C (cancelling)");
        banner.flush();
    }

    /**
     * 横幅：**只有客户端名字一行**，只写 {@code banner}（stderr）。
     *
     * <p>不再打印用法/连接状态那一行：进控制台就该直接到提示符，怎么用查 {@code _HELP}，
     * 有没有连上由语句自己回答（没连接时执行任何 SQL 都会得到 {@code Not connected.}）。</p>
     *
     * <p><b>刻意只打印名字</b>：控制台横幅是每次启动都出现的行，版本串（构建时刻 + git 提交）
     * 不是使用者在这个位置需要的信号，而且它会让横幅每次都不同（对逐字节比对的场景是噪音）。
     * 版本信息只从 {@code --version} 取（见 {@link BuildInfo}）。</p>
     */
    private void printBanner() {
        banner.println(BuildInfo.name());
        banner.flush();
        context.output().printTranscript(BuildInfo.name());
    }

    private static String messageOf(IOException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
