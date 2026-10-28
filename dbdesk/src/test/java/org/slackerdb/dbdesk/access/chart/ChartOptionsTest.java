package org.slackerdb.dbdesk.access.chart;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ECharts option 生成（纯逻辑）：折线/柱状有坐标轴，饼图没有。 */
class ChartOptionsTest {

    private static Chart chart(String type, String x, List<String> y) {
        return new Chart(null, "营收", "ds-1", type, x, y, null, "none", Map.of(), null);
    }

    private static ChartData.Result data() {
        return new ChartData.Result(List.of("一月", "二月"),
                List.of(new ChartData.Series("amount", List.of(10, 20))), false, 3, null);
    }

    @Test
    @DisplayName("折线图：xAxis 用分类、series 带类型与数据")
    void lineChartHasAxes() {
        Map<String, Object> option = ChartOptions.toEcharts(chart("line", "month", List.of("amount")), data());

        assertEquals(List.of("一月", "二月"), ((Map<?, ?>) option.get("xAxis")).get("data"));
        List<?> series = (List<?>) option.get("series");
        assertEquals(1, series.size());
        Map<?, ?> first = (Map<?, ?>) series.get(0);
        assertEquals("line", first.get("type"));
        assertEquals("amount", first.get("name"));
        assertEquals(List.of(10, 20), first.get("data"));
    }

    @Test
    @DisplayName("饼图：没有坐标轴，data 是 name/value 点集")
    void pieChartHasPoints() {
        Map<String, Object> option = ChartOptions.toEcharts(chart("pie", null, List.of("amount")), data());

        assertTrue(option.get("xAxis") == null, "饼图不应有 xAxis");
        List<?> series = (List<?>) option.get("series");
        Map<?, ?> pie = (Map<?, ?>) series.get(0);
        assertEquals("pie", pie.get("type"));
        List<?> points = (List<?>) pie.get("data");
        assertEquals("一月", ((Map<?, ?>) points.get(0)).get("name"));
        assertEquals(10, ((Map<?, ?>) points.get(0)).get("value"));
    }

    @Test
    @DisplayName("数据加载失败时给标题提示，不抛异常")
    void errorProducesTitleOnly() {
        Map<String, Object> option = ChartOptions.toEcharts(chart("line", "month", List.of("amount")),
                ChartData.Result.error("数据集中没有列：nope"));
        assertTrue(String.valueOf(option.get("title")).contains("chart.error.load"), "标题应带图表加载失败的 key：" + option.get("title"));
    }
}
