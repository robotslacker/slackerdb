package org.slackerdb.dbcli.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 表格渲染与状态行的**字节级契约测试**。
 *
 * <p>期望值取自既有 golden 夹具的原文（逐字节，不是"按我的理解写的"）：</p>
 * <ul>
 *   <li>单行表格块与 {@code 1 row selected.}</li>
 *   <li>{@code 0 row affected.}（**0 用单数**）</li>
 *   <li>{@code 2 rows selected.} 与含 {@code 6}/{@code 10} 的两行表格</li>
 * </ul>
 *
 * <p>注意：这些期望值只锁定**格式**。端到端跑 SlackerDB 时列名大小写等会与夹具来源库不同
 * （夹具的 {@code ID} vs SlackerDB 的 {@code id}），所以旧夹具不能当端到端字节级期望值直接复用。</p>
 */
class SqlTableRendererTest {

    @Test
    @DisplayName("单元格结尾的 CR 被去掉（单行单元格也一样），列宽不把它算进去")
    void trailingCarriageReturnIsStripped() {
        List<String> lines = SqlTableRenderer.renderTab(List.of("T"), List.of("VARCHAR"),
                List.of(List.of("abc\r")), 0);

        for (String line : lines) {
            assertFalse(line.contains("\r"), "渲染结果里不该有裸 CR: " + lines);
        }
        // 列宽按去掉 CR 之后的 abc（3 列）算，不再多出 1 列
        assertEquals("+--------+-----+", lines.get(0), lines.toString());
    }

    @Test
    @DisplayName("列类型名是 null（Types.NULL）时按右对齐渲染，不抛 NPE")
    void nullColumnTypeDoesNotThrow() {
        List<String> lines = SqlTableRenderer.renderTab(List.of("N"),
                java.util.Arrays.asList((String) null), List.of(java.util.Arrays.asList((Object) 7)), 0);

        assertEquals("+--------+---+", lines.get(0), lines.toString());
        assertEquals("|      1 | 7 |", lines.get(3), lines.toString());
    }

    @Test
    @DisplayName("补充平面的宽字符按 2 列算（不是 3 列）：整行宽度与边框一致")
    void supplementaryPlaneWideCharacterCountsTwoColumns() {
        String wide = new String(Character.toChars(0x20000));
        assertEquals(2, SqlTableRenderer.displayWidth(wide));

        List<String> lines = SqlTableRenderer.renderTab(List.of("W"), List.of("VARCHAR"),
                List.of(List.of(wide)), 0);
        assertEquals(SqlTableRenderer.displayWidth(lines.get(0)),
                SqlTableRenderer.displayWidth(lines.get(3)),
                "数据行的显示宽度必须与边框一致: " + lines);
    }

    @Test
    @DisplayName("单行单列：表格块与 1 row selected. 逐字节一致")
    void singleRowMatchesReference() {
        List<String> lines = SqlTableRenderer.renderTab(List.of("ID"), List.of("INTEGER"),
                List.of(List.of(3)), 0);

        assertEquals(List.of(
                "+--------+----+",
                "|   ##   | ID |",
                "+--------+----+",
                "|      1 |  3 |",
                "+--------+----+"), lines);
        assertEquals("1 row selected.", SqlTableRenderer.statusLine(true, 1, false, true, false));
    }

    @Test
    @DisplayName("两行（数值右对齐）：表格形状与 golden 夹具一致")
    void twoRowsNumericRightAligned() {
        List<String> lines = SqlTableRenderer.renderTab(List.of("ID"), List.of("INTEGER"),
                List.of(List.of(6), List.of(10)), 0);

        assertEquals(List.of(
                "+--------+----+",
                "|   ##   | ID |",
                "+--------+----+",
                "|      1 |  6 |",
                "|      2 | 10 |",
                "+--------+----+"), lines);
        assertEquals("2 rows selected.", SqlTableRenderer.statusLine(true, 2, false, true, false));
    }

    @Test
    @DisplayName("字符串列左对齐、数值列右对齐；startIter 影响起始行号")
    void alignmentFollowsColumnTypes() {
        List<String> lines = SqlTableRenderer.renderTab(List.of("NAME", "ID"),
                List.of("VARCHAR", "INTEGER"),
                List.of(Arrays.asList("alpha", 3)), 0);
        assertEquals("|      1 | alpha |  3 |", lines.get(3));

        // startIter=10 → 第一条数据行号是 11
        List<String> shifted = SqlTableRenderer.renderTab(List.of("ID"), List.of("INTEGER"),
                List.of(List.of(3)), 10);
        assertEquals("|     11 |  3 |", shifted.get(3));
    }

    @Test
    @DisplayName("CJK 宽字符：列宽按显示宽度算，字符串列左对齐时扣掉宽字符")
    void wideCharacters() {
        List<String> lines = SqlTableRenderer.renderTab(List.of("姓名"), List.of("VARCHAR"),
                List.of(List.of("张三")), 0);

        assertEquals(List.of(
                "+--------+------+",
                "|   ##   | 姓名 |",
                "+--------+------+",
                "|      1 | 张三 |",
                "+--------+------+"), lines);
        // 显示宽度：'姓名' = 2 字符 + 2 宽 = 4
        assertEquals(4, SqlTableRenderer.displayWidth("姓名"));
        assertEquals(0, SqlTableRenderer.wideChars("abc"));
    }

    @Test
    @DisplayName("NULL → <null>（列宽至少 6）")
    void nullCells() {
        List<String> lines = SqlTableRenderer.renderTab(List.of("A"), List.of("VARCHAR"),
                List.of(Arrays.asList((Object) null)), 0);
        // 表头永远是"居中"（与类型无关）：centerIn("A", 6) = "  A   "
        assertEquals("|   ##   |   A    |", lines.get(1));
        assertEquals("|      1 | <null> |", lines.get(3));
    }

    @Test
    @DisplayName("多行单元格撑高该行，续行的行号列打印 8 个空格")
    void multiLineCell() {
        List<String> lines = SqlTableRenderer.renderTab(List.of("A"), List.of("VARCHAR"),
                List.of(List.of("a\nb")), 0);
        assertEquals("|      1 | a |", lines.get(3));
        assertEquals("|        | b |", lines.get(4));
    }

    @Test
    @DisplayName("状态行：0/1 单数、警告后缀、FEEDBACK 关闭与分页时为 null")
    void statusLines() {
        // 0 row affected.（0 用单数）的真实内容
        assertEquals("0 row affected.", SqlTableRenderer.statusLine(false, 0, false, true, false));
        assertEquals("0 row selected.", SqlTableRenderer.statusLine(true, 0, false, true, false));
        assertEquals("1 row affected.", SqlTableRenderer.statusLine(false, 1, false, true, false));
        assertEquals("2 rows affected.", SqlTableRenderer.statusLine(false, 2, false, true, false));
        assertEquals("1 row selected with warnings.", SqlTableRenderer.statusLine(true, 1, true, true, false));
        assertEquals("0 row affected with warnings.", SqlTableRenderer.statusLine(false, 0, true, true, false));
        // SET FEEDBACK OFF 与分页（还没取完）都不打印状态行
        assertNull(SqlTableRenderer.statusLine(true, 5, false, false, false));
        assertNull(SqlTableRenderer.statusLine(true, 5, false, true, true));
    }

    @Test
    @DisplayName("表头居中的补空格公式必须精确（多出的空格落在哪边不能凭直觉）")
    void headerCenterFormula() {
        assertEquals("  ab ", SqlTableRenderer.centerIn("ab", 5));
        assertEquals(" ab ", SqlTableRenderer.centerIn("ab", 4));
        assertEquals("a ", SqlTableRenderer.centerIn("a", 2));
        assertEquals("ID", SqlTableRenderer.centerIn("ID", 2));
        assertEquals("abc", SqlTableRenderer.centerIn("abc", 3));
        assertEquals("abc", SqlTableRenderer.centerIn("abc", 2), "宽度不足时原样返回");
    }
}
