package org.slackerdb.dbcli.compare;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 mustache 渲染器（**只为 {@code htmldiff} 的四个模板**存在）。
 *
 * <p>Java 侧不引入模板引擎依赖（无配置文件、不加额外依赖），故这里只实现模板**实际用到**
 * 的标签，并实现 mustache 的"独立行"（standalone）规则：</p>
 * <ul>
 *   <li>{@code {{name}}} → 变量，取当前作用域（section 循环体是当前作用域），值做 HTML 转义
 *       （{@code &} 先转，再 {@code " < > '}）；</li>
 *   <li>{@code {{#name}}} … {@code {{/name}}} → 列表 section：对列表中每一项渲染一遍体内容
 *       （逐项把该项 push 成内层作用域）；</li>
 *   <li><b>独立行（standalone）</b>：当 section 开始/结束标签**独占一行**（标签前只有空白、标签后到
 *       行尾只有空白）时，该标签所在行的缩进与换行会被吃掉 —— 这是
 *       {@code body.content.html} 里 {@code var data = [...]} 每项独占一行、且空列表不留空行的原因。</li>
 * </ul>
 *
 * <p>未实现的 mustache 特性（模板里没有出现）：{@code {{{no escape}}}/{@code {{&}}}、注释
 * {@code {{! }}}、反 section {@code {{^ }}}、partial {@code {{> }}}、{@code {{= =}}} 改分隔符、
 * 非列表 scope、点号取值。用到时按需补，不要假定本类是通用模板引擎。</p>
 */
final class HtmlTemplateRenderer {

    private HtmlTemplateRenderer() {
    }

    /** 渲染一个模板：{@code data} 的值只支持 {@code String} 与 {@code List<Map<String,Object>>}（section）。 */
    static String render(String template, Map<String, Object> data) {
        List<Token> tokens = parse(tokenize(template));
        StringBuilder output = new StringBuilder();
        renderTokens(tokens, List.of(data), output);
        return output.toString();
    }

    // ------------------------------------------------------------------ 分词

    /** 扁平标签：{@code literal} / {@code variable} / {@code section} / {@code end} / {@code comment}。 */
    private record RawTag(String type, String key) {
    }

    private static List<RawTag> tokenize(String template) {
        List<RawTag> tags = new ArrayList<>();
        String rest = template;
        boolean standalone = true;
        while (true) {
            int open = rest.indexOf("{{");
            if (open < 0) {
                tags.add(new RawTag("literal", rest));
                return tags;
            }
            String literal = rest.substring(0, open);
            standalone = leftStandalone(literal, standalone);

            int close = rest.indexOf("}}", open + 2);
            if (close < 0) {
                throw new IllegalArgumentException("unclosed tag in html template");
            }
            String inner = rest.substring(open + 2, close);
            String type;
            String key;
            char marker = inner.isEmpty() ? ' ' : inner.charAt(0);
            switch (marker) {
                case '#' -> {
                    type = "section";
                    key = inner.substring(1).strip();
                }
                case '/' -> {
                    type = "end";
                    key = inner.substring(1).strip();
                }
                case '!' -> {
                    type = "comment";
                    key = inner.substring(1).strip();
                }
                default -> {
                    type = "variable";
                    key = inner.strip();
                }
            }

            String after = rest.substring(close + 2);
            if (rightStandalone(after, type, standalone)) {
                int newline = after.indexOf('\n');
                after = newline < 0 ? "" : after.substring(newline + 1);
                literal = stripTrailingSpaces(literal);
            }
            if (!literal.isEmpty()) {
                tags.add(new RawTag("literal", literal));
            }
            if (!"comment".equals(type)) {
                tags.add(new RawTag(type, key));
            }
            rest = after;
        }
    }

    /** 独立行判定（左）：标签左边是不是"只有空白"（或上一个标签已经是独立行）。 */
    private static boolean leftStandalone(String literal, boolean previousStandalone) {
        if (literal.indexOf('\n') >= 0 || previousStandalone) {
            String padding = literal.substring(literal.lastIndexOf('\n') + 1);
            return padding.isBlank();
        }
        return false;
    }

    /** 独立行判定（右）：标签右边到行尾是不是"只有空白"（变量标签不参与独立行判断）。 */
    private static boolean rightStandalone(String after, String type, boolean previousStandalone) {
        if (previousStandalone && !"variable".equals(type)) {
            int newline = after.indexOf('\n');
            String head = newline < 0 ? after : after.substring(0, newline);
            return head.isBlank();
        }
        return false;
    }

    private static String stripTrailingSpaces(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\t')) {
            end--;
        }
        return text.substring(0, end);
    }

    // ------------------------------------------------------------------ 建树

    private sealed interface Token {
        record Literal(String text) implements Token {
        }

        record Variable(String key) implements Token {
        }

        record Section(String key, List<Token> body) implements Token {
        }
    }

    private static List<Token> parse(List<RawTag> tags) {
        int[] cursor = {0};
        List<Token> tokens = parseSection(tags, cursor, null);
        if (cursor[0] != tags.size()) {
            throw new IllegalArgumentException("unexpected closing section tag");
        }
        return tokens;
    }

    private static List<Token> parseSection(List<RawTag> tags, int[] cursor, String closingKey) {
        List<Token> body = new ArrayList<>();
        while (cursor[0] < tags.size()) {
            RawTag tag = tags.get(cursor[0]++);
            switch (tag.type()) {
                case "literal" -> body.add(new Token.Literal(tag.key()));
                case "variable" -> body.add(new Token.Variable(tag.key()));
                case "section" -> body.add(new Token.Section(tag.key(), parseSection(tags, cursor, tag.key())));
                case "end" -> {
                    if (!tag.key().equals(closingKey)) {
                        throw new IllegalArgumentException("mismatched section tag [" + tag.key() + "]");
                    }
                    return body;
                }
                default -> {
                    // comment 已在分词阶段丢弃
                }
            }
        }
        if (closingKey != null) {
            throw new IllegalArgumentException("unclosed section tag [" + closingKey + "]");
        }
        return body;
    }

    // ------------------------------------------------------------------ 渲染

    private static void renderTokens(List<Token> tokens, List<Map<String, Object>> scopes, StringBuilder output) {
        for (Token token : tokens) {
            if (token instanceof Token.Literal literal) {
                output.append(literal.text());
            } else if (token instanceof Token.Variable variable) {
                output.append(escape(value(variable.key(), scopes)));
            } else if (token instanceof Token.Section section) {
                Object scope = value(section.key(), scopes);
                if (scope instanceof List<?> items) {
                    for (Object item : items) {
                        if (item instanceof Map<?, ?> rawItem) {
                            Map<String, Object> itemScope = new LinkedHashMap<>();
                            rawItem.forEach((key, value) -> itemScope.put(String.valueOf(key), value));
                            List<Map<String, Object>> inner = new ArrayList<>(scopes.size() + 1);
                            inner.add(itemScope);
                            inner.addAll(scopes);
                            renderTokens(section.body(), inner, output);
                        }
                    }
                }
            }
        }
    }

    /** 取值：从内层作用域往外找第一个命中的键；找不到渲染成空串。 */
    private static Object value(String key, List<Map<String, Object>> scopes) {
        for (Map<String, Object> scope : scopes) {
            if (scope.containsKey(key)) {
                return scope.get(key);
            }
        }
        return "";
    }

    /** HTML 转义：{@code &} 必须先转。 */
    private static String escape(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return text.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&#39;");
    }
}
