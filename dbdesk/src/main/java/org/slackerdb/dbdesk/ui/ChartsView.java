package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import org.slackerdb.dbdesk.access.chart.Chart;
import org.slackerdb.dbdesk.access.chart.ChartData;
import org.slackerdb.dbdesk.access.chart.ChartOptions;
import org.slackerdb.dbdesk.access.chart.ChartStore;
import org.slackerdb.dbdesk.access.dataset.Dataset;
import org.slackerdb.dbdesk.access.dataset.DatasetRunner;
import org.slackerdb.dbdesk.access.dataset.DatasetStore;
import org.slackerdb.dbdesk.jsbridge.ChartView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 图表编辑器（M4-2）：选数据集 → 选类型/列 → 预览 → 保存。
 *
 * <p>图表**只引用数据集**，不复制 SQL：口径改了，图跟着改。选数据集时会用它的列做默认映射
 * （第一个维度当 X、所有度量当 Y），用户只在需要时调整 —— 少点几下才有人愿意用。</p>
 */
@Route(value = "charts", layout = MainLayout.class)
public class ChartsView extends HorizontalLayout {

    private final DatasetStore datasets = DatasetStore.defaultStore();
    private final ChartStore charts = ChartStore.defaultStore();

    private final Grid<Chart> list = new Grid<>(Chart.class, false);
    private final TextField name = new TextField("图表名");
    private final ComboBox<String> dataset = new ComboBox<>("数据集");
    private final ComboBox<String> type = new ComboBox<>("类型");
    private final ComboBox<String> x = new ComboBox<>("X 轴（分类）");
    private final MultiSelectComboBox<String> y = new MultiSelectComboBox<>("Y 轴（值，可多选）");
    private final ComboBox<String> series = new ComboBox<>("分组（可选）");
    private final ComboBox<String> aggregate = new ComboBox<>("聚合");
    private final ChartView preview = new ChartView();
    private final Span status = new Span("就绪");

    private final Button save = new Button("保存");
    private final Button render = new Button("预览");
    private final Button remove = new Button("删除");
    private final Button create = new Button("新建");

    private String editingId;
    /** 数据集名 → Dataset（下拉里显示名字，内部要 id）。 */
    private List<Dataset> datasetItems = List.of();

    public ChartsView() {
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        VerticalLayout left = new VerticalLayout();
        left.setWidth("300px");
        left.setHeightFull();
        left.setPadding(false);
        H2 title = new H2("图表");
        title.getStyle().set("margin", "0");
        create.setId("chart-new");
        create.addClickListener(event -> newChart());

        list.setId("chart-list");
        list.setWidthFull();
        list.setHeightFull();
        list.addColumn(Chart::name).setHeader("名称").setAutoWidth(true);
        list.addColumn(Chart::type).setHeader("类型").setAutoWidth(true);
        list.addSelectionListener(event -> event.getFirstSelectedItem().ifPresent(this::load));
        left.add(title, new HorizontalLayout(create), list);
        left.setFlexGrow(1, list);

        VerticalLayout right = new VerticalLayout();
        right.setHeightFull();
        right.setPadding(false);

        name.setId("chart-name");
        name.setWidth("240px");
        dataset.setId("chart-dataset");
        dataset.setWidth("260px");
        dataset.setItems(List.of());
        dataset.addValueChangeListener(event -> onDatasetSelected(event.getValue()));
        type.setId("chart-type");
        type.setItems(Chart.TYPES);
        type.setValue("line");
        type.setWidth("140px");
        x.setId("chart-x");
        x.setWidth("200px");
        y.setId("chart-y");
        y.setWidth("320px");
        series.setId("chart-series");
        series.setWidth("200px");
        aggregate.setId("chart-aggregate");
        aggregate.setItems(Chart.AGGREGATES);
        aggregate.setValue("none");
        aggregate.setWidth("140px");

        preview.setId("chart-preview");
        preview.setWidthFull();
        preview.setHeight("420px");

        save.setId("chart-save");
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        save.addClickListener(event -> saveCurrent());
        render.setId("chart-render");
        render.addClickListener(event -> renderPreview());
        remove.setId("chart-delete");
        remove.addClickListener(event -> deleteCurrent());
        status.setId("chart-status");

        right.add(new HorizontalLayout(name, dataset, type),
                new HorizontalLayout(x, y, series, aggregate),
                new HorizontalLayout(render, save, remove, status),
                new H3("预览"), preview);
        right.setFlexGrow(1, preview);

        add(left, right);
        setFlexGrow(1, right);

        refreshList();
        refreshDatasets();
        newChart();
    }

    private void refreshList() {
        list.setItems(charts.all());
    }

    private void refreshDatasets() {
        datasetItems = datasets.all();
        dataset.setItems(datasetItems.stream().map(Dataset::name).toList());
    }

    private void newChart() {
        editingId = null;
        name.setValue("");
        type.setValue("line");
        x.clear();
        y.clear();
        series.clear();
        aggregate.setValue("none");
        preview.setOption(Map.of("title", Map.of("text", "选择数据集后点『预览』")));
        status.setText("新建图表");
        list.deselectAll();
        if (dataset.getValue() == null && !datasetItems.isEmpty()) {
            dataset.setValue(datasetItems.get(0).name());
        }
    }

    private void load(Chart chart) {
        editingId = chart.id();
        name.setValue(chart.name());
        type.setValue(chart.type());
        aggregate.setValue(chart.aggregate());
        Dataset source = datasetItems.stream()
                .filter(item -> item.id().equals(chart.datasetId())).findFirst().orElse(null);
        dataset.setValue(source == null ? null : source.name());
        applyColumns(source);
        x.setValue(chart.x());
        y.setValue(new java.util.LinkedHashSet<>(chart.y()));
        series.setValue(chart.series());
        // 打开已保存的图表就直接出图，不用再点一次"预览"
        renderPreview();
        // 渲染成功时把状态改回"已载入"（失败信息要保留，别被覆盖）
        if (!status.getText().startsWith("预览失败")) {
            status.setText("已载入：" + chart.name());
        }
    }

    private void onDatasetSelected(String datasetName) {
        if (datasetName == null) {
            return;
        }
        Dataset source = datasetItems.stream().filter(item -> item.name().equals(datasetName))
                .findFirst().orElse(null);
        applyColumns(source);
    }

    /** 选数据集后自动给一版默认映射：第一个维度当 X、所有度量当 Y。 */
    private void applyColumns(Dataset source) {
        List<Dataset.Column> columns = new ArrayList<>();
        if (source != null) {
            columns.addAll(source.columns());
            if (columns.isEmpty()) {
                columns.addAll(inferColumns(source));
            }
        }
        List<String> names = columns.stream().map(Dataset.Column::name).toList();
        x.setItems(names);
        y.setItems(names);
        series.setItems(names);

        String firstDimension = columns.stream().filter(column -> "dimension".equals(column.role()))
                .map(Dataset.Column::name).findFirst().orElse(null);
        List<String> measures = columns.stream().filter(column -> "measure".equals(column.role()))
                .map(Dataset.Column::name).toList();
        if (x.getValue() == null) {
            x.setValue(firstDimension);
        }
        if (y.getValue().isEmpty() && !measures.isEmpty()) {
            y.setValue(new java.util.LinkedHashSet<>(measures));
        }
    }

    /** 数据集还没跑过、没有列信息：跑一次（限制 1 行）拿列名与类型。 */
    private List<Dataset.Column> inferColumns(Dataset source) {
        try {
            DatasetRunner.Result result = DatasetRunner.run(source, Map.of(), 1);
            List<Dataset.Column> columns = new ArrayList<>();
            for (int i = 0; i < result.columns().size(); i++) {
                String columnType = i < result.columnTypes().size() ? result.columnTypes().get(i) : "text";
                columns.add(new Dataset.Column(result.columns().get(i), columnType,
                        "number".equals(columnType) ? "measure" : "dimension"));
            }
            return columns;
        } catch (Exception e) {
            status.setText("读取数据集列失败：" + message(e));
            return List.of();
        }
    }

    private void renderPreview() {
        Dataset source = datasetItems.stream().filter(item -> item.name().equals(dataset.getValue()))
                .findFirst().orElse(null);
        if (source == null) {
            status.setText("先选一个数据集");
            return;
        }
        try {
            Chart chart = buildChart();
            DatasetRunner.Result rows = DatasetRunner.run(source, Map.of(), 2_000);
            ChartData.Result data = ChartData.build(rows, chart);
            preview.setOption(ChartOptions.toEcharts(chart, data));
            if (!data.ok()) {
                status.setText("预览失败：" + data.error());
                return;
            }
            status.setText(String.format("预览成功：%d 个分类 / %d 条序列%s",
                    data.categories().size(), data.series().size(),
                    data.truncated() ? "（数据已截断）" : ""));
        } catch (Exception e) {
            status.setText("预览失败：" + message(e));
        }
    }

    private void saveCurrent() {
        try {
            Chart chart = buildChart();
            Chart saved = new Chart(editingId, chart.name(), chart.datasetId(), chart.type(),
                    chart.x(), chart.y(), chart.series(), chart.aggregate(),
                    chart.options(), null);
            charts.save(saved);
            editingId = saved.id();
            refreshList();
            charts.find(saved.id()).ifPresent(list::select);
            status.setText("已保存：" + saved.name());
        } catch (Exception e) {
            status.setText("保存失败：" + message(e));
        }
    }

    private void deleteCurrent() {
        if (editingId == null) {
            status.setText("当前没有已保存的图表");
            return;
        }
        try {
            String deleted = name.getValue();
            charts.delete(editingId);
            refreshList();
            newChart();
            status.setText("已删除：" + deleted);
        } catch (Exception e) {
            status.setText("删除失败：" + message(e));
        }
    }

    private Chart buildChart() {
        Dataset source = datasetItems.stream().filter(item -> item.name().equals(dataset.getValue()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("先选一个数据集"));
        return new Chart(editingId, name.getValue() == null || name.getValue().isBlank()
                ? "未命名图表" : name.getValue(),
                source.id(), type.getValue(), x.getValue(),
                new ArrayList<>(y.getValue()), series.getValue(), aggregate.getValue(),
                Map.of(), null);
    }

    private static String message(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    /** 测试用。 */
    public String statusText() {
        return status.getText();
    }
}
