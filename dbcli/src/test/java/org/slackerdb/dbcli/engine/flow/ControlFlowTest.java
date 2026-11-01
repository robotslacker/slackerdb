package org.slackerdb.dbcli.engine.flow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbcli.assertion.ResultModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 控制流核心的 hermetic 测试：捕获/建树/执行/错误恢复/上限。 */
class ControlFlowTest {

    @Test
    @DisplayName("IF 真/假/ELSEIF/ELSE：只执行命中的分支")
    void ifBranches() {
        FakeHost host = new FakeHost();
        ControlFlow flow = new ControlFlow(host);

        flow.accept("_IF {% TRUE %}", ir("name", "IF", "expression", " TRUE "), null, false);
        flow.appendStatement("taken");
        flow.accept("_ELSEIF {% TRUE %}", ir("name", "ELSEIF", "expression", " TRUE "), null, false);
        flow.appendStatement("not-taken");
        flow.accept("_ELSE", ir("name", "ELSE"), null, false);
        flow.appendStatement("not-taken-else");
        flow.accept("_ENDIF", ir("name", "ENDIF"), null, false);

        assertEquals(List.of("taken"), host.executed);
        assertTrue(host.errors.isEmpty(), host.errors.toString());
    }

    @Test
    @DisplayName("IF 条件为假且无 ELSE：body 不执行，后续语句继续")
    void falseIfWithoutElse() {
        FakeHost host = new FakeHost();
        ControlFlow flow = new ControlFlow(host);

        flow.accept("_IF {% FALSE %}", ir("name", "IF", "expression", " FALSE "), null, false);
        flow.appendStatement("never");
        flow.accept("_ENDIF", ir("name", "ENDIF"), null, false);
        flow.appendStatement("after");

        // 根块关闭后执行；after 在 ControlFlow 外由引擎正常执行，这里只验证块内
        assertEquals(List.of(), host.executed);
        assertTrue(host.errors.isEmpty(), host.errors.toString());
    }

    @Test
    @DisplayName("_LOOP n：每轮一条进度行，body 执行 n 次")
    void countLoop() {
        FakeHost host = new FakeHost();
        ControlFlow flow = new ControlFlow(host);

        flow.accept("_LOOP 2", ir("name", "LOOP", "action", "BLOCK", "mode", "COUNT", "count", 2), null, false);
        flow.appendStatement("body");
        flow.accept("_ENDLOOP", ir("name", "ENDLOOP"), null, false);

        assertEquals(List.of("body", "body"), host.executed);
        assertEquals(List.of("Loop iteration 1 of 2.", "Loop iteration 2 of 2."), host.statuses);
    }

    @Test
    @DisplayName("_LOOP 0 / WHILE 首轮为假：只有一条 0 次提示")
    void zeroIterations() {
        FakeHost zero = new FakeHost();
        ControlFlow zeroFlow = new ControlFlow(zero);
        zeroFlow.accept("_LOOP 0", ir("name", "LOOP", "action", "BLOCK", "mode", "COUNT", "count", 0), null, false);
        zeroFlow.appendStatement("never");
        zeroFlow.accept("_ENDLOOP", ir("name", "ENDLOOP"), null, false);
        assertEquals(List.of(), zero.executed);
        assertEquals(List.of("Loop did not execute (0 iterations)."), zero.statuses);

        FakeHost host = new FakeHost();
        ControlFlow flow = new ControlFlow(host);
        flow.accept("_LOOP WHILE {% FALSE %}", ir("name", "LOOP", "action", "BLOCK", "mode", "WHILE",
                "expression", " FALSE "), null, false);
        flow.appendStatement("never");
        flow.accept("_ENDLOOP", ir("name", "ENDLOOP"), null, false);
        assertEquals(List.of(), host.executed);
        assertEquals(List.of("Loop did not execute (0 iterations)."), host.statuses);
    }

    @Test
    @DisplayName("_LOOP UNTIL：至少执行一次，条件为真后结束")
    void untilLoop() {
        FakeHost host = new FakeHost();
        ControlFlow flow = new ControlFlow(host);

        flow.accept("_LOOP UNTIL {% TRUE %}", ir("name", "LOOP", "action", "BLOCK", "mode", "UNTIL",
                "expression", " TRUE "), null, false);
        flow.appendStatement("body");
        flow.accept("_ENDLOOP", ir("name", "ENDLOOP"), null, false);

        assertEquals(List.of("body"), host.executed);
        assertEquals(List.of("Loop iteration 1."), host.statuses);
    }

    @Test
    @DisplayName("BREAK / CONTINUE：只影响最内层循环")
    void breakAndContinue() {
        FakeHost host = new FakeHost();
        ControlFlow flow = new ControlFlow(host);

        flow.accept("_LOOP 3", ir("name", "LOOP", "action", "BLOCK", "mode", "COUNT", "count", 3), null, false);
        flow.appendStatement("before");
        flow.accept("_IF {% TRUE %}", ir("name", "IF", "expression", " TRUE "), null, false);
        flow.accept("_LOOP CONTINUE", ir("name", "LOOP", "action", "CONTINUE"), null, false);
        flow.accept("_ENDIF", ir("name", "ENDIF"), null, false);
        flow.appendStatement("after");
        flow.accept("_ENDLOOP", ir("name", "ENDLOOP"), null, false);

        assertEquals(List.of("before", "before", "before"), host.executed);
        assertEquals(3, host.statuses.size());
    }

    @Test
    @DisplayName("BREAK 跳出循环，循环后的节点不在这里执行")
    void breakStopsLoop() {
        FakeHost host = new FakeHost();
        ControlFlow flow = new ControlFlow(host);

        flow.accept("_LOOP 5", ir("name", "LOOP", "action", "BLOCK", "mode", "COUNT", "count", 5), null, false);
        flow.appendStatement("before");
        flow.accept("_LOOP BREAK", ir("name", "LOOP", "action", "BREAK"), null, false);
        flow.appendStatement("after");
        flow.accept("_ENDLOOP", ir("name", "ENDLOOP"), null, false);

        assertEquals(List.of("before"), host.executed);
        assertEquals(List.of("Loop iteration 1 of 5."), host.statuses);
    }

    @Test
    @DisplayName("嵌套循环：depth 标记")
    void nestedDepth() {
        FakeHost host = new FakeHost();
        ControlFlow flow = new ControlFlow(host);

        flow.accept("_LOOP 1", ir("name", "LOOP", "action", "BLOCK", "mode", "COUNT", "count", 1), null, false);
        flow.accept("_LOOP 1", ir("name", "LOOP", "action", "BLOCK", "mode", "COUNT", "count", 1), null, false);
        flow.appendStatement("inner");
        flow.accept("_ENDLOOP", ir("name", "ENDLOOP"), null, false);
        flow.accept("_ENDLOOP", ir("name", "ENDLOOP"), null, false);

        assertEquals(List.of("inner"), host.executed);
        assertEquals(List.of("Loop iteration 1 of 1.", "Loop iteration 1 of 1 (depth 2)."), host.statuses);
    }

    @Test
    @DisplayName("条件错误：报错、放弃构造、继续脚本")
    void conditionErrorAbortsConstruct() {
        FakeHost host = new FakeHost();
        ControlFlow flow = new ControlFlow(host);

        flow.accept("_IF {% RESULT.NOPE = 1 %}", ir("name", "IF", "expression", " RESULT.NOPE = 1 "), null, false);
        flow.appendStatement("never");
        flow.accept("_ELSE", ir("name", "ELSE"), null, false);
        flow.appendStatement("also-never");
        flow.accept("_ENDIF", ir("name", "ENDIF"), null, false);

        assertEquals(List.of(), host.executed);
        assertEquals(1, host.errors.size());
        assertTrue(host.errors.get(0).contains("If condition error"), host.errors.toString());
    }

    @Test
    @DisplayName("固定次数超过上限：读取时报错并丢弃 body")
    void countOverLimit() {
        FakeHost host = new FakeHost();
        ControlFlow flow = new ControlFlow(host, new FlowLimits(8, 2));

        flow.accept("_LOOP 3", ir("name", "LOOP", "action", "BLOCK", "mode", "COUNT", "count", 3), null, false);
        flow.appendStatement("never");
        flow.accept("_ENDLOOP", ir("name", "ENDLOOP"), null, false);

        assertEquals(List.of(), host.executed);
        assertEquals(1, host.errors.size(), host.errors.toString());
        assertTrue(host.errors.get(0).contains("exceeds the limit"), host.errors.toString());
    }

    @Test
    @DisplayName("循环轮数上限：报错并停止")
    void iterationLimit() {
        FakeHost host = new FakeHost();
        ControlFlow flow = new ControlFlow(host, new FlowLimits(8, 2));

        flow.accept("_LOOP", ir("name", "LOOP", "action", "BLOCK", "mode", "FOREVER"), null, false);
        flow.appendStatement("body");
        flow.accept("_ENDLOOP", ir("name", "ENDLOOP"), null, false);

        assertEquals(List.of("body", "body"), host.executed);
        assertEquals(2, host.statuses.size());
        assertTrue(host.errors.get(0).contains("iteration limit"), host.errors.toString());
    }

    @Test
    @DisplayName("嵌套深度上限 / 未关闭块 / 未配对标记")
    void structureErrors() {
        FakeHost host = new FakeHost();
        ControlFlow flow = new ControlFlow(host, new FlowLimits(1, 10));
        flow.accept("_IF {% TRUE %}", ir("name", "IF", "expression", " TRUE "), null, false);
        flow.accept("_IF {% TRUE %}", ir("name", "IF", "expression", " TRUE "), null, false);
        flow.accept("_ENDIF", ir("name", "ENDIF"), null, false);
        flow.accept("_ENDIF", ir("name", "ENDIF"), null, false);
        assertTrue(host.errors.get(0).contains("nesting is too deep"), host.errors.toString());

        FakeHost unclosed = new FakeHost();
        ControlFlow open = new ControlFlow(unclosed);
        open.accept("_IF {% TRUE %}", ir("name", "IF", "expression", " TRUE "), null, false);
        open.finish();
        assertTrue(unclosed.errors.get(0).contains("requires _ENDIF"), unclosed.errors.toString());

        FakeHost unmatched = new FakeHost();
        ControlFlow stray = new ControlFlow(unmatched);
        stray.accept("_ENDIF", ir("name", "ENDIF"), null, false);
        stray.accept("_ELSE", ir("name", "ELSE"), null, false);
        stray.accept("_ENDLOOP", ir("name", "ENDLOOP"), null, false);
        assertEquals(3, unmatched.errors.size(), unmatched.errors.toString());
    }

    @Test
    @DisplayName("根 IF 里的 BREAK 被报为 outside a loop")
    void breakOutsideLoop() {
        FakeHost host = new FakeHost();
        ControlFlow flow = new ControlFlow(host);

        flow.accept("_IF {% TRUE %}", ir("name", "IF", "expression", " TRUE "), null, false);
        flow.accept("_LOOP BREAK", ir("name", "LOOP", "action", "BREAK"), null, false);
        flow.accept("_ENDIF", ir("name", "ENDIF"), null, false);

        assertEquals(List.of(), host.executed);
        assertEquals(1, host.errors.size());
        assertTrue(host.errors.get(0).contains("outside a loop"), host.errors.toString());
    }

    private static Map<String, Object> ir(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static final class FakeHost implements FlowHost {
        private final List<String> executed = new ArrayList<>();
        private final List<String> statuses = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private boolean exit;

        @Override
        public ResultModel conditionModel() {
            return ResultModel.empty();
        }

        @Override
        public String resolveText(String text) {
            return text;
        }

        @Override
        public void executeLeaf(String rawText) {
            executed.add(rawText);
        }

        @Override
        public void reportError(String message) {
            errors.add(message);
        }

        @Override
        public boolean exitRequested() {
            return exit;
        }

        @Override
        public void checkStopRequested() {
        }

        @Override
        public void printStatus(String line) {
            statuses.add(line);
        }
    }
}
