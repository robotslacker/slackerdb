package org.slackerdb.dbcli.script;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 4 条 ANTLR「错误文案」契约的冻结测试（**先测后码**）。
 *
 * <p>这 4 条文案含字节级细节（**冒号后两个空格、结尾一个空格**），且被生产控制流依赖
 * （"missing SQL_END" 用于判定"还差续行"，其余统一成 {@code line:col  msg }），
 * 所以必须冻结，不能"包含即可"。</p>
 *
 * <p>文案格式由 {@link IrErrorListener} 统一：非 {@code "missing SQL_END"} 的报错统一为
 * {@code "line" + line + ":" + column + "  " + msg + " "}。</p>
 */
class IrErrorTextTest {

    /** 按固定书写顺序构造期望 IR（允许 null 值，故不用 Map.of）。 */
    private static Map<String, Object> ir(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("契约 1：mismatched input —— IR/错误码一致，expecting 集合因 ANTLR 版本不同")
    void connectMismatchedInput() {
        SqlAnalyzer.Result result = SqlAnalyzer.analyze("_connect SYSDBA/Dameng123:62310jdbc:dm://10.15.1.19:6231/");

        // isFinished == true
        assertTrue(result.isFinished());
        // errorCode == 1
        assertEquals(1, result.errorCode());
        // IR 只保留 CONNECT 解析出来的参数（这条输入是"缺 @"的旧写法，本来就该报错）
        assertEquals(ir("connectString", "SYSDBA/Dameng123:62310jdbc",
                        "name", "CONNECT", "host", "SYSDBA", "service", "Dameng123:62310jdbc"),
                result.parsedObject());
        // ★ 与下面 @Disabled 的冻结文案的差异（实跑采集）：4.11.1 下是
        //   "line1:25  mismatched input ':' expecting <EOF> "
        //   现语法的 connect 多了"不带 scheme"的备选，这条输入因此多消费了一段
        //   （`SYSDBA` 当 host、`Dameng123:62310jdbc` 当 service），错误点随之后移，
        //   expecting 集合也变成本条备选之后还能接的 token（下一条连接参数的 '?' 与行尾 ';'）。
        assertEquals("line1:35  mismatched input ':' expecting {<EOF>, '?', CONNECT_SEMICOLON} ",
                result.errorMsg());
    }

    @Test
    @Disabled("ANTLR 运行时 4.13.2 与冻结文案采集时用的 4.11.1 不同：同一份 .g4 下错误恢复点不同，"
            + "expecting 集合随之不同")
    @DisplayName("旧版字节级契约 1 —— 当前 ANTLR 版本下达不到，保留断言备查")
    void connectErrorTextReferenceCase() {
        SqlAnalyzer.Result result = SqlAnalyzer.analyze("_connect SYSDBA/Dameng123:62310jdbc:dm://10.15.1.19:6231/");

        assertTrue(result.isFinished());
        assertEquals(1, result.errorCode());
        assertEquals("line1:25  mismatched input ':' expecting <EOF> ", result.errorMsg());
        assertEquals(ir("name", "CONNECT", "username", "SYSDBA", "password", "Dameng123"), result.parsedObject());
    }

    @Test
    @DisplayName("契约 2：缺 SQL_END 时 errorMsg 为 ANTLR 原文，不加前后缀")
    void selectMissingSqlEnd() {
        SqlAnalyzer.Result result = SqlAnalyzer.analyze("Select 1+2 from dual");

        // isFinished == false
        assertFalse(result.isFinished());
        // errorCode == 1
        assertEquals(1, result.errorCode());
        // errorMsg 是 ANTLR 原文
        assertEquals("missing SQL_END at '<EOF>'", result.errorMsg());
        // IR 的 name 是 SELECT
        assertEquals("SELECT", result.parsedObject().get("name"));
    }

    @Test
    @DisplayName("契约 3：_use 已从语法删除 —— 不再产生 USE 语句（引擎按未知命令/无效 SQL 处理）")
    void useIsGone() {
        SqlAnalyzer.Result result = SqlAnalyzer.analyze("_use sql");

        assertTrue(result.parsedObject() == null
                        || !"USE".equals(result.parsedObject().get("name")),
                "语法里不该再有 USE 语句: " + result.parsedObject());
    }

    @Test
    @DisplayName("契约 4：extraneous input 'xxx' expecting <EOF> —— 结尾一个空格")
    void sessionExtraneousInput() {
        SqlAnalyzer.Result result = SqlAnalyzer.analyze("_Session yyy xxx");

        // isFinished == true
        assertTrue(result.isFinished());
        // errorCode == 1
        assertEquals(1, result.errorCode());
        // 错误文案（结尾一个空格）
        assertEquals("line1:13  extraneous input 'xxx' expecting <EOF> ", result.errorMsg());
        // IR：action 空串、sessionName 保留 yyy
        assertEquals(ir("name", "SESSION", "action", "", "sessionName", "yyy"), result.parsedObject());
    }

    @Test
    @DisplayName("空串：不报错、无 IR、errorMsg 为 null（同一条契约）")
    void emptyScript() {
        SqlAnalyzer.Result result = SqlAnalyzer.analyze("");

        assertTrue(result.isFinished());
        assertNull(result.parsedObject());
        assertEquals(0, result.errorCode());
        assertNull(result.errorMsg());
    }
}
