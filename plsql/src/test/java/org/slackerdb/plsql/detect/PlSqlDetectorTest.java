package org.slackerdb.plsql.detect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L0 检测器的程序化边界用例（无法用文本语料表达的输入）。
 */
class PlSqlDetectorTest {

    private static String kind(PlSqlScript script) {
        if (script.hasError()) {
            return "ERROR";
        }
        if (script.isEmpty()) {
            return "EMPTY";
        }
        if (script.isSingleBlock()) {
            return "BLOCK";
        }
        if (script.isScript()) {
            return "SCRIPT";
        }
        return "SQL";
    }

    /**
     * 回归：简单查询消息的 SQL 以 NUL 结尾，历史实现会把 {@code "BEGIN\0"} 当成匿名块
     * 丢给 PL/SQL 词法器（token recognition error）。
     *
     * <p>pgjdbc 在 {@code autoCommit=false} 下会先发一个 {@code BEGIN} 开启事务，
     * 因此这条路径是<b>每个事务连接都会走</b>的。</p>
     */
    @Test
    void bareBeginWithTrailingNulIsTransactionNotBlock() {
        PlSqlScript script = PlSqlDetector.analyze("BEGIN\u0000");
        assertEquals("SQL", kind(script), "BEGIN + NUL 必须按事务控制处理，不能当匿名块");
        assertEquals("BEGIN\u0000", script.first().sql());
    }

    @Test
    void bareBeginFormsAreTransactions() {
        assertEquals("SQL", kind(PlSqlDetector.analyze("BEGIN")));
        assertEquals("SQL", kind(PlSqlDetector.analyze("BEGIN;")));
        assertEquals("SQL", kind(PlSqlDetector.analyze("begin work")));
        assertEquals("SQL", kind(PlSqlDetector.analyze("BEGIN TRANSACTION;")));
        assertEquals("SQL", kind(PlSqlDetector.analyze("BEGIN ISOLATION LEVEL SERIALIZABLE")));
    }

    /**
     * 回归：pgjdbc 在 {@code readOnly=true} 时发送 {@code BEGIN READ ONLY}。
     * 若被误判成匿名块，只读连接会悄悄变成可写连接（Sanity02Test#testReadOnlyConnection）。
     */
    @Test
    void beginReadOnlyIsTransaction() {
        assertEquals("SQL", kind(PlSqlDetector.analyze("BEGIN READ ONLY")));
        assertEquals("SQL", kind(PlSqlDetector.analyze("begin read write")));
        assertEquals("SQL", kind(PlSqlDetector.analyze("BEGIN READ ONLY;")));
    }

    @Test
    void beginWithStatementsIsBlock() {
        assertEquals("BLOCK", kind(PlSqlDetector.analyze("begin insert into t values(1); end;")));
        assertEquals("BLOCK", kind(PlSqlDetector.analyze("begin\n  end;")));
    }

    @Test
    void wrapperBodyIsExtracted() {
        // 注意：$$ 引用内部不能再出现 $$（PG 语义：遇到相同 tag 即结束），
        // 要在体内写 $$ 必须换 tag（$b$）。
        PlSqlScript script = PlSqlDetector.analyze("DO $b$\nbegin\n  insert into t values('$$');\nend;\n$b$;");
        assertEquals("BLOCK", kind(script));
        assertFalse(script.hasError(), String.valueOf(script.error()));
        String body = script.first().body();
        assertTrue(body.contains("insert into t values('$$')"), body);
        assertTrue(body.strip().startsWith("begin"), body);
        assertTrue(body.strip().endsWith("end;"), body);
    }

    @Test
    void multipleStatementsKeepOrderAndOffsets() {
        String text = "select 1;\ndrop table if exists aaa;\n$$\nbegin pass; end;\n$$\n";
        PlSqlScript script = PlSqlDetector.analyze(text);
        assertEquals("SCRIPT", kind(script));
        assertEquals(3, script.size());
        assertEquals("select 1", script.statements().get(0).sql());
        assertEquals("drop table if exists aaa", script.statements().get(1).sql());
        assertTrue(script.statements().get(2).isBlock());
        // 偏移必须落在原文内，且第 3 条确实覆盖 $$ 包装
        assertTrue(text.substring(script.statements().get(2).start()).startsWith("$$"));
        assertNull(script.error());
    }

    @Test
    void unwrappedBlockInsideScriptIsMerged() {
        String text = "select 1;\ndeclare\n  x int;\nbegin\n  begin\n    let x = 1;\n  end;\n  insert into t values(:x);\nend;\nselect 2";
        PlSqlScript script = PlSqlDetector.analyze(text);
        assertEquals("SCRIPT", kind(script));
        assertEquals(3, script.size());
        assertEquals("SQL", script.statements().get(0).kind().name());
        assertEquals("BLOCK", script.statements().get(1).kind().name());
        assertEquals("SQL", script.statements().get(2).kind().name());
        assertTrue(script.statements().get(1).body().toLowerCase().startsWith("declare"));
        assertTrue(script.statements().get(1).body().toLowerCase().endsWith("end;"),
                "块末尾必须补回分号：" + script.statements().get(1).body());
    }

    @Test
    void singleUnwrappedBlockKeepsEndSemicolon() {
        String text = "declare\n    x int;\nbegin\n    create or replace table unwrapped(i int);\n"
                + "    let x = 11;\n    insert into unwrapped values(:x);\nend;";
        PlSqlScript script = PlSqlDetector.analyze(text);
        assertEquals("BLOCK", kind(script));
        assertEquals(1, script.size());
        String body = script.first().body();
        assertTrue(body.strip().startsWith("declare"), body);
        assertTrue(body.strip().endsWith("end;"), "块末尾必须补回分号，实际 body=" + body);
        assertTrue(body.contains("insert into unwrapped values(:x)"), body);
    }

    @Test
    void unterminatedDollarQuoteIsReported() {
        PlSqlScript script = PlSqlDetector.analyze("DO $$\nbegin pass; end;");
        assertTrue(script.hasError());
        assertTrue(script.error().contains("unterminated"), script.error());
    }

    @Test
    void sqlWithSemicolonInStringStaysOneStatement() {
        PlSqlScript script = PlSqlDetector.analyze("insert into t values('a;b')");
        assertEquals("SQL", kind(script));
        assertEquals(1, script.size());
    }
}
