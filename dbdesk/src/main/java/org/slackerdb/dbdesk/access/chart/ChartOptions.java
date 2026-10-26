package org.slackerdb.dbdesk.access.chart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@link ChartData.Result} 变成 ECharts option（纯数据，可单测）。
 *
 * <p>只生成"够用"的 option：标题、图例、坐标轴、系列。样式细节留给以后
 * （要调样式时往 {@code Chart.options} 里加，而不是改这里）。</p>
 */
public final class ChartOptions {

    private ChartOptions() {
    }

    public static Map<String, Object> toEcharts(Chart chart, ChartData.Result data) {
        Map<String, Object> option = new LinkedHashMap<>();
        if (!data.ok()) {
            option.put("title", Map.of("text", "图表数据加载失败：" + data.error()));
            return option;
        }
        option.put("title", Map.of("text", chart.name()));
        option.put("tooltip", Map.of("trigger", "pie".equals(chart.type()) ? "item" : "axis"));
        option.put("legend", Map.of("type", "scroll", "top", 24));
        option.put("grid", Map.of("left", 48, "right", 24, "top", 56, "bottom", 40));

        if ("pie".equals(chart.type())) {
            option.put("series", List.of(pieSeries(chart, data)));
            return option;
        }

        option.put("xAxis", Map.of("type", "category", "data", data.categories(),
                "axisLabel", Map.of("rotate", data.categories().size() > 8 ? 30 : 0)));
        option.put("yAxis", Map.of("type", "value"));
        List<Map<String, Object>> series = new ArrayList<>();
        for (ChartData.Series one : data.series()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", one.name());
            item.put("type", chart.type());
            item.put("data", one.data());
            item.put("smooth", "line".equals(chart.type()));
            series.add(item);
        }
        option.put("series", series);
        return option;
    }

    private static Map<String, Object> pieSeries(Chart chart, ChartData.Result data) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", chart.y().isEmpty() ? "值" : chart.y().get(0));
        item.put("type", "pie");
        item.put("radius", "62%");
        List<Map<String, Object>> points = new ArrayList<>();
        List<Object> values = data.series().isEmpty() ? List.of() : data.series().get(0).data();
        for (int i = 0; i < data.categories().size(); i++) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("name", String.valueOf(data.categories().get(i)));
            point.put("value", i < values.size() ? values.get(i) : null);
            points.add(point);
        }
        item.put("data", points);
        return item;
    }
}
