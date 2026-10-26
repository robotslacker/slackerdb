package org.slackerdb.dbdesk.access.dashboard;

import org.slackerdb.dbdesk.access.chart.Chart;
import org.slackerdb.dbdesk.access.chart.ChartData;
import org.slackerdb.dbdesk.access.chart.ChartOptions;
import org.slackerdb.dbdesk.access.chart.ChartStore;
import org.slackerdb.dbdesk.access.dataset.Dataset;
import org.slackerdb.dbdesk.access.dataset.DatasetRunner;
import org.slackerdb.dbdesk.access.dataset.DatasetStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 渲染一个仪表板：每张卡片 = 图表 → 数据集 → 执行 → ECharts option。
 *
 * <p>单张卡片失败**不影响**其它卡片：失败信息放在该卡片的 {@code error} 里，
 * 看板照常显示其余部分（运维场景里"一块坏了整页白屏"是不可接受的）。</p>
 *
 * <p>全局筛选按名字下发到数据集参数；数据集没有这个参数就自然忽略。</p>
 */
public final class DashboardData {

    /** 一张卡片的渲染结果：成功时 {@code option} 有值，失败时 {@code error} 有值。 */
    public record CardResult(Dashboard.Card card, String chartName, String datasetName,
                             Map<String, Object> option, String error) {
    }

    public record Result(String dashboardName, List<CardResult> cards, long elapsedMillis) {
    }

    private final DatasetStore datasets;
    private final ChartStore charts;

    public DashboardData(DatasetStore datasets, ChartStore charts) {
        this.datasets = datasets;
        this.charts = charts;
    }

    /**
     * @param filterValues 覆盖仪表板筛选的默认值（为空则全用默认值）
     * @param maxRows      每个数据集最多取多少行（图表用不了那么多）
     */
    public Result render(Dashboard dashboard, Map<String, Object> filterValues, int maxRows) {
        long start = System.nanoTime();
        Map<String, Object> effective = new LinkedHashMap<>();
        for (Dashboard.Filter filter : dashboard.filters()) {
            Object value = filterValues == null ? null : filterValues.get(filter.name());
            effective.put(filter.name(), value == null || String.valueOf(value).isEmpty()
                    ? filter.defaultValue() : value);
        }

        List<CardResult> results = new ArrayList<>();
        for (Dashboard.Card card : dashboard.cards()) {
            results.add(renderCard(card, effective, maxRows));
        }
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        return new Result(dashboard.name(), results, elapsed);
    }

    private CardResult renderCard(Dashboard.Card card, Map<String, Object> values, int maxRows) {
        Chart chart = charts.find(card.chartId()).orElse(null);
        if (chart == null) {
            return new CardResult(card, "(图表已删除)", null, null, "图表不存在：" + card.chartId());
        }
        Dataset dataset = datasets.find(chart.datasetId()).orElse(null);
        if (dataset == null) {
            return new CardResult(card, chart.name(), null, null, "数据集不存在：" + chart.datasetId());
        }
        try {
            DatasetRunner.Result rows = DatasetRunner.run(dataset, values, maxRows);
            ChartData.Result data = ChartData.build(rows, chart);
            if (!data.ok()) {
                return new CardResult(card, chart.name(), dataset.name(), null, data.error());
            }
            return new CardResult(card, chart.name(), dataset.name(),
                    ChartOptions.toEcharts(chart, data), null);
        } catch (Exception e) {
            return new CardResult(card, chart.name(), dataset.name(), null, message(e));
        }
    }

    private static String message(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
