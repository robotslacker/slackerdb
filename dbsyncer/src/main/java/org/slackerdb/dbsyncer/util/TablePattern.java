package org.slackerdb.dbsyncer.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表名匹配/目标名展开。
 *
 * <p>match 支持三种写法，均按整串匹配：</p>
 * <ul>
 *   <li>精确/通配：{@code public.orders}、{@code public.orders_*}、{@code public.orders_?}；</li>
 *   <li>内联正则：{@code public.orders{.*}}，花括号内是正则，整体是一个捕获组；</li>
 *   <li>完整正则：{@code /^public\.orders_(.*)$/}，按 Java 正则匹配。</li>
 * </ul>
 *
 * <p>target 可用 {@code {table}} 表示源表名，用 {@code $1}、{@code ${1}}、{@code {1}}、
 * {@code {$1}} 引用 match 的捕获组。示例：match={@code public.orders{.*}}，
 * target={@code orders{$1}}。</p>
 */
public final class TablePattern {

    private final Pattern pattern;
    private final int groupCount;

    private TablePattern(Pattern pattern) {
        this.pattern = pattern;
        this.groupCount = pattern.matcher("").groupCount();
    }

    public static TablePattern compile(String expression, boolean caseSensitive) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("table match pattern must not be blank");
        }
        String value = expression.trim();
        String regex;
        if (value.length() > 2 && value.startsWith("/") && value.endsWith("/")) {
            regex = value.substring(1, value.length() - 1);
        } else {
            regex = toRegex(value);
        }
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
        return new TablePattern(Pattern.compile(regex, flags));
    }

    public boolean matches(String value) {
        return pattern.matcher(value).matches();
    }

    public String expandTarget(String template, String sourceTable, String fullName) {
        if (template == null || template.isBlank()) {
            return sourceTable;
        }
        Matcher matcher = pattern.matcher(fullName);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("value [" + fullName + "] does not match pattern ["
                    + pattern.pattern() + "]");
        }
        String result = template.replace("{table}", sourceTable);
        result = expandGroups(result, matcher, "\\{\\$(\\d+)}");
        result = expandGroups(result, matcher, "\\$\\{(\\d+)}");
        result = expandGroups(result, matcher, "\\{(\\d+)}");
        result = expandGroups(result, matcher, "\\$(\\d+)");
        return result.replace("$$", "$");
    }

    private String expandGroups(String template, Matcher matcher, String placeholderRegex) {
        Matcher placeholder = Pattern.compile(placeholderRegex).matcher(template);
        StringBuilder result = new StringBuilder();
        while (placeholder.find()) {
            int group = Integer.parseInt(placeholder.group(1));
            if (group < 0 || group > groupCount) {
                throw new IllegalArgumentException("target references capture group $" + group
                        + " but match has only " + groupCount + " group(s)");
            }
            String replacement = group == 0 ? matcher.group(0) : matcher.group(group);
            placeholder.appendReplacement(result, Matcher.quoteReplacement(replacement == null ? "" : replacement));
        }
        placeholder.appendTail(result);
        return result.toString();
    }

    private static String toRegex(String expression) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (c == '{') {
                int close = expression.indexOf('}', i + 1);
                if (close < 0) {
                    throw new IllegalArgumentException("unclosed '{' in table match pattern: " + expression);
                }
                String inner = expression.substring(i + 1, close);
                if (inner.isEmpty()) {
                    throw new IllegalArgumentException("empty '{}' in table match pattern: " + expression);
                }
                regex.append('(').append(inner).append(')');
                i = close + 1;
            } else if (c == '*') {
                regex.append("([^.]*)");
                i++;
            } else if (c == '?') {
                regex.append("([^.])");
                i++;
            } else {
                int start = i;
                while (i < expression.length()) {
                    char next = expression.charAt(i);
                    if (next == '{' || next == '*' || next == '?') {
                        break;
                    }
                    i++;
                }
                regex.append(Pattern.quote(expression.substring(start, i)));
            }
        }
        return regex.toString();
    }
}
