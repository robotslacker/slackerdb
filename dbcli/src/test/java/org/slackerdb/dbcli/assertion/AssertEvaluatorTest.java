package org.slackerdb.dbcli.assertion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code _ASSERT} 固定形态求值器的契约测试。
 *
 * <p>三类来源必须对齐：</p>
 * <ol>
 *   <li><b>状态文案</b>逐字节冻结：{@code Assert successful.} / {@code Assert [name] successful.} /
 *       {@code Assert fail.} / {@code Assert [name] fail.} / {@code Assert fail. SyntaxError =>[...]}；</li>
 *   <li><b>既有夹具</b>：{@code src/test/resources/scripts/sanity.sql} 里的 {@code _Assert {% 3==3 %}}
 *       与 {@code _Assert {% 1==2 %}} 必须分别得到 {@code Assert successful.} 与 {@code Assert fail.}
 *       （这正是"常量断言"分支存在的理由：既有脚本不用改写）；</li>
 *   <li><b>两种外观</b>（裸形态与 {@code {% %}} 外观）结果一致，且都能直接用 IR 取出的 expression 文本求值。</li>
 * </ol>
 */
class AssertEvaluatorTest {

    @Test
    @DisplayName("引号里的比较符不是比较符：`'a=b' = 'c'` 是 false，不是语法错")
    void comparatorInsideQuotedLiteralIsNotTheComparator() {
        assertEquals("Assert fail.",
                AssertEvaluator.evaluate("{% 'a=b' = 'c' %}", model(), null).status());
        // 引号外真的是等号时照旧通过
        assertEquals("Assert successful.",
                AssertEvaluator.evaluate("{% 'a=b' = 'a=b' %}", model(), null).status());
    }

    private static ResultModel model() {
        return new ResultModel(
                0,
                2,
                "2 rows selected.",
                List.of(List.of(1, "alpha"), List.of(2, "beta")),
                List.of("ID", "NAME"),
                List.of("INTEGER", "VARCHAR"),
                Map.of("MYVAR", "hello"));
    }

    @Test
    @DisplayName("右侧永远是字面量：裸词按字符串比较，不再被当成取值器")
    void rightHandSideIsAlwaysALiteral() {
        // 文档：左侧是取值器（或字面量），右侧是字面量
        assertEquals("Assert successful.",
                AssertEvaluator.evaluate("RESULT.HEADERS[0] = ID", model(), null).status());
        assertEquals("Assert successful.",
                AssertEvaluator.evaluate("RESULT.STATUS = 2 rows selected.", model(), null).status());
        assertEquals("Assert successful.",
                AssertEvaluator.evaluate("RESULT.ROWS[0][1] = alpha", model(), null).status());
        assertEquals("Assert fail.",
                AssertEvaluator.evaluate("RESULT.HEADERS[0] = NOPE", model(), null).status());
        // 数字仍然是数字（不是字符串 "2"）
        assertEquals("Assert successful.",
                AssertEvaluator.evaluate("RESULT.ROWCOUNT = 2", model(), null).status());
    }

    @Test
    @DisplayName("sanity 夹具的两个常量断言：{% 3==3 %} 成功、{% 1==2 %} 失败")
    void testsqlSanityConstants() {
        assertEquals("Assert successful.", AssertEvaluator.evaluate("{% 3==3 %}", model(), null).status());
        assertEquals("Assert fail.", AssertEvaluator.evaluate("{% 1==2 %}", model(), null).status());
    }

    @Test
    @DisplayName("两种外观结果一致（裸形态 / {% %} 外观）")
    void bothAppearancesAgree() {
        AssertEvaluator.Verdict bare = AssertEvaluator.evaluate("RESULT.ERRORCODE = 0", model(), null);
        AssertEvaluator.Verdict braced = AssertEvaluator.evaluate("{% RESULT.ERRORCODE = 0 %}", model(), null);
        assertEquals(bare, braced, "两种外观必须等价");
        assertEquals("Assert successful.", bare.status());
    }

    @Test
    @DisplayName("取值器：ERRORCODE / ROWCOUNT / STATUS / ROWS[r][c]（0 基）/ ROWS.COUNT / HEADERS[i]")
    void selectors() {
        assertTrue(AssertEvaluator.evaluate("RESULT.ERRORCODE = 0", model(), null).passed());
        assertFalse(AssertEvaluator.evaluate("RESULT.ERRORCODE = 1", model(), null).passed());
        assertTrue(AssertEvaluator.evaluate("RESULT.ROWCOUNT >= 2", model(), null).passed());
        assertTrue(AssertEvaluator.evaluate("RESULT.STATUS = '2 rows selected.'", model(), null).passed());
        assertTrue(AssertEvaluator.evaluate("RESULT.ROWS[0][0] = 1", model(), null).passed(), "0 基：第 1 行第 1 列");
        assertTrue(AssertEvaluator.evaluate("RESULT.ROWS[1][1] = 'beta'", model(), null).passed());
        assertTrue(AssertEvaluator.evaluate("RESULT.ROWS.COUNT = 2", model(), null).passed());
        assertTrue(AssertEvaluator.evaluate("RESULT.HEADERS[0] = 'ID'", model(), null).passed());
        assertTrue(AssertEvaluator.evaluate("RESULT.COLUMNS[1] = 'VARCHAR'", model(), null).passed());
        assertTrue(AssertEvaluator.evaluate("VAR.MYVAR = 'hello'", model(), null).passed());
        // 数字比较按数值而非字符串（避免 10 < 9 这种字典序陷阱）
        ResultModel ten = new ResultModel(0, 1, null, List.of(List.of(10)), List.of("A"), List.of("INTEGER"), Map.of());
        assertTrue(AssertEvaluator.evaluate("RESULT.ROWS[0][0] > 9", ten, null).passed());
    }

    @Test
    @DisplayName("比较符：<> / < / <= / CONTAINS / MATCHES（整串正则）/ EXISTS / NULL")
    void comparators() {
        assertTrue(AssertEvaluator.evaluate("RESULT.ROWS[0][1] <> 'beta'", model(), null).passed());
        assertTrue(AssertEvaluator.evaluate("RESULT.ROWS.COUNT < 3", model(), null).passed());
        assertTrue(AssertEvaluator.evaluate("RESULT.ROWS.COUNT <= 2", model(), null).passed());
        assertTrue(AssertEvaluator.evaluate("RESULT.ROWS[0][1] CONTAINS 'lph'", model(), null).passed());
        assertTrue(AssertEvaluator.evaluate("RESULT.ROWS[0][1] NOTCONTAINS 'zzz'", model(), null).passed());
        assertTrue(AssertEvaluator.evaluate("RESULT.ROWS[0][1] MATCHES 'a.*a'", model(), null).passed());
        assertTrue(AssertEvaluator.evaluate("RESULT.STATUS EXISTS", model(), null).passed());
        assertFalse(AssertEvaluator.evaluate("RESULT.ERRORCODE = NULL", model(), null).passed());
        ResultModel withNull = new ResultModel(0, 1, null, List.of(java.util.Arrays.asList((Object) null)),
                List.of("A"), List.of("VARCHAR"), Map.of());
        assertTrue(AssertEvaluator.evaluate("RESULT.ROWS[0][0] = NULL", withNull, null).passed(), "= NULL 用来判空");
    }

    @Test
    @DisplayName("断言名进状态文案：Assert [name] successful. / Assert [name] fail.")
    void assertNameInStatus() {
        assertEquals("Assert [错误码检查] successful.",
                AssertEvaluator.evaluate("RESULT.ERRORCODE = 0", model(), "错误码检查").status());
        assertEquals("Assert [错误码检查] fail.",
                AssertEvaluator.evaluate("RESULT.ERRORCODE = 9", model(), "错误码检查").status());
    }

    @Test
    @DisplayName("非法断言 → Assert fail. SyntaxError =>[...]（且不抛异常）")
    void syntaxErrorsAreReportedAsStatus() {
        // 未知取值器：必须报错，不能静默当成 null
        assertTrue(AssertEvaluator.evaluate("RESULT.NOSUCH = 1", model(), null).status()
                .startsWith("Assert fail. SyntaxError =>["), "未知取值器要报语法错");
        // 缺右值
        assertTrue(AssertEvaluator.evaluate("RESULT.ERRORCODE =", model(), null).status()
                .startsWith("Assert fail. SyntaxError =>["));
        // 行号越界（0 基）
        assertTrue(AssertEvaluator.evaluate("RESULT.ROWS[5][0] = 1", model(), null).status()
                .startsWith("Assert fail. SyntaxError =>["));
        // 正则非法
        assertTrue(AssertEvaluator.evaluate("RESULT.STATUS MATCHES '['", model(), null).status()
                .startsWith("Assert fail. SyntaxError =>["));
        // 空断言
        assertTrue(AssertEvaluator.evaluate("   ", model(), null).status()
                .startsWith("Assert fail. SyntaxError =>["));
    }

    @Test
    @DisplayName("与 IR 串起来：从 _ASSERT 语句的 IR expression 直接求值")
    void worksWithIrExpression() {
        // 裸形态
        org.slackerdb.dbcli.script.SqlAnalyzer.Result bare =
                org.slackerdb.dbcli.script.SqlAnalyzer.analyze("_ASSERT RESULT.ROWS[0][0] = 1, 首行首列");
        assertEquals("ASSERT", bare.parsedObject().get("name"));
        assertEquals("Assert [首行首列] successful.",
                AssertEvaluator.evaluate((String) bare.parsedObject().get("expression"), model(),
                        (String) bare.parsedObject().get("assertName")).status());

        // {% %} 外观
        org.slackerdb.dbcli.script.SqlAnalyzer.Result braced =
                org.slackerdb.dbcli.script.SqlAnalyzer.analyze("_ASSERT {% 3==3 %}");
        assertEquals("Assert successful.",
                AssertEvaluator.evaluate((String) braced.parsedObject().get("expression"), model(),
                        (String) braced.parsedObject().get("assertName")).status());
    }
}
