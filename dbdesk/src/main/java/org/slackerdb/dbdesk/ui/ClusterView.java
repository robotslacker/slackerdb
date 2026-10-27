package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import org.slackerdb.dbdesk.access.http.ClusterInstance;
import org.slackerdb.dbdesk.access.http.ManagementException;
import org.slackerdb.dbdesk.access.http.ProxyAdminClient;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 集群“实例管理”：列出 dbproxy 管理的内部实例与组播发现的外部实例，
 * 支持查看、添加、启动、停止、销毁。
 *
 * <p>只有“只填 dbproxy 管理端口”的连接会进入本页；代理没配 {@code data_dir} 时
 * 不由页面特殊处理，操作失败按代理返回的错误原样展示。</p>
 */
@Route(value = "cluster", layout = MainLayout.class)
public class ClusterView extends VerticalLayout {

    private static final Set<String> RESERVED_ALIASES =
            Set.of("admin", "web", "status", "capabilities", "health", "favicon.ico");
    private static final String ALIAS_PATTERN = "[a-z][a-z0-9_-]{0,62}";
    private static final String NAME_PATTERN = "[A-Za-z][A-Za-z0-9_-]{0,62}";

    private final Button refresh = new Button("刷新");
    private final Checkbox autoRefresh = new Checkbox("自动刷新（5 s，缓存）");
    private final Button add = new Button("添加实例");
    private final Span endpoint = new Span();
    private final Span summary = new Span("—");
    private final Span status = new Span();
    private final Grid<ClusterInstance> grid = new Grid<>(ClusterInstance.class, false);

    private transient ProxyAdminClient client;
    private List<ClusterInstance> instances = List.of();
    private boolean loading;
    private boolean busy;
    private boolean detached;
    private boolean pollRegistered;
    private boolean ready;

    /** 详情/配置表的一行。 */
    public record MapRow(String key, String value) {
    }

    public ClusterView() {
        setPadding(true);
        setSpacing(true);
        setId("cluster-view");

        H2 title = new H2("实例管理");
        if (!ConnectionManager.current().hasClusterManagement()) {
            Span guard = new Span("当前连接的管理口没有指向 dbproxy，实例管理不可用。");
            guard.setId("cluster-guard");
            add(title, guard);
            return;
        }
        ready = true;

        endpoint.setId("cluster-endpoint");
        endpoint.setText("管理口：" + ConnectionManager.current().clusterUrl());
        summary.setId("cluster-summary");
        status.setId("cluster-status");
        status.setText("就绪");

        refresh.setId("cluster-refresh");
        refresh.addClickListener(event -> refresh(true));
        autoRefresh.setId("cluster-auto-refresh");
        autoRefresh.addValueChangeListener(event -> {
            getUI().ifPresent(ui -> ui.setPollInterval(event.getValue() ? 5_000 : -1));
            if (event.getValue()) {
                refresh(false);
            }
        });
        add.setId("cluster-add");
        add.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        add.addClickListener(event -> openAddDialog());

        configureGrid();
        add(title,
                new HorizontalLayout(refresh, autoRefresh, add),
                endpoint,
                summary,
                status,
                grid);
        setFlexGrow(1, grid);
    }

    private void configureGrid() {
        grid.setId("cluster-grid");
        grid.setWidthFull();
        grid.setHeight("calc(100vh - 330px)");
        grid.addColumn(ClusterInstance::alias).setHeader("别名").setAutoWidth(true);
        grid.addColumn(item -> item.external() ? "外部（组播）" : "内部")
                .setHeader("来源").setAutoWidth(true);
        grid.addColumn(ClusterInstance::nameText).setHeader("库名").setAutoWidth(true);
        grid.addComponentColumn(item -> stateBadge(item.stateText()))
                .setHeader("状态").setAutoWidth(true);
        grid.addColumn(ClusterInstance::healthText).setHeader("健康").setAutoWidth(true);
        grid.addColumn(ClusterInstance::pidText).setHeader("PID").setAutoWidth(true);
        grid.addColumn(ClusterInstance::pgPortText).setHeader("PG 端口").setAutoWidth(true);
        grid.addColumn(ClusterInstance::mgmtPortText).setHeader("管理端口").setAutoWidth(true);
        grid.addColumn(ClusterInstance::uptimeText).setHeader("运行时长").setAutoWidth(true);
        grid.addColumn(ClusterInstance::locationText).setHeader("位置/目标")
                .setWidth("260px").setFlexGrow(0);
        grid.addColumn(ClusterInstance::errorText).setHeader("错误")
                .setWidth("220px").setFlexGrow(0);
        grid.addComponentColumn(this::actions).setHeader("操作")
                .setWidth("300px").setFlexGrow(0);
    }

    private Span stateBadge(String state) {
        Span badge = new Span(stateText(state));
        badge.getStyle().set("font-weight", "600");
        badge.getStyle().set("color", switch (state == null ? "" : state) {
            case "RUNNING" -> "var(--lumo-success-text-color)";
            case "DEGRADED", "STARTING", "STOPPING" -> "#f9a825";
            case "STOPPED" -> "var(--lumo-secondary-text-color)";
            default -> "var(--lumo-error-text-color)";
        });
        return badge;
    }

    private static String stateText(String state) {
        if (state == null) {
            return "未知";
        }
        return switch (state) {
            case "RUNNING" -> "运行中";
            case "DEGRADED" -> "降级";
            case "STOPPED" -> "已停止";
            case "STARTING" -> "启动中";
            case "STOPPING" -> "停止中";
            default -> "未知";
        };
    }

    private HorizontalLayout actions(ClusterInstance item) {
        HorizontalLayout actions = new HorizontalLayout();
        actions.setSpacing(false);

        Button view = smallButton("查看", "cluster-view-" + item.alias());
        view.addClickListener(event -> openDetail(item));
        actions.add(view);

        if (!item.manageable()) {
            Span readOnly = new Span("只读");
            readOnly.getStyle().set("color", "var(--lumo-secondary-text-color)")
                    .set("font-size", "var(--lumo-font-size-s)");
            actions.add(readOnly);
            return actions;
        }

        if (!item.busyState() && item.stopped()) {
            Button start = smallButton("启动", "cluster-start-" + item.alias());
            start.addClickListener(event -> start(item));
            actions.add(start);
        }
        if (!item.busyState() && item.running()) {
            Button stop = smallButton("停止", "cluster-stop-" + item.alias());
            stop.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL,
                    ButtonVariant.LUMO_ERROR);
            stop.addClickListener(event -> confirmStop(item));
            actions.add(stop);
        }
        Button destroy = smallButton("销毁", "cluster-destroy-" + item.alias());
        destroy.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL,
                ButtonVariant.LUMO_ERROR);
        destroy.setEnabled(!item.busyState());
        destroy.addClickListener(event -> confirmDestroy(item));
        actions.add(destroy);
        return actions;
    }

    private static Button smallButton(String text, String id) {
        Button button = new Button(text);
        button.setId(id);
        button.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        return button;
    }

    // ------------------------------------------------------------------ 列表

    private void refresh(boolean probe) {
        if (!ready || loading || busy) {
            return;
        }
        loading = true;
        updateControls();
        ProxyAdminClient admin = client();
        CompletableFuture.supplyAsync(() -> {
            try {
                return admin.databases(probe);
            } catch (ManagementException e) {
                throw new CompletionException(e);
            }
        }).whenComplete((list, error) -> {
            UI ui = getUI().orElse(null);
            if (ui == null || detached) {
                return;
            }
            ui.access(() -> {
                loading = false;
                updateControls();
                if (error != null) {
                    instances = List.of();
                    grid.setItems(List.of());
                    summary.setText("—");
                    setStatus("加载失败：" + message(error), true);
                } else {
                    instances = List.copyOf(list);
                    grid.setItems(instances);
                    updateSummary();
                }
            });
        });
    }

    private void updateSummary() {
        long internal = instances.stream().filter(ClusterInstance::manageable).count();
        long external = instances.size() - internal;
        long running = instances.stream().filter(ClusterInstance::running).count();
        summary.setText("内部实例 " + internal + " · 外部实例 " + external + " · 运行中 " + running);
    }

    private void updateControls() {
        refresh.setEnabled(!loading && !busy);
        add.setEnabled(!loading && !busy);
        autoRefresh.setEnabled(!busy);
        grid.setEnabled(!loading && !busy);
    }

    private void setStatus(String text, boolean error) {
        status.setText(text);
        status.getStyle().set("color", error
                ? "var(--lumo-error-text-color)"
                : "var(--lumo-secondary-text-color)");
    }

    // ------------------------------------------------------------------ 生命周期

    private void start(ClusterInstance item) {
        ProxyAdminClient admin = client();
        runInstanceAction("正在启动 " + item.alias() + "…",
                () -> admin.start(item.alias()),
                "已启动 " + item.alias());
    }

    private void confirmStop(ClusterInstance item) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("停止实例 " + item.alias() + "？");
        dialog.setText("将优雅停止该实例，并断开所有客户端连接；数据会保留。");
        dialog.setCancelable(true);
        dialog.setConfirmText("停止");
        dialog.setConfirmButtonTheme("error primary");
        ProxyAdminClient admin = client();
        dialog.addConfirmListener(event -> {
            dialog.close();
            runInstanceAction("正在停止 " + item.alias() + "…",
                    () -> admin.shutdown(item.alias()),
                    "已停止 " + item.alias());
        });
        dialog.open();
    }

    private void confirmDestroy(ClusterInstance item) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("销毁实例 " + item.alias() + "？");
        dialog.setText("将先停止实例，再删除整个实例目录：\n" + item.locationText()
                + "\n\n数据不可恢复；如果当前 SQL 连接指向该实例，也会断开。");
        dialog.setCancelable(true);
        dialog.setConfirmText("销毁");
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(event -> {
            dialog.close();
            runDestroy(item);
        });
        dialog.open();
    }

    private void runInstanceAction(String pending, Callable<ClusterInstance> action, String success) {
        if (busy) {
            return;
        }
        busy = true;
        setStatus(pending, false);
        updateControls();
        CompletableFuture.supplyAsync(() -> {
            try {
                return action.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }).whenComplete((result, error) -> {
            UI ui = getUI().orElse(null);
            if (ui == null || detached) {
                return;
            }
            ui.access(() -> {
                busy = false;
                updateControls();
                if (error != null) {
                    showOperationFailure(error);
                } else {
                    setStatus(success, false);
                    refresh(false);
                }
            });
        });
    }

    private void runDestroy(ClusterInstance item) {
        if (busy) {
            return;
        }
        busy = true;
        setStatus("正在销毁 " + item.alias() + "…", false);
        updateControls();
        ProxyAdminClient admin = client();
        CompletableFuture.supplyAsync(() -> {
            try {
                admin.destroy(item.alias());
                return null;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }).whenComplete((ignored, error) -> {
            UI ui = getUI().orElse(null);
            if (ui == null || detached) {
                return;
            }
            ui.access(() -> {
                busy = false;
                updateControls();
                if (error != null) {
                    showOperationFailure(error);
                } else {
                    setStatus("已销毁 " + item.alias(), false);
                    refresh(false);
                }
            });
        });
    }

    private void showOperationFailure(Throwable error) {
        String text = message(error);
        setStatus("操作失败：" + text, true);
        showErrorDialog("操作失败", text);
        refresh(false);
    }

    // ------------------------------------------------------------------ 添加

    private void openAddDialog() {
        Dialog dialog = new Dialog();
        dialog.setId("cluster-create-dialog");
        dialog.setHeaderTitle("添加实例");
        dialog.setWidth("520px");

        TextField alias = new TextField("别名");
        alias.setId("cluster-create-alias");
        alias.setRequiredIndicatorVisible(true);
        alias.setPlaceholder("小写字母开头，例如 sales");
        alias.setHelperText("目录名 / 代理路由键；创建后不可修改");

        TextField name = new TextField("数据库名（可选）");
        name.setId("cluster-create-name");
        name.setPlaceholder("默认同别名");

        Checkbox start = new Checkbox("立即启动", true);
        start.setId("cluster-create-start");

        Span error = new Span();
        error.setId("cluster-create-error");
        error.getStyle().set("color", "var(--lumo-error-text-color)");

        Button submit = new Button("创建");
        submit.setId("cluster-create-submit");
        submit.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button("取消", event -> dialog.close());
        cancel.setId("cluster-create-cancel");

        submit.addClickListener(event -> {
            String normalized = alias.getValue() == null
                    ? "" : alias.getValue().strip().toLowerCase(Locale.ROOT);
            if (!normalized.matches(ALIAS_PATTERN)) {
                error.setText("别名必须以小写字母开头，只能包含小写字母、数字、下划线和连字符");
                return;
            }
            if (RESERVED_ALIASES.contains(normalized)) {
                error.setText("别名 [" + normalized + "] 被代理管理口保留，请换一个");
                return;
            }
            String database = name.getValue() == null ? "" : name.getValue().strip();
            if (!database.isEmpty() && !database.matches(NAME_PATTERN)) {
                error.setText("数据库名必须以字母开头，只能包含字母、数字、下划线和连字符");
                return;
            }

            error.setText("");
            submit.setEnabled(false);
            boolean startNow = start.getValue();
            ProxyAdminClient admin = client();
            CompletableFuture.supplyAsync(() -> {
                try {
                    return admin.create(normalized, database, startNow);
                } catch (ManagementException e) {
                    throw new CompletionException(e);
                }
            }).whenComplete((created, failure) -> {
                UI ui = getUI().orElse(null);
                if (ui == null || detached) {
                    return;
                }
                ui.access(() -> {
                    submit.setEnabled(true);
                    if (failure != null) {
                        error.setText(message(failure));
                        return;
                    }
                    dialog.close();
                    setStatus("已创建 " + created.alias(), false);
                    refresh(false);
                });
            });
        });

        dialog.add(new VerticalLayout(alias, name, start, error,
                new HorizontalLayout(submit, cancel)));
        dialog.open();
    }

    // ------------------------------------------------------------------ 查看

    private void openDetail(ClusterInstance item) {
        Dialog dialog = new Dialog();
        dialog.setId("cluster-detail-dialog");
        dialog.setHeaderTitle("实例详情：" + item.alias());
        dialog.setWidth("760px");

        VerticalLayout content = new VerticalLayout();
        content.setPadding(false);
        content.setSpacing(false);
        content.add(field("别名", item.alias()),
                field("来源", item.external() ? "外部（组播）" : "内部"),
                field("数据库名", item.nameText()),
                field("状态", stateText(item.stateText())),
                field("健康", item.healthText()),
                field("PID", item.pidText()),
                field("PG 端口", item.pgPortText()),
                field("管理端口", item.mgmtPortText()),
                field("运行时长", item.uptimeText()),
                field("位置/目标", item.locationText()));
        if (!"—".equals(item.errorText())) {
            content.add(field("错误", item.errorText()));
        }

        H3 confTitle = new H3("配置");
        confTitle.getStyle().set("margin-bottom", "0");
        content.add(confTitle);

        Grid<MapRow> confGrid = new Grid<>(MapRow.class, false);
        confGrid.setId("cluster-conf-grid");
        confGrid.setWidthFull();
        confGrid.setHeight("220px");
        confGrid.addColumn(MapRow::key).setHeader("配置项").setAutoWidth(true);
        confGrid.addColumn(MapRow::value).setHeader("值").setAutoWidth(true);

        if (!item.conf().isEmpty()) {
            confGrid.setItems(confRows(item));
            content.add(confGrid);
        } else {
            Span loading = new Span("加载配置…");
            content.add(loading);
            ProxyAdminClient admin = client();
            CompletableFuture.supplyAsync(() -> {
                try {
                    return admin.detail(item.alias());
                } catch (ManagementException e) {
                    throw new CompletionException(e);
                }
            }).whenComplete((full, failure) -> {
                UI ui = getUI().orElse(null);
                if (ui == null || detached) {
                    return;
                }
                ui.access(() -> {
                    content.remove(loading);
                    if (failure != null) {
                        content.add(new Span("配置加载失败：" + message(failure)));
                    } else if (full.conf().isEmpty()) {
                        content.add(new Span("—"));
                    } else {
                        confGrid.setItems(confRows(full));
                        content.add(confGrid);
                    }
                });
            });
        }

        Button close = new Button("关闭", event -> dialog.close());
        close.setId("cluster-detail-close");
        content.add(new HorizontalLayout(close));
        dialog.add(content);
        dialog.open();
    }

    private static List<MapRow> confRows(ClusterInstance item) {
        return item.sortedConf().stream()
                .map(entry -> new MapRow(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static HorizontalLayout field(String label, String value) {
        Span name = new Span(label + "：");
        name.getStyle().set("color", "var(--lumo-secondary-text-color)");
        name.setWidth("110px");
        Span text = new Span(value == null || value.isBlank() ? "—" : value);
        text.getStyle().set("white-space", "pre-wrap");
        HorizontalLayout row = new HorizontalLayout(name, text);
        row.setSpacing(false);
        row.setWidthFull();
        return row;
    }

    // ------------------------------------------------------------------ 错误

    private void showErrorDialog(String title, String message) {
        Dialog dialog = new Dialog();
        dialog.setId("cluster-error-dialog");
        dialog.setHeaderTitle(title);
        dialog.setWidth("720px");
        TextArea text = new TextArea();
        text.setValue(message == null ? "" : message);
        text.setReadOnly(true);
        text.setWidthFull();
        text.setHeight("240px");
        Button close = new Button("关闭", event -> dialog.close());
        close.setId("cluster-error-close");
        dialog.add(new VerticalLayout(text, new HorizontalLayout(close)));
        dialog.open();
    }

    private static String message(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof ManagementException management) {
            return management.retMsg() != null && !management.retMsg().isBlank()
                    ? management.retMsg() : management.getMessage();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    // ------------------------------------------------------------------ 生命周期

    private ProxyAdminClient client() {
        if (client == null) {
            client = ConnectionManager.current().clusterClient();
        }
        return client;
    }

    @Override
    protected void onAttach(com.vaadin.flow.component.AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        if (!ready) {
            return;
        }
        if (!ConnectionManager.current().hasClusterManagement()) {
            return;
        }
        detached = false;
        getUI().ifPresent(ui -> {
            if (autoRefresh.getValue()) {
                ui.setPollInterval(5_000);
            }
            if (!pollRegistered) {
                ui.addPollListener(event -> {
                    if (autoRefresh.getValue() && !busy && !loading) {
                        refresh(false);
                    }
                });
                pollRegistered = true;
            }
        });
        refresh(false);
    }

    @Override
    protected void onDetach(com.vaadin.flow.component.DetachEvent detachEvent) {
        detached = true;
        getUI().ifPresent(ui -> ui.setPollInterval(-1));
        if (client != null) {
            client.close();
            client = null;
        }
        super.onDetach(detachEvent);
    }

    /** 测试/排错：当前列表里的实例数。 */
    public int instanceCount() {
        return instances.size();
    }
}
