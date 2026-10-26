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
    private final TextField name = new TextField("名称");
    private final SqlEditor sql = new SqlEditor("select 1 as a, 'x' as b");
    private final Grid<ParamRow> params = new Grid<>(ParamRow.class, false);
    private final Grid<Dataset.Column> columns = new Grid<>(Dataset.Column.class, false);
    private final Grid<List<Object>> preview = new Grid<>();
    private final Span status = new Span("就绪");

    private final Button save = new Button("保存");
    private final Button testRun = new Button("试跑");
    private final Button remove = new Button("删除");
    private final Button create = new Button("新建");

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
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        VerticalLayout left = new VerticalLayout();
        left.setWidth("320px");
        left.setHeightFull();
        left.setPadding(false);
        H2 title = new H2("数据集");
        title.getStyle().set("margin", "0");
        if (!ConnectionManager.current().hasManagement()) {
            Span guard = new Span("当前连接不可用，请先连接带管理口的服务端。");
            guard.setId("dataset-guard");
            add(title, guard);
            return;
        }
        create.setId("dataset-new");
        create.addClickListener(event -> newDataset());

        list.setId("dataset-list");
        list.setWidthFull();
        list.setHeightFull();
        list.addColumn(Dataset::name).setHeader("名称").setAutoWidth(true);
        list.addColumn(Dataset::updatedAt).setHeader("更新").setAutoWidth(true);
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
            field.setPlaceholder("参数名（对应 SQL 里的 :name）");
            field.addValueChangeListener(e -> row.name = e.getValue());
            return field;
        }).setHeader("参数名").setFlexGrow(1);
        params.addComponentColumn(row -> {
            ComboBox<String> type = new ComboBox<>();
            type.setItems("text", "number", "date");
            type.setValue(row.type);
            type.setWidthFull();
            type.addValueChangeListener(e -> row.type = e.getValue());
            return type;
        }).setHeader("类型").setWidth("140px");
        params.addComponentColumn(row -> {
            TextField field = new TextField();
            field.setValue(row.defaultValue == null ? "" : row.defaultValue);
            field.setWidthFull();
            field.setPlaceholder("默认值");
            field.addValueChangeListener(e -> row.defaultValue = e.getValue());
            return field;
        }).setHeader("默认值").setFlexGrow(1);

        Button addParam = new Button("添加参数");
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
        columns.addColumn(Dataset.Column::name).setHeader("列").setAutoWidth(true);
        columns.addColumn(Dataset.Column::type).setHeader("类型").setAutoWidth(true);
        columns.addComponentColumn(column -> {
            ComboBox<String> role = new ComboBox<>();
            role.setItems("dimension", "measure", "ignore");
            role.setValue(column.role());
            role.setWidthFull();
            return role;
        }).setHeader("角色").setWidth("160px");

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
                new H3("参数"), new HorizontalLayout(addParam), params,
                new H3("列（试跑后自动推断，可调整角色）"), columns,
                new HorizontalLayout(testRun, save, remove, status),
                new H3("预览"), preview);

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
        status.setText("新建数据集");
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
        status.setText("已载入：" + dataset.name());
    }

    private void saveCurrent() {
        sql.readValue(sqlText -> {
            try {
                Dataset dataset = new Dataset(editingId, name.getValue(), sqlText, activeJdbcUrl(),
                        currentParams(), columns.getListDataView().getItems().toList(), null);
                store.save(dataset);
                editingId = dataset.id();
                refreshListSelecting(dataset.id());
                status.setText("已保存：" + dataset.name());
            } catch (Exception e) {
                status.setText("保存失败：" + message(e));
            }
        });
    }

    private void deleteCurrent() {
        if (editingId == null) {
            status.setText("当前没有已保存的数据集");
            return;
        }
        try {
            String deleted = name.getValue();
            store.delete(editingId);
            refreshList();
            newDataset();
            // newDataset() 会把状态重置成"新建数据集"：删除成功的信息要在它之后写
            status.setText("已删除：" + deleted);
        } catch (Exception e) {
            status.setText("删除失败：" + message(e));
        }
    }

    private void testRun() {
        sql.readValue(sqlText -> {
            try {
                Dataset probe = new Dataset(editingId, name.getValue() == null || name.getValue().isBlank()
                        ? "临时" : name.getValue(), sqlText, activeJdbcUrl(),
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
                status.setText(String.format("试跑成功：%,d 行 / %d ms%s",
                        result.rows().size(), result.elapsedMillis(),
                        result.truncated() ? "（已截断到 1000 行）" : ""));
            } catch (Exception e) {
                status.setText("试跑失败：" + message(e));
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

    private static String message(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    /** 测试用：当前状态文本。 */
    public String statusText() {
        return status.getText();
    }
}
