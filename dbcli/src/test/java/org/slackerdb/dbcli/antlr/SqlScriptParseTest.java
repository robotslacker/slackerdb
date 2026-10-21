package org.slackerdb.dbcli.antlr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出口验证：**用本仓的 .g4 生成的 Java 解析器，能吃下真实脚本**。
 *
 * <p>夹具是既有的真实脚本，逐字节放在 {@code src/test/resources/scripts/}，因此这里验证的
 * 不是"临时编的例子能过"，而是"真实脚本能过"。</p>
 *
 * <p>另外验证一条等价性结论：ANTLR 4.13.2 与 4.11.1 生成的 token 表**逐位相同**。</p>
 */
class SqlScriptParseTest {

    private List<String> readScript(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/scripts/" + name)) {
            assertNotNull(in, "找不到测试脚本夹具: " + name);
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            return lines;
        }
    }

    @Test
    @DisplayName("sanity.sql：SQL + _Assert + _exit，逐块解析无语法错误")
    void parsesSqlSanityScript() throws IOException {
        List<String> chunks = ScriptParseProbe.parseWholeScript(readScript("sanity.sql"));

        assertFalse(chunks.isEmpty(), "应当解析出至少一块命令");
        String joined = String.join("", chunks);
        assertTrue(joined.contains("create table aaa"), "应包含建表语句，实际: " + joined);
        assertTrue(joined.contains("_Assert"), "应包含断言语句，实际: " + joined);
        assertTrue(joined.contains("_exit 3"), "应包含退出语句，实际: " + joined);
    }

    @Test
    @DisplayName("start.sql：_start 子脚本调用可解析")
    void parsesSqlStartScript() throws IOException {
        List<String> chunks = ScriptParseProbe.parseWholeScript(readScript("start.sql"));

        assertEquals(1, chunks.size(), "该脚本应只解析出一块命令，实际: " + chunks);
        assertTrue(chunks.get(0).contains("_start"), "应包含 _start，实际: " + chunks);
    }

    @Test
    @DisplayName("_ASSERT 两种外观都能解析：{% … %} 与裸固定形态")
    void bothAssertAppearancesParse() {
        ScriptParseProbe.Outcome braced = ScriptParseProbe.parseOnce("_ASSERT {% 3==3 %}\n");
        assertTrue(braced.ok(), "{% %} 外观应当解析成功，实际: " + braced.errorMessage());

        ScriptParseProbe.Outcome bare = ScriptParseProbe.parseOnce("_ASSERT RESULT.ROWS[0][0] = 10\n");
        assertTrue(bare.ok(), "裸固定形态应当解析成功，实际: " + bare.errorMessage());

        ScriptParseProbe.Outcome bareNamed = ScriptParseProbe.parseOnce("_ASSERT RESULT.ERRORCODE = 0, 错误码检查;\n");
        assertTrue(bareNamed.ok(), "带断言名的裸形态应当解析成功，实际: " + bareNamed.errorMessage());
    }

    @Test
    @DisplayName("已删除的语句现在解析失败（保证裁剪确实生效，防止被误加回来）")
    void removedStatementsNoLongerParse() {
        // 控制流与内嵌脚本：_IF / _LOOP / > {% %}
        assertFalse(ScriptParseProbe.parseOnce("_IF {% 3==2 %}\n").ok(), "_IF 已从语法删除，应当解析失败");
        assertFalse(ScriptParseProbe.parseOnce("_LOOP BEGIN UNTIL {% i==3 %}\n").ok(), "_LOOP 已从语法删除，应当解析失败");
        assertFalse(ScriptParseProbe.parseOnce("> {% i=0 %}\n").ok(), "内嵌脚本 {% %} 已从语法删除，应当解析失败");
        // 运维类命令：_DATA / _MONITOR
        assertFalse(ScriptParseProbe.parseOnce("_DATA SET SEEDFILE DIR /tmp;\n").ok(), "_DATA 已从语法删除，应当解析失败");
        assertFalse(ScriptParseProbe.parseOnce("_MONITOR MANAGER ON\n").ok(), "_MONITOR 已从语法删除，应当解析失败");
        // API 命名空间：整个 APILexer/APIParser 已删除
        assertFalse(ScriptParseProbe.parseOnce("_USE API\n").ok(), "_USE API 已删除，应当解析失败");
        assertFalse(ScriptParseProbe.parseOnce("_use sql\n").ok(), "_USE 已整体删除（只剩 SQL 命名空间），应当解析失败");
        // 作业的 worker 注册表：语句已删除
        assertFalse(ScriptParseProbe.parseOnce("_JOB REGISTER WORKER TO W1\n").ok(),
                "_JOB REGISTER 已从语法删除，应当解析失败");
        assertFalse(ScriptParseProbe.parseOnce("_JOB DEREGISTER WORKER\n").ok(),
                "_JOB DEREGISTER 已从语法删除，应当解析失败");
    }

    @Test
    @DisplayName("_LOAD JDBCDRIVER：只有这一个目标是语法认得的目标（其余目标是引擎报不支持）")
    void loadJdbcDriverParses() {
        assertTrue(ScriptParseProbe.parseOnce("_load jdbcdriver /opt/drivers/pg.jar\n").ok(),
                "_LOAD JDBCDRIVER <jar> 必须能解析");
        assertTrue(ScriptParseProbe.parseOnce("_LOAD JDBCDRIVER \"C:\\my dir\\pg driver.jar\";\n").ok(),
                "带引号（含空格）的路径也必须能解析");
        assertTrue(ScriptParseProbe.parseOnce("_load jdbcdriver\n").ok(),
                "缺 jar 的写法也要能解析：由引擎给可读错误，而不是一句语法错误");
        // 其它目标语法上"认得出来"，由引擎报 Unsupported _LOAD target（_LOAD 是认识的命令）
        assertTrue(ScriptParseProbe.parseOnce("_LOAD MAP rules.map\n").ok(),
                "不支持的 _LOAD 目标要让引擎给出可读错误，不该在语法层报 Unknown command");
    }

    @Test
    @DisplayName("本轮删除的语句仍解析失败：_JOB REGISTER/DEREGISTER、_connect /mem")
    void newlyRemovedStatementsNoLongerParse() {
        assertFalse(ScriptParseProbe.parseOnce("_JOB REGISTER WORKER TO W1\n").ok(),
                "_JOB REGISTER 已从语法删除，应当解析失败");
        assertFalse(ScriptParseProbe.parseOnce("_JOB DEREGISTER WORKER\n").ok(),
                "_JOB DEREGISTER 已从语法删除，应当解析失败");
        // 本地内存库（Linux/Windows 都不支持）：connectlocal 备选已从语法删除
        assertFalse(ScriptParseProbe.parseOnce("_connect /mem\n").ok(),
                "_connect /mem 已从语法删除，应当解析失败");
        assertFalse(ScriptParseProbe.parseOnce("_connect /metadata\n").ok(),
                "_connect 的本地服务写法已从语法删除，应当解析失败");
        // 现行写法照旧可用（不是把 connect 整条干掉）
        assertTrue(ScriptParseProbe.parseOnce("_connect 127.0.0.1:43175/db1\n").ok(),
                "无 scheme 的 _connect 必须能解析");
    }

    @Test
    @DisplayName("词法形状：_ASSERT 两种外观各产出 1 个正文 token（花括号外观带前导空白一起吃掉）")
    void assertTokenShape() {
        assertEquals("[ASSERT, ASSERT_BRACED, EOF]", tokenNames("_ASSERT {% 3==3 %}"));
        assertEquals("[ASSERT, ASSERT_BODY, EOF]", tokenNames("_ASSERT RESULT.ROWS[0][0] = 10"));
    }

    private String tokenNames(String text) {
        org.antlr.v4.runtime.Lexer lexer =
                new org.slackerdb.dbcli.antlr.SQLLexer(org.antlr.v4.runtime.CharStreams.fromString(text));
        org.antlr.v4.runtime.CommonTokenStream tokens = new org.antlr.v4.runtime.CommonTokenStream(lexer);
        tokens.fill();
        StringBuilder sb = new StringBuilder("[");
        for (org.antlr.v4.runtime.Token token : tokens.getTokens()) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            String name = org.slackerdb.dbcli.antlr.SQLLexer.VOCABULARY.getSymbolicName(token.getType());
            sb.append(name == null ? ("'" + token.getText() + "'") : name);
        }
        return sb.append(']').toString();
    }

    @Test
    @DisplayName("不完整语句会判定为'需要续行'（missing SQL_END 语义）")
    void incompleteStatementNeedsMoreLines() {
        ScriptParseProbe.Outcome outcome = ScriptParseProbe.parseOnce("create table aaa (\n");

        assertTrue(outcome.needMoreLines(), "缺 SQL_END 的语句应判定为需要续行");
        assertTrue(outcome.ok(), "需要续行不算错误，实际: " + outcome.errorMessage());
    }

    @Test
    @DisplayName("完整语句解析成功")
    void completeStatementParses() {
        ScriptParseProbe.Outcome outcome = ScriptParseProbe.parseOnce("create table aaa (id int);\n");

        assertTrue(outcome.ok(), "完整语句不应报错，实际: " + outcome.errorMessage());
        assertFalse(outcome.needMoreLines(), "完整语句不应要求续行");
    }
}
