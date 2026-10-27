package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import org.slackerdb.dbdesk.access.ConnectionProbe;
import org.slackerdb.dbdesk.access.profile.DbDeskProfile;
import org.slackerdb.dbdesk.access.profile.DbDeskProfileStore;

/**
 * 连接对话框：默认空表单，不自动连接；连接成功后由 ConnectionManager 记住历史。
 *
 * <p>没有“管理口路径”字段：管理口指向实例还是 dbproxy 由探测阶段判断；
 * 经代理时自动用数据库名作为别名。</p>
 */
final class ConnectDialog {

    private ConnectDialog() {
    }

    static void open(ConnectionManager manager, Runnable afterSuccess) {
        Dialog dialog = new Dialog();
        dialog.setId("connect-dialog");
        dialog.setHeaderTitle("连接服务端");
        dialog.setWidth("620px");

        ComboBox<DbDeskProfile> history = new ComboBox<>("历史连接");
        history.setId("connect-history");
        history.setWidthFull();
        history.setItemLabelGenerator(DbDeskProfile::alias);
        DbDeskProfileStore.Snapshot snapshot = manager.store().load();
        history.setItems(snapshot.profiles());

        TextField alias = new TextField("别名");
        alias.setId("connect-alias");
        alias.setRequiredIndicatorVisible(true);
        TextField host = new TextField("主机");
        host.setId("connect-host");
        host.setValue("127.0.0.1");
        host.setRequiredIndicatorVisible(true);
        IntegerField databasePort = new IntegerField("数据库端口（可选）");
        databasePort.setId("connect-db-port");
        databasePort.setMin(1);
        databasePort.setMax(65535);
        databasePort.setHelperText("与管理端口至少填一个；只填管理端口时进入 dbproxy 集群管理");
        IntegerField managementPort = new IntegerField("管理端口（可选）");
        managementPort.setId("connect-mgmt-port");
        managementPort.setMin(1);
        managementPort.setMax(65535);
        managementPort.setHelperText("实例或 dbproxy 的管理端口；程序会校验它与数据库端口是否匹配");
        TextField database = new TextField("数据库名");
        database.setId("connect-database");
        database.setHelperText("填写数据库端口时必填；只连代理管理端口时可留空");

        Span status = new Span();
        status.setId("connect-status");
        status.getStyle().set("color", "var(--lumo-error-text-color)");

        databasePort.addValueChangeListener(event ->
                database.setRequiredIndicatorVisible(event.getValue() != null));

        history.addValueChangeListener(event -> {
            DbDeskProfile selected = event.getValue();
            if (selected != null) {
                alias.setValue(selected.alias());
                host.setValue(selected.host());
                databasePort.setValue(selected.databasePort());
                managementPort.setValue(selected.managementPort());
                database.setValue(selected.database());
                database.setRequiredIndicatorVisible(selected.databasePort() != null);
                status.setText("");
            }
        });

        Button connect = new Button("Connect");
        connect.setId("connect-submit");
        connect.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        connect.addClickListener(event -> {
            status.setText("");
            if (alias.getValue() == null || alias.getValue().isBlank()) {
                status.setText("请填写别名");
                return;
            }
            if (host.getValue() == null || host.getValue().isBlank()) {
                status.setText("请填写主机");
                return;
            }
            Integer dbPort = databasePort.getValue();
            Integer mgmtPort = managementPort.getValue();
            if (dbPort == null && mgmtPort == null) {
                status.setText("数据库端口和管理端口至少填写一个");
                return;
            }
            if (dbPort != null && (database.getValue() == null || database.getValue().isBlank())) {
                status.setText("填写数据库端口时必须填写数据库名");
                return;
            }

            DbDeskProfile candidate;
            try {
                candidate = new DbDeskProfile(
                        alias.getValue(), host.getValue(), dbPort, mgmtPort, database.getValue());
            } catch (IllegalArgumentException e) {
                status.setText(e.getMessage());
                return;
            }

            connect.setEnabled(false);
            status.setText("连接中…");
            try {
                manager.connect(candidate);
                dialog.close();
                if (afterSuccess != null) {
                    afterSuccess.run();
                }
            } catch (ConnectionProbe.ProbeException e) {
                status.setText(e.getMessage());
                connect.setEnabled(true);
            } catch (RuntimeException e) {
                status.setText("连接失败：" + e.getMessage());
                connect.setEnabled(true);
            }
        });

        Button cancel = new Button("取消", event -> dialog.close());
        cancel.setId("connect-cancel");

        dialog.add(new VerticalLayout(
                history,
                new HorizontalLayout(alias, host),
                new HorizontalLayout(databasePort, managementPort),
                database,
                status,
                new HorizontalLayout(connect, cancel)));
        dialog.open();
    }
}
