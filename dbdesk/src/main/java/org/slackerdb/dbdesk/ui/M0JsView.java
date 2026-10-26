package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import org.slackerdb.dbdesk.jsbridge.ChartView;
import org.slackerdb.dbdesk.jsbridge.SqlEditor;

import java.util.List;
import java.util.Map;

/**
 * M0-V3 验证页：CodeMirror 6 与 ECharts 5 两个包装件能不能在生产构建里稳定工作。
 *
 * <p>验证点（对应 m0-checklist §4）：编辑器有高亮/行号、Ctrl+Enter 能回到服务端；图表按需构建、
 * 跟随容器 resize、能出图。**页面本身不是产品**，M1 会把它们放进 SQL 工作台与报表块。</p>
 */
@Route("m0-js")
public class M0JsView extends VerticalLayout {

    private static final String SAMPLE_SQL = """
            -- Ctrl+Enter 触发服务端回调（M0-V3 验证点）
            select id, c1, c2, c3, c4
            from m0_probe
            where id < 100
            order by id
            limit 10
            """;

    public M0JsView() {
        setPadding(true);
        setSpacing(true);

        H2 title = new H2("M0-V3 两处 JS 包装件");

        SqlEditor editor = new SqlEditor(SAMPLE_SQL);
        editor.setId("sql-editor");
        editor.setWidthFull();

        Span sqlStatus = new Span("等待 Ctrl+Enter…");
        sqlStatus.setId("sql-status");
        editor.addRunListener(event -> sqlStatus.setText("运行：" + firstLine(event.getSql())));
        editor.addChangeListener(event -> sqlStatus.setText("已修改，长度 " + event.getSql().length()));

        ChartView chart = new ChartView();
        chart.setId("chart");
        chart.setWidthFull();

        Span chartStatus = new Span("图表未生成");
        chartStatus.setId("chart-status");

        Button draw = new Button("画图", event -> {
            chart.setOption(sampleOption());
            chartStatus.setText("已下发 option（" + chart.optionJson().length() + " 字符）");
        });
        draw.setId("chart-button");

        add(title, editor, new HorizontalLayout(new Span("编辑器状态："), sqlStatus),
                new HorizontalLayout(draw, chartStatus), chart);
    }

    private static String firstLine(String sql) {
        for (String line : sql.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                return trimmed;
            }
        }
        return "(空)";
    }

    private static Map<String, Object> sampleOption() {
        return Map.of(
                "title", Map.of("text", "M0-V3 图表验证", "left", "center"),
                "tooltip", Map.of("trigger", "axis"),
                "legend", Map.of("data", List.of("销售额"), "bottom", 0),
                "grid", Map.of("left", 48, "right", 24, "top", 48, "bottom", 48),
                "xAxis", Map.of("type", "category",
                        "data", List.of("一月", "二月", "三月", "四月", "五月", "六月")),
                "yAxis", Map.of("type", "value"),
                "series", List.of(Map.of(
                        "name", "销售额",
                        "type", "bar",
                        "data", List.of(120, 200, 150, 80, 70, 110))));
    }
}
