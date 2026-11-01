package org.slackerdb.dbcli.assertion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 控制流条件的布尔求值：与 `_ASSERT` 共用固定形态，额外支持 TRUE/FALSE。 */
class ConditionEvaluatorTest {

    private static ResultModel model() {
        return new ResultModel(
                0,
                2,
                "2 rows selected.",
                List.of(List.of(1, "alpha"), List.of(2, "beta")),
                List.of("ID", "NAME"),
                List.of("INTEGER", "VARCHAR"),
                Map.of("DONE", "1"));
    }

    @Test
    @DisplayName("TRUE / FALSE 常量（带不带 {% %} 都行）")
    void constants() {
        assertTrue(ConditionEvaluator.evaluate("TRUE", model()).value());
        assertTrue(ConditionEvaluator.evaluate("{% TRUE %}", model()).value());
        assertFalse(ConditionEvaluator.evaluate("FALSE", model()).value());
        assertFalse(ConditionEvaluator.evaluate("{% false %}", model()).value());
    }

    @Test
    @DisplayName("与 _ASSERT 相同的取值器/比较符")
    void sharedExpressions() {
        assertTrue(ConditionEvaluator.evaluate("{% RESULT.ERRORCODE = 0 %}", model()).value());
        assertTrue(ConditionEvaluator.evaluate("RESULT.ROWS[1][1] = beta", model()).value());
        assertTrue(ConditionEvaluator.evaluate("VAR.DONE = 1", model()).value());
        assertFalse(ConditionEvaluator.evaluate("RESULT.ROWCOUNT > 2", model()).value());
    }

    @Test
    @DisplayName("空条件 / 未知取值器：error 非空，不抛异常")
    void errorsAreReturned() {
        ConditionEvaluator.Condition empty = ConditionEvaluator.evaluate("", model());
        assertFalse(empty.ok());
        assertEquals("empty condition", empty.error());

        ConditionEvaluator.Condition unknown = ConditionEvaluator.evaluate("RESULT.NOPE = 1", model());
        assertFalse(unknown.ok());
        assertTrue(unknown.error().contains("unknown selector"), unknown.error());
    }
}
