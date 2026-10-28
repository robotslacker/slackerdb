package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import org.slackerdb.dbdesk.access.dataset.Dataset;
import org.slackerdb.dbdesk.access.dataset.DatasetRunner;
import org.slackerdb.dbdesk.access.dataset.DatasetStore;
import org.slackerdb.dbdesk.jsbridge.SqlEditor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据集管理页（M4-1）：左侧列表、右侧编辑 + 试跑。
 *
 * <p>为什么先做这一页：图表、仪表板、门户都消费数据集；没有它，图表编辑器里就只能各存一条 SQL，
 * 口径没法复用。这里把"查询 + 参数 + 列语义"沉淀成一个可命名的对象。</p>
 *
 * <p>试跑与保存都从编辑器<b>读回真实值</b>（{@code SqlEditor} 是 JS 组件，服务端缓存值可能过期），
 * 并且试跑用"编辑器里的 SQL"而不是已保存的定义 —— 改完不保存也能先看结果。</p>
 */
@Route(value = "datasets", layout = MainLayout.class)
public class DatasetsView extends HorizontalLayout {

    private final DatasetStore store = DatasetStore.defaultStore();

    private final Grid<Dataset> list = new Grid<>(Dataset.class, false);
    private final TextField name = new TextField(I18n.t("dataset.name"));
    private final SqlEditor sql = new SqlEditor("select 1 as a, 'x' as b");
    private final Grid<ParamRow> params = new Grid<>(ParamRow.class, false);
    private final Grid<Dataset.Column> columns = new Grid<>(Dataset.Column.class, false);
    private final Grid<List<Object>> preview = new Grid<>();
    private final Span status = new Span(I18n.t("dataset.status.ready"));

    private final Button save = new Button(I18n.t("common.save"));
    private final Button testRun = new Button(I18n.t("dataset.testRun"));
    private final Button remove = new Button(I18n.t("common.delete"));
    private final Button create = new Button(I18n.t("common.create"));

    private String editingId;
    private final List<ParamRow> paramRows = new ArrayList<>();

    /** 参数编辑行（可变，绑到界面控件）。 */
    public static class ParamRow {
        private String name;
        private String type = "text";
        private String defaultValue = "";

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public String getDefaultValue() {
            return defaultValue;
        }
    }

    public DatasetsView() {
        I18n.setTitle("nav.datasets");
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        VerticalLayout left = new VerticalLayout();
        left.setWidth("320px");
        left.setHeightFull();
        left.setPadding(false);
        H2 title = new H2(I18n.t("nav.datasets"));
        title.getStyle().set("margin", "0");
        if (!ConnectionManager.current().hasInstanceManagement()) {
            Span guard = new Span(I18n.t("dataset.guard"));
            guard.setId("dataset-guard");
            add(title, guard);
            return;
        }
        create.setId("dataset-new");
        create.addClickListener(event -> newDataset());

        list.setId("dataset-list");
        list.setWidthFull();
        list.setHeightFull();
        list.addColumn(Dataset::name).setHeader(I18n.t("dataset.name")).setAutoWidth(true);
        list.addColumn(Dataset::updatedAt).setHeader(I18n.t("dataset.updatedAt")).setAutoWidth(true);
        list.addSelectionListener(event -> event.getFirstSelectedItem().ifPresent(this::load));
        left.add(title, new HorizontalLayout(create), list);
        left.setFlexGrow(1, list);

        VerticalLayout right = new VerticalLayout();
        right.setHeightFull();
        right.setPadding(false);

        name.setId("dataset-name");
        name.setWidth("260px");

        sql.setId("dataset-sql");
        sql.setWidthFull();
        sql.setHeight("220px");

        params.setId("dataset-params");
        params.setWidthFull();
        params.setHeight("160px");
        params.addComponentColumn(row -> {
            TextField field = new TextField();
            field.setValue(row.name == null ? "" : row.name);
            field.setWidthFull();
            field.setPlaceholder(I18n.t("dataset.param.namePlaceholder"));
            field.addValueChangeListener(e -> row.name = e.getValue());
            return field;
        }).setHeader(I18n.t("dataset.column.paramName")).setFlexGrow(1);
        params.addComponentColumn(row -> {
            ComboBox<String> type = new ComboBox<>();
            type.setItems("text", "number", "date");
            type.setItemLabelGenerator(DatasetsView::paramTypeLabel);
            type.setValue(row.type);
            type.setWidthFull();
            type.addValueChangeListener(e -> row.type = e.getValue());
            return type;
        }).setHeader(I18n.t("dataset.column.type")).setWidth("140px");
        params.addComponentColumn(row -> {
            TextField field = new TextField();
            field.setValue(row.defaultValue == null ? "" : row.defaultValue);
            field.setWidthFull();
            field.setPlaceholder(I18n.t("dataset.param.defaultValuePlaceholder"));
            field.addValueChangeListener(e -> row.defaultValue = e.getValue());
            return field;
        }).setHeader(I18n.t("dataset.column.defaultValue")).setFlexGrow(1);

        Button addParam = new Button(I18n.t("dataset.addParam"));
        addParam.setId("dataset-add-param");
        addParam.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        addParam.addClickListener(event -> {
            ParamRow row = new ParamRow();
            paramRows.add(row);
            params.setItems(paramRows);
        });

        columns.setId("dataset-columns");
        columns.setWidthFull();
        columns.setHeight("160px");
        columns.addColumn(Dataset.Column::name).setHeader(I18n.t("dataset.column.name")).setAutoWidth(true);
        columns.addColumn(Dataset.Column::type).setHeader(I18n.t("dataset.column.type")).setAutoWidth(true);
        columns.addComponentColumn(column -> {
            ComboBox<String> role = new ComboBox<>();
            role.setItems("dimension", "measure", "ignore");
            role.setItemLabelGenerator(DatasetsView::roleLabel);
            role.setValue(column.role());
            role.setWidthFull();
            return role;
        }).setHeader(I18n.t("dataset.column.role")).setWidth("160px");

        save.setId("dataset-save");
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        save.addClickListener(event -> saveCurrent());
        testRun.setId("dataset-test");
        testRun.addClickListener(event -> testRun());
        remove.setId("dataset-delete");
        remove.addClickListener(event -> deleteCurrent());

        status.setId("dataset-status");

        preview.setId("dataset-preview");
        preview.setWidthFull();
        preview.setHeight("240px");

        right.add(new HorizontalLayout(name),
                sql,
                new H3(I18n.t("dataset.section.params")), new HorizontalLayout(addParam), params,
                new H3(I18n.t("dataset.section.columns")), columns,
                new HorizontalLayout(testRun, save, remove, status),
                new H3(I18n.t("dataset.section.preview")), preview);

        add(left, right);
        setFlexGrow(1, right);

        refreshList();
        newDataset();
    }

    private void refreshList() {
        list.setItems(store.all());
    }

    private void refreshListSelecting(String id) {
        refreshList();
        store.find(id).ifPresent(dataset -> list.select(dataset));
    }

    private void newDataset() {
        editingId = null;
        name.setValue("");
        sql.setValue("select 1 as a, 'x' as b");
        paramRows.clear();
        params.setItems(paramRows);
        columns.setItems(List.of());
        preview.removeAllColumns();
        preview.setItems(List.of());
        status.setText(I18n.t("dataset.status.new"));
        list.deselectAll();
    }

    private void load(Dataset dataset) {
        editingId = dataset.id();
        name.setValue(dataset.name());
        sql.setValue(dataset.sql());
        paramRows.clear();
        for (Dataset.Param param : dataset.params()) {
            ParamRow row = new ParamRow();
            row.name = param.name();
            row.type = param.type();
            row.defaultValue = param.defaultValue();
            paramRows.add(row);
        }
        params.setItems(paramRows);
        columns.setItems(dataset.columns());
        status.setText(I18n.t("dataset.status.loaded", dataset.name()));
    }

    private void saveCurrent() {
        sql.readValue(sqlText -> {
            try {
                Dataset dataset = new Dataset(editingId, name.getValue(), sqlText, activeJdbcUrl(),
                        currentParams(), columns.getListDataView().getItems().toList(), null);
                store.save(dataset);
                editingId = dataset.id();
                refreshListSelecting(dataset.id());
                status.setText(I18n.t("dataset.status.saved", dataset.name()));
            } catch (Exception e) {
                status.setText(I18n.t("dataset.status.saveFailed", I18n.message(e)));
            }
        });
    }

    private void deleteCurrent() {
        if (editingId == null) {
            status.setText(I18n.t("dataset.status.none"));
            return;
        }
        try {
            String deleted = name.getValue();
            store.delete(editingId);
            refreshList();
            newDataset();
            // newDataset() 会把状态重置成"新建数据集"：删除成功的信息要在它之后写
            status.setText(I18n.t("dataset.status.deleted", deleted));
        } catch (Exception e) {
            status.setText(I18n.t("dataset.status.deleteFailed", I18n.message(e)));
        }
    }

    private void testRun() {
        sql.readValue(sqlText -> {
            try {
                Dataset probe = new Dataset(editingId, name.getValue() == null || name.getValue().isBlank()
                        ? I18n.t("dataset.untitled") : name.getValue(), sqlText, activeJdbcUrl(),
                        currentParams(), List.of(), null);
                DatasetRunner.Result result = DatasetRunner.run(probe, Map.of(), 1000);
                showPreview(result);
                List<Dataset.Column> inferred = new ArrayList<>();
                for (int i = 0; i < result.columns().size(); i++) {
                    String type = i < result.columnTypes().size() ? result.columnTypes().get(i) : "text";
                    inferred.add(new Dataset.Column(result.columns().get(i), type,
                            "number".equals(type) ? "measure" : "dimension"));
                }
                columns.setItems(inferred);
                int rowCount = result.rows().size();
                status.setText(result.truncated()
                        ? I18n.t("dataset.status.testOk.truncated", rowCount, result.elapsedMillis(), 1000)
                        : I18n.t("dataset.status.testOk", rowCount, result.elapsedMillis()));
            } catch (Exception e) {
                status.setText(I18n.t("dataset.status.testFailed", I18n.message(e)));
            }
        });
    }

    private void showPreview(DatasetRunner.Result result) {
        preview.removeAllColumns();
        for (int i = 0; i < result.columns().size(); i++) {
            int index = i;
            preview.addColumn(row -> index < row.size() && row.get(index) != null
                    ? String.valueOf(row.get(index)) : "").setHeader(result.columns().get(index));
        }
        preview.setItems(result.rows());
    }

    private List<Dataset.Param> currentParams() {
        List<Dataset.Param> result = new ArrayList<>();
        for (ParamRow row : paramRows) {
            if (row.name == null || row.name.isBlank()) {
                continue;
            }
            result.add(new Dataset.Param(row.name, row.type, row.defaultValue, ""));
        }
        return result;
    }

    private static String activeJdbcUrl() {
        return ConnectionManager.current().jdbcUrl();
    }

    /**
     * 参数类型的显示文案：下拉框里的 value 仍是 {@code text} / {@code number} / {@code date}
     * （保存到数据集里的线值不变），只有标签跟着语言走。
     */
    private static String paramTypeLabel(String type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case "number" -> I18n.t("dataset.paramType.number");
            case "date" -> I18n.t("dataset.paramType.date");
            case "text" -> I18n.t("dataset.paramType.text");
            default -> type;
        };
    }

    /**
     * 列角色的显示文案：value 仍是 {@code dimension} / {@code measure} / {@code ignore}，
     * 只有标签跟着语言走。
     */
    private static String roleLabel(String role) {
        if (role == null) {
            return "";
        }
        return switch (role) {
            case "dimension" -> I18n.t("dataset.role.dimension");
            case "measure" -> I18n.t("dataset.role.measure");
            case "ignore" -> I18n.t("dataset.role.ignore");
            default -> role;
        };
    }

    /** 测试用：当前状态文本。 */
    public String statusText() {
        return status.getText();
    }
}
