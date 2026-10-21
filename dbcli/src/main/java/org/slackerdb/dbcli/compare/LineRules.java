package org.slackerdb.dbcli.compare;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * {@code _COMPARE} 的行规则：mask（改写行内容）与 skip（丢弃整行）。
 *
 * <p>由 {@code _COMPARE MASKLINE a=>b} / {@code NOMASKLINE a} / {@code SKIPLINE p} /
 * {@code NOSKIPLINE p} 四个动作驱动。IR 里 mask 规则的 {@code source}/{@code target} **保留引号**，
 * skip 规则的 {@code source} **已去引号**；本类只接受"已经去好引号的正则与替换串"，
 * 把引号处理留给调用方（见 {@code CompareCommandDispatcher}）。</p>
 *
 * <h2>匹配语义</h2>
 * <ul>
 *   <li>mask：正则**查找并替换全部命中**（`re.sub` 语义）；</li>
 *   <li>skip：正则**必须整行匹配**才丢弃（`fullmatch` 语义，不是"包含"）。</li>
 * </ul>
 * 规则按加入顺序依次生效。
 */
public final class LineRules {

    /** 一条规则：{@code type} 决定语义。 */
    public record Rule(Type type, String source, String target) {
        public enum Type {
            /** 改写命中内容（{@code source} → {@code target}）。 */
            MASK,
            /** 取消一条 mask（{@code NOMASKLINE}）。 */
            UNMASK,
            /** 丢弃命中行（{@code SKIPLINE}）。 */
            SKIP,
            /** 取消一条 skip（{@code NOSKIPLINE}）。 */
            UNSKIP
        }
    }

    private final List<Rule> maskRules = new ArrayList<>();
    private final List<Rule> skipRules = new ArrayList<>();

    /** 加规则；正则非法时抛 {@link IllegalArgumentException}（文案由调用方包装）。 */
    public void add(Rule.Type type, String source, String target) {
        switch (type) {
            case MASK -> {
                compile(source, MASK_FLAGS);
                maskRules.add(new Rule(type, source, target == null ? "" : target));
            }
            case SKIP -> {
                compile(source, MASK_FLAGS);
                skipRules.add(new Rule(type, source, target));
            }
            case UNMASK -> removeMatching(maskRules, source);
            case UNSKIP -> removeMatching(skipRules, source);
        }
    }

    /** 清空所有规则（{@code _COMPARE RESET} 也应清规则）。 */
    public void clear() {
        maskRules.clear();
        skipRules.clear();
    }

    public int maskRuleCount() {
        return maskRules.size();
    }

    public int skipRuleCount() {
        return skipRules.size();
    }

    /**
     * 现有的 mask 规则（按加入顺序）。
     *
     * <p>供 {@code _COMPARE SET} 打印 Masklines 表与"同一 source 不覆盖"的去重使用：
     * {@link #maskSources()} 给的是 {@code "src=>tgt"} 拼接串，无法安全拆回两列。</p>
     */
    public List<Rule> maskRules() {
        return List.copyOf(maskRules);
    }

    /** 现有的 skip 规则（按加入顺序）；{@code _COMPARE SET} 打印 Skiplines 表用。 */
    public List<Rule> skipRules() {
        return List.copyOf(skipRules);
    }

    public List<String> maskSources() {
        List<String> sources = new ArrayList<>();
        for (Rule rule : maskRules) {
            sources.add(rule.source() + "=>" + rule.target());
        }
        return sources;
    }

    public List<String> skipSources() {
        List<String> sources = new ArrayList<>();
        for (Rule rule : skipRules) {
            sources.add(rule.source());
        }
        return sources;
    }

    /**
     * 依次套用全部 mask 规则（空白规则集时原样返回）。
     *
     * <p>规则**逐条叠加**、每条**替换全部命中**（`re.sub` 语义），并**强制忽略大小写**
     * （mask/skip 都一样，与 {@code case} 选项无关）。</p>
     */
    public String applyMask(String line) {
        String text = line;
        for (Rule rule : maskRules) {
            text = compile(rule.source(), MASK_FLAGS).matcher(text)
                    .replaceAll(Matcher.quoteReplacement(rule.target()));
        }
        return text;
    }

    /**
     * 是否应当丢弃该行。
     *
     * <p>**整行匹配**（`fullmatch` 语义）且**强制忽略大小写** —— 与 {@code case} 选项无关。</p>
     */
    public boolean shouldSkip(String line) {
        for (Rule rule : skipRules) {
            if (compile(rule.source(), MASK_FLAGS).matcher(line).matches()) {
                return true;
            }
        }
        return false;
    }

    private void removeMatching(List<Rule> rules, String source) {
        if (source == null) {
            rules.clear();
            return;
        }
        rules.removeIf(rule -> source.equals(rule.source()));
    }

    /** mask 与 skip 都强制忽略大小写（与 {@code case} 选项无关）。 */
    private static final int MASK_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static Pattern compile(String regex, int flags) {
        try {
            return Pattern.compile(regex, flags);
        } catch (PatternSyntaxException syntaxError) {
            throw new IllegalArgumentException("invalid pattern [" + regex + "]: " + syntaxError.getDescription(),
                    syntaxError);
        }
    }
}
