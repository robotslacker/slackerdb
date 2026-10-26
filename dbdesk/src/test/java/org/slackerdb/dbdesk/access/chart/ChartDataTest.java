package org.slackerdb.dbdesk.access.chart;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbdesk.access.dataset.DatasetRunner;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 图表数据变换（纯逻辑）：分类顺序、分组拆分、聚合兜底、缺列报错。 */
class ChartDataTest {

    private static DatasetRunner.Result result(List<String> columns, List<List<Object>> rows) {
        return new DatasetRunner.Result(columns, List.of(), rows, 5, false);
    }

    private static Chart chart(String type, String x, List<String> y, String series, String aggregate) {
        return new Chart(null, "测试图表", "ds-1", type, x, y, series, aggregate, java.util.Map.of(), null);
    }

    @Test
    @DisplayName("按 X 分类，每列 Y 一条序列，分类顺序按首次出现")
    void buildsSeriesPerYColumn() {
        DatasetRunner.Result source = result(List.of("k", "a", "b"), List.of(
                List.of("B", 2, 20),
                List.of("A", 1, 10),
                List.of("C", 3, 30)));

        ChartData.Result data = ChartData.build(source, chart("bar", "k", List.of("a", "b"), null, "none"));

        assertTrue(data.ok());
        assertEquals(List.of("B", "A", "C"), data.categories());
        assertEquals(2, data.series().size());
        assertEquals("a", data.series().get(0).name());
        assertEquals(List.of(2, 1, 3), data.series().get(0).data());
        assertEquals(List.of(20, 10, 30), data.series().get(1).data());
    }

    @Test
    @DisplayName("有分组列时：每个分组 × 每个 Y 列各一条序列")
    void splitsSeriesByGroupColumn() {
        DatasetRunner.Result source = result(List.of("k", "g", "v"), List.of(
                List.of("A", "x", 1),
                List.of("A", "y", 2),
                List.of("B", "x", 3),
                List.of("B", "y", 4)));

        ChartData.Result data = ChartData.build(source, chart("line", "k", List.of("v"), "g", "none"));

        assertEquals(List.of("A", "B"), data.categories());
        assertEquals(2, data.series().size());
        assertTrue(data.series().get(0).name().startsWith("v · "), "序列名应带分组值：" + data.series().get(0).name());
        assertEquals(List.of(1, 3), data.series().get(0).data());
        assertEquals(List.of(2, 4), data.series().get(1).data());
    }

    @Test
    @DisplayName("同一 (分类, 分组) 重复时按 aggregate 合并；默认 none = 取最后一行")
    void aggregatesDuplicates() {
        DatasetRunner.Result source = result(List.of("k", "v"), List.of(
                List.of("A", 1),
                List.of("A", 2)));

        ChartData.Result summed = ChartData.build(source, chart("bar", "k", List.of("v"), null, "sum"));
        assertEquals(List.of(new java.math.BigDecimal("3")), summed.series().get(0).data(), "sum 应把两行相加");

        ChartData.Result last = ChartData.build(source, chart("bar", "k", List.of("v"), null, "none"));
        assertEquals(List.of(2), last.series().get(0).data(), "none 取最后一行");
    }

    @Test
    @DisplayName("Y 列不存在 → 返回错误而不是空图")
    void reportsMissingColumn() {
        ChartData.Result data = ChartData.build(result(List.of("k"), List.of(List.of("A"))),
                chart("line", "k", List.of("nope"), null, "none"));
        assertFalse(data.ok());
        assertTrue(data.error().contains("nope"));
    }

    @Test
    @DisplayName("饼图：没有 X 时每行一个扇区，值取第一列 Y")
    void pieWithoutXAxis() {
        DatasetRunner.Result source = result(List.of("name", "amount"), List.of(
                List.of("直接", 10),
                List.of("搜索", 20)));

        ChartData.Result data = ChartData.build(source, chart("pie", null, List.of("amount"), null, "none"));

        assertEquals(2, data.categories().size());
        assertEquals(List.of(10, 20), data.series().get(0).data());
    }
}
