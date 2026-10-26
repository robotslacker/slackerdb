package org.slackerdb.dbcli.engine;

import java.util.Locale;
import java.util.Map;

/**
 * 语句文本的纯函数小工具：命令字、{@code _connect} 原文、{@code _SET ECHO OFF} 的识别。
 *
 * <p>这些判断以前散落在 {@link ScriptRunner} 的各个分支里，读一遍"解析失败怎么分流"要在
 * 300 行里来回找。集中到这里之后，它们没有状态、可以单独测，{@link ScriptRunner} 只负责"用它们做决定"。</p>
 */
final class StatementText {

    private StatementText() {
    }

    /**
     * 首词（命令字）：在空白、行尾或 {@code ;} 处结束。
     *
     * <p>{@code _bogus;} 的命令字是 {@code _bogus}——否则错误文案会写成 {@code Unknown command [_bogus;]}。</p>
     *
     * @return 没有首词（空串/纯空白）时返回 null
     */
    static String firstWord(String text) {
        String stripped = text == null ? "" : text.strip();
        int end = 0;
        while (end < stripped.length()) {
            char character = stripped.charAt(end);
            if (Character.isWhitespace(character) || character == ';') {
                break;
            }
            end++;
        }
        return end == 0 ? null : stripped.substring(0, end);
    }

    /**
     * 从一条 {@code _connect} 语句里取出使用者写的连接串原文：去掉命令字、首尾空白与结尾的 {@code ';'}。
     *
     * <p>只在**解析阶段**就要报"无效连接串"时用：那时 IR 里没有完整原文（解析器只为成功匹配的 token
     * 建节点）。错误文案必须回显使用者写的那一段。</p>
     */
    static String connectStringOf(String statement) {
        String text = statement == null ? "" : statement.strip();
        String command = firstWord(text);
        if (command != null) {
            text = text.substring(command.length()).strip();
        }
        if (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).strip();
        }
        return text;
    }

    /**
     * 这条语句是不是 {@code _SET ECHO OFF}（或它的变体）。
     *
     * <p>它自己**不**回显：否则"关掉 echo 之后日志里只剩结果"永远差这一行。</p>
     */
    static boolean turnsEchoOff(Map<String, Object> ir) {
        if (ir == null || !"SET".equalsIgnoreCase(String.valueOf(ir.get("name")))) {
            return false;
        }
        Object option = ir.get("optionName");
        Object value = ir.get("optionValue");
        return option != null && "echo".equalsIgnoreCase(String.valueOf(option))
                && value != null && "off".equalsIgnoreCase(String.valueOf(value).strip());
    }

    /** 这条命令是不是 {@code _ECHO} 文本块（它的正文是数据，回显等于把数据打两遍）。 */
    static boolean isEchoBlock(String text) {
        String first = firstWord(text);
        return first != null && "_echo".equalsIgnoreCase(first);
    }

    /** IR 的 {@code name}，统一大写（缺失时为空串，避免调用方到处判 null）。 */
    static String irName(Map<String, Object> ir) {
        Object name = ir == null ? null : ir.get("name");
        return name == null ? "" : String.valueOf(name).toUpperCase(Locale.ROOT);
    }
}
