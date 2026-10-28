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
    private final TextField name = new TextField(I18n.t("chart.name"));
    private final ComboBox<String> dataset = new ComboBox<>(I18n.t("chart.dataset"));
    private final ComboBox<String> type = new ComboBox<>(I18n.t("chart.type"));
    private final ComboBox<String> x = new ComboBox<>(I18n.t("chart.x"));
    private final MultiSelectComboBox<String> y = new MultiSelectComboBox<>(I18n.t("chart.y"));
    private final ComboBox<String> series = new ComboBox<>(I18n.t("chart.series"));
    private final ComboBox<String> aggregate = new ComboBox<>(I18n.t("chart.aggregate"));
    private final ChartView preview = new ChartView();
    private final Span status = new Span(I18n.t("chart.status.ready"));

    private final Button save = new Button(I18n.t("common.save"));
    private final Button render = new Button(I18n.t("chart.preview"));
    private final Button remove = new Button(I18n.t("common.delete"));
    private final Button create = new Button(I18n.t("common.create"));

    private String editingId;
    /** 数据集名 → Dataset（下拉里显示名字，内部要 id）。 */
    private List<Dataset> datasetItems = List.of();

    public ChartsView() {
        I18n.setTitle("nav.charts");
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        VerticalLayout left = new VerticalLayout();
        left.setWidth("300px");
        left.setHeightFull();
        left.setPadding(false);
        H2 title = new H2(I18n.t("nav.charts"));
        title.getStyle().set("margin", "0");
        create.setId("chart-new");
        create.addClickListener(event -> newChart());

        list.setId("chart-list");
        list.setWidthFull();
        list.setHeightFull();
        list.addColumn(Chart::name).setHeader(I18n.t("chart.column.name")).setAutoWidth(true);
        list.addColumn(Chart::type).setHeader(I18n.t("chart.column.type")).setAutoWidth(true);
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
        type.setItemLabelGenerator(ChartsView::typeLabel);
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
        aggregate.setItemLabelGenerator(ChartsView::aggregateLabel);
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
                new H3(I18n.t("chart.preview")), preview);
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
        preview.setOption(Map.of("title", Map.of("text", I18n.t("chart.preview.hint"))));
        status.setText(I18n.t("chart.status.new"));
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
        if (!status.getText().startsWith(I18n.t("chart.error.preview", ""))) {
            status.setText(I18n.t("chart.status.loaded", chart.name()));
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
            status.setText(I18n.t("chart.error.readColumns", I18n.message(e)));
            return List.of();
        }
    }

    private void renderPreview() {
        Dataset source = datasetItems.stream().filter(item -> item.name().equals(dataset.getValue()))
                .findFirst().orElse(null);
        if (source == null) {
            status.setText(I18n.t("chart.error.selectDataset"));
            return;
        }
        try {
            Chart chart = buildChart();
            DatasetRunner.Result rows = DatasetRunner.run(source, Map.of(), 2_000);
            ChartData.Result data = ChartData.build(rows, chart);
            preview.setOption(ChartOptions.toEcharts(chart, data));
            if (!data.ok()) {
                status.setText(I18n.t("chart.error.preview", data.error()));
                return;
            }
            status.setText(data.truncated()
                    ? I18n.t("chart.status.previewOkTruncated",
                            data.categories().size(), data.series().size())
                    : I18n.t("chart.status.previewOk",
                            data.categories().size(), data.series().size()));
        } catch (Exception e) {
            status.setText(I18n.t("chart.error.preview", I18n.message(e)));
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
            status.setText(I18n.t("chart.status.saved", saved.name()));
        } catch (Exception e) {
            status.setText(I18n.t("chart.error.save", I18n.message(e)));
        }
    }

    private void deleteCurrent() {
        if (editingId == null) {
            status.setText(I18n.t("chart.error.noSaved"));
            return;
        }
        try {
            String deleted = name.getValue();
            charts.delete(editingId);
            refreshList();
            newChart();
            status.setText(I18n.t("chart.status.deleted", deleted));
        } catch (Exception e) {
            status.setText(I18n.t("chart.error.delete", I18n.message(e)));
        }
    }

    private Chart buildChart() {
        Dataset source = datasetItems.stream().filter(item -> item.name().equals(dataset.getValue()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        I18n.t("chart.error.selectDataset")));
        return new Chart(editingId, name.getValue() == null || name.getValue().isBlank()
                ? I18n.t("chart.untitled") : name.getValue(),
                source.id(), type.getValue(), x.getValue(),
                new ArrayList<>(y.getValue()), series.getValue(), aggregate.getValue(),
                Map.of(), null);
    }

    /** 图表类型是数据值，下拉里只翻译显示。 */
    private static String typeLabel(String value) {
        return switch (value) {
            case "line" -> I18n.t("chart.type.line");
            case "bar" -> I18n.t("chart.type.bar");
            case "pie" -> I18n.t("chart.type.pie");
            case "scatter" -> I18n.t("chart.type.scatter");
            default -> value;
        };
    }

    /** 聚合方式同样是数据值，下拉里只翻译显示。 */
    private static String aggregateLabel(String value) {
        return switch (value) {
            case "none" -> I18n.t("chart.aggregate.none");
            case "sum" -> I18n.t("chart.aggregate.sum");
            case "avg" -> I18n.t("chart.aggregate.avg");
            case "count" -> I18n.t("chart.aggregate.count");
            case "min" -> I18n.t("chart.aggregate.min");
            case "max" -> I18n.t("chart.aggregate.max");
            default -> value;
        };
    }

    /** 测试用。 */
    public String statusText() {
        return status.getText();
    }
}
