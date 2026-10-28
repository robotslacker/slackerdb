package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.treegrid.TreeGrid;
import org.slackerdb.dbdesk.access.sql.MetadataReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 左侧元数据浏览器：schema → 表（树）+ 选中表的列（表格）+ "导出该表"。
 *
 * <p>为什么用 {@link TreeGrid} 而不是自己拼节点：树 + 展开/折叠 + 键盘导航都是现成的；
 * 数据量在 M1 只有"几十张表"的量级，**一次性把表读全**比懒加载更简单，也不会出现"展开一半卡住"。</p>
 *
 * <p>列是选中表之后再查（一张表一次查询），避免启动时 N+1。</p>
 */
public class MetadataPanel extends VerticalLayout {

    /** 树节点：schema 或表。 */
    private record Node(String label, String schema, String table) {
        boolean isTable() {
            return table != null;
        }
    }

    private final TreeGrid<Node> tree = new TreeGrid<>();
    private final Grid<MetadataReader.ColumnRef> columns = new Grid<>(MetadataReader.ColumnRef.class, false);
    private final Span status = new Span(I18n.t("metadata.status.idle"));
    private final Button refresh = new Button(I18n.t("common.refresh"));
    private final Button exportTable = new Button(I18n.t("metadata.exportTable"));

    private String jdbcUrl;
    /** "导出该表"：交给工作台复用同一套导出流程（进度/取消/下载链接都在那边）。 */
    private final Consumer<String> onExportTable;
    private Node selectedTable;

    public MetadataPanel(String jdbcUrl, Consumer<String> onExportTable) {
        this.jdbcUrl = jdbcUrl;
        this.onExportTable = onExportTable;
        setWidth("340px");
        setHeightFull();
        setPadding(false);
        setSpacing(true);

        H3 title = new H3(I18n.t("metadata.title"));
        title.getStyle().set("margin", "0");

        refresh.setId("metadata-refresh");
        refresh.addThemeVariants(ButtonVariant.LUMO_SMALL);
        refresh.addClickListener(event -> reload());

        status.setId("metadata-status");
        status.getStyle().set("font-size", "var(--lumo-font-size-s)");

        tree.setId("metadata-tree");
        tree.setHeight("45%");
        tree.addHierarchyColumn(Node::label).setHeader(I18n.t("metadata.column.schemaTable")).setAutoWidth(true);
        tree.addSelectionListener(event -> event.getFirstSelectedItem().ifPresent(this::select));

        columns.setId("metadata-columns");
        columns.setHeight("40%");
        columns.addColumn(MetadataReader.ColumnRef::name).setHeader(I18n.t("metadata.column.name")).setAutoWidth(true);
        columns.addColumn(MetadataReader.ColumnRef::type).setHeader(I18n.t("metadata.column.type")).setAutoWidth(true);
        columns.addColumn(c -> I18n.t(c.nullable() ? "common.yes" : "common.no"))
                .setHeader(I18n.t("metadata.column.nullable")).setAutoWidth(true);

        exportTable.setId("metadata-export-table");
        exportTable.setEnabled(false);
        exportTable.addThemeVariants(ButtonVariant.LUMO_SMALL);
        exportTable.addClickListener(event -> exportSelected());

        add(title, new HorizontalLayout(refresh, status), tree, columns,
                new HorizontalLayout(exportTable));
        setFlexGrow(1, tree);

        reload();
    }

    /** 换连接串后重新读（"重连"按钮用）。 */
    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        reload();
    }

    /** 重新读表清单（列在选中时再读）。 */
    public void reload() {
        try {
            List<MetadataReader.TableRef> tables = MetadataReader.tables(jdbcUrl);
            Map<String, List<Node>> bySchema = new LinkedHashMap<>();
            for (MetadataReader.TableRef table : tables) {
                bySchema.computeIfAbsent(table.schema(), key -> new ArrayList<>())
                        .add(new Node(I18n.t("metadata.tree.tableLabel",
                                table.name(), table.type().toLowerCase().replace("base ", "")),
                                table.schema(), table.name()));
            }
            List<Node> roots = new ArrayList<>();
            bySchema.forEach((schema, children) -> roots.add(new Node(schema, schema, null)));

            tree.setItems(roots, node -> node.isTable() ? List.of()
                    : bySchema.getOrDefault(node.schema(), List.of()));
            // M1 的表不多，默认展开一眼看到底；表多了再改成懒展开
            tree.expand(roots);
            status.setText(I18n.t("metadata.status.loaded", tables.size()));
        } catch (Exception e) {
            tree.setItems(List.of(), node -> List.of());
            columns.setItems(List.of());
            status.setText(I18n.t("metadata.status.loadFailed", I18n.message(e)));
        }
    }

    private void select(Node node) {
        if (!node.isTable()) {
            return;
        }
        selectedTable = node;
        exportTable.setEnabled(true);
        try {
            columns.setItems(MetadataReader.columns(jdbcUrl, node.schema(), node.table()));
        } catch (Exception e) {
            columns.setItems(List.of());
            status.setText(I18n.t("metadata.status.columnsFailed", I18n.message(e)));
        }
    }

    private void exportSelected() {
        if (selectedTable == null) {
            return;
        }
        onExportTable.accept("select * from " + selectedTable.schema() + "." + selectedTable.table());
    }

    /** 测试用：状态文案。 */
    public String statusText() {
        return status.getText();
    }
}
