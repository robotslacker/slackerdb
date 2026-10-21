package org.slackerdb.dbcli.engine;

import java.nio.file.Path;
import java.util.List;

/**
 * 一次"逐行喂入"的脚本执行会话：**批处理与交互式控制台共用的唯一入口**。
 *
 * <h2>为什么要有它</h2>
 * <p>交互式与批处理必须行为一致。
 * 最省事也最可靠的做法不是写两套行为再对齐，而是让两条路**共用同一段状态机**：
 * 本类持有"累积缓冲"，{@link ScriptRunner} 负责"一条候选语句 → 执行"。
 * 批处理（{@link ScriptRunner#runFile}/{@link ScriptRunner#runText}）把整个脚本文本按行喂进来，
 * 控制台（{@code repl.InteractiveConsole}）把用户敲的每一行喂进来——两者的语句切分、
 * 变量替换、错误文案、退出码、{@code --logfile} 记录因此天然一致。</p>
 *
 * <h2>一行多条语句怎么切</h2>
 * <p>{@code select 1; select 2;} 这类输入按"顶层 {@code ;}"切分（见 {@link StatementSplitter}），
 * 但切分只是**候选**：依次把"到第 i 个 {@code ;} 为止的前缀"交给分析器，第一个被判定
 * {@code isFinished=true} 的前缀才被消费并执行；一个都不接受（例如
 * {@code create procedure p() begin select 1; end;} 里那个 {@code ;}）时，退回
 * "整段当一条语句"，于是多行语句、{@code /} 收尾的块、
 * {@code {% %}} 断言、{@code _echo … echo off} 块都不会被切坏。
 * 末尾不完整的一段留在缓冲里继续累积（控制台据此给出续行提示）。</p>
 *
 * <h2>什么时候才值得解析（性能）</h2>
 * <p>语句只能在三种位置收尾：顶层 {@code ;}、行首 {@code /}、{@code echo off}。缓冲不停在这些位置时
 * <b>直接不解析</b>（见 {@link StatementBoundary}）；一旦某条语句被判定为"只能等 {@code /}"的块
 * （{@code declare}/{@code begin}/{@code create procedure …}），后续的 {@code ;} 行也一概不解析。
 * 于是多行语句/存储过程的解析量从"每行一次全量"降到"整条语句一次"。</p>
 *
 * <h2>EOF 时缓冲里还剩东西</h2>
 * <p>{@link #finish()} 把它交给 {@link ScriptRunner#finishPending}，按"解析失败"处理：
 * {@code _} 开头的命令报一条 {@code Incomplete command [...]}，其余原样送数据库执行
 * （看到的是数据库的错误，未连接时是 {@code Not connected.}）。本类不再自己拼
 * "脚本结尾不完整"那类解析层文案。</p>
 */
public final class ScriptSession implements AutoCloseable {

    private final ScriptRunner runner;
    private final ScriptContext context;

    /** 进入本会话之前的 {@code ARGVn}（{@code _START} 子脚本用；退出时恢复）。 */
    private final List<String> previousArgv;

    private final StringBuilder buffer = new StringBuilder();

    /** 缓冲的词法边界状态（增量喂入；决定"要不要解析"）。 */
    private final StatementBoundary boundary = new StatementBoundary();

    /**
     * 当前这条语句已确认是"要行首 {@code /} 才能收尾"的块。
     *
     * <p>一旦确认，块体内部的 {@code ;} 行就完全不必解析——这是长存储过程不再 O(N²) 的原因。
     * 缓冲被消费或丢弃时清零。</p>
     */
    private boolean awaitingSlash;

    /**
     * 缓冲里"到某个 {@code ;} 为止的前缀"已被判定为没写完的位置（独占式下标）。
     *
     * <p>前缀文本不会再变短，所以下一行只需试**这个位置之后**的新候选：避免对同一批前缀
     * 反复解析。消费掉一条语句后清零（余下文本是新的前缀）。</p>
     */
    private int knownIncompleteUpTo;

    /** 已经喂入的物理行数（1 基计数：喂完第一行后为 1）。 */
    private int fedLines;

    /** 缓冲里这条语句的起始行号（语句跨行时，位置仍是它的第一行）。 */


    private boolean closed;

    ScriptSession(ScriptRunner runner, ScriptContext context, String scriptName, Path scriptFile,
                  List<String> argv) {
        this(runner, context, scriptName, scriptFile, argv, true);
    }

    /**
     * @param scriptSession 本会话是不是脚本会话（交互式控制台为 false，见 {@link ScriptContext#enterScript}）
     */
    ScriptSession(ScriptRunner runner, ScriptContext context, String scriptName, Path scriptFile,
                  List<String> argv, boolean scriptSession) {
        this.runner = runner;
        this.context = context;
        this.previousArgv = context.variables().argv();
        context.variables().setArgv(argv == null ? List.of() : List.copyOf(argv));
        context.enterScript(scriptName, scriptFile, scriptSession);
    }

    /** 喂入一行（不含行尾）。语句已经写过 {@code _EXIT} 时后续输入被忽略。 */
    public void feedLine(String line) {
        accumulate(line);
        processBuffer();
    }

    /**
     * 只把一行放进缓冲、**不**尝试执行。
     *
     * <p>批处理的 {@link #feedLine} 与控制台的每一行输入都走这里（控制台随后自己决定要不要推进，
     * 见 {@link #feedConsoleLine}）：先只累积，{@code _assert 1 =} 这种半截语句才不会被当成完整语句执行掉。</p>
     */
    private void accumulate(String line) {
        // 行号在**任何**早退之前推进：被跳过的注释行/空行仍然是文件里的一行，
        // 否则后面每条错误的行号都会比真实行号小
        fedLines = fedLines + 1;
        if (context.exitRequested()) {
            return;
        }
        String text = line == null ? "" : line;
        if (buffer.length() == 0) {
            String stripped = text.strip();
            // 注释与空行只在"语句之外"被跳过（语句内部的行原样保留）
            if (stripped.isEmpty() || stripped.startsWith("--") || stripped.startsWith("#")) {
                return;
            }

        }
        if (buffer.length() > 0) {
            buffer.append('\n');
        }
        buffer.append(text);
        // 边界状态机增量吃下这一行（决定"要不要解析"；真正的判定仍在 SqlAnalyzer）
        boundary.feedLine(text);
    }

    /** 喂入一段文本（按 {@code CRLF}/{@code LF} 切行；空行也照喂，由 {@link #feedLine} 决定是否跳过）。 */
    public void feedText(String text) {
        for (String line : Utf8Text.splitLines(text == null ? "" : text)) {
            feedLine(line);
        }
    }

    /** 缓冲里是否还有"没写完的语句"（控制台据此切换续行提示）。 */
    public boolean isIncomplete() {
        return buffer.length() > 0;
    }

    /** 丢弃没写完的缓冲（控制台的 Ctrl-C：取消当前这条输入）。 */
    public void reset() {
        clearBuffer();
    }

    /**
     * 输入结束：缓冲里还有没写完的语句时，按"解析失败"处理 ——
     * {@code _} 开头的命令报一条 {@code Incomplete command [...]}，其余原样送执行层；
     * 而不是静默丢掉，也不再打印任何解析层文案。
     *
     * <p>已经请求退出（{@code _EXIT}）时不处理——那时剩下的输入本来就不该被执行。</p>
     */
    public void finish() {
        if (context.exitRequested()) {
            clearBuffer();
            return;
        }
        if (buffer.length() == 0) {
            return;
        }
        // 先把"停在收尾位置上"的都执行掉（`;`/`/`/`echo off`），再把尾巴按"补一个 `;`"试一次：
        // 这样 `select 1; select 2`（最后一句没有 ';'）仍然逐条执行，而不是被当成一条整段文本送库。
        processBuffer(true);
        if (buffer.length() == 0 || context.exitRequested()) {
            clearBuffer();
            return;
        }
        runner.finishPending(buffer.toString());
        clearBuffer();
    }

    /** 退出会话：出栈脚本名/文件、恢复 {@code ARGVn}（幂等）。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        context.leaveScript();
        context.variables().setArgv(previousArgv);
    }

    // -------------------------------------------------------------- 语句切分/推进

    /**
     * 处理一次累积：能消费多少条就消费多少条。
     *
     * <p>用循环而不是递归：一行里塞几百条语句时递归会变成栈深度问题。</p>
     */
    private void processBuffer() {
        processBuffer(false);
    }

    /**
     * 处理一次累积（带"要不要把尾巴当成补了 {@code ;}"的开关）。
     *
     * <p><b>先看边界，再决定解析</b>：</p>
     * <ol>
     *   <li>缓冲没停在三种收尾位置之一（见 {@link StatementBoundary.Kind}）→ 直接返回，不解析；</li>
     *   <li>已确认当前语句是"等行首 {@code /}"的块 → 只有 {@code /} 那一行才解析；</li>
     *   <li>否则按"顶层 {@code ;} 前缀"逐个试，跳过**已知没写完**的前缀（{@link #knownIncompleteUpTo}）；</li>
     *   <li>都不接受时：{@code /} 与 {@code echo off} 把整段交给分析器；{@code ;} 结尾不再重复试
     *       （最后那个候选就是整段）；输入结束（{@code terminateRemainder}）把整段补一个
     *       虚拟 {@code ;} 再试一次。</li>
     * </ol>
     *
     * @param terminateRemainder 输入结束（脚本/会话读到结尾）：缓冲尾部按"已补 {@code ;}"处理
     */
    private void processBuffer(boolean terminateRemainder) {
        while (buffer.length() > 0) {
            String raw = buffer.toString();
            if (raw.isBlank()) {
                clearBuffer();
                return;
            }
            if (context.exitRequested()) {
                // _EXIT/_QUIT（或 _WHENEVER ERROR EXIT）要求立刻收尾：同一行里剩下的语句不再执行
                clearBuffer();
                return;
            }
            StatementBoundary.Kind kind = boundary.kind();
            boolean virtualTerminator = terminateRemainder && kind == StatementBoundary.Kind.NONE;
            if (kind == StatementBoundary.Kind.NONE && !virtualTerminator) {
                // 没停在收尾位置：继续攒，不解析（这是 O(N) 的关键）
                return;
            }
            if (awaitingSlash && kind != StatementBoundary.Kind.SLASH) {
                // 块语句只认行首 '/'：后面的 ';' 再多也不会让它完整
                return;
            }
            boolean consumed = false;
            if (!awaitingSlash) {
                for (Integer end : StatementSplitter.topLevelSemicolons(raw)) {
                    if (end <= knownIncompleteUpTo) {
                        // 这个位置之前的前缀上一行已经判定"没写完"，文本没变，不必再解析
                        continue;
                    }
                    if (runner.executeStatement(raw.substring(0, end))) {
                        consume(end);
                        consumed = true;
                        break;
                    }
                    knownIncompleteUpTo = end;
                }
            }
            if (consumed) {
                continue;
            }
            // 没有任何前缀是完整语句
            boolean finished;
            if (virtualTerminator) {
                // 输入结束：SQL 当作"已补 ';'"的一条语句；`_` 命令本身就按行（到 EOF）收尾，
                // **不补** ';' —— 补上去会改变词法：`_HOST """…"""` 末尾的引号与 ';' 会拼成
                // 一个残缺的字符串，整块于是被报成 `Unknown command [_HOST]`。
                String head = raw.stripLeading();
                finished = runner.executeStatement(head.startsWith("_") ? raw : raw + ";");
            } else if (kind == StatementBoundary.Kind.SEMICOLON) {
                // 最后那个顶层 ';' 就是候选里的最后一个前缀，刚刚已经试过，不再重复解析
                finished = false;
            } else {
                finished = runner.executeStatement(raw);
            }
            if (finished) {
                clearBuffer();
                return;
            }
            // 判定"没写完"：如果它看起来是块语句，记住"后面只等行首 '/'"
            if (!awaitingSlash && StatementBoundary.startsBlock(raw)
                    && (kind == StatementBoundary.Kind.SEMICOLON || virtualTerminator)) {
                awaitingSlash = true;
            }
            return;
        }
    }

    /** 清空缓冲与所有随缓冲变化的状态（边界扫描、块标记、前缀记忆）。 */
    private void clearBuffer() {
        buffer.setLength(0);
        boundary.reset();
        awaitingSlash = false;
        knownIncompleteUpTo = 0;
    }

    /** 消费缓冲的前 {@code end} 个字符：把剩下的内容（若还有效）留在缓冲里。 */
    private void consume(int end) {
        String rest = buffer.substring(end);
        // 余下文本开头的换行不属于任何语句：去掉
        String kept = rest.substring(leadingNewlines(rest));
        // 同一行里塞了多条语句时，下一条的起始行要跟着被消费掉的换行一起前进

        buffer.setLength(0);
        // 余下文本是全新的前缀：边界状态与前缀记忆都要重来
        boundary.reset();
        awaitingSlash = false;
        knownIncompleteUpTo = 0;
        if (StatementSplitter.isBlankOrCommentOnly(kept)) {
            return;
        }
        buffer.append(kept);
        boundary.feedText(kept);
    }

    /**
     * 控制台的一次输入：**与批处理同一条收尾规则**。
     *
     * <p>控制台没有自己的输入语法（以前有过"回车即执行 + 行尾 {@code \} 续行"，那套规则让"敲回车"
     * 与"语句写完"变成两件事，粘贴和手敲的多行语句都会走样）。现在一条语句只在下面两种位置收尾：</p>
     * <ol>
     *   <li><b>{@code ;}</b>：且它必须在**顶层**——字符串（{@code 'a;b'}）、注释、{@code {% %}} 里的
     *       {@code ;} 不算（判据是 {@link StatementBoundary}，与批处理同一份词法状态机）；</li>
     *   <li><b>顶格的 {@code /}</b>：单独一行、行首就是 {@code /}（行尾空白无所谓，**缩进的 {@code /} 不算**；
     *       {@code create procedure …}、{@code declare …} 这类块用它收尾）。</li>
     * </ol>
     *
     * <p>除此之外**回车不执行**：语句没写完就继续给续行提示 {@code "   > "}，等下一行——
     * 这与批处理"读到下一行"是同一件事。于是粘贴一段带 {@code ;} 的脚本照旧逐条执行，
     * 粘贴一段不带 {@code ;} 的多行查询则老老实实等着（不再"尾部整体补一个虚拟 ; 执行掉"）。</p>
     *
     * <p><b>例外：{@code _} 命令按行收尾</b>（{@code _help}、{@code _exit}、
     * {@code _set …}、{@code _assert …} 这些不是 SQL 语句，词法上就是一行一条，
     * 见 {@link StatementBoundary.Kind#COMMAND}）：它们回车即执行，不需要 {@code ;}。
     * 这与批处理完全一致（脚本里写 {@code _help} 也不需要分号）。</p>
     *
     * <p>单独一个 {@code ;} 不是语句：上一句已经执行后它只会让数据库报语法错误，因此直接忽略。</p>
     *
     * <p>一次取行可能带回多行（JLine 的 bracketed paste 就是这样）：按行拆开逐行喂，
     * 结果与"手敲这几行"逐字节相同。</p>
     */
    public void feedConsoleLine(String line) {
        String text = line == null ? "" : line;
        for (String physical : Utf8Text.splitLines(text)) {
            if (context.exitRequested()) {
                return;
            }
            if (buffer.length() == 0 && ";".equals(physical.strip())) {
                continue;
            }
            accumulate(physical);
            processBuffer();
        }
    }


    private static int leadingNewlines(String text) {
        int count = 0;
        while (count < text.length() && text.charAt(count) == '\n') {
            count++;
        }
        return count;
    }
}
