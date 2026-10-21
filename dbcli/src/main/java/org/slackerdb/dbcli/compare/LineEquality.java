package org.slackerdb.dbcli.compare;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 行相等的判定（{@code _COMPARE} 的核心语义）。
 *
 * <h2>判定顺序</h2>
 * <ol>
 *   <li>按选项剥离首尾空白（{@code ignoreHeadTailBlank}：{@code SET TRIM} 打开、{@code SET NOTRIM}
 *       关闭，默认打开）；</li>
 *   <li>整串相等 → 相等；</li>
 *   <li>{@code caseSensitive=false}（默认）时忽略大小写再比一次 → 相等则相等；</li>
 *   <li>{@code mask} 打开时：把**参考行当作正则**做**整行匹配**（{@code fullmatch} 语义）——
 *       大小写不敏感时正则本身带 {@code CASE_INSENSITIVE} 标志；</li>
 *   <li>否则不相等。</li>
 * </ol>
 *
 * <p><b>非法正则的处理</b>：参考行不是合法正则时抛出带**参考行内容**的可读异常，
 * 由调用方转成错误输出。这里不做"编译失败就静默判为不相等"的处理 —— "比对结果不同"与
 * "你的正则写错了"是两件事，混为一谈会让使用者完全看不到原因。</p>
 */
public final class LineEquality {

    private final CompareOptions options;

    public LineEquality(CompareOptions options) {
        this.options = options;
    }

    /**
     * 判断参考侧一行与工作（目标）侧一行是否"相等"。
     *
     * @param referenceLine 参考文件的行（mask 打开时它被当作正则）
     * @param targetLine    工作/目标文件的行（mask 打开时它是被匹配的字符串）
     * @throws IllegalArgumentException mask 打开且参考行不是合法正则时（带参考行内容，便于定位）
     */
    public boolean equal(String referenceLine, String targetLine) {
        String left = options.ignoreHeadTailBlank() ? referenceLine.strip() : referenceLine;
        String right = options.ignoreHeadTailBlank() ? targetLine.strip() : targetLine;

        if (left.equals(right)) {
            return true;
        }
        if (!options.caseSensitive() && left.equalsIgnoreCase(right)) {
            return true;
        }
        if (!options.mask()) {
            return false;
        }
        int flags = options.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        try {
            Matcher matcher = Pattern.compile(left, flags).matcher(right);
            // 整行匹配（fullmatch 语义）：整串必须完全匹配，而不是"包含"
            return matcher.matches();
        } catch (PatternSyntaxException syntaxError) {
            throw new IllegalArgumentException("invalid mask pattern in reference line [" + left + "]: "
                    + syntaxError.getDescription(), syntaxError);
        }
    }

    /** 供日志/调试：当前是否忽略大小写。 */
    String describeCaseMode() {
        return options.caseSensitive() ? "case-sensitive" : "ignore-case(" + Locale.ROOT + ")";
    }
}
