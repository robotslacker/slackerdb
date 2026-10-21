package org.slackerdb.dbcli.compare;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行规则（{@code MASKLINE}/{@code SKIPLINE}）的契约测试。
 *
 * <p><b>按语义本意实现。</b>{@code MASKLINE} 的规则真正生效：按规则改写行内容后再比对，
 * 所以 source/target 在登记时就去掉引号（见 {@code CompareCommandDispatcher#addMaskRule}）。</p>
 */
class LineRulesTest {

    @Test
    @DisplayName("mask 规则真的生效：替换全部命中、多条规则叠加、忽略大小写")
    void maskRulesRewriteLines() {
        LineRules rules = new LineRules();
        rules.add(LineRules.Rule.Type.MASK, "\\d+", "#");
        rules.add(LineRules.Rule.Type.MASK, "TS", "TIME");

        assertEquals("id=# TIME=#", rules.applyMask("id=1 TS=2"), "数字全替换、TS 也替换（忽略大小写）");
        assertEquals("id=# TIME=#", rules.applyMask("id=99 ts=100"), "小写也要命中");
        assertEquals("nothing", rules.applyMask("nothing"), "没命中就原样返回");
        assertEquals(2, rules.maskRuleCount());
    }

    @Test
    @DisplayName("skip 是**整行匹配**且强制忽略大小写")
    void skipIsWholeLineAndCaseInsensitive() {
        LineRules rules = new LineRules();
        rules.add(LineRules.Rule.Type.SKIP, "data-\\d+", null);

        assertTrue(rules.shouldSkip("data-1"), "整行匹配");
        assertTrue(rules.shouldSkip("DATA-9"), "强制忽略大小写（与 case 选项无关）");
        assertFalse(rules.shouldSkip("xdata-1"), "必须从行首开始（fullmatch 语义）");
        assertFalse(rules.shouldSkip("data-1 extra"), "必须整行匹配，不能只匹配前缀");

        LineRules plain = new LineRules();
        plain.add(LineRules.Rule.Type.SKIP, "data", null);
        assertFalse(plain.shouldSkip("data-1"), "模式 data 不该匹配整行 data-1");
        assertTrue(plain.shouldSkip("DATA"), "但整行等于 data（忽略大小写）时应当匹配");
    }

    @Test
    @DisplayName("unmask/noskip 按 source 精确移除；NOSKIPLINE 无 source 时清空")
    void removingRules() {
        LineRules rules = new LineRules();
        rules.add(LineRules.Rule.Type.SKIP, "a", null);
        rules.add(LineRules.Rule.Type.SKIP, "b", null);
        rules.add(LineRules.Rule.Type.UNSKIP, "a", null);
        assertEquals(1, rules.skipRuleCount());
        assertFalse(rules.shouldSkip("a"));
        assertTrue(rules.shouldSkip("b"));
        rules.add(LineRules.Rule.Type.UNSKIP, null, null);
        assertEquals(0, rules.skipRuleCount(), "不给 source 表示清空");

        rules.add(LineRules.Rule.Type.MASK, "x", "y");
        rules.add(LineRules.Rule.Type.UNMASK, "x", null);
        assertEquals(0, rules.maskRuleCount());
    }

    @Test
    @DisplayName("非法正则 → 可读异常（带模式文本）")
    void invalidPattern() {
        LineRules rules = new LineRules();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> rules.add(LineRules.Rule.Type.SKIP, "[", null));
        assertTrue(error.getMessage().contains("invalid pattern"), error.getMessage());
    }
}
