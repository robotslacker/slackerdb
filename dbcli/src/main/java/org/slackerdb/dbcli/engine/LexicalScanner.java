package org.slackerdb.dbcli.engine;

/**
 * 脚本的**唯一**词法状态机：单/双引号（含反斜杠转义）、{@code --} 行注释、行首 {@code #} 注释、
 * {@code {% %}} 断言外观、行首标记。
 *
 * <h2>为什么要有它</h2>
 * <p>同一套"哪些字符在引号/注释里"的规则以前实现了三遍：ANTLR 的 {@code SQLLexer} 一份、
 * {@link StatementBoundary} 一份、{@link StatementSplitter} 一份。后两份是手写的兄弟实现，
 * 一旦不一致就会出现"边界判成收尾、切分却不认"这类 bug（本模块已经踩过两次：
 * {@code _ECHO} 正文里的撇号、多行 {@code _HOST} 块）。
 * 现在只有一个状态机，两个调用方各取所需：</p>
 * <ul>
 *   <li>{@link StatementBoundary}：增量喂行，靠 {@link Events} 维护"待处理语句"的状态；</li>
 *   <li>{@link StatementSplitter}：把整段文本按行喂一遍，收集顶层 {@code ;} 的位置。</li>
 * </ul>
 *
 * <h2>位置约定</h2>
 * <p>{@link Events} 收到的是**整段文本里的绝对下标**：调用方给 {@code offset}（本行在整段文本里的起点），
 * 本类负责加上行内下标。{@code topLevelSemicolon} 给的是分号之后一位（可直接做 {@code substring} 右边界）。</p>
 */
final class LexicalScanner {

    /**
     * 扫描事件：{@code false} 表示"这一行剩下的部分不用再看了"。
     *
     * <p>为什么需要提前停：{@code _ECHO <标题>} 之后同一行的内容属于块标题/块体，
     * 里面出现撇号也是数据，不能把状态机带进字符串。</p>
     */
    interface Events {

        /** 顶层 {@code ;}：{@code position} = 分号之后一位。 */
        void topLevelSemicolon(int position);

        /**
         * 引号/注释/断言外观之外的一个**有效代码**字符（空白已过滤）。
         *
         * @return {@code false} 表示本行剩余部分不再扫描
         */
        boolean codePoint(int position, char character);
    }

    private boolean inSingleQuote;
    private boolean inDoubleQuote;
    private boolean inAssertBraces;
    private boolean inLineComment;
    private boolean atLineStart = true;

    /** 清空状态（一段新的待处理文本开始时调用）。 */
    void reset() {
        inSingleQuote = false;
        inDoubleQuote = false;
        inAssertBraces = false;
        inLineComment = false;
        atLineStart = true;
    }

    /** 当前是否停在未闭合的引号/断言外观里（=后面还有内容，不能当语句收尾）。 */
    boolean insideQuoteOrBraces() {
        return inSingleQuote || inDoubleQuote || inAssertBraces;
    }

    /**
     * 扫描一行（不含行尾）。行内的引号/注释状态会带到下一行；{@code --}/{@code #} 注释只到行尾。
     *
     * @param line   这一行的原文
     * @param offset 本行第一个字符在整段文本里的下标（用于事件定位）
     * @param events 事件接收者
     */
    void scanLine(String line, int offset, Events events) {
        int length = line.length();
        int index = 0;
        while (index < length) {
            char character = line.charAt(index);
            if (inLineComment) {
                break;
            }
            if (inSingleQuote || inDoubleQuote) {
                char quote = inSingleQuote ? '\'' : '"';
                if (character == '\\' && index + 1 < length) {
                    index += 2;
                    continue;
                }
                if (character == quote) {
                    inSingleQuote = false;
                    inDoubleQuote = false;
                }
                index++;
                continue;
            }
            if (inAssertBraces) {
                if (character == '%' && index + 1 < length && line.charAt(index + 1) == '}') {
                    inAssertBraces = false;
                    index += 2;
                    continue;
                }
                index++;
                continue;
            }
            if (character == '-' && index + 1 < length && line.charAt(index + 1) == '-') {
                inLineComment = true;
                break;
            }
            if (character == '#' && atLineStart) {
                inLineComment = true;
                break;
            }
            if (character == '{' && index + 1 < length && line.charAt(index + 1) == '%') {
                inAssertBraces = true;
                atLineStart = false;
                if (!events.codePoint(offset + index, character)) {
                    break;
                }
                index += 2;
                continue;
            }
            if (character == '\'') {
                inSingleQuote = true;
                atLineStart = false;
                if (!events.codePoint(offset + index, character)) {
                    break;
                }
                index++;
                continue;
            }
            if (character == '"') {
                inDoubleQuote = true;
                atLineStart = false;
                if (!events.codePoint(offset + index, character)) {
                    break;
                }
                index++;
                continue;
            }
            if (character == ';') {
                atLineStart = false;
                events.topLevelSemicolon(offset + index + 1);
                index++;
                continue;
            }
            if (!Character.isWhitespace(character)) {
                atLineStart = false;
                if (!events.codePoint(offset + index, character)) {
                    break;
                }
            }
            index++;
        }
        // 行注释不跨行；行首标记按行重置
        inLineComment = false;
        atLineStart = true;
    }
}
