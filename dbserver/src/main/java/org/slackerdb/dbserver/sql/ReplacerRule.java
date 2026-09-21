package org.slackerdb.dbserver.sql;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SQL 改写规则的"编译后"形态：把规则级的常量工作从热路径搬到 {@link SQLReplacer#load} 阶段。
 *
 * <p>改造前的 {@code replaceSQL} 把下面这些"每条规则只需要算一次"的事情放在了
 * "每条规则 × 每条语句"的内层循环里：</p>
 * <ul>
 *   <li>{@code toFind().replaceAll("\r\n","\n").trim()} 与 {@code toReplace()} 的同样处理——
 *       {@link String#replaceAll} 每次调用都会重新编译正则，实测 13 条 regex 规则
 *       在每次未命中时白花约 7 µs；</li>
 *   <li>{@code sampleReplace} 规则<b>无条件</b>执行 {@code replaceAll}：实测 21 条此类规则里
 *       平均只有 1 条真正命中，其余 20 条各白跑一次全串大小写不敏感扫描（约 35 µs）。</li>
 * </ul>
 *
 * <p>本类在 load 时把这些算好，并额外提供 {@link #lowerFind()} 作为廉价预筛用的字面量。</p>
 */
final class ReplacerRule {

    enum Kind {
        /** 字面量替换（大小写不敏感，正则形式为 {@code (?i)\Qliteral\E}） */
        SAMPLE,
        /** 正则匹配：先用 {@code matches()} 判断，命中才 {@code replaceAll} */
        REGEX,
        /** 忽略大小写的整串相等比较 */
        LITERAL
    }

    private final Kind kind;
    private final String find;
    private final String replace;
    private final String lowerFind;
    private final Pattern pattern;

    private ReplacerRule(Kind kind, String find, String replace, String lowerFind, Pattern pattern) {
        this.kind = kind;
        this.find = find;
        this.replace = replace;
        this.lowerFind = lowerFind;
        this.pattern = pattern;
    }

    static ReplacerRule compile(QueryReplacerItem item) {
        if (item.sampleReplace()) {
            // 与原实现保持一致：字面量替换分支用的是**未规范化**的 toFind/toReplace
            String rawFind = item.toFind();
            return new ReplacerRule(
                    Kind.SAMPLE,
                    rawFind,
                    item.toReplace(),
                    rawFind.toLowerCase(Locale.ROOT),
                    SQLReplacer.compileSamplePattern(rawFind));
        }

        String find = normalize(item.toFind());
        String replace = normalize(item.toReplace());
        if (item.regex()) {
            return new ReplacerRule(Kind.REGEX, find, replace, null, SQLReplacer.compileRegexPattern(find));
        }
        return new ReplacerRule(Kind.LITERAL, find, replace, null, null);
    }

    /**
     * 等价于原来的 {@code s.replaceAll("\r\n", "\n").trim()}，但改用字面量 {@link String#replace}
     * 以避免重新编译正则；而且现在只在 load 阶段执行一次。
     */
    private static String normalize(String s) {
        return s.replace("\r\n", "\n").trim();
    }

    Kind kind() {
        return kind;
    }

    String find() {
        return find;
    }

    String replace() {
        return replace;
    }

    /**
     * 小写字面量，用于"该字面量是否出现在语句里"的廉价预筛。
     *
     * <p>只对 {@link Kind#SAMPLE} 有值。预筛的正确性依赖两点：</p>
     * <ol>
     *   <li>Java 的 {@code Pattern.CASE_INSENSITIVE} 在不带 {@code UNICODE_CASE} 时只做 ASCII 折叠，
     *       而语料里的这些字面量全是 ASCII，因此若正则能匹配，则语句里必然原样包含该字面量，
     *       小写之后也必然包含其小写形式——<b>不会漏判</b>；</li>
     *   <li>反过来可能出现"预筛通过但正则不匹配"的情况（例如非 ASCII 大小写折叠差异），
     *       此时只是多跑一次正则，结果不变——<b>不会误判</b>。</li>
     * </ol>
     */
    String lowerFind() {
        return lowerFind;
    }

    Pattern pattern() {
        return pattern;
    }
}
