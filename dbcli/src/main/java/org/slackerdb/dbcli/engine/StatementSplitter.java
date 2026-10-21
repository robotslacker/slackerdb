package org.slackerdb.dbcli.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 累积缓冲的**顶层 {@code ;} 切分**：把"一行多条语句"（{@code select 1; select 2;}）拆成可逐条分析执行的片段。
 *
 * <h2>什么算"顶层"</h2>
 * <p>下面这些位置上的 {@code ;} <b>不算</b>语句分隔符：</p>
 * <ol>
 *   <li>单引号 / 双引号字符串内部（{@code insert into t values ('a;b')}）；反斜杠转义也算在字符串内；</li>
 *   <li>行注释内部：{@code --} 到行尾（SQL 惯例，任意位置生效）、{@code #} 到行尾（本引擎只把**行首**的
 *       {@code #} 当注释，与既有"注释行"判定一致）；</li>
 *   <li>{@code {% ... %}} 断言外观内部（它的 {@code ;} 是断言正文的一部分）。</li>
 * </ol>
 *
 * <h2>只给"切分点"，不替调用方决定哪一段能执行</h2>
 * <p>本类只做词法层面的定位。{@code select 1;} 与 {@code create procedure p() begin … } 里的
 * {@code select 2;} 在词法上长得一样，只有 {@code SqlAnalyzer} 知道后者"还没写完"
 * （{@code isFinished=false}）。因此调用方（{@link ScriptSession}）的用法是：
 * 依次尝试"到第 i 个切分点为止的前缀"，第一个被分析器接受的前缀才被消费；
 * 一个都不接受时退回"整段当一条语句"（即切换前的既有行为）。</p>
 */
public final class StatementSplitter {

    private StatementSplitter() {
    }

    /**
     * 顶层 {@code ;} 的位置（**独占式**下标：{@code end} 指向分号之后一位，可直接做 {@code substring} 的右边界）。
     *
     * <p>按出现顺序返回；调用方对第 i 个位置取前缀 {@code text.substring(0, end)} 即为"第 i 条候选语句"。</p>
     */
    public static List<Integer> topLevelSemicolons(String text) {
        return scan(text).semicolons();
    }

    /**
     * 这段文本是否"只有空白与注释"。
     *
     * <p>用途：消费掉一条语句后，缓冲里可能只剩 {@code " -- 注释"} 这类残渣；若不当成空，
     * 交互式控制台会一直显示续行提示、脚本末尾还会把它当成"没写完的语句"交给执行层。</p>
     */
    public static boolean isBlankOrCommentOnly(String text) {
        return !scan(text).hasCode();
    }

    /** 一次扫描的结果：切分点 + "是否出现过有效代码"。 */
    private record Scan(List<Integer> semicolons, boolean hasCode) {
    }

    /**
     * 单遍扫描：把整段文本按行喂给 {@link LexicalScanner}（**唯一**的词法规则），
     * 收集顶层 {@code ;} 的位置与"是否出现过有效代码"。
     *
     * <p>换行原样保留在文本里（只跳过注释正文），这样交给执行层的文本就是使用者写的那一段。</p>
     */
    private static Scan scan(String text) {
        String body = text == null ? "" : text;
        List<Integer> semicolons = new ArrayList<>();
        boolean[] hasCode = {false};
        LexicalScanner.Events events = new LexicalScanner.Events() {
            @Override
            public void topLevelSemicolon(int position) {
                semicolons.add(position);
                // 一个裸 ';' 也算"出现过有效代码"（与旧实现一致：它让 ";" 不是"只有空白/注释"）
                hasCode[0] = true;
            }

            @Override
            public boolean codePoint(int position, char character) {
                hasCode[0] = true;
                return true;
            }
        };
        LexicalScanner scanner = new LexicalScanner();
        int lineStart = 0;
        for (int index = 0; index <= body.length(); index++) {
            if (index < body.length() && body.charAt(index) != '\n') {
                continue;
            }
            scanner.scanLine(body.substring(lineStart, index), lineStart, events);
            lineStart = index + 1;
        }
        return new Scan(semicolons, hasCode[0]);
    }
}
