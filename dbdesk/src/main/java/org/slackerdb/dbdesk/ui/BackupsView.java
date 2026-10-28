package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import org.slackerdb.dbdesk.access.http.BackupInfo;
import org.slackerdb.dbdesk.access.http.ManagementClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 备份页：启动异步备份任务、轮询进度、下载结果。
 *
 * <p>不依赖 dbserver 的本地路径：下载走 {@code GET /backups/{id}/download}，
 * 因此 DbDesk 管远端实例时同样可用。</p>
 */
@Route(value = "backups", layout = MainLayout.class)
public class BackupsView extends VerticalLayout {

    private final TextField tag = new TextField(I18n.t("backup.tag.label"));
    private final Button start = new Button(I18n.t("backup.start"));
    private final Button refresh = new Button(I18n.t("backup.refresh"));
    private final Span status = new Span(I18n.t("backup.status.ready"));
    private final Grid<BackupInfo> grid = new Grid<>(BackupInfo.class, false);
    private final HorizontalLayout downloads = new HorizontalLayout();

    private transient ManagementClient client;
    private final AtomicBoolean polling = new AtomicBoolean(false);

    public BackupsView() {
        setSizeFull();
        setPadding(true);
        setSpacing(true);
        I18n.setTitle("nav.backups");

        H2 title = new H2(I18n.t("backup.title"));
        if (!ConnectionManager.current().hasInstanceManagement()) {
            Span guard = new Span(I18n.t("backup.guard"));
            guard.setId("backup-guard");
            add(title, guard);
            return;
        }
        Span endpoint = new Span(ConnectionManager.current().managementUrl());
        endpoint.setId("backup-endpoint");
        tag.setId("backup-tag");
        tag.setPlaceholder(I18n.t("backup.tag.placeholder"));
        tag.setWidth("320px");
        start.setId("backup-start");
        start.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        start.addClickListener(event -> startBackup());
        refresh.setId("backup-refresh");
        refresh.addClickListener(event -> refreshList());
        status.setId("backup-status");
        downloads.setId("backup-downloads");

        grid.setId("backup-grid");
        grid.setSizeFull();
        grid.addColumn(BackupInfo::status).setHeader(I18n.t("backup.column.status")).setAutoWidth(true);
        grid.addColumn(BackupInfo::backupTag).setHeader(I18n.t("backup.column.tag")).setAutoWidth(true);
        grid.addColumn(info -> info.sizeBytes() > 0 ? String.valueOf(info.sizeBytes()) : "")
                .setHeader(I18n.t("backup.column.bytes")).setAutoWidth(true);
        grid.addColumn(BackupInfo::startedAt).setHeader(I18n.t("backup.column.started")).setAutoWidth(true);
        grid.addColumn(BackupInfo::finishedAt).setHeader(I18n.t("backup.column.finished")).setAutoWidth(true);
        grid.addColumn(BackupInfo::error).setHeader(I18n.t("backup.column.error")).setAutoWidth(true);
        grid.addComponentColumn(this::downloadButton).setHeader("").setAutoWidth(true);

        add(title,
                new HorizontalLayout(new Span(I18n.t("management.label")), endpoint),
                new HorizontalLayout(tag, start, refresh),
                status, grid, downloads);
        setFlexGrow(1, grid);

        refreshList();
    }

    private Button downloadButton(BackupInfo info) {
        Button download = new Button(I18n.t("backup.download"));
        download.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        download.setEnabled(info.completed());
        download.addClickListener(event -> download(info));
        return download;
    }

    private void startBackup() {
        String backupTag = tag.getValue() == null ? "" : tag.getValue().strip();
        if (backupTag.isEmpty()) {
            status.setText(I18n.t("backup.tag.required"));
            return;
        }
        if (backupTag.matches(".*[<>:\"|?*\\\\/.].*")) {
            status.setText(I18n.t("backup.tag.invalid"));
            return;
        }
        start.setEnabled(false);
        status.setText(I18n.t("backup.status.starting"));
        try {
            BackupInfo info = client().startBackup(backupTag);
            status.setText(I18n.t("backup.status.started", info.id()));
            refreshList();
            poll(info.id());
        } catch (Exception e) {
            start.setEnabled(true);
            status.setText(I18n.t("backup.status.startFailed", I18n.message(e)));
        }
    }

    private void poll(String id) {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        UI ui = getUI().orElse(null);
        ManagementClient management = client();
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                for (int attempt = 0; attempt < 3600; attempt++) {
                    BackupInfo info;
                    try {
                        info = management.backup(id);
                    } catch (Exception e) {
                        if (ui != null) {
                            ui.access(() -> status.setText(
                                    I18n.t("backup.status.pollFailed", I18n.message(e))));
                        }
                        return;
                    }
                    if (!info.running()) {
                        if (ui != null) {
                            ui.access(() -> {
                                start.setEnabled(true);
                                status.setText(info.completed()
                                        ? I18n.t("backup.status.done", info.filename(), info.sizeBytes())
                                        : I18n.t("backup.status.failed", info.error()));
                                refreshList();
                            });
                        }
                        return;
                    }
                    if (ui != null) {
                        ui.access(() -> status.setText(I18n.t("backup.status.running", info.backupTag())));
                    }
                    Thread.sleep(1_000);
                }
                if (ui != null) {
                    ui.access(() -> {
                        start.setEnabled(true);
                        status.setText(I18n.t("backup.status.stillRunning"));
                    });
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                polling.set(false);
            }
        });
    }

    private void download(BackupInfo info) {
        status.setText(I18n.t("backup.status.downloading"));
        UI ui = getUI().orElse(null);
        ManagementClient management = client();
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                byte[] payload = management.downloadBackup(info.id());
                if (ui != null) {
                    ui.access(() -> {
                        downloads.removeAll();
                        downloads.add(DownloadSupport.anchor(
                                info.filename() == null ? "backup.db" : info.filename(), payload));
                        status.setText(I18n.t("backup.status.downloadReady", payload.length));
                    });
                }
            } catch (Exception e) {
                if (ui != null) {
                    ui.access(() -> status.setText(
                            I18n.t("backup.status.downloadFailed", I18n.message(e))));
                }
            }
        });
    }

    private void refreshList() {
        try {
            List<BackupInfo> items = client().backups();
            grid.setItems(items);
            status.setText(items.isEmpty()
                    ? I18n.t("backup.empty")
                    : I18n.t("backup.count", items.size()));
        } catch (Exception e) {
            grid.setItems(List.of());
            status.setText(I18n.t("backup.status.loadFailed", I18n.message(e)));
        }
    }

    private ManagementClient client() {
        if (client == null) {
            client = ConnectionManager.current().managementClient();
        }
        return client;
    }

    @Override
    protected void onDetach(com.vaadin.flow.component.DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        polling.set(false);
        if (client != null) {
            client.close();
            client = null;
        }
    }
}
