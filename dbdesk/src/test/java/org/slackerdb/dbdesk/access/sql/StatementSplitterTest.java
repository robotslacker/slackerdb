package org.slackerdb.dbdesk.access.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StatementSplitter} 的行为用例。
 *
 * <p><b>与 {@code dbcli} 同源</b>：这个类的规则是从 {@code dbcli/engine/StatementSplitter} 复制的
 * （决策 R3）。改规则时两边一起改，用例表也对齐 —— 同一句 SQL 在 CLI 和图形控制台里被切成
 * 不同的语句，是很难查的 bug。</p>
 */
class StatementSplitterTest {

    @Test
    void splitsSimpleStatements() {
        assertEquals(List.of("select 1;", "select 2;"), StatementSplitter.split("select 1; select 2;"));
    }

    @Test
    void keepsSemicolonsInsideStrings() {
        assertEquals(List.of("insert into t values ('a;b');"),
                StatementSplitter.split("insert into t values ('a;b');"));
        assertEquals(List.of("select \"x;y\" from t;"),
                StatementSplitter.split("select \"x;y\" from t;"));
    }

    @Test
    void keepsSemicolonsInsideLineComments() {
        // 行注释里的 ; 不切分，注释留在它后面那条语句上（与 dbcli 的前缀语义一致）
        assertEquals(List.of("select 1;", "-- a;b\nselect 2;"),
                StatementSplitter.split("select 1; -- a;b\nselect 2;"));
        // 行首 # 是注释
        assertTrue(StatementSplitter.isBlankOrCommentOnly("# 只是注释"));
    }

    @Test
    void keepsTrailingStatementWithoutSemicolon() {
        assertEquals(List.of("select 1;", "select 2"), StatementSplitter.split("select 1; select 2"));
    }

    @Test
    void ignoresCommentOnlyAndBlankInput() {
        assertEquals(List.of(), StatementSplitter.split(""));
        assertEquals(List.of(), StatementSplitter.split("   \n\t "));
        assertEquals(List.of(), StatementSplitter.split("-- 只有注释\n"));
        assertTrue(StatementSplitter.isBlankOrCommentOnly(" -- x\n"));
        assertTrue(!StatementSplitter.isBlankOrCommentOnly("select 1"));
    }

    @Test
    void handlesAssertionBraces() {
        // {% ... %} 是断言外观，里面的 ; 不是分隔符
        assertEquals(List.of("select 1 {% a; b %};"),
                StatementSplitter.split("select 1 {% a; b %};"));
    }

    @Test
    void handlesBackslashEscapesInsideString() {
        assertEquals(List.of("select 'it\\'s;ok';"),
                StatementSplitter.split("select 'it\\'s;ok';"));
    }

    @Test
    void reportsTopLevelSemicolonPositions() {
        List<Integer> positions = StatementSplitter.topLevelSemicolons("select 1; select 2;");
        assertEquals(param(9, 19), positions);
    }

    private static List<Integer> param(int... values) {
        return java.util.Arrays.stream(values).boxed().toList();
    }
}
