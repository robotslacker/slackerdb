package org.slackerdb.dbcli.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结果渲染的**字节级契约**：格式（table / vertical / csv）与值渲染（{@link CellRenderer}）。
 *
 * <p>这些文本会直接进 golden 比对（{@code --ref}），所以每一条都逐字节断言；换一个空格都算破坏契约。
 * 格式由脚本里的 {@code _SET OUTFORMAT|HEADER|LINEWIDTH|COLWIDTH} 决定（命令行不再有这些参数）。</p>
 */
class ResultRenderersTest {

    @Test
    @DisplayName("NaN / ±Infinity 是合法的 DOUBLE/FLOAT 取值：按固定写法渲染，不抛异常")
    void nonFiniteNumbersAreRendered() {
        assertEquals("NaN", CellRenderer.render(Double.NaN));
        assertEquals("Infinity", CellRenderer.render(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", CellRenderer.render(Double.NEGATIVE_INFINITY));
        assertEquals("NaN", CellRenderer.render(Float.NaN));
        assertEquals("Infinity", CellRenderer.render(Float.POSITIVE_INFINITY));

        // 三种格式都必须能渲染：一个数据值不能把整次运行变成"未捕获异常 + 255"
        for (RenderOptions.Format format : RenderOptions.Format.values()) {
            String rendered = String.join("\n", ResultRenderers.render(
                    RenderOptions.defaults().withFormat(format), List.of("v"), List.of("DOUBLE"),
                    List.of(java.util.Arrays.asList((Object) Double.NaN))));
            assertTrue(rendered.contains("NaN"), format + ": " + rendered);
        }
    }

    private static final List<String> HEADERS = List.of("id", "name");
    private static final List<String> TYPES = List.of("INTEGER", "VARCHAR");

    private static List<List<Object>> rows() {
        return List.of(
                List.of(1, "alice"),
                List.of(2, "bob"));
    }

    private static RenderOptions options(RenderOptions.Format format, boolean header) {
        return new RenderOptions(format, header, 40, 200);
    }

    // ------------------------------------------------------------------ CSV

    @Test
    @DisplayName("CSV：带表头、逗号分隔、LF；HEADER OFF 去掉第一行")
    void csvWithAndWithoutHeader() {
        assertEquals(List.of("id,name", "1,alice", "2,bob"),
                ResultRenderers.render(options(RenderOptions.Format.CSV, true), HEADERS, TYPES, rows()));

        assertEquals(List.of("1,alice", "2,bob"),
                ResultRenderers.render(options(RenderOptions.Format.CSV, false), HEADERS, TYPES, rows()));
    }

    @Test
    @DisplayName("CSV 转义：逗号/引号/换行加引号，内部引号写成两个")
    void csvQuotesWhenNeeded() {
        List<List<Object>> tricky = List.of(List.of(1, "a,b"), List.of(2, "he said \"hi\""),
                List.of(3, "line1\nline2"));

        assertEquals(List.of("id,name", "1,\"a,b\"", "2,\"he said \"\"hi\"\"\"", "3,\"line1\nline2\""),
                ResultRenderers.render(options(RenderOptions.Format.CSV, true), HEADERS, TYPES, tricky));
    }

    @Test
    @DisplayName("CSV 的 NULL 写成空字段（与空串等效，不加引号）")
    void csvWritesNullAsAnEmptyField() {
        List<List<Object>> withNull = List.of(java.util.Arrays.asList(1, null));

        assertEquals(List.of("id,name", "1,"),
                ResultRenderers.render(options(RenderOptions.Format.CSV, true), HEADERS, TYPES, withNull));
    }

    @Test
    @DisplayName("CSV 不截断、不换行：COLWIDTH / LINEWIDTH 只作用于给人看的格式")
    void csvIsNeverTruncatedOrWrapped() {
        String longValue = "abcdefghijklmnopqrstuvwxyz0123456789";
        RenderOptions narrow = new RenderOptions(RenderOptions.Format.CSV, false, 3, 5);

        assertEquals(List.of("1," + longValue),
                ResultRenderers.render(narrow, HEADERS, TYPES, List.of(List.of(1, longValue))));
    }

    // ------------------------------------------------------------- vertical

    @Test
    @DisplayName("vertical：一条记录一个块，字段名左对齐，块间空行")
    void verticalFormat() {
        assertEquals(List.of(
                        "-[ RECORD 1 ]",
                        "id   | 1",
                        "name | alice",
                        "",
                        "-[ RECORD 2 ]",
                        "id   | 2",
                        "name | bob"),
                ResultRenderers.render(options(RenderOptions.Format.VERTICAL, true), HEADERS, TYPES, rows()));
    }

    @Test
    @DisplayName("vertical：NULL 固定 <null>（不截断），数据按 COLWIDTH 用 ... 截断")
    void verticalNullAndTruncation() {
        List<List<Object>> data = List.of(java.util.Arrays.asList(1, null), List.of(2, "abcdefghij"));

        assertEquals(List.of(
                        "-[ RECORD 1 ]",
                        "id   | 1",
                        "name | <null>",
                        "",
                        "-[ RECORD 2 ]",
                        "id   | 2",
                        "name | abc..."),
                ResultRenderers.render(new RenderOptions(RenderOptions.Format.VERTICAL, true, 6, 200),
                        HEADERS, TYPES, data));
    }

    @Test
    @DisplayName("vertical：LINEWIDTH 超出时换行（不截断、不丢字符）")
    void verticalWrapsLongLines() {
        List<String> lines = ResultRenderers.render(
                new RenderOptions(RenderOptions.Format.VERTICAL, true, 40, 10), HEADERS, TYPES,
                List.of(List.of(1, "alice")));

        assertEquals(List.of("-[ RECORD ", "1 ]", "id   | 1", "name | ali", "ce"), lines);
    }

    // ---------------------------------------------------------------- table

    @Test
    @DisplayName("table：默认参数与既有渲染完全一致")
    void tableDefaultsMatchTheLegacyRenderer() {
        assertEquals(SqlTableRenderer.renderTab(HEADERS, TYPES, rows(), 0),
                ResultRenderers.render(RenderOptions.defaults(), HEADERS, TYPES, rows()));
    }

    @Test
    @DisplayName("table：HEADER OFF 只省表头与分割线，边框与行号仍在；NULL 固定 <null>")
    void tableWithoutHeaderKeepsBorders() {
        List<List<Object>> data = List.of(java.util.Arrays.asList(1, null));

        assertEquals(List.of(
                        "+--------+----+--------+",
                        "|      1 | 1  | <null> |",
                        "+--------+----+--------+"),
                ResultRenderers.render(options(RenderOptions.Format.TABLE, false), HEADERS, null, data));
    }

    @Test
    @DisplayName("table：COLWIDTH 用 ... 截断（结果总宽不超过上限）")
    void tableTruncatesWithDots() {
        List<String> lines = ResultRenderers.render(
                new RenderOptions(RenderOptions.Format.TABLE, false, 6, 200), HEADERS, TYPES,
                List.of(List.of(1, "abcdefghij")));

        String valueLine = lines.get(1);
        assertTrue(valueLine.contains("abc..."), valueLine);
        assertFalse(valueLine.contains("abcdefghij"), valueLine);
    }

    // ---------------------------------------------------------- CellRenderer

    @Test
    @DisplayName("CellRenderer：BigDecimal/Double 不用科学计数法，BigInteger 原样")
    void numbersArePlain() {
        assertEquals("10000000000", CellRenderer.render(new BigDecimal("1E+10")));
        assertEquals("1.50", CellRenderer.render(new BigDecimal("1.50")));
        assertEquals("10000000000", CellRenderer.render(1.0E10));
        assertEquals("123456789012345678901234567890",
                CellRenderer.render(new BigInteger("123456789012345678901234567890")));
    }

    @Test
    @DisplayName("CellRenderer：时间按固定格式；带时区的归一为 UTC 并带 Z")
    void temporalsAreStable() {
        assertEquals("2026-10-18 09:30:01",
                CellRenderer.render(LocalDateTime.of(2026, 10, 18, 9, 30, 1)));
        assertEquals("2026-10-18 09:30:01.250",
                CellRenderer.render(LocalDateTime.of(2026, 10, 18, 9, 30, 1, 250_000_000)));
        assertEquals("2026-10-18",
                CellRenderer.render(LocalDate.of(2026, 10, 18)));
        assertEquals("2026-10-18 09:30:01Z",
                CellRenderer.render(Instant.parse("2026-10-18T09:30:01Z")));
        assertEquals("2026-10-18 01:30:01Z",
                CellRenderer.render(OffsetDateTime.of(2026, 10, 18, 9, 30, 1, 0,
                        ZoneOffset.ofHours(8))),
                "带时区的值归一为 UTC");
    }

    @Test
    @DisplayName("CellRenderer：byte[] 打十六进制与长度，不出现 [B@")
    void bytesRenderAsHex() {
        assertEquals("0x6162(2 bytes)", CellRenderer.render("ab".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("CellRenderer：截断用 ... 且按显示宽度（CJK 算两列；总宽不超上限）")
    void truncationCountsDisplayColumns() {
        assertEquals("...", CellRenderer.truncate("abcdef", 3));
        assertEquals("a...", CellRenderer.truncate("abcdef", 4));
        assertEquals("中文", CellRenderer.truncate("中文", 4), "刚好放得下就不截");
        assertEquals("...", CellRenderer.truncate("中文", 3));
        assertEquals("abcdef", CellRenderer.truncate("abcdef", 0), "0 = 不截断");
    }
}
