package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.Uses;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import org.slackerdb.dbdesk.access.ConnectionProbe;
import org.slackerdb.dbdesk.access.profile.DbDeskProfile;

/**
 * 顶栏连接区：未连接时只有一个 Connect；连接后显示别名并提供切换/断开。
 */
@Uses(Dialog.class)
@Uses(ComboBox.class)
@Uses(IntegerField.class)
@Uses(TextField.class)
@JsModule("@vaadin/dialog/src/vaadin-dialog.js")
@JsModule("@vaadin/combo-box/src/vaadin-combo-box.js")
@JsModule("@vaadin/integer-field/src/vaadin-integer-field.js")
@JsModule("@vaadin/text-field/src/vaadin-text-field.js")
final class ConnectionBar extends HorizontalLayout {

    private final ConnectionManager manager;
    private final Span status = new Span("未连接");
    private final Runnable onChanged = this::refresh;

    ConnectionBar(ConnectionManager manager) {
        this.manager = manager;
        setDefaultVerticalComponentAlignment(Alignment.CENTER);
        setSpacing(true);
        status.setId("connection-status");
        manager.addListener(onChanged);
        refresh();
    }

    private void refresh() {
        removeAll();
        switch (manager.state()) {
            case DISCONNECTED -> {
                setStatus(I18n.t("connection.disconnected"), "var(--lumo-error-text-color)");
                Button connect = new Button(I18n.t("connection.connect"),
                        event -> ConnectDialog.open(manager, this::goMain));
                connect.setId("connect-server");
                connect.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
                add(status, connect);
            }
            case CONNECTING -> {
                setStatus(I18n.t("connection.connecting"), "#f9a825");
                Button connect = new Button(I18n.t("connection.connect"));
                connect.setId("connect-server");
                connect.setEnabled(false);
                add(status, connect);
            }
            case SQL_ONLY -> {
                setStatus(manager.profile().alias() + " · " + I18n.t("connection.sqlOnly"),
                        "var(--lumo-success-text-color)");
                add(status, switchButton(), disconnectButton());
            }
            case CONNECTED -> {
                setStatus(manager.profile().alias(), "var(--lumo-success-text-color)");
                add(status, switchButton(), disconnectButton());
            }
        }
    }

    private void setStatus(String text, String color) {
        status.setText("● " + text);
        status.getStyle().set("color", color).set("white-space", "nowrap");
    }

    private Button switchButton() {
        Button button = new Button(I18n.t("connection.switch"));
        button.setId("connection-menu-button");
        button.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        button.addClickListener(event -> openMenu());
        return button;
    }

    private Button disconnectButton() {
        Button button = new Button(I18n.t("connection.disconnect"), event -> {
            manager.disconnect();
            goMain();
        });
        button.setId("connection-disconnect");
        button.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
        return button;
    }

    private void openMenu() {
        Dialog dialog = new Dialog();
        dialog.setId("connection-menu");
        dialog.setHeaderTitle(I18n.t("connection.menu"));
        VerticalLayout content = new VerticalLayout();
        content.setPadding(false);

        Span error = new Span();
        error.setId("connection-menu-status");
        error.getStyle().set("color", "var(--lumo-error-text-color)");

        for (DbDeskProfile profile : manager.store().load().profiles()) {
            Button switchButton = new Button(profile.alias());
            switchButton.setId("switch-" + profile.alias());
            switchButton.setWidthFull();
            switchButton.addClickListener(event -> {
                try {
                    manager.switchTo(profile);
                    dialog.close();
                    goMain();
                } catch (ConnectionProbe.ProbeException e) {
                    error.setText(I18n.t("connection.failed") + ": " + e.getMessage());
                }
            });
            content.add(switchButton);
        }

        Button newConnection = new Button(I18n.t("connection.new"), event -> {
            dialog.close();
            ConnectDialog.open(manager, this::goMain);
        });
        newConnection.setId("connection-new");

        content.add(error, newConnection);
        dialog.add(content);
        dialog.open();
    }

    private void goMain() {
        getUI().ifPresent(ui -> ui.navigate("main"));
    }

    @Override
    protected void onDetach(com.vaadin.flow.component.DetachEvent detachEvent) {
        manager.removeListener(onChanged);
        super.onDetach(detachEvent);
    }
}
