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

    private final Button refresh = new Button(I18n.t("common.refresh"));
    private final Checkbox autoRefresh = new Checkbox(I18n.t("cluster.autoRefresh"));
    private final Button add = new Button(I18n.t("cluster.add"));
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
        I18n.setTitle("nav.cluster");

        H2 title = new H2(I18n.t("nav.cluster"));
        if (!ConnectionManager.current().hasClusterManagement()) {
            Span guard = new Span(I18n.t("cluster.guard"));
            guard.setId("cluster-guard");
            add(title, guard);
            return;
        }
        ready = true;

        endpoint.setId("cluster-endpoint");
        endpoint.setText(I18n.t("management.label") + ConnectionManager.current().clusterUrl());
        summary.setId("cluster-summary");
        status.setId("cluster-status");
        status.setText(I18n.t("cluster.status.ready"));

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
        grid.addColumn(ClusterInstance::alias).setHeader(I18n.t("cluster.column.alias")).setAutoWidth(true);
        grid.addColumn(item -> item.external()
                        ? I18n.t("cluster.source.external") : I18n.t("cluster.source.internal"))
                .setHeader(I18n.t("cluster.column.source")).setAutoWidth(true);
        grid.addColumn(ClusterInstance::nameText)
                .setHeader(I18n.t("cluster.column.database")).setAutoWidth(true);
        grid.addComponentColumn(item -> stateBadge(item.stateText()))
                .setHeader(I18n.t("cluster.column.state")).setAutoWidth(true);
        grid.addColumn(ClusterInstance::healthText)
                .setHeader(I18n.t("cluster.column.health")).setAutoWidth(true);
        grid.addColumn(ClusterInstance::pidText).setHeader("PID").setAutoWidth(true);
        grid.addColumn(ClusterInstance::pgPortText)
                .setHeader(I18n.t("cluster.column.pgPort")).setAutoWidth(true);
        grid.addColumn(ClusterInstance::mgmtPortText)
                .setHeader(I18n.t("cluster.column.managementPort")).setAutoWidth(true);
        grid.addColumn(ClusterInstance::uptimeText)
                .setHeader(I18n.t("cluster.column.uptime")).setAutoWidth(true);
        grid.addColumn(ClusterInstance::locationText).setHeader(I18n.t("cluster.column.location"))
                .setWidth("260px").setFlexGrow(0);
        grid.addColumn(ClusterInstance::errorText).setHeader(I18n.t("cluster.column.error"))
                .setWidth("220px").setFlexGrow(0);
        grid.addComponentColumn(this::actions).setHeader(I18n.t("cluster.column.actions"))
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
            return I18n.t("cluster.state.unknown");
        }
        return switch (state) {
            case "RUNNING" -> I18n.t("cluster.state.running");
            case "DEGRADED" -> I18n.t("cluster.state.degraded");
            case "STOPPED" -> I18n.t("cluster.state.stopped");
            case "STARTING" -> I18n.t("cluster.state.starting");
            case "STOPPING" -> I18n.t("cluster.state.stopping");
            default -> I18n.t("cluster.state.unknown");
        };
    }

    private HorizontalLayout actions(ClusterInstance item) {
        HorizontalLayout actions = new HorizontalLayout();
        actions.setSpacing(false);

        Button view = smallButton(I18n.t("cluster.view"), "cluster-view-" + item.alias());
        view.addClickListener(event -> openDetail(item));
        actions.add(view);

        if (!item.manageable()) {
            Span readOnly = new Span(I18n.t("cluster.readOnly"));
            readOnly.getStyle().set("color", "var(--lumo-secondary-text-color)")
                    .set("font-size", "var(--lumo-font-size-s)");
            actions.add(readOnly);
            return actions;
        }

        if (!item.busyState() && item.stopped()) {
            Button start = smallButton(I18n.t("cluster.start"), "cluster-start-" + item.alias());
            start.addClickListener(event -> start(item));
            actions.add(start);
        }
        if (!item.busyState() && item.running()) {
            Button stop = smallButton(I18n.t("cluster.stop"), "cluster-stop-" + item.alias());
            stop.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL,
                    ButtonVariant.LUMO_ERROR);
            stop.addClickListener(event -> confirmStop(item));
            actions.add(stop);
        }
        Button destroy = smallButton(I18n.t("cluster.destroy"), "cluster-destroy-" + item.alias());
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
                    setStatus(I18n.t("cluster.error.loadFailed", I18n.message(error)), true);
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
        summary.setText(I18n.t("cluster.summary", internal, external, running));
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
        runInstanceAction(I18n.t("cluster.starting", item.alias()),
                () -> admin.start(item.alias()),
                I18n.t("cluster.started", item.alias()));
    }

    private void confirmStop(ClusterInstance item) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader(I18n.t("cluster.confirm.stop.title", item.alias()));
        dialog.setText(I18n.t("cluster.confirm.stop.body"));
        dialog.setCancelable(true);
        dialog.setCancelText(I18n.t("common.cancel"));
        dialog.setConfirmText(I18n.t("cluster.stop"));
        dialog.setConfirmButtonTheme("error primary");
        ProxyAdminClient admin = client();
        dialog.addConfirmListener(event -> {
            dialog.close();
            runInstanceAction(I18n.t("cluster.stopping", item.alias()),
                    () -> admin.shutdown(item.alias()),
                    I18n.t("cluster.stopped", item.alias()));
        });
        dialog.open();
    }

    private void confirmDestroy(ClusterInstance item) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader(I18n.t("cluster.confirm.destroy.title", item.alias()));
        dialog.setText(I18n.t("cluster.confirm.destroy.body", item.locationText()));
        dialog.setCancelable(true);
        dialog.setCancelText(I18n.t("common.cancel"));
        dialog.setConfirmText(I18n.t("cluster.destroy"));
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
        setStatus(I18n.t("cluster.destroying", item.alias()), false);
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
                    setStatus(I18n.t("cluster.destroyed", item.alias()), false);
                    refresh(false);
                }
            });
        });
    }

    private void showOperationFailure(Throwable error) {
        String text = I18n.message(error);
        setStatus(I18n.t("cluster.error.actionFailed", text), true);
        showErrorDialog(I18n.t("cluster.error.title"), text);
        refresh(false);
    }

    // ------------------------------------------------------------------ 添加

    private void openAddDialog() {
        Dialog dialog = new Dialog();
        dialog.setId("cluster-create-dialog");
        dialog.setHeaderTitle(I18n.t("cluster.add"));
        dialog.setWidth("520px");

        TextField alias = new TextField(I18n.t("cluster.field.alias"));
        alias.setId("cluster-create-alias");
        alias.setRequiredIndicatorVisible(true);
        alias.setPlaceholder(I18n.t("cluster.create.alias.placeholder"));
        alias.setHelperText(I18n.t("cluster.create.alias.helper"));

        TextField name = new TextField(I18n.t("cluster.create.database"));
        name.setId("cluster-create-name");
        name.setPlaceholder(I18n.t("cluster.create.database.placeholder"));

        Checkbox start = new Checkbox(I18n.t("cluster.create.start"), true);
        start.setId("cluster-create-start");

        Span error = new Span();
        error.setId("cluster-create-error");
        error.getStyle().set("color", "var(--lumo-error-text-color)");

        Button submit = new Button(I18n.t("cluster.create.submit"));
        submit.setId("cluster-create-submit");
        submit.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button(I18n.t("common.cancel"), event -> dialog.close());
        cancel.setId("cluster-create-cancel");

        submit.addClickListener(event -> {
            String normalized = alias.getValue() == null
                    ? "" : alias.getValue().strip().toLowerCase(Locale.ROOT);
            if (!normalized.matches(ALIAS_PATTERN)) {
                error.setText(I18n.t("cluster.create.alias.invalid"));
                return;
            }
            if (RESERVED_ALIASES.contains(normalized)) {
                error.setText(I18n.t("cluster.create.alias.reserved", normalized));
                return;
            }
            String database = name.getValue() == null ? "" : name.getValue().strip();
            if (!database.isEmpty() && !database.matches(NAME_PATTERN)) {
                error.setText(I18n.t("cluster.create.database.invalid"));
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
                        error.setText(I18n.message(failure));
                        return;
                    }
                    dialog.close();
                    setStatus(I18n.t("cluster.created", created.alias()), false);
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
        dialog.setHeaderTitle(I18n.t("cluster.detail.title", item.alias()));
        dialog.setWidth("760px");

        VerticalLayout content = new VerticalLayout();
        content.setPadding(false);
        content.setSpacing(false);
        content.add(field(I18n.t("cluster.field.alias"), item.alias()),
                field(I18n.t("cluster.field.source"), item.external()
                        ? I18n.t("cluster.source.external") : I18n.t("cluster.source.internal")),
                field(I18n.t("cluster.field.database"), item.nameText()),
                field(I18n.t("cluster.field.state"), stateText(item.stateText())),
                field(I18n.t("cluster.field.health"), item.healthText()),
                field("PID", item.pidText()),
                field(I18n.t("cluster.field.pgPort"), item.pgPortText()),
                field(I18n.t("cluster.field.managementPort"), item.mgmtPortText()),
                field(I18n.t("cluster.field.uptime"), item.uptimeText()),
                field(I18n.t("cluster.field.location"), item.locationText()));
        if (!"—".equals(item.errorText())) {
            content.add(field(I18n.t("cluster.field.error"), item.errorText()));
        }

        H3 confTitle = new H3(I18n.t("cluster.detail.config"));
        confTitle.getStyle().set("margin-bottom", "0");
        content.add(confTitle);

        Grid<MapRow> confGrid = new Grid<>(MapRow.class, false);
        confGrid.setId("cluster-conf-grid");
        confGrid.setWidthFull();
        confGrid.setHeight("220px");
        confGrid.addColumn(MapRow::key).setHeader(I18n.t("cluster.column.confKey")).setAutoWidth(true);
        confGrid.addColumn(MapRow::value).setHeader(I18n.t("cluster.column.confValue")).setAutoWidth(true);

        if (!item.conf().isEmpty()) {
            confGrid.setItems(confRows(item));
            content.add(confGrid);
        } else {
            Span loading = new Span(I18n.t("cluster.detail.config.loading"));
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
                        content.add(new Span(
                                I18n.t("cluster.detail.config.loadFailed", I18n.message(failure))));
                    } else if (full.conf().isEmpty()) {
                        content.add(new Span("—"));
                    } else {
                        confGrid.setItems(confRows(full));
                        content.add(confGrid);
                    }
                });
            });
        }

        Button close = new Button(I18n.t("common.close"), event -> dialog.close());
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
        Span name = new Span(I18n.t("cluster.field.label", label));
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
        Button close = new Button(I18n.t("common.close"), event -> dialog.close());
        close.setId("cluster-error-close");
        dialog.add(new VerticalLayout(text, new HorizontalLayout(close)));
        dialog.open();
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
