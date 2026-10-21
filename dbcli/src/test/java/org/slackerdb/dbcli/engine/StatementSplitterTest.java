package org.slackerdb.dbcli.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 顶层 {@code ;} 切分器的字节级小测试（纯词法，不需要数据库、不需要引擎）。
 *
 * <p>{@link ScriptRunnerTest} 从"行为"一侧覆盖同一件事（一行两条/三条语句、引号里的 {@code ;}、
 * {@code /} 收尾的块）；这里补的是**切分点本身**的边界：注释、{@code {% %}}、转义引号、
 * "只有注释"的残渣判定。</p>
 */
class StatementSplitterTest {

    @Test
    @DisplayName("没有分号：没有切分点")
    void noSemicolon() {
        assertTrue(StatementSplitter.topLevelSemicolons("select 1").isEmpty());
        assertTrue(StatementSplitter.topLevelSemicolons("").isEmpty());
        assertTrue(StatementSplitter.topLevelSemicolons(null).isEmpty());
    }

    @Test
    @DisplayName("一行两条语句：两个切分点，且位置在分号之后")
    void twoStatements() {
        String text = "select 1; select 2;";

        List<Integer> points = StatementSplitter.topLevelSemicolons(text);

        assertEquals(List.of(9, 19), points);
        assertEquals("select 1;", text.substring(0, points.get(0)));
        assertEquals(" select 2;", text.substring(points.get(0), points.get(1)));
    }

    @Test
    @DisplayName("引号里的 ';' 不算：单引号、双引号、转义引号")
    void semicolonsInsideQuotes() {
        assertEquals(List.of(29), StatementSplitter.topLevelSemicolons("insert into t values ('a;b');"));
        assertEquals(List.of(29), StatementSplitter.topLevelSemicolons("insert into t values (\"a;b\");"));
        // 反斜杠转义的单引号不会提前结束字符串
        assertTrue(StatementSplitter.topLevelSemicolons("select 'a\\'; b'").isEmpty());
    }

    @Test
    @DisplayName("注释里的 ';' 不算：'--' 与行首 '#'")
    void semicolonsInsideComments() {
        assertTrue(StatementSplitter.topLevelSemicolons("select 1 -- x;y").isEmpty());
        assertTrue(StatementSplitter.topLevelSemicolons("select 1\n# x;y\n").isEmpty());
        // 注释里的 ';' 不算：只剩"select 1;"（9）与最后那个 ';'（26）两个切分点
        assertEquals(List.of(9, 26), StatementSplitter.topLevelSemicolons("select 1; -- x;y\nselect 2;"));
    }

    @Test
    @DisplayName("{% %} 断言里的 ';' 不算")
    void semicolonInsideAssertBraces() {
        assertEquals(List.of(24), StatementSplitter.topLevelSemicolons("_assert {% ';' = ';' %};"));
        // 花括号没闭合时里面的 ';' 一律不算分隔符（整段留给分析器去报错）
        assertTrue(StatementSplitter.topLevelSemicolons("_assert {% a;b").isEmpty());
    }

    @Test
    @DisplayName("只有空白与注释 = 空：消费语句后的残渣判定")
    void blankOrCommentOnly() {
        assertTrue(StatementSplitter.isBlankOrCommentOnly(""));
        assertTrue(StatementSplitter.isBlankOrCommentOnly("   \n\t"));
        assertTrue(StatementSplitter.isBlankOrCommentOnly(" -- just a comment"));
        assertTrue(StatementSplitter.isBlankOrCommentOnly("\n# another comment\n"));
        assertFalse(StatementSplitter.isBlankOrCommentOnly("select 1"));
        assertFalse(StatementSplitter.isBlankOrCommentOnly("'a'"));
        assertFalse(StatementSplitter.isBlankOrCommentOnly("{% 1=1 %}"));
        assertFalse(StatementSplitter.isBlankOrCommentOnly(";"));
    }
}
