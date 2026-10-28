package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.Route;
import org.slackerdb.dbdesk.access.http.ManagementClient;

/**
 * 日志页：只取最近 N 行，支持手动/自动刷新。
 */
@Route(value = "logs", layout = MainLayout.class)
public class LogsView extends VerticalLayout {

    private final ComboBox<Integer> lines = new ComboBox<>(I18n.t("log.lines"));
    private final Button refresh = new Button(I18n.t("common.refresh"));
    private final Checkbox autoRefresh = new Checkbox(I18n.t("log.autoRefresh"));
    private final Span status = new Span(I18n.t("log.status.ready"));
    private final TextArea content = new TextArea();

    private transient ManagementClient client;

    public LogsView() {
        setSizeFull();
        setPadding(true);
        setSpacing(true);
        I18n.setTitle("nav.logs");

        H2 title = new H2(I18n.t("log.title"));
        if (!ConnectionManager.current().hasInstanceManagement()) {
            Span guard = new Span(I18n.t("log.guard"));
            guard.setId("log-guard");
            add(title, guard);
            return;
        }
        Span endpoint = new Span(ConnectionManager.current().managementUrl());
        endpoint.setId("log-endpoint");

        lines.setId("log-lines");
        lines.setItems(100, 500, 1000, 5000);
        lines.setValue(500);
        lines.setWidth("140px");

        refresh.setId("log-refresh");
        refresh.addClickListener(event -> load());
        autoRefresh.setId("log-auto-refresh");
        autoRefresh.addValueChangeListener(event -> applyPolling());
        status.setId("log-status");

        content.setId("log-content");
        content.setReadOnly(true);
        content.setWidthFull();
        content.setHeight("calc(100vh - 260px)");
        content.getStyle().set("font-family", "monospace").set("font-size", "12px");

        add(title,
                new HorizontalLayout(new Span(I18n.t("management.label")), endpoint),
                new HorizontalLayout(lines, refresh, autoRefresh, status),
                content);
        setFlexGrow(1, content);
    }

    @Override
    protected void onAttach(com.vaadin.flow.component.AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        load();
    }

    private void load() {
        int count = lines.getValue() == null ? 500 : lines.getValue();
        status.setText(I18n.t("log.status.loading"));
        UI ui = getUI().orElse(null);
        ManagementClient management = client();
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                String text = management.logText(count);
                if (ui != null) {
                    ui.access(() -> {
                        content.setValue(text == null ? "" : text);
                        status.setText(I18n.t("log.status.loaded", count));
                    });
                }
            } catch (Exception e) {
                if (ui != null) {
                    ui.access(() -> status.setText(
                            I18n.t("log.status.loadFailed", I18n.message(e))));
                }
            }
        });
    }

    private void applyPolling() {
        getUI().ifPresent(ui -> {
            if (autoRefresh.getValue()) {
                ui.setPollInterval(5_000);
                ui.addPollListener(event -> load());
            } else {
                ui.setPollInterval(-1);
            }
        });
    }

    private ManagementClient client() {
        if (client == null) {
            client = ConnectionManager.current().managementClient();
        }
        return client;
    }

    @Override
    protected void onDetach(com.vaadin.flow.component.DetachEvent detachEvent) {
        getUI().ifPresent(ui -> ui.setPollInterval(-1));
        super.onDetach(detachEvent);
        if (client != null) {
            client.close();
            client = null;
        }
    }
}
