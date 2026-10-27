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

    private final TextField tag = new TextField("备份标签");
    private final Button start = new Button("开始备份");
    private final Button refresh = new Button("刷新列表");
    private final Span status = new Span("就绪");
    private final Grid<BackupInfo> grid = new Grid<>(BackupInfo.class, false);
    private final HorizontalLayout downloads = new HorizontalLayout();

    private transient ManagementClient client;
    private final AtomicBoolean polling = new AtomicBoolean(false);

    public BackupsView() {
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        H2 title = new H2("备份");
        if (!ConnectionManager.current().hasInstanceManagement()) {
            Span guard = new Span("当前连接没有实例级管理口，备份不可用。");
            guard.setId("backup-guard");
            add(title, guard);
            return;
        }
        Span endpoint = new Span("管理口：" + ConnectionManager.current().managementUrl());
        endpoint.setId("backup-endpoint");
        tag.setId("backup-tag");
        tag.setPlaceholder("例如 mybackup（不能包含点或路径符号）");
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
        grid.addColumn(BackupInfo::status).setHeader("状态").setAutoWidth(true);
        grid.addColumn(BackupInfo::backupTag).setHeader("标签").setAutoWidth(true);
        grid.addColumn(info -> info.sizeBytes() > 0 ? String.valueOf(info.sizeBytes()) : "")
                .setHeader("字节数").setAutoWidth(true);
        grid.addColumn(BackupInfo::startedAt).setHeader("开始").setAutoWidth(true);
        grid.addColumn(BackupInfo::finishedAt).setHeader("结束").setAutoWidth(true);
        grid.addColumn(BackupInfo::error).setHeader("错误").setAutoWidth(true);
        grid.addComponentColumn(this::downloadButton).setHeader("").setAutoWidth(true);

        add(title,
                new HorizontalLayout(new Span("管理口："), endpoint),
                new HorizontalLayout(tag, start, refresh),
                status, grid, downloads);
        setFlexGrow(1, grid);

        refreshList();
    }

    private Button downloadButton(BackupInfo info) {
        Button download = new Button("下载");
        download.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        download.setEnabled(info.completed());
        download.addClickListener(event -> download(info));
        return download;
    }

    private void startBackup() {
        String backupTag = tag.getValue() == null ? "" : tag.getValue().strip();
        if (backupTag.isEmpty()) {
            status.setText("请输入备份标签");
            return;
        }
        if (backupTag.matches(".*[<>:\"|?*\\\\/.].*")) {
            status.setText("备份标签不能包含路径符号或点");
            return;
        }
        start.setEnabled(false);
        status.setText("启动备份…");
        try {
            BackupInfo info = client().startBackup(backupTag);
            status.setText("备份已启动：" + info.id());
            refreshList();
            poll(info.id());
        } catch (Exception e) {
            start.setEnabled(true);
            status.setText("启动失败：" + message(e));
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
                            ui.access(() -> status.setText("查询备份状态失败：" + message(e)));
                        }
                        return;
                    }
                    if (!info.running()) {
                        if (ui != null) {
                            ui.access(() -> {
                                start.setEnabled(true);
                                status.setText(info.completed()
                                        ? "备份完成：" + info.filename() + "（" + info.sizeBytes() + " 字节）"
                                        : "备份失败：" + info.error());
                                refreshList();
                            });
                        }
                        return;
                    }
                    if (ui != null) {
                        ui.access(() -> status.setText("备份中…（" + info.backupTag() + "）"));
                    }
                    Thread.sleep(1_000);
                }
                if (ui != null) {
                    ui.access(() -> {
                        start.setEnabled(true);
                        status.setText("备份仍在服务端进行，可稍后刷新列表查看");
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
        status.setText("下载中…");
        UI ui = getUI().orElse(null);
        ManagementClient management = client();
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                byte[] payload = management.downloadBackup(info.id());
                if (ui != null) {
                    ui.access(() -> {
                        downloads.removeAll();
                        downloads.add(DownloadSupport.anchor(info.filename() == null ? "backup.db" : info.filename(), payload));
                        status.setText("已生成下载：" + payload.length + " 字节");
                    });
                }
            } catch (Exception e) {
                if (ui != null) {
                    ui.access(() -> status.setText("下载失败：" + message(e)));
                }
            }
        });
    }

    private void refreshList() {
        try {
            List<BackupInfo> items = client().backups();
            grid.setItems(items);
            status.setText(items.isEmpty() ? "暂无备份任务" : "共 " + items.size() + " 个备份任务");
        } catch (Exception e) {
            grid.setItems(List.of());
            status.setText("读取失败：" + message(e));
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

    private static String message(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
