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

/** 连接对话框：默认空表单，不自动连接；连接成功后由 ConnectionManager 记住历史。 */
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
        IntegerField databasePort = new IntegerField("数据库端口");
        databasePort.setId("connect-db-port");
        databasePort.setMin(1);
        databasePort.setMax(65535);
        databasePort.setRequiredIndicatorVisible(true);
        IntegerField managementPort = new IntegerField("管理端口（可选）");
        managementPort.setId("connect-mgmt-port");
        managementPort.setMin(1);
        managementPort.setMax(65535);
        TextField database = new TextField("数据库名");
        database.setId("connect-database");
        database.setRequiredIndicatorVisible(true);

        Span status = new Span();
        status.setId("connect-status");
        status.getStyle().set("color", "var(--lumo-error-text-color)");

        history.addValueChangeListener(event -> {
            DbDeskProfile selected = event.getValue();
            if (selected != null) {
                alias.setValue(selected.alias());
                host.setValue(selected.host());
                databasePort.setValue(selected.databasePort());
                managementPort.setValue(selected.managementPort());
                database.setValue(selected.database());
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
            if (databasePort.getValue() == null) {
                status.setText("请填写数据库端口");
                return;
            }
            if (database.getValue() == null || database.getValue().isBlank()) {
                status.setText("请填写数据库名");
                return;
            }

            DbDeskProfile candidate;
            try {
                candidate = new DbDeskProfile(
                        alias.getValue(), host.getValue(), databasePort.getValue(),
                        managementPort.getValue(), database.getValue());
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
