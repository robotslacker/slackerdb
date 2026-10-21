package org.slackerdb.dbcli.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * COMMIT / ROLLBACK 的 IR 契约（**dbcli 自己的契约**）。
 *
 * <p>为什么单独一个测试类：{@code SQL_COMMIT}/{@code SQL_ROLLBACK} 只有词法 token、
 * <b>没有对应语法规则</b>（{@code COMMIT;} 同样解析失败），这两条不在 {@link SqlIrContractTest}
 * 的契约清单里。这里定义 dbcli 自己的契约：形状与其它 SQL 语句同构 —— {@code {name, statement}}，
 * 其中 statement 是"关键字到终结符之前的原样文本"（终结符 {@code ;} 与行首 {@code /} 都不计入）。</p>
 */
class SqlTransactionIrTest {

    private static Map<String, Object> ir(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static void assertAnalyze(String text, boolean isFinished, Map<String, Object> parsedObject,
                                      int errorCode, String errorMsg) {
        SqlAnalyzer.Result result = SqlAnalyzer.analyze(text);
        String where = "输入 >>>" + text + "<<<";
        assertEquals(isFinished, result.isFinished(), "isFinished 不一致：" + where);
        assertEquals(parsedObject, result.parsedObject(), "IR 不一致：" + where);
        assertEquals(errorCode, result.errorCode(), "errorCode 不一致：" + where);
        assertEquals(errorMsg, result.errorMsg(), "errorMsg 不一致：" + where);
    }

    @Test
    @DisplayName("COMMIT; 解析为 {name=COMMIT, statement=COMMIT}")
    void commitWithSemicolon() {
        assertAnalyze("COMMIT;", true, ir("name", "COMMIT", "statement", "COMMIT"), 0, null);
    }

    @Test
    @DisplayName("ROLLBACK; 解析为 {name=ROLLBACK, statement=ROLLBACK}")
    void rollbackWithSemicolon() {
        assertAnalyze("ROLLBACK;", true, ir("name", "ROLLBACK", "statement", "ROLLBACK"), 0, null);
    }

    @Test
    @DisplayName("大小写不敏感（词法是 caseInsensitive），statement 保留原文大小写")
    void caseInsensitiveKeyword() {
        assertAnalyze("commit;", true, ir("name", "COMMIT", "statement", "commit"), 0, null);
        assertAnalyze("Rollback;", true, ir("name", "ROLLBACK", "statement", "Rollback"), 0, null);
    }

    @Test
    @DisplayName("行首 / 也可作为终结符（与其它 SQL 语句一致）")
    void slashTerminator() {
        assertAnalyze("COMMIT\n/", true, ir("name", "COMMIT", "statement", "COMMIT"), 0, null);
    }

    @Test
    @DisplayName("缺终结符 → isFinished=false 且文案是 missing SQL_END 契约")
    void missingTerminator() {
        SqlAnalyzer.Result result = SqlAnalyzer.analyze("COMMIT");
        assertEquals(false, result.isFinished(), "缺终结符应判为未结束");
        assertEquals(1, result.errorCode(), "缺终结符应报错");
        assertEquals("missing SQL_END at '<EOF>'", result.errorMsg(), "文案必须是 SQL_END 契约");
        assertEquals("COMMIT", result.parsedObject().get("name"), "仍应给出部分 IR");
    }
}
