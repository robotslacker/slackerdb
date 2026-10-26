package org.slackerdb.dbdesk.access.sql;

/**
 * 脚本的**唯一**词法状态机：单/双引号（含反斜杠转义）、{@code --} 行注释、行首 {@code #} 注释、
 * {@code {% %}} 断言外观。
 *
 * <p><b>来源</b>：复制自 {@code dbcli/engine/LexicalScanner}（决策 R3）。原类里还服务于
 * {@code StatementBoundary}（增量判断"语句是否收尾"），控制台目前只用切分，所以删掉了
 * {@code reset()}/{@code insideQuoteOrBraces()} 两个只被边界用的方法；**扫描规则本身未改**。</p>
 *
 * <p>位置约定：{@link Events} 收到的是整段文本里的**绝对下标**；{@code topLevelSemicolon} 给的是
 * 分号之后一位（可直接做 {@code substring} 右边界）。</p>
 */
final class LexicalScanner {

    /** 扫描事件：{@code false} 表示"这一行剩下的部分不用再看了"。 */
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
