package org.slackerdb.dbserver.service;

import ch.qos.logback.classic.Logger;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.duckdb.DuckDBConnection;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 异步数据库备份任务。
 *
 * <p>备份可能持续数分钟，不能占住一个 HTTP 请求；DbDesk 需要轮询进度并在完成后
 * 通过管理口下载结果。旧同步入口 {@code POST /backup} 继续保留，供脚本兼容使用。</p>
 *
 * <p>任务只保存在本进程内；进程重启后历史任务消失，但备份文件仍在 {@code backup/}
 * 目录下，可由运维直接取用。</p>
 */
public final class BackupService {

    /** 备份目录（相对服务端工作目录）。 */
    private static final Path BACKUP_DIR = Path.of("backup").toAbsolutePath().normalize();

    private final DBInstance dbInstance;
    private final Javalin managementApp;
    private final Logger logger;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Session-Backup");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, BackupTask> tasks = new ConcurrentHashMap<>();

    public BackupService(DBInstance dbInstance, Javalin managementApp, Logger logger) {
        this.dbInstance = dbInstance;
        this.managementApp = managementApp;
        this.logger = logger;
        registerRoutes();
    }

    private void registerRoutes() {
        // 启动异步备份任务
        managementApp.unsafe.routes.post("/backups", ctx -> {
            String backupTag;
            try {
                JSONObject body = JSONObject.parseObject(ctx.body());
                backupTag = body == null ? null : body.getString("backupTag");
            } catch (JSONException ignored) {
                ctx.status(400).json(Map.of(
                        "retCode", 400,
                        "retMsg", "Rejected. Incomplete or format error in backup request."));
                return;
            }
            String validationError = validateTag(backupTag);
            if (validationError != null) {
                ctx.status(400).json(Map.of("retCode", 400, "retMsg", validationError));
                return;
            }

            String id = UUID.randomUUID().toString();
            BackupTask task = new BackupTask(id, backupTag.strip());
            tasks.put(id, task);
            executor.submit(() -> run(task));

            ctx.status(202).json(Map.of(
                    "retCode", 0,
                    "retMsg", "Backup started.",
                    "id", id,
                    "status", task.status));
        });

        // 任务列表（新任务在前）
        managementApp.unsafe.routes.get("/backups", ctx -> {
            List<Map<String, Object>> result = new ArrayList<>();
            tasks.values().stream()
                    .sorted(Comparator.comparing((BackupTask task) -> task.startedAt).reversed())
                    .forEach(task -> result.add(task.toJson()));
            ctx.json(result);
        });

        // 单个任务状态
        managementApp.unsafe.routes.get("/backups/{id}", ctx -> {
            BackupTask task = tasks.get(ctx.pathParam("id"));
            if (task == null) {
                ctx.status(404).json(Map.of(
                        "retCode", 404,
                        "retMsg", "Backup task [" + ctx.pathParam("id") + "] is not found."));
                return;
            }
            ctx.json(task.toJson());
        });

        // 下载已完成的备份
        managementApp.unsafe.routes.get("/backups/{id}/download", this::download);
    }

    private void download(Context ctx) {
        BackupTask task = tasks.get(ctx.pathParam("id"));
        if (task == null) {
            ctx.status(404).json(Map.of(
                    "retCode", 404,
                    "retMsg", "Backup task [" + ctx.pathParam("id") + "] is not found."));
            return;
        }
        if (!"COMPLETED".equals(task.status) || task.file == null || !Files.isRegularFile(task.file)) {
            ctx.status(409).json(Map.of(
                    "retCode", 409,
                    "retMsg", "Backup task [" + task.id + "] is not ready (status=" + task.status + ")."));
            return;
        }

        ctx.status(200);
        ctx.header("Content-Type", "application/octet-stream");
        ctx.header("Content-Disposition", "attachment; filename=\"" + task.file.getFileName() + "\"");
        ctx.header("Content-Length", String.valueOf(task.sizeBytes));
        try (OutputStream out = ctx.res().getOutputStream()) {
            Files.copy(task.file, out);
            out.flush();
        } catch (IOException e) {
            // 客户端断开连接是正常现象，不记为错误
            logger.debug("[BACKUP] Download interrupted: {}", e.getMessage());
        }
    }

    private void run(BackupTask task) {
        Connection backupConnection = null;
        Statement statement = null;
        Path target = null;
        try {
            Files.createDirectories(BACKUP_DIR);
            String filename = task.backupTag + ".db";
            target = BACKUP_DIR.resolve(filename).normalize();
            if (!target.startsWith(BACKUP_DIR)) {
                throw new IllegalArgumentException("Backup path escapes backup directory.");
            }
            Files.deleteIfExists(target);

            backupConnection = ((DuckDBConnection) dbInstance.backendSysConnection).duplicate();
            // DETACH/ATTACH/COPY/DETACH 必须各自独立提交，否则 DuckDB 会拒绝 DETACH。
            backupConnection.setAutoCommit(true);
            statement = backupConnection.createStatement();

            String currentDatabaseName = dbInstance.serverConfiguration.getData_Dir().equalsIgnoreCase(":memory:")
                    ? "memory"
                    : dbInstance.serverConfiguration.getData();
            String backupDBName = "backup_" + task.backupTag;
            statement.execute("DETACH DATABASE IF EXISTS " + backupDBName);
            statement.execute("ATTACH '" + target + "' as " + backupDBName);
            statement.execute("COPY FROM DATABASE \"" + currentDatabaseName + "\" TO " + backupDBName);
            statement.execute("DETACH DATABASE " + backupDBName);

            task.file = target;
            task.sizeBytes = Files.size(target);
            task.filename = "backup/" + filename;
            task.status = "COMPLETED";
            task.finishedAt = LocalDateTime.now().toString();
            logger.info("[BACKUP] Task {} completed: {} ({} bytes)", task.id, target, task.sizeBytes);
        } catch (Throwable e) {
            task.status = "FAILED";
            task.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            task.finishedAt = LocalDateTime.now().toString();
            logger.error("[BACKUP] Task {} failed: {}", task.id, task.error, e);
            if (target != null) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException ignored) {
                    // 半成品删不掉也不影响任务状态
                }
            }
        } finally {
            closeQuietly(statement);
            closeQuietly(backupConnection);
        }
    }

    /**
     * 供同步 {@code POST /backup} 复用的校验规则。
     *
     * @return null 表示合法，否则为可读错误
     */
    public static String validateTag(String backupTag) {
        if (backupTag == null || backupTag.trim().isEmpty()) {
            return "Rejected. Backup tag is empty.";
        }
        if (backupTag.matches(".*[<>:\"|?*\\\\/.].*")) {
            return "Rejected. Backup tag contains invalid characters (path symbols or dots).";
        }
        return null;
    }

    public void stop() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                logger.warn("[BACKUP] Backup executor did not terminate in time.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (BackupTask task : tasks.values()) {
            if ("RUNNING".equals(task.status)) {
                task.status = "FAILED";
                task.error = "Server is stopping.";
                task.finishedAt = LocalDateTime.now().toString();
            }
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    /** 一个备份任务的可观测状态。 */
    private static final class BackupTask {
        private final String id;
        private final String backupTag;
        private final String startedAt;
        private volatile String status = "RUNNING";
        private volatile String filename;
        private volatile String finishedAt;
        private volatile String error;
        private volatile long sizeBytes;
        private volatile Path file;

        private BackupTask(String id, String backupTag) {
            this.id = id;
            this.backupTag = backupTag;
            this.startedAt = LocalDateTime.now().toString();
        }

        private Map<String, Object> toJson() {
            Map<String, Object> json = new java.util.LinkedHashMap<>();
            json.put("id", id);
            json.put("backupTag", backupTag);
            json.put("status", status);
            json.put("filename", filename);
            json.put("sizeBytes", sizeBytes);
            json.put("startedAt", startedAt);
            json.put("finishedAt", finishedAt);
            json.put("error", error);
            return json;
        }
    }
}
