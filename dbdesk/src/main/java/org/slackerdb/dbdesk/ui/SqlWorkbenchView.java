package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;

/**
 * SQL 控制台：只使用当前活动连接，不再提供 JDBC URL/历史连接下拉/管理连接等第二套入口。
 */
@Route(value = "sql", layout = MainLayout.class)
@RouteAlias("console")
public class SqlWorkbenchView extends VerticalLayout {

    private static final String SAMPLE_SQL = """
            -- Ctrl+Enter 或点「执行」
            """;

    private final Button newTab = new Button("+ 新建查询");
    private final Button renameTab = new Button("重命名");
    private final Button closeTab = new Button("关闭标签");
    private final TabSheet tabs = new TabSheet();
    private final String jdbcUrl;
    private final MetadataPanel metadata;

    private int tabSequence;

    public SqlWorkbenchView() {
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        H2 title = new H2("SQL 控制台");
        ConnectionManager connection = ConnectionManager.current();
        if (!connection.isConnected()) {
            jdbcUrl = null;
            metadata = null;
            Span guard = new Span("未连接服务端，请使用右上角 Connect。");
            guard.setId("sql-console-guard");
            add(title, guard);
            return;
        }
        if (!connection.hasDatabase()) {
            jdbcUrl = null;
            metadata = null;
            Span guard = new Span("当前连接只有 dbproxy 管理端口，没有数据库端口，SQL 控制台不可用。");
            guard.setId("sql-console-guard");
            add(title, guard);
            return;
        }
        jdbcUrl = connection.jdbcUrl();

        newTab.setId("new-tab-button");
        newTab.addClickListener(event -> addQueryTab());
        renameTab.setId("rename-tab-button");
        renameTab.addClickListener(event -> openRenameDialog());
        closeTab.setId("close-tab-button");
        closeTab.addClickListener(event -> closeCurrentTab());

        tabs.setId("query-tabs");
        tabs.setSizeFull();

        metadata = new MetadataPanel(jdbcUrl, this::exportFromMetadata);
        metadata.setId("metadata-panel");

        HorizontalLayout body = new HorizontalLayout(metadata, tabs);
        body.setWidthFull();
        body.setHeight("calc(100vh - 190px)");
        body.setFlexGrow(1, tabs);
        body.setPadding(false);

        add(title, new HorizontalLayout(newTab, renameTab, closeTab), body);
        addQueryTab();
    }

    private void addQueryTab() {
        if (jdbcUrl == null) {
            return;
        }
        tabSequence++;
        String suffix = tabSequence == 1 ? "" : "-" + tabSequence;
        QueryPanel panel = new QueryPanel(jdbcUrl, SAMPLE_SQL, suffix);
        Tab tab = tabs.add("查询 " + tabSequence, panel);
        tabs.setSelectedTab(tab);
    }

    /** 重命名当前标签。 */
    private void openRenameDialog() {
        Tab tab = tabs.getSelectedTab();
        if (tab == null) {
            return;
        }
        TextField name = new TextField("标签名");
        name.setValue(tab.getLabel());
        name.setId("rename-field");

        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("重命名标签");
        Button confirm = new Button("确定", event -> {
            if (name.getValue() != null && !name.getValue().isBlank()) {
                tab.setLabel(name.getValue().strip());
            }
            dialog.close();
        });
        confirm.setId("rename-confirm");
        Button cancel = new Button("取消", event -> dialog.close());
        dialog.add(new VerticalLayout(name, new HorizontalLayout(confirm, cancel)));
        dialog.open();
    }

    /** 关闭当前标签；关完最后一个就自动补一个新的。 */
    private void closeCurrentTab() {
        Tab tab = tabs.getSelectedTab();
        if (tab == null) {
            return;
        }
        com.vaadin.flow.component.Component content = tabs.getComponent(tab);
        if (content != null) {
            tabs.remove(content);
        }
        if (tabs.getChildren().findAny().isEmpty()) {
            tabSequence = 0;
            addQueryTab();
        }
    }

    /** 元数据树的"导出该表"：复用当前标签的导出流程。 */
    private void exportFromMetadata(String sql) {
        Tab selected = tabs.getSelectedTab();
        if (selected == null) {
            return;
        }
        if (tabs.getComponent(selected) instanceof QueryPanel panel) {
            panel.exportSql(sql, org.slackerdb.dbdesk.access.sql.ResultExporter.Format.CSV);
        }
    }
}
