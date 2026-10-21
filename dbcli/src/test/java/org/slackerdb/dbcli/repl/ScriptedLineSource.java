package org.slackerdb.dbcli.repl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 测试用的 {@link LineSource}：按预置序列"还回"输入行，并记下每次拿到的提示符。
 *
 * <p>这是"控制台必须能在无 TTY 环境里单测"的落地点：
 * CI / surefire 里既没有终端，也不该真的等键盘输入；控制台只认这个接口，
 * 因此"逐行粘进控制台"与"批处理跑同一脚本"可以直接比较。</p>
 */
public final class ScriptedLineSource implements LineSource {

    private final List<String> lines;

    private final Set<Integer> interruptAt;

    private final List<String> prompts = new ArrayList<>();

    /** 第几次调用（含被中断的那次）——{@link #interruptAt} 用的下标。 */
    private int callIndex;

    /** 下一次要还回的行下标；被中断的那次**不消耗**行，因为用户没输入任何内容。 */
    private int lineIndex;

    private boolean closed;

    public ScriptedLineSource(List<String> lines) {
        this(lines, Set.of());
    }

    /**
     * @param lines       预置输入行（按顺序返回；取完返回 null，模拟 Ctrl-D）
     * @param interruptAt 第几次调用 {@code readLine} 抛"中断"（0 基；模拟 Ctrl-C）；
     *                    中断不消耗输入行——现实里 Ctrl-C 就是"这一行没敲完"
     */
    public ScriptedLineSource(List<String> lines, Set<Integer> interruptAt) {
        this.lines = List.copyOf(lines);
        this.interruptAt = new HashSet<>(interruptAt);
    }

    @Override
    public String readLine(String prompt) throws InputInterruptedException {
        prompts.add(prompt);
        int index = callIndex;
        callIndex++;
        if (interruptAt.contains(index)) {
            throw new InputInterruptedException("scripted interrupt");
        }
        return lineIndex < lines.size() ? lines.get(lineIndex++) : null;
    }

    /** 每次 {@code readLine} 收到的提示符（按顺序）。 */
    public List<String> prompts() {
        return List.copyOf(prompts);
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
    }
}
