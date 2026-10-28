package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import org.slackerdb.dbdesk.access.http.Capabilities;
import org.slackerdb.dbdesk.access.http.Health;
import org.slackerdb.dbdesk.access.http.ManagementClient;
import org.slackerdb.dbdesk.access.http.ManagementException;
import org.slackerdb.dbdesk.access.http.PluginActionResult;
import org.slackerdb.dbdesk.access.http.ServerStatus;
import org.slackerdb.dbdesk.access.http.SessionInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 实例状态页：能力、健康、服务器/数据库指标、参数、usage、扩展、插件与会话。
 *
 * <p>不是"又一个验证页"：菜单/提示由 {@code /capabilities} 驱动，健康红点由 {@code /health}
 * 驱动，详情与会话来自 {@code /status}，终止走 {@code POST /sessions/{id}/kill}。
 * 视图层只做展示与编排，HTTP 全部在 {@link ManagementClient}。</p>
 */
@Route(value = "instances", layout = MainLayout.class)
@RouteAlias("m1-ops")
public class InstancesView extends VerticalLayout {

    private final Button connect = new Button(I18n.t("common.refresh"));
    private final com.vaadin.flow.component.checkbox.Checkbox autoRefresh =
            new com.vaadin.flow.component.checkbox.Checkbox(I18n.t("instance.autoRefresh"));
    private final Span capabilitySummary = new Span(I18n.t("instance.status.disconnected"));
    private final Span healthStatus = new Span("—");
    private final Span menuHint = new Span("—");
    private final Span serverSummary = new Span("—");
    private final Span databaseSummary = new Span("—");
    private final Span errorText = new Span();
    private final TextField pluginFile = new TextField();
    private final Button pluginLoad = new Button(I18n.t("instance.plugin.load"));
    private final Checkbox pluginStartAfterLoad = new Checkbox(I18n.t("instance.plugin.startAfterLoad"), true);
    private final Span pluginStatus = new Span();
    private final Grid<SessionInfo> sessionGrid = new Grid<>(SessionInfo.class, false);
    private final Grid<MapRow> parametersGrid = new Grid<>(MapRow.class, false);
    private final Grid<MapRow> usageGrid = new Grid<>(MapRow.class, false);
    private final Grid<String> extensionsGrid = new Grid<>(String.class, false);
    private final Grid<ServerStatus.PluginInfo> pluginGrid = new Grid<>(ServerStatus.PluginInfo.class, false);

    private transient ManagementClient client;
    private List<SessionInfo> sessions = List.of();
    private boolean pluginBusy;

    /** 一行键值。 */
    public record MapRow(String key, String value) {
    }

    /** 插件操作可能抛受检异常，Supplier 不够用。 */
    @FunctionalInterface
    private interface PluginCall {
        PluginActionResult run() throws ManagementException;
    }

    public InstancesView() {
        setPadding(true);
        setSpacing(true);
        I18n.setTitle("nav.instances");

        H2 title = new H2(I18n.t("instance.title"));
        if (!ConnectionManager.current().hasInstanceManagement()) {
            Span guard = new Span(I18n.t("instance.guard"));
            guard.setId("instances-guard");
            add(title, guard);
            return;
        }

        connect.setId("connect-button");
        connect.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        connect.addClickListener(event -> refresh());

        autoRefresh.setId("auto-refresh");
        autoRefresh.addValueChangeListener(event -> applyPolling());

        capabilitySummary.setId("cap-summary");
        healthStatus.setId("health-status");
        menuHint.setId("menu-hint");
        serverSummary.setId("server-summary");
        databaseSummary.setId("database-summary");
        errorText.setId("error-text");
        errorText.getStyle().set("color", "var(--lumo-error-text-color)");

        pluginFile.setId("plugin-file");
        pluginFile.setWidth("420px");
        pluginFile.setPlaceholder(I18n.t("instance.plugin.file.placeholder"));
        pluginFile.setHelperText(I18n.t("instance.plugin.file.helper"));
        pluginLoad.setId("plugin-load");
        pluginLoad.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        pluginLoad.addClickListener(event -> loadPlugin());
        pluginStartAfterLoad.setId("plugin-start-after-load");
        pluginStatus.setId("plugin-status");
        pluginStatus.getStyle().set("color", "var(--lumo-secondary-text-color)");

        configureGrids();

        add(title,
                new HorizontalLayout(connect, autoRefresh),
                new HorizontalLayout(new Span(I18n.t("instance.label.capabilities")), capabilitySummary),
                new HorizontalLayout(new Span(I18n.t("instance.label.health")), healthStatus),
                new HorizontalLayout(new Span(I18n.t("instance.label.menu")), menuHint),
                new HorizontalLayout(new Span(I18n.t("instance.label.server")), serverSummary),
                new HorizontalLayout(new Span(I18n.t("instance.label.database")), databaseSummary),
                errorText,
                new H3(I18n.t("instance.sessions")), sessionGrid,
                new H3(I18n.t("instance.parameters")), parametersGrid,
                new H3("Usage"), usageGrid,
                new H3(I18n.t("instance.extensions")), extensionsGrid,
                new H3(I18n.t("instance.plugins")),
                new HorizontalLayout(pluginFile, pluginLoad, pluginStartAfterLoad),
                pluginStatus,
                pluginGrid);
        setFlexGrow(1, sessionGrid);
        refresh();
    }

    private void configureGrids() {
        sessionGrid.setId("session-grid");
        sessionGrid.setWidthFull();
        sessionGrid.setHeight("260px");
        sessionGrid.addColumn(SessionInfo::id).setHeader("id").setAutoWidth(true);
        sessionGrid.addColumn(session -> session.fromConsole()
                        ? I18n.t("instance.source.console", nullToDash(session.applicationName()))
                        : nullToDash(session.applicationName()))
                .setHeader(I18n.t("instance.column.source")).setAutoWidth(true);
        sessionGrid.addColumn(SessionInfo::database).setHeader(I18n.t("instance.column.database")).setAutoWidth(true);
        sessionGrid.addColumn(SessionInfo::clientIp).setHeader(I18n.t("instance.column.client")).setAutoWidth(true);
        sessionGrid.addColumn(SessionInfo::connectedTime).setHeader(I18n.t("instance.column.connectedTime")).setAutoWidth(true);
        sessionGrid.addColumn(session -> session.busy()
                        ? I18n.t("instance.session.executing")
                        : I18n.t("instance.session.idle"))
                .setHeader(I18n.t("instance.column.status")).setAutoWidth(true);
        sessionGrid.addComponentColumn(this::killButton).setHeader("").setAutoWidth(true);

        parametersGrid.setId("parameters-grid");
        parametersGrid.setWidthFull();
        parametersGrid.setHeight("220px");
        parametersGrid.addColumn(MapRow::key).setHeader(I18n.t("instance.parameters")).setAutoWidth(true);
        parametersGrid.addColumn(MapRow::value).setHeader(I18n.t("instance.column.value")).setAutoWidth(true);

        usageGrid.setId("usage-grid");
        usageGrid.setWidthFull();
        usageGrid.setHeight("220px");
        usageGrid.addColumn(MapRow::key).setHeader(I18n.t("instance.column.metric")).setAutoWidth(true);
        usageGrid.addColumn(MapRow::value).setHeader(I18n.t("instance.column.value")).setAutoWidth(true);

        extensionsGrid.setId("extensions-grid");
        extensionsGrid.setWidthFull();
        extensionsGrid.setHeight("160px");
        extensionsGrid.addColumn(value -> value).setHeader(I18n.t("instance.extensions")).setAutoWidth(true);

        pluginGrid.setId("plugins-grid");
        pluginGrid.setWidthFull();
        pluginGrid.setHeight("220px");
        pluginGrid.addColumn(ServerStatus.PluginInfo::id).setHeader("id").setAutoWidth(true);
        pluginGrid.addColumn(ServerStatus.PluginInfo::version).setHeader(I18n.t("instance.column.version")).setAutoWidth(true);
        pluginGrid.addColumn(ServerStatus.PluginInfo::state).setHeader(I18n.t("instance.column.status")).setAutoWidth(true);
        pluginGrid.addColumn(ServerStatus.PluginInfo::mountTime).setHeader(I18n.t("instance.column.mountTime")).setAutoWidth(true);
        pluginGrid.addComponentColumn(this::pluginActions).setHeader(I18n.t("instance.column.actions")).setAutoWidth(true);
    }

    private Button killButton(SessionInfo session) {
        Button kill = new Button(I18n.t("instance.session.kill"));
        kill.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        kill.addClickListener(event -> confirmKill(session));
        return kill;
    }

    // ------------------------------------------------------------- 插件管理

    private void loadPlugin() {
        String filename = pluginFile.getValue() == null ? "" : pluginFile.getValue().strip();
        if (filename.isEmpty()) {
            setPluginStatus(I18n.t("instance.plugin.file.required"), true);
            return;
        }
        if (pluginBusy) {
            return;
        }

        pluginBusy = true;
        updatePluginControls();
        try {
            PluginActionResult loaded = client().loadPlugin(filename);
            if (!loaded.success()) {
                setPluginStatus(I18n.t("instance.plugin.loadFailed", loaded.message()), true);
                return;
            }

            String pluginId = loaded.pluginId();
            String message = pluginId == null
                    ? I18n.t("instance.plugin.loaded")
                    : I18n.t("instance.plugin.loaded.id", pluginId);
            if (pluginStartAfterLoad.getValue() && pluginId != null) {
                PluginActionResult started = client().startPlugin(pluginId);
                message = started.success()
                        ? I18n.t("instance.plugin.loaded.started", pluginId)
                        : I18n.t("instance.plugin.loaded.startFailed", pluginId, started.message());
            }
            refresh();
            setPluginStatus(message, false);
        } catch (ManagementException e) {
            setPluginStatus(I18n.t("instance.plugin.loadFailed", I18n.message(e)), true);
        } finally {
            pluginBusy = false;
            updatePluginControls();
        }
    }

    private HorizontalLayout pluginActions(ServerStatus.PluginInfo plugin) {
        HorizontalLayout actions = new HorizontalLayout();
        actions.setSpacing(false);

        boolean started = "STARTED".equalsIgnoreCase(plugin.state());
        if (!started) {
            Button start = new Button(I18n.t("instance.plugin.start"));
            start.setId("plugin-start-" + plugin.id());
            start.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            start.addClickListener(event -> runPluginAction(I18n.t("instance.plugin.start"),
                    () -> client().startPlugin(plugin.id()),
                    I18n.t("instance.plugin.started", plugin.id())));
            actions.add(start);
        } else {
            Button stop = new Button(I18n.t("instance.plugin.stop"));
            stop.setId("plugin-stop-" + plugin.id());
            stop.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            stop.addClickListener(event -> runPluginAction(I18n.t("instance.plugin.stop"),
                    () -> client().stopPlugin(plugin.id()),
                    I18n.t("instance.plugin.stopped", plugin.id())));
            actions.add(stop);
        }

        Button unload = new Button(I18n.t("instance.plugin.unload"));
        unload.setId("plugin-unload-" + plugin.id());
        unload.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL,
                ButtonVariant.LUMO_ERROR);
        unload.addClickListener(event -> confirmUnload(plugin));
        actions.add(unload);
        return actions;
    }

    private void confirmUnload(ServerStatus.PluginInfo plugin) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setId("plugin-unload-dialog");
        dialog.setHeader(I18n.t("instance.plugin.unload.title", plugin.id()));
        dialog.setText(I18n.t("instance.plugin.unload.body"));
        dialog.setCancelable(true);
        dialog.setCancelText(I18n.t("common.cancel"));
        dialog.setConfirmText(I18n.t("instance.plugin.unload"));
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(event -> {
            dialog.close();
            runPluginAction(I18n.t("instance.plugin.unload"), () -> client().unloadPlugin(plugin.id()),
                    I18n.t("instance.plugin.unloaded", plugin.id()));
        });
        dialog.open();
    }

    private void runPluginAction(String action, PluginCall call, String success) {
        if (pluginBusy) {
            return;
        }
        pluginBusy = true;
        updatePluginControls();
        try {
            PluginActionResult result = call.run();
            if (!result.success()) {
                setPluginStatus(I18n.t("instance.plugin.actionFailed", action, result.message()), true);
                return;
            }
            refresh();
            setPluginStatus(success, false);
        } catch (ManagementException e) {
            setPluginStatus(I18n.t("instance.plugin.actionFailed", action, I18n.message(e)), true);
        } finally {
            pluginBusy = false;
            updatePluginControls();
        }
    }

    private void updatePluginControls() {
        pluginFile.setEnabled(!pluginBusy);
        pluginLoad.setEnabled(!pluginBusy);
        pluginStartAfterLoad.setEnabled(!pluginBusy);
        pluginGrid.setEnabled(!pluginBusy);
    }

    private void setPluginStatus(String text, boolean error) {
        pluginStatus.setText(text);
        pluginStatus.getStyle().set("color", error
                ? "var(--lumo-error-text-color)"
                : "var(--lumo-success-text-color)");
    }

    private void confirmKill(SessionInfo session) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader(I18n.t("instance.kill.title", session.id()));
        String source = nullToDash(session.applicationName());
        String clientIp = nullToDash(session.clientIp());
        String body;
        if (session.fromConsole()) {
            body = session.busy()
                    ? I18n.t("instance.kill.console.body.busy", source, clientIp, session.executingSql())
                    : I18n.t("instance.kill.console.body", source, clientIp);
        } else {
            body = session.busy()
                    ? I18n.t("instance.kill.body.busy", source, clientIp, session.executingSql())
                    : I18n.t("instance.kill.body", source, clientIp);
        }
        dialog.setText(body);
        dialog.setCancelable(true);
        dialog.setCancelText(I18n.t("common.cancel"));
        dialog.setConfirmText(I18n.t("instance.session.kill"));
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(event -> kill(session, dialog));
        dialog.open();
    }

    private void kill(SessionInfo session, ConfirmDialog dialog) {
        try {
            boolean killed = client().killSession(session.id());
            dialog.close();
            // 先刷新再写提示：refresh() 开头会清空 errorText，顺序反了提示会被自己抹掉（实测踩到）
            refresh();
            String message = killed
                    ? I18n.t("instance.kill.done", session.id())
                    : I18n.t("instance.kill.notKilled", session.id());
            errorText.setText(message);

            // 服务端把会话从 /status 摘掉有延迟（实测 0.5~2 s 不等），轮询到它真的消失再刷新一次
            refreshAfterKill(session.id(), message);
        } catch (ManagementException e) {
            dialog.close();
            errorText.setText(I18n.t("instance.kill.failed", I18n.message(e)));
        }
    }

    /** 后台轮询：等被杀的会话从 /status 消失后，回 UI 线程刷一次表并把提示写回去。 */
    private void refreshAfterKill(long sessionId, String message) {
        com.vaadin.flow.component.UI ui = getUI().orElse(null);
        ManagementClient management = client();
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            for (int attempt = 0; attempt < 15; attempt++) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                boolean gone;
                try {
                    gone = management.sessions().stream().noneMatch(session -> session.id() == sessionId);
                } catch (Exception e) {
                    gone = true;
                }
                if (gone) {
                    break;
                }
            }
            if (ui != null) {
                ui.access(() -> {
                    refresh();
                    errorText.setText(message);
                });
            }
        });
    }

    private void refresh() {
        closeClient();
        errorText.setText("");
        try {
            ManagementClient management = client();
            Capabilities capabilities = management.capabilities();
            Health health = management.health();
            ServerStatus status = management.status();
            sessions = status.sessions();

            capabilitySummary.setText(String.format("apiVersion=%d · %s %s · %s · pg=%d/mgmt=%d",
                    capabilities.apiVersion(),
                    nullToDash(capabilities.serverName()),
                    nullToDash(capabilities.serverVersion()),
                    nullToDash(capabilities.accessMode()),
                    capabilities.pgPort(),
                    capabilities.managementPort()));

            healthStatus.setText(health.up()
                    ? I18n.t("instance.health.up", health.database(), health.management())
                    : I18n.t("instance.health.down", health.status(), health.database()));
            healthStatus.getStyle().set("color", health.up() ? "var(--lumo-success-text-color)"
                    : "var(--lumo-error-text-color)");

            menuHint.setText(I18n.t("instance.menu.summary",
                    capabilities.hasEtl() ? I18n.t("instance.menu.shown") : I18n.t("instance.menu.hidden"),
                    capabilities.features().aiChat()
                            ? I18n.t("instance.menu.shown") : I18n.t("instance.menu.hidden"),
                    capabilities.features().backupTasks()
                            ? I18n.t("instance.menu.available") : I18n.t("instance.menu.unavailable"),
                    capabilities.features().logTail()
                            ? I18n.t("instance.menu.available") : I18n.t("instance.menu.unavailable")));

            serverSummary.setText(status.server() == null ? "—"
                    : I18n.t("instance.server.summary",
                    nullToDash(status.server().status()),
                    nullToDash(status.server().version()),
                    String.valueOf(status.server().pid()),
                    nullToDash(status.server().runTime())));
            databaseSummary.setText(status.database() == null ? "—"
                    : String.format("%s · size=%s · memory=%s · wal=%s",
                    nullToDash(status.database().version()),
                    nullToDash(status.database().size()),
                    nullToDash(status.database().memoryUsage()),
                    nullToDash(status.database().walSize())));

            parametersGrid.setItems(rows(status.parameters(), "mcpLlmKey"));
            usageGrid.setItems(rows(status.usage(), null));
            extensionsGrid.setItems(status.extensions());
            pluginGrid.setItems(status.plugins());
            sessionGrid.setItems(sessions);
        } catch (ManagementException e) {
            capabilitySummary.setText(I18n.t("instance.capabilities.unavailable"));
            healthStatus.setText(e.transportFailure()
                    ? I18n.t("instance.health.unreachable") : I18n.t("instance.health.error"));
            menuHint.setText("—");
            serverSummary.setText("—");
            databaseSummary.setText("—");
            sessionGrid.setItems(List.of());
            parametersGrid.setItems(List.of());
            usageGrid.setItems(List.of());
            extensionsGrid.setItems(List.of());
            pluginGrid.setItems(List.of());
            errorText.setText(I18n.message(e));
        }
    }

    private static List<MapRow> rows(Map<String, Object> values, String hiddenKey) {
        List<MapRow> rows = new ArrayList<>();
        if (values == null) {
            return rows;
        }
        values.forEach((key, value) -> {
            if (hiddenKey != null && hiddenKey.equalsIgnoreCase(key)) {
                return;
            }
            rows.add(new MapRow(key, value == null ? "" : String.valueOf(value)));
        });
        rows.sort(java.util.Comparator.comparing(MapRow::key));
        return rows;
    }

    private ManagementClient client() {
        if (client == null) {
            client = ConnectionManager.current().managementClient();
        }
        return client;
    }

    private void closeClient() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    /** 勾选"自动刷新"就开 5 s 轮询；取消就关掉（页面 detach 时也会关）。 */
    private void applyPolling() {
        getUI().ifPresent(ui -> {
            if (autoRefresh.getValue()) {
                ui.setPollInterval(5_000);
                ui.addPollListener(event -> refresh());
            } else {
                ui.setPollInterval(-1);
            }
        });
    }

    @Override
    protected void onDetach(com.vaadin.flow.component.DetachEvent detachEvent) {
        getUI().ifPresent(ui -> ui.setPollInterval(-1));
        super.onDetach(detachEvent);
        closeClient();
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    /** 测试与排错用：当前列表里的会话数。 */
    public int sessionCount() {
        return sessions.size();
    }
}
