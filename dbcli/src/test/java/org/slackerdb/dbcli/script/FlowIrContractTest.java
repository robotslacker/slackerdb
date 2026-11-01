package org.slackerdb.dbcli.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 控制流的 IR 契约：字段名/值/可读错误逐条冻结。 */
class FlowIrContractTest {

    private static Map<String, Object> ir(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static SqlAnalyzer.Result analyze(String text) {
        return SqlAnalyzer.analyze(text);
    }

    @Test
    @DisplayName("_IF / _ELSEIF / _ELSE / _ENDIF 的 IR")
    void ifIr() {
        SqlAnalyzer.Result first = analyze("_IF {% 3 = 3 %}");
        assertTrue(first.isFinished());
        assertEquals(0, first.errorCode());
        assertEquals(ir("name", "IF", "expression", " 3 = 3 "), first.parsedObject());

        SqlAnalyzer.Result elseIf = analyze("_ELSEIF {% VAR.x > 1 %}");
        assertEquals(ir("name", "ELSEIF", "expression", " VAR.x > 1 "), elseIf.parsedObject());

        assertEquals(ir("name", "ELSE"), analyze("_ELSE").parsedObject());
        assertEquals(ir("name", "ENDIF"), analyze("_ENDIF").parsedObject());
    }

    @Test
    @DisplayName("_IF 裸条件给可读错误，不报解析器位置文案")
    void bareConditionIsReadableError() {
        SqlAnalyzer.Result result = analyze("_IF 3 = 3");
        assertEquals(1, result.errorCode());
        assertFalse(result.parserError(), "这是访问器的可读错误，不是解析层错误");
        assertTrue(result.errorMsg().contains("_IF condition must be written as"), result.errorMsg());
        assertEquals("IF", result.parsedObject().get("name"));
    }

    @Test
    @DisplayName("_LOOP 各形态的 IR")
    void loopIr() {
        assertEquals(ir("name", "LOOP", "action", "BLOCK", "mode", "FOREVER"),
                analyze("_LOOP").parsedObject());
        assertEquals(ir("name", "LOOP", "action", "BLOCK", "mode", "FOREVER"),
                analyze("_LOOP FOREVER").parsedObject());
        assertEquals(ir("name", "LOOP", "action", "BLOCK", "mode", "COUNT", "count", 3),
                analyze("_LOOP 3").parsedObject());
        assertEquals(ir("name", "LOOP", "action", "BLOCK", "mode", "WHILE", "expression", " VAR.i < 3 "),
                analyze("_LOOP WHILE {% VAR.i < 3 %}").parsedObject());
        assertEquals(ir("name", "LOOP", "action", "BLOCK", "mode", "UNTIL", "expression", " RESULT.ROWCOUNT = 0 "),
                analyze("_LOOP UNTIL {% RESULT.ROWCOUNT = 0 %}").parsedObject());
        assertEquals(ir("name", "LOOP", "action", "BREAK"), analyze("_LOOP BREAK").parsedObject());
        assertEquals(ir("name", "LOOP", "action", "CONTINUE"), analyze("_LOOP CONTINUE").parsedObject());
        assertEquals(ir("name", "ENDLOOP"), analyze("_ENDLOOP").parsedObject());
    }

    @Test
    @DisplayName("_LOOP WHILE/UNTIL 缺 {% %}、计数越界：可读错误")
    void loopHeaderErrors() {
        SqlAnalyzer.Result missing = analyze("_LOOP WHILE");
        assertEquals(1, missing.errorCode());
        assertTrue(missing.errorMsg().contains("_LOOP WHILE requires"), missing.errorMsg());

        SqlAnalyzer.Result tooLarge = analyze("_LOOP 99999999999");
        assertEquals(1, tooLarge.errorCode());
        assertNotNull(tooLarge.errorMsg());
        assertTrue(tooLarge.errorMsg().contains("loop count"), tooLarge.errorMsg());
    }
}
