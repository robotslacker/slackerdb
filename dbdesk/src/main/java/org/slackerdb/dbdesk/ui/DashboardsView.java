package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import org.slackerdb.dbdesk.access.chart.Chart;
import org.slackerdb.dbdesk.access.chart.ChartStore;
import org.slackerdb.dbdesk.access.dashboard.Dashboard;
import org.slackerdb.dbdesk.access.dashboard.DashboardData;
import org.slackerdb.dbdesk.access.dashboard.DashboardStore;
import org.slackerdb.dbdesk.access.dataset.DatasetStore;
import org.slackerdb.dbdesk.jsbridge.ChartView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仪表板编辑器（M4-3）：栅格布局的图表卡片 + 全局筛选。
 *
 * <p>布局用 12 列栅格（{@code x/w}）+ 行高（{@code h}），卡片宽度按 {@code span w} 排。
 * 不做自由像素拖拽：栅格能表达绝大多数看板，且不会因为窗口变化错位。</p>
 *
 * <p>刷新时逐卡片渲染，单卡失败只在该卡显示错误，不影响整页。</p>
 */
@Route(value = "dashboards", layout = MainLayout.class)
public class DashboardsView extends HorizontalLayout {

    private final DashboardStore dashboards = DashboardStore.defaultStore();
    private final ChartStore charts = ChartStore.defaultStore();
    private final DatasetStore datasets = DatasetStore.defaultStore();
    private final DashboardData renderer = new DashboardData(datasets, charts);

    private final Grid<Dashboard> list = new Grid<>(Dashboard.class, false);
    private final TextField name = new TextField("名称");
    private final Grid<Dashboard.Filter> filters = new Grid<>(Dashboard.Filter.class, false);
    private final List<Dashboard.Filter> filterRows = new ArrayList<>();
    private final Map<String, TextField> filterInputs = new LinkedHashMap<>();
    private final Grid<Dashboard.Card> cards = new Grid<>(Dashboard.Card.class, false);
    private final List<Dashboard.Card> cardRows = new ArrayList<>();
    private final ComboBox<String> chartToAdd = new ComboBox<>("添加图表卡片");
    private final Div canvas = new Div();
    private final Span status = new Span("就绪");

    private final Button refresh = new Button("刷新全部");
    private final Button save = new Button("保存");
    private final Button remove = new Button("删除");
    private final Button create = new Button("新建");

    private String editingId;
    private List<Chart> chartItems = List.of();

    public DashboardsView() {
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        VerticalLayout left = new VerticalLayout();
        left.setWidth("300px");
        left.setHeightFull();
        left.setPadding(false);
        H2 title = new H2("仪表板");
        title.getStyle().set("margin", "0");
        create.setId("dashboard-new");
        create.addClickListener(event -> newDashboard());

        list.setId("dashboard-list");
        list.setWidthFull();
        list.setHeightFull();
        list.addColumn(Dashboard::name).setHeader("名称").setAutoWidth(true);
        list.addSelectionListener(event -> event.getFirstSelectedItem().ifPresent(this::load));
        left.add(title, new HorizontalLayout(create), list);
        left.setFlexGrow(1, list);

        VerticalLayout right = new VerticalLayout();
        right.setPadding(false);
        name.setId("dashboard-name");
        name.setWidth("260px");

        filters.setId("dashboard-filters");
        filters.setHeight("120px");
        filters.addComponentColumn(filter -> {
            TextField field = new TextField();
            field.setValue(filter.name());
            field.setWidthFull();
            field.addValueChangeListener(event -> filterRows.set(filterRows.indexOf(filter),
                    new Dashboard.Filter(event.getValue(), filter.type(), filter.defaultValue())));
            return field;
        }).setHeader("筛选名").setFlexGrow(1);
        filters.addColumn(Dashboard.Filter::type).setHeader("类型").setAutoWidth(true);
        filters.addComponentColumn(filter -> {
            TextField field = new TextField();
            field.setValue(filter.defaultValue());
            field.setWidthFull();
            field.addValueChangeListener(event -> filterRows.set(filterRows.indexOf(filter),
                    new Dashboard.Filter(filter.name(), filter.type(), event.getValue())));
            return field;
        }).setHeader("默认值").setFlexGrow(1);
        Button addFilter = new Button("添加筛选");
        addFilter.setId("dashboard-add-filter");
        addFilter.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        addFilter.addClickListener(event -> {
            Dashboard.Filter filter = new Dashboard.Filter("param" + (filterRows.size() + 1), "text", "");
            filterRows.add(filter);
            filters.setItems(filterRows);
        });

        cards.setId("dashboard-cards");
        cards.setHeight("160px");
        cards.addColumn(card -> chartName(card.chartId())).setHeader("图表").setAutoWidth(true);
        cards.addColumn(card -> card.w() + "×" + card.h()).setHeader("尺寸").setAutoWidth(true);
        cards.addComponentColumn(card -> {
            Button delete = new Button("移除");
            delete.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
            delete.addClickListener(event -> {
                cardRows.remove(card);
                cards.setItems(cardRows);
            });
            return delete;
        }).setHeader("");

        chartToAdd.setId("dashboard-chart-to-add");
        chartToAdd.setWidth("260px");
        Button addCard = new Button("添加卡片");
        addCard.setId("dashboard-add-card");
        addCard.addClickListener(event -> {
            if (chartToAdd.getValue() == null) {
                status.setText("先选一个图表");
                return;
            }
            String chartId = chartItems.stream().filter(chart -> chart.name().equals(chartToAdd.getValue()))
                    .map(Chart::id).findFirst().orElse(null);
            if (chartId == null) {
                return;
            }
            cardRows.add(new Dashboard.Card(chartId, 0,
                    cardRows.stream().mapToInt(Dashboard.Card::y).max().orElse(-1) + 1, 6, 4));
            cards.setItems(cardRows);
        });

        refresh.setId("dashboard-refresh");
        refresh.addClickListener(event -> render());
        save.setId("dashboard-save");
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        save.addClickListener(event -> saveCurrent());
        remove.setId("dashboard-delete");
        remove.addClickListener(event -> deleteCurrent());
        status.setId("dashboard-status");

        canvas.setId("dashboard-canvas");
        canvas.setWidthFull();
        canvas.getStyle()
                .set("display", "grid")
                .set("grid-template-columns", "repeat(12, 1fr)")
                .set("gap", "12px")
                .set("min-height", "200px");

        right.add(new HorizontalLayout(name),
                new H3("全局筛选"), new HorizontalLayout(addFilter), filters,
                new H3("卡片"), new HorizontalLayout(chartToAdd, addCard), cards,
                new HorizontalLayout(refresh, save, remove, status),
                new H3("看板"), canvas);
        right.setFlexGrow(1, canvas);

        add(left, right);
        setFlexGrow(1, right);

        refreshList();
        refreshCharts();
        newDashboard();
    }

    private void refreshList() {
        list.setItems(dashboards.all());
    }

    private void refreshCharts() {
        chartItems = charts.all();
        chartToAdd.setItems(chartItems.stream().map(Chart::name).toList());
    }

    private String chartName(String chartId) {
        return chartItems.stream().filter(chart -> chart.id().equals(chartId))
                .map(Chart::name).findFirst().orElse("(图表已删除)");
    }

    private void newDashboard() {
        editingId = null;
        name.setValue("");
        filterRows.clear();
        filters.setItems(filterRows);
        cardRows.clear();
        cards.setItems(cardRows);
        canvas.removeAll();
        status.setText("新建仪表板");
        list.deselectAll();
    }

    private void load(Dashboard dashboard) {
        editingId = dashboard.id();
        name.setValue(dashboard.name());
        filterRows.clear();
        filterRows.addAll(dashboard.filters());
        filters.setItems(filterRows);
        cardRows.clear();
        cardRows.addAll(dashboard.cards());
        cards.setItems(cardRows);
        status.setText("已载入：" + dashboard.name());
        render();
        if (!status.getText().startsWith("刷新失败")) {
            status.setText("已载入：" + dashboard.name());
        }
    }

    private void saveCurrent() {
        try {
            Dashboard dashboard = new Dashboard(editingId, name.getValue() == null || name.getValue().isBlank()
                    ? "未命名看板" : name.getValue(), filterRows, cardRows, null);
            dashboards.save(dashboard);
            editingId = dashboard.id();
            refreshList();
            dashboards.find(dashboard.id()).ifPresent(list::select);
            status.setText("已保存：" + dashboard.name());
        } catch (Exception e) {
            status.setText("保存失败：" + message(e));
        }
    }

    private void deleteCurrent() {
        if (editingId == null) {
            status.setText("当前没有已保存的仪表板");
            return;
        }
        try {
            String deleted = name.getValue();
            dashboards.delete(editingId);
            refreshList();
            newDashboard();
            status.setText("已删除：" + deleted);
        } catch (Exception e) {
            status.setText("删除失败：" + message(e));
        }
    }

    /** 用当前编辑中的定义（不要求先保存）刷新看板。 */
    private void render() {
        Dashboard dashboard = new Dashboard(editingId, name.getValue() == null || name.getValue().isBlank()
                ? "未命名看板" : name.getValue(), filterRows, cardRows, null);
        Map<String, Object> values = new LinkedHashMap<>();
        for (Dashboard.Filter filter : dashboard.filters()) {
            TextField input = filterInputs.get(filter.name());
            if (input != null && input.getValue() != null && !input.getValue().isBlank()) {
                values.put(filter.name(), input.getValue());
            }
        }
        DashboardData.Result result = renderer.render(dashboard, values, 2_000);
        canvas.removeAll();
        for (DashboardData.CardResult card : result.cards()) {
            canvas.add(buildCard(card));
        }
        status.setText(String.format("刷新成功：%d 张卡片 / %d ms", result.cards().size(), result.elapsedMillis()));
    }

    private VerticalLayout buildCard(DashboardData.CardResult card) {
        VerticalLayout box = new VerticalLayout();
        box.setPadding(false);
        box.setSpacing(false);
        box.getStyle()
                .set("grid-column", "span " + card.card().w())
                .set("border", "1px solid var(--lumo-contrast-10pct)")
                .set("border-radius", "8px")
                .set("padding", "8px")
                .set("background", "var(--lumo-base-color)");
        H4 title = new H4(card.chartName());
        title.getStyle().set("margin", "0 0 4px 0");
        box.add(title);
        if (card.error() != null) {
            box.add(new Span("加载失败：" + card.error()));
            box.setHeight("120px");
            return box;
        }
        ChartView view = new ChartView();
        view.setId("dashboard-card-" + card.card().chartId());
        view.setWidthFull();
        view.setHeight((card.card().h() * 70) + "px");
        view.setOption(card.option());
        box.add(view);
        return box;
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
