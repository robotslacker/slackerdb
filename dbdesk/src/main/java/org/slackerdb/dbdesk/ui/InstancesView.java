package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import org.slackerdb.dbdesk.access.http.Capabilities;
import org.slackerdb.dbdesk.access.http.Health;
import org.slackerdb.dbdesk.access.http.ManagementClient;
import org.slackerdb.dbdesk.access.http.ManagementException;
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

    private final Button connect = new Button("刷新");
    private final com.vaadin.flow.component.checkbox.Checkbox autoRefresh =
            new com.vaadin.flow.component.checkbox.Checkbox("自动刷新（5 s）");
    private final Span capabilitySummary = new Span("未连接");
    private final Span healthStatus = new Span("—");
    private final Span menuHint = new Span("—");
    private final Span serverSummary = new Span("—");
    private final Span databaseSummary = new Span("—");
    private final Span errorText = new Span();
    private final Grid<SessionInfo> sessionGrid = new Grid<>(SessionInfo.class, false);
    private final Grid<MapRow> parametersGrid = new Grid<>(MapRow.class, false);
    private final Grid<MapRow> usageGrid = new Grid<>(MapRow.class, false);
    private final Grid<String> extensionsGrid = new Grid<>(String.class, false);
    private final Grid<ServerStatus.PluginInfo> pluginGrid = new Grid<>(ServerStatus.PluginInfo.class, false);

    private transient ManagementClient client;
    private List<SessionInfo> sessions = List.of();

    /** 一行键值。 */
    public record MapRow(String key, String value) {
    }

    public InstancesView() {
        setPadding(true);
        setSpacing(true);

        H2 title = new H2("实例与状态");
        if (!ConnectionManager.current().hasManagement()) {
            Span guard = new Span("当前连接没有管理口，实例状态不可用。");
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

        configureGrids();

        add(title,
                new HorizontalLayout(connect, autoRefresh),
                new HorizontalLayout(new Span("能力："), capabilitySummary),
                new HorizontalLayout(new Span("健康："), healthStatus),
                new HorizontalLayout(new Span("菜单："), menuHint),
                new HorizontalLayout(new Span("服务器："), serverSummary),
                new HorizontalLayout(new Span("数据库："), databaseSummary),
                errorText,
                new H3("会话"), sessionGrid,
                new H3("参数"), parametersGrid,
                new H3("Usage"), usageGrid,
                new H3("扩展"), extensionsGrid,
                new H3("插件"), pluginGrid);
        setFlexGrow(1, sessionGrid);
        refresh();
    }

    private void configureGrids() {
        sessionGrid.setId("session-grid");
        sessionGrid.setWidthFull();
        sessionGrid.setHeight("260px");
        sessionGrid.addColumn(SessionInfo::id).setHeader("id").setAutoWidth(true);
        sessionGrid.addColumn(session -> session.fromConsole()
                        ? nullToDash(session.applicationName()) + "（本机控制台）"
                        : nullToDash(session.applicationName()))
                .setHeader("来源").setAutoWidth(true);
        sessionGrid.addColumn(SessionInfo::database).setHeader("库").setAutoWidth(true);
        sessionGrid.addColumn(SessionInfo::clientIp).setHeader("客户端").setAutoWidth(true);
        sessionGrid.addColumn(SessionInfo::connectedTime).setHeader("连接时间").setAutoWidth(true);
        sessionGrid.addColumn(session -> session.busy() ? "执行中" : "空闲").setHeader("状态").setAutoWidth(true);
        sessionGrid.addComponentColumn(this::killButton).setHeader("").setAutoWidth(true);

        parametersGrid.setId("parameters-grid");
        parametersGrid.setWidthFull();
        parametersGrid.setHeight("220px");
        parametersGrid.addColumn(MapRow::key).setHeader("参数").setAutoWidth(true);
        parametersGrid.addColumn(MapRow::value).setHeader("值").setAutoWidth(true);

        usageGrid.setId("usage-grid");
        usageGrid.setWidthFull();
        usageGrid.setHeight("220px");
        usageGrid.addColumn(MapRow::key).setHeader("指标").setAutoWidth(true);
        usageGrid.addColumn(MapRow::value).setHeader("值").setAutoWidth(true);

        extensionsGrid.setId("extensions-grid");
        extensionsGrid.setWidthFull();
        extensionsGrid.setHeight("160px");
        extensionsGrid.addColumn(value -> value).setHeader("扩展").setAutoWidth(true);

        pluginGrid.setId("plugins-grid");
        pluginGrid.setWidthFull();
        pluginGrid.setHeight("160px");
        pluginGrid.addColumn(ServerStatus.PluginInfo::id).setHeader("id").setAutoWidth(true);
        pluginGrid.addColumn(ServerStatus.PluginInfo::version).setHeader("版本").setAutoWidth(true);
        pluginGrid.addColumn(ServerStatus.PluginInfo::state).setHeader("状态").setAutoWidth(true);
        pluginGrid.addColumn(ServerStatus.PluginInfo::mountTime).setHeader("挂载").setAutoWidth(true);
    }

    private Button killButton(SessionInfo session) {
        Button kill = new Button("终止");
        kill.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        kill.addClickListener(event -> confirmKill(session));
        return kill;
    }

    private void confirmKill(SessionInfo session) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("终止会话 " + session.id() + "？");
        dialog.setText((session.fromConsole() ? "**这是本控制台自己的会话**，" : "")
                + "来源：" + nullToDash(session.applicationName())
                + "，客户端：" + nullToDash(session.clientIp())
                + (session.busy() ? "；它正在执行：" + session.executingSql() : ""));
        dialog.setCancelable(true);
        dialog.setConfirmText("终止");
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
            String message = killed ? "已终止会话 " + session.id() : "服务端说没杀成：" + session.id();
            errorText.setText(message);

            // 服务端把会话从 /status 摘掉有延迟（实测 0.5~2 s 不等），轮询到它真的消失再刷新一次
            refreshAfterKill(session.id(), message);
        } catch (ManagementException e) {
            dialog.close();
            errorText.setText("终止失败：" + e.getMessage());
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
                    ? "UP（数据库 " + health.database() + "，管理口 " + health.management() + "）"
                    : health.status() + "（数据库 " + health.database() + "）");
            healthStatus.getStyle().set("color", health.up() ? "var(--lumo-success-text-color)"
                    : "var(--lumo-error-text-color)");

            menuHint.setText(String.format("ETL：%s · AI：%s · 备份任务：%s · 日志尾行：%s",
                    capabilities.hasEtl() ? "显示" : "隐藏",
                    capabilities.features().aiChat() ? "显示" : "隐藏",
                    capabilities.features().backupTasks() ? "可用" : "不可用",
                    capabilities.features().logTail() ? "可用" : "不可用"));

            serverSummary.setText(status.server() == null ? "—"
                    : String.format("%s · %s · pid=%d · 运行 %s",
                    nullToDash(status.server().status()),
                    nullToDash(status.server().version()),
                    status.server().pid(),
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
            capabilitySummary.setText("不可用");
            healthStatus.setText(e.transportFailure() ? "连不上" : "错误");
            menuHint.setText("—");
            serverSummary.setText("—");
            databaseSummary.setText("—");
            sessionGrid.setItems(List.of());
            parametersGrid.setItems(List.of());
            usageGrid.setItems(List.of());
            extensionsGrid.setItems(List.of());
            pluginGrid.setItems(List.of());
            errorText.setText(e.getMessage());
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
