package org.slackerdb.dbcli.compare;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行相等判定的契约测试。
 *
 * <p>锁住行相等判定的判定顺序与语义，尤其是**最容易实现错**的两点：
 * mask 打开时参考行是正则且必须**整行匹配**；大小写不敏感时正则本身带 {@code CASE_INSENSITIVE}。</p>
 */
class LineEqualityTest {

    private static CompareOptions defaults() {
        return new CompareOptions();
    }

    @Test
    @DisplayName("默认：不剥离首尾空白 + 区分大小写（NOTRIM / CASE）")
    void defaultBehaviour() {
        LineEquality equality = new LineEquality(defaults());
        assertFalse(equality.equal("  Hello  ", "Hello"), "默认不剥离首尾空白");
        assertFalse(equality.equal("HELLO", "hello"), "默认区分大小写");
        assertFalse(equality.equal("Hello", "World"));

        CompareOptions lenient = defaults();
        lenient.setCaseSensitive(false);
        lenient.setIgnoreHeadTailBlank(true);
        assertTrue(new LineEquality(lenient).equal("  HELLO  ", "hello"), "NOCASE + TRIM 之后相等");
    }

    @Test
    @DisplayName("mask：参考行是正则，且必须**整行匹配**（fullmatch 语义）")
    void maskRequiresWholeLineMatch() {
        CompareOptions options = defaults();
        options.setMask(true);
        LineEquality equality = new LineEquality(options);

        assertTrue(equality.equal("id=\\d+ ts=\\d+", "id=2 ts=999"), "整行匹配应当相等");
        assertFalse(equality.equal("id=\\d+", "id=2 ts=999"), "只匹配前缀不算相等（必须整行）");
        assertFalse(equality.equal("ts=\\d+", "id=2 ts=999"), "不是从行首开始也不算（整行匹配隐含 ^）");
        // 默认 CASE（区分大小写）⇒ 正则不带 CASE_INSENSITIVE
        assertFalse(equality.equal("ID=\\d+", "id=7"), "默认区分大小写，正则也不忽略大小写");
        // 反方向不成立：工作行当正则、参考行当普通串时（mask 是单向的）
        assertFalse(equality.equal("id=2 ts=999", "id=\\d+ ts=\\d+"),
                "mask 单向：参考行才是正则，模式文本作为待匹配串不会被展开");
    }

    @Test
    @DisplayName("mask 关闭时正则不参与：文本不同就是不相等")
    void maskOffMeansLiteralComparison() {
        LineEquality equality = new LineEquality(defaults());
        assertFalse(equality.equal("id=\\d+", "id=2"), "没开 mask 时按字面比较");
    }

    @Test
    @DisplayName("mask 的非法正则 → 带参考行内容的可读异常（不静默判为不相等）")
    void invalidMaskPatternIsReported() {
        CompareOptions options = defaults();
        options.setMask(true);
        LineEquality equality = new LineEquality(options);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> equality.equal("id=[", "id=2"));
        assertTrue(error.getMessage().contains("invalid mask pattern"), "要有可读文案: " + error.getMessage());
        assertTrue(error.getMessage().contains("id=["), "文案里要带出问题所在的那一行: " + error.getMessage());
    }
}
