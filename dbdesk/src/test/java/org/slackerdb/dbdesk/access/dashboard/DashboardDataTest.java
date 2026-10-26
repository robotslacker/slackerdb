package org.slackerdb.dbdesk.access.dashboard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbdesk.access.chart.Chart;
import org.slackerdb.dbdesk.access.chart.ChartStore;
import org.slackerdb.dbdesk.access.dataset.Dataset;
import org.slackerdb.dbdesk.access.dataset.DatasetStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仪表板渲染（真库）：每张卡片独立渲染、全局筛选下发到数据集参数、坏卡片不影响好卡片。
 */
@EnabledIfSystemProperty(named = "dbdesk.it.url", matches = ".+")
class DashboardDataTest {

    private static final String URL = System.getProperty("dbdesk.it.url");

    @TempDir
    Path tempDir;

    private DatasetStore datasets;
    private ChartStore charts;
    private String datasetId;

    private DashboardData prepare() throws Exception {
        datasets = new DatasetStore(tempDir.resolve("datasets.json"));
        charts = new ChartStore(tempDir.resolve("charts.json"));

        Dataset dataset = datasets.save(new Dataset(null, "月度金额",
                // 参数列必须显式 cast：服务端对未声明类型的参数列给出的列名不可靠（已知限制）
                "select '一月' as month, cast(:factor as integer) as amount "
                        + "union all select '二月', cast(:factor as integer)",
                URL,
                List.of(new Dataset.Param("factor", "number", "10", "")),
                List.of(new Dataset.Column("month", "text", "dimension"),
                        new Dataset.Column("amount", "number", "measure")), null));
        datasetId = dataset.id();

        Chart chart = charts.save(new Chart(null, "金额趋势", dataset.id(), "bar",
                "month", List.of("amount"), null, "none", Map.of(), null));
        return new DashboardData(datasets, charts);
    }

    @Test
    @DisplayName("按默认筛选渲染：卡片有 option，序列值 = 参数默认值")
    void rendersWithFilterDefaults() throws Exception {
        DashboardData renderer = prepare();
        Chart chart = charts.all().get(0);
        Dashboard dashboard = new Dashboard(null, "看板", List.of(), List.of(
                new Dashboard.Card(chart.id(), 0, 0, 6, 4)), null);

        DashboardData.Result result = renderer.render(dashboard, Map.of(), 100);

        assertEquals(1, result.cards().size());
        DashboardData.CardResult card = result.cards().get(0);
        assertNull(card.error(), "不应有错误：" + card.error());
        assertNotNull(card.option());
        List<?> series = (List<?>) card.option().get("series");
        Map<?, ?> first = (Map<?, ?>) series.get(0);
        assertEquals(List.of(10, 10), first.get("data"), "应使用参数默认值 10");
    }

    @Test
    @DisplayName("覆盖筛选值会下发到数据集参数")
    void appliesFilterOverride() throws Exception {
        DashboardData renderer = prepare();
        Chart chart = charts.all().get(0);
        Dashboard dashboard = new Dashboard(null, "看板",
                List.of(new Dashboard.Filter("factor", "number", "10")),
                List.of(new Dashboard.Card(chart.id(), 0, 0, 6, 4)), null);

        DashboardData.Result result = renderer.render(dashboard, Map.of("factor", "42"), 100);

        List<?> series = (List<?>) result.cards().get(0).option().get("series");
        assertEquals(List.of(42, 42), ((Map<?, ?>) series.get(0)).get("data"));
    }

    @Test
    @DisplayName("引用不存在的图表 → 该卡片报错，其它卡片照常渲染")
    void badCardDoesNotBreakOthers() throws Exception {
        DashboardData renderer = prepare();
        Chart chart = charts.all().get(0);
        Dashboard dashboard = new Dashboard(null, "看板", List.of(), List.of(
                new Dashboard.Card(chart.id(), 0, 0, 6, 4),
                new Dashboard.Card("不存在的图表", 6, 0, 6, 4)), null);

        DashboardData.Result result = renderer.render(dashboard, Map.of(), 100);

        assertEquals(2, result.cards().size());
        assertNull(result.cards().get(0).error());
        assertNotNull(result.cards().get(1).error());
        assertTrue(result.cards().get(1).error().contains("图表不存在"));
    }
}
