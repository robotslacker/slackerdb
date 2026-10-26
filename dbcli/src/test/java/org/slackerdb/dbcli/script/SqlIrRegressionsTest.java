package org.slackerdb.dbcli.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：审计中确认过的 IR 层缺陷（每条都对应一个曾经的真实误判）。
 *
 * <p>与 {@link SqlIrContractTest} 的分工：那边冻结既有契约（防止行为漂移），这边只断言"某个输入不再
 * 被误判"，因此用最小断言、不复制整张 IR 表。</p>
 */
class SqlIrRegressionsTest {

    @Test
    @DisplayName("_ssh execute 的正文里出现落单撇号是合法的（\"echo don't\" 不再被判成未闭合）")
    void sshExecuteAcceptsApostropheInsideCommand() {
        SqlAnalyzer.Result result = SqlAnalyzer.analyze("_ssh execute \"echo don't\"");

        assertTrue(result.isFinished(), "带撇号的 shell 命令应当是一条完整语句");
        assertFalse(result.incompleteInput(), "不该被判成'还没写完'（否则控制台永远等续行）");
        assertEquals(0, result.errorCode(), result.errorMsg());
        assertEquals("execute", result.parsedObject().get("action"));
        assertEquals("echo don't", result.parsedObject().get("command"),
                "命令正文要完整保留");
    }

    @Test
    @DisplayName("_connect 的连接串原文保留空白（错误回显才与使用者写的一致）")
    void connectStringKeepsWhitespace() {
        SqlAnalyzer.Result result = SqlAnalyzer.analyze("_connect dbhost : 5432 / db1");

        assertEquals(0, result.errorCode(), result.errorMsg());
        Map<String, Object> ir = result.parsedObject();
        assertEquals("dbhost", ir.get("host"));
        assertEquals("5432", ir.get("port"));
        assertEquals("db1", ir.get("service"));
        assertEquals("dbhost : 5432 / db1", ir.get("connectString"),
                "原样回显：隐藏通道的空白不能被 getText() 吞掉");
    }

    @Test
    @DisplayName("_compare set algorithm auto 与 lcs/myers/difflib 产出同样形状的 IR（都要被分派器拒绝）")
    void compareAlgorithmAutoIsNotSilentlyDropped() {
        for (String algorithm : List.of("auto", "lcs", "myers", "difflib")) {
            SqlAnalyzer.Result result = SqlAnalyzer.analyze("_compare set algorithm " + algorithm);

            assertEquals(0, result.errorCode(), algorithm + " 应当能解析：" + result.errorMsg());
            @SuppressWarnings("unchecked")
            Map<String, Object> options = (Map<String, Object>) result.parsedObject().get("compareOptions");
            assertNotNull(options, algorithm + " 应当产出 compareOptions");
            assertEquals(algorithm, options.get("algorithm"),
                    algorithm + " 必须进 IR：漏掉会让 SET 变成'什么也没改'");
        }
    }
}
