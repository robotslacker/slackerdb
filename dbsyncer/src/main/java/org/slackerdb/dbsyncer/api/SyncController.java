package org.slackerdb.dbsyncer.api;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slackerdb.dbsyncer.DbSyncerConfigException;
import org.slackerdb.dbsyncer.DbSyncerModule;
import org.slackerdb.dbsyncer.config.SyncConfigLoader;
import org.slackerdb.dbsyncer.engine.JobManager;
import org.slackerdb.dbsyncer.engine.JobRuntime;
import org.slackerdb.dbsyncer.engine.JobState;
import org.slackerdb.dbsyncer.state.SyncStateStore;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * /dbsyncer/* REST 路由。
 */
public final class SyncController {

    /**
     * 作业状态查询会访问源库（lag 需要新开连接），必须串行化：
     * 同一时刻只允许一个 /jobs 或 /jobs/{name} 请求在跑，避免轮询打爆源库连接数。
     */
    private static final long STATUS_LOCK_WAIT_MS = 5_000;

    private final DbSyncerModule module;
    private final Javalin app;
    private final Logger logger;
    private final ReentrantLock statusLock = new ReentrantLock();
    private boolean registered;

    public SyncController(DbSyncerModule module, Javalin app, Logger logger) {
        this.module = module;
        this.app = app;
        this.logger = logger;
    }

    public void registerRoutes() {
        if (registered) {
            return;
        }
        registered = true;
        String base = DbSyncerModule.ROUTE_PREFIX;
        app.unsafe.routes.get(base + "/status", this::status);
        app.unsafe.routes.get(base + "/jobs", this::listJobs);
        app.unsafe.routes.get(base + "/jobs/{name}", this::getJob);
        app.unsafe.routes.get(base + "/jobs/{name}/runs", this::listRuns);
        app.unsafe.routes.post(base + "/jobs/{name}/start", this::startJob);
        app.unsafe.routes.post(base + "/jobs/{name}/stop", this::stopJob);
        app.unsafe.routes.post(base + "/validate", this::validate);
        app.unsafe.routes.post(base + "/reload", this::reload);
    }

    private void status(Context ctx) {
        ok(ctx, module.status());
    }

    private void listJobs(Context ctx) {
        if (controlUnavailable(ctx)) {
            return;
        }
        if (!acquireStatusLock(ctx)) {
            return;
        }
        try {
            JSONArray jobs = new JSONArray();
            for (JobRuntime runtime : module.jobs()) {
                jobs.add(runtime.status());
            }
            JSONObject data = new JSONObject();
            data.put("jobs", jobs);
            ok(ctx, data);
        } finally {
            statusLock.unlock();
        }
    }

    private void getJob(Context ctx) {
        if (!acquireStatusLock(ctx)) {
            return;
        }
        try {
            JobRuntime runtime = module.jobManager().job(ctx.pathParam("name"));
            if (runtime == null) {
                notFound(ctx, "job [" + ctx.pathParam("name") + "] not found");
                return;
            }
            ok(ctx, runtime.status());
        } finally {
            statusLock.unlock();
        }
    }

    private boolean acquireStatusLock(Context ctx) {
        try {
            if (statusLock.tryLock(STATUS_LOCK_WAIT_MS, TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ctx.status(503).json(envelope(503,
                "another job status request is in progress; retry later", null));
        return false;
    }

    /** 最近的运行历史（v$dbsync_run）。 */
    private void listRuns(Context ctx) {
        String name = ctx.pathParam("name");
        JobRuntime runtime = module.jobManager().job(name);
        if (runtime == null) {
            notFound(ctx, "job [" + name + "] not found");
            return;
        }
        int limit = 20;
        String rawLimit = ctx.queryParam("limit");
        if (rawLimit != null && !rawLimit.isBlank()) {
            try {
                limit = Math.min(200, Math.max(1, Integer.parseInt(rawLimit.trim())));
            } catch (NumberFormatException e) {
                badRequest(ctx, "limit must be an integer between 1 and 200");
                return;
            }
        }
        try {
            JSONArray runs = new JSONArray();
            for (SyncStateStore.RunRecord run : module.loadRuns(name, limit)) {
                JSONObject json = new JSONObject();
                json.put("runId", run.runId);
                json.put("startedAt", run.startedAt == null ? null : run.startedAt.toInstant().toString());
                json.put("endedAt", run.endedAt == null ? null : run.endedAt.toInstant().toString());
                json.put("state", run.state);
                json.put("error", run.error);
                json.put("rowsRead", run.rowsRead);
                json.put("rowsApplied", run.rowsApplied);
                runs.add(json);
            }
            JSONObject data = new JSONObject();
            data.put("job", name);
            data.put("runs", runs);
            ok(ctx, data);
        } catch (SQLException e) {
            internal(ctx, e);
        }
    }

    private void startJob(Context ctx) {
        if (controlUnavailable(ctx)) {
            return;
        }
        JSONObject body = body(ctx);
        if (body == null) {
            return;
        }
        JobRuntime runtime = module.jobManager().job(ctx.pathParam("name"));
        if (runtime == null) {
            notFound(ctx, "job [" + ctx.pathParam("name") + "] not found");
            return;
        }
        if (runtime.orphaned()) {
            conflict(ctx, "job [" + runtime.name() + "] is orphaned; reload its definition first");
            return;
        }
        try {
            boolean resync = bool(body, "resync", false);
            JobState state = module.jobManager().start(runtime.name(), resync);
            JSONObject data = new JSONObject();
            data.put("job", runtime.name());
            data.put("state", state.name());
            data.put("phase", state.name());
            data.put("lastError", runtime.lastError());
            ctx.status(202).json(envelope(0, "Accepted", data));
        } catch (IllegalStateException e) {
            conflict(ctx, e.getMessage());
        } catch (Exception e) {
            internal(ctx, e);
        }
    }

    private void stopJob(Context ctx) {
        if (controlUnavailable(ctx)) {
            return;
        }
        JSONObject body = body(ctx);
        if (body == null) {
            return;
        }
        JobRuntime runtime = module.jobManager().job(ctx.pathParam("name"));
        if (runtime == null) {
            notFound(ctx, "job [" + ctx.pathParam("name") + "] not found");
            return;
        }
        try {
            boolean wait = bool(body, "wait", true);
            long timeout = Math.max(1000, longValue(body, "timeoutMs", 10_000));
            JobState state = module.jobManager().stop(runtime.name(), wait, timeout);
            JSONObject data = new JSONObject();
            data.put("job", runtime.name());
            data.put("state", state.name());
            if (state == JobState.STOPPING) {
                ctx.status(202).json(envelope(0, "Accepted", data));
            } else {
                ok(ctx, data);
            }
        } catch (Exception e) {
            internal(ctx, e);
        }
    }

    private void validate(Context ctx) {
        JSONObject body = body(ctx);
        if (body == null) {
            return;
        }
        String content = body.getString("content");
        JSONObject data = new JSONObject();
        try {
            if (content != null) {
                SyncConfigLoader.loadText(content, "inline");
            } else {
                SyncConfigLoader.load(module.configPath());
            }
            data.put("valid", true);
            data.put("errors", new JSONArray());
            data.put("warnings", new JSONArray());
            ok(ctx, data);
        } catch (DbSyncerConfigException e) {
            JSONObject error = new JSONObject();
            error.put("path", e.path());
            error.put("message", e.getMessage());
            JSONArray errors = new JSONArray();
            errors.add(error);
            data.put("valid", false);
            data.put("errors", errors);
            data.put("warnings", new JSONArray());
            // 与 README 的状态码约定一致：配置非法 = 400（响应体仍带结构化 errors）。
            ctx.status(400).json(envelope(400, e.getMessage(), data));
        }
    }

    private void reload(Context ctx) {
        if (controlUnavailable(ctx)) {
            return;
        }
        try {
            JobManager.ReloadResult result = module.reload();
            JSONObject data = new JSONObject();
            data.put("added", result.added());
            data.put("updated", result.updated());
            data.put("removed", result.removed());
            data.put("staleRunning", result.staleRunning());
            data.put("warnings", result.warnings());
            data.put("configHash", module.configHash());
            ok(ctx, data);
        } catch (DbSyncerConfigException e) {
            badRequest(ctx, e.getMessage());
        } catch (Exception e) {
            internal(ctx, e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean controlUnavailable(Context ctx) {
        if (module.isStopping()) {
            ctx.status(503).json(envelope(-1, "dbsyncer is stopping", null));
            return true;
        }
        return false;
    }

    private JSONObject body(Context ctx) {
        String text = ctx.body();
        if (text.isBlank()) {
            return new JSONObject();
        }
        try {
            JSONObject object = JSONObject.parseObject(text);
            return object == null ? new JSONObject() : object;
        } catch (Exception e) {
            badRequest(ctx, "invalid JSON body: " + e.getMessage());
            return null;
        }
    }

    private static boolean bool(JSONObject body, String key, boolean fallback) {
        Boolean value = body.getBoolean(key);
        return value == null ? fallback : value;
    }

    private static long longValue(JSONObject body, String key, long fallback) {
        Long value = body.getLong(key);
        return value == null ? fallback : value;
    }

    private static void ok(Context ctx, Object data) {
        ctx.json(envelope(0, "OK", data));
    }

    private static void notFound(Context ctx, String message) {
        ctx.status(404).json(envelope(404, message, null));
    }

    private static void conflict(Context ctx, String message) {
        ctx.status(409).json(envelope(409, message, null));
    }

    private static void badRequest(Context ctx, String message) {
        ctx.status(400).json(envelope(400, message, null));
    }

    private void internal(Context ctx, Exception e) {
        logger.warn("[DBSYNCER] {} {} failed: {}", ctx.method(), ctx.path(), e.getMessage(), e);
        ctx.status(500).json(envelope(-1, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                null));
    }
    private static JSONObject envelope(int retCode, String retMsg, Object data) {
        JSONObject object = new JSONObject();
        object.put("retCode", retCode);
        object.put("retMsg", retMsg);
        if (data != null) {
            object.put("data", data);
        }
        return object;
    }
}
