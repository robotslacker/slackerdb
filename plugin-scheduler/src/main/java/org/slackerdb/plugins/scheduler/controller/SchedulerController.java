package org.slackerdb.plugins.scheduler.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slackerdb.plugins.scheduler.entity.SchedulerRun;
import org.slackerdb.plugins.scheduler.entity.SchedulerTask;
import org.slackerdb.plugins.scheduler.entity.TaskHistory;
import org.slackerdb.plugins.scheduler.meta.SchedulerMeta;
import org.slackerdb.plugins.scheduler.quartz.DynamicQuartzScheduler;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for the scheduler plugin.
 *
 * <p>Registers all /scheduler/* routes on the Javalin application.
 * Task parameters come from the API or project template files.
 * Global scheduler parameters (thread count, etc.) come from the API, not config files.</p>
 */
public class SchedulerController {

    private final Javalin app;
    private final SchedulerMeta meta;
    private final DynamicQuartzScheduler quartzScheduler;
    private final Logger logger;
    private final String workHome;

    /** 项目目录，存放项目模板（sql、hop 文件等） */
    private final String projectHome;

    /** Hop 运行环境配置（方案 A：Hop 独立目录 + 命令行执行）。 */
    private final org.slackerdb.plugins.scheduler.runner.TaskRequest.HopConfig hopConfig;

    /** 全局变量层：{@code <scheduler.home>/variables.properties}。 */
    private final java.util.Map<String, String> globalVariables;

    /** run 启动编排器：组/顺序/必选项语义在这里落地。 */
    private final org.slackerdb.plugins.scheduler.quartz.RunOrchestrator orchestrator;

    public SchedulerController(Javalin app, SchedulerMeta meta,
                               DynamicQuartzScheduler quartzScheduler,
                               Logger logger, String workHome, String projectHome) {
        this(app, meta, quartzScheduler, logger, workHome, projectHome, null);
    }

    public SchedulerController(Javalin app, SchedulerMeta meta,
                               DynamicQuartzScheduler quartzScheduler,
                               Logger logger, String workHome, String projectHome,
                               org.slackerdb.plugins.scheduler.runner.TaskRequest.HopConfig hopConfig) {
        this.app = app;
        this.meta = meta;
        this.quartzScheduler = quartzScheduler;
        this.logger = logger;
        this.workHome = workHome;
        this.projectHome = projectHome;
        this.hopConfig = hopConfig == null
                ? new org.slackerdb.plugins.scheduler.runner.TaskRequest.HopConfig() : hopConfig;
        this.globalVariables = org.slackerdb.plugins.scheduler.runner.VariableSet
                .loadProperties(Paths.get(workHome, "variables.properties"));
        this.orchestrator = new org.slackerdb.plugins.scheduler.quartz.RunOrchestrator(
                quartzScheduler, meta, logger);
    }

    /**
     * Register all routes.
     */
    public void registerRoutes() {
        // ========== Global scheduler routes ==========
        app.unsafe.routes.post("/scheduler/start", this::handleSchedulerStart);
        app.unsafe.routes.get("/scheduler/status", this::handleSchedulerStatus);

        // ========== Project routes ==========
        app.unsafe.routes.get("/scheduler/project/{projectType}", this::handleGetProject);
        app.unsafe.routes.get("/scheduler/project/list", this::handleListProjects);

        // ========== Run routes ==========
        // IMPORTANT: Static routes must be registered BEFORE parameterized routes
        // to avoid path parameters matching literal path segments.
        app.unsafe.routes.post("/scheduler/run/create", this::handleCreateRun);
        app.unsafe.routes.get("/scheduler/run/list", this::handleListRuns);
        app.unsafe.routes.post("/scheduler/run/{runId}/drop", this::handleDropRun);
        app.unsafe.routes.post("/scheduler/run/{runId}/start", this::handleStartRun);
        app.unsafe.routes.post("/scheduler/run/{runId}/stop", this::handleStopRun);
        app.unsafe.routes.post("/scheduler/run/{runId}/abort", this::handleAbortRun);
        app.unsafe.routes.get("/scheduler/run/{runId}", this::handleGetRun);

        // ========== Task routes (scoped to run) ==========
        // Task fields (scriptType, runPolicy, failPolicy, etc.) come from project template files.
        // The API only allows scheduling operations.
        app.unsafe.routes.post("/scheduler/run/{runId}/task/schedule", this::handleScheduleTask);
        app.unsafe.routes.post("/scheduler/run/{runId}/task/start", this::handleStartTask);
        app.unsafe.routes.post("/scheduler/run/{runId}/task/stop", this::handleStopTask);
        app.unsafe.routes.post("/scheduler/run/{runId}/task/abort", this::handleAbortTask);
        app.unsafe.routes.get("/scheduler/run/{runId}/task/list", this::handleListTasks);
        app.unsafe.routes.get("/scheduler/run/{runId}/task/history", this::handleTaskHistory);
        app.unsafe.routes.get("/scheduler/run/{runId}/log", this::handleViewLog);
        app.unsafe.routes.get("/scheduler/run/{runId}/variables", this::handleRunVariables);
        app.unsafe.routes.get("/scheduler/describeCrontabExpr", this::handleDescribeCrontabExpr);

        logger.info("[SCHEDULER] REST routes registered.");
    }

    // ========== Global Scheduler Handlers ==========

    /**
     * POST /scheduler/start
     * Start the Quartz scheduler.
     *
     * Body: { "threadCount": 10 } (optional, default 10)
     * Note: threadCount only takes effect on first start. To change thread count,
     * the plugin must be restarted.
     */
    private void handleSchedulerStart(Context ctx) {
        try {
            // Try to parse body, but don't fail if empty
            int threadCount = 10;
            try {
                String bodyStr = ctx.body();
                if (bodyStr != null && !bodyStr.isEmpty()) {
                    JSONObject body = JSON.parseObject(bodyStr);
                    threadCount = body.getIntValue("threadCount", 10);
                }
            } catch (Exception ignored) {
                // Use default thread count if body parsing fails
            }

            if (threadCount < 1) {
                threadCount = 10;
            }

            // Start the scheduler (if already started, this is a no-op)
            quartzScheduler.start();

            logger.info("[SCHEDULER] Scheduler started.");
            ctx.json(Map.of(
                    "message", "Scheduler started",
                    "threadCount", quartzScheduler.getThreadCount()
            ));
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error starting scheduler: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to start scheduler: " + e.getMessage()));
        }
    }

    /**
     * GET /scheduler/status
     * Get the current scheduler status.
     */
    private void handleSchedulerStatus(Context ctx) {
        try {
            int threadCount = quartzScheduler.getThreadCount();
            ctx.json(Map.of(
                    "status", "running",
                    "threadCount", threadCount
            ));
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error getting scheduler status: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to get scheduler status: " + e.getMessage()));
        }
    }

    // ========== Project Handlers ==========

    /**
     * GET /scheduler/project/{projectType}
     * Get the default task configuration for a project type.
     */
    private void handleGetProject(Context ctx) {
        String projectType = ctx.pathParam("projectType");
        Path projectDir = Paths.get(projectHome, projectType);
        try {
            org.slackerdb.plugins.scheduler.meta.TaskTemplateLoader.Loaded loaded =
                    org.slackerdb.plugins.scheduler.meta.TaskTemplateLoader
                            .load(Paths.get(projectHome), projectType, null);
            if (!Files.isDirectory(projectDir) && loaded.file() == null) {
                ctx.status(404).json(Map.of("error", "Project not found: " + projectType));
                return;
            }
            ctx.json(Map.of(
                    "projectType", projectType,
                    "projectDir", projectDir.toString(),
                    "taskListFile", loaded.fileText(),
                    "warnings", loaded.warnings(),
                    "tasks", loaded.tasks()
            ));
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error reading project config: {}", e.getMessage());
            ctx.status(500).json(Map.of("error", "Failed to read project config: " + e.getMessage()));
        }
    }

    /**
     * GET /scheduler/project/list
     * List all available project types.
     */
    private void handleListProjects(Context ctx) {
        File dir = new File(projectHome);
        if (!dir.exists() || !dir.isDirectory()) {
            ctx.json(Map.of("projects", Collections.emptyList()));
            return;
        }

        String[] projectTypes = dir.list((d, name) -> new File(d, name).isDirectory());
        ctx.json(Map.of("projects", projectTypes != null ? Arrays.asList(projectTypes) : Collections.emptyList()));
    }

    // ========== Run Handlers ==========

    /**
     * POST /scheduler/run/create
     * Create a new Run based on a project type, with optional inline task overrides.
     *
     * Body: {
     *   "projectType": "...",
     *   "configJson": "...",
     *   "tasks": [
     *     {
     *       "taskName": "...",
     *       "taskScript": "...",
     *       "taskScriptType": "SQL|HOP|SHELL",
     *       "taskRunPolicy": "RUN_ONCE|CRONTAB|INTERVAL",
     *       "taskFailPolicy": "STOP|CONTINUE|RETRY",
     *       "taskParallelPolicy": "PARALLEL|SERIAL_DISCARD|SERIAL_DELAY|SERIAL_CATCHUP",
     *       "taskCrontabExpr": "...",
     *       "taskInterval": 0,
     *       "taskTimeout": 0,
     *       "taskEnabled": true,
     *       "taskGroup": "...",
     *       "taskStartupOrder": 0,
     *       "taskDescription": "..."
     *     }
     *   ]
     * }
     */
    private void handleCreateRun(Context ctx) {
        try {
            JSONObject body = ctx.bodyAsClass(JSONObject.class);
            String projectType = body.getString("projectType");
            String configJson = body.getString("configJson");

            if (projectType == null || projectType.isEmpty()) {
                ctx.status(400).json(Map.of("error", "projectType is required"));
                return;
            }

            // Generate run ID: use provided one or auto-generate from timestamp (14 digits)
            String runId = body.getString("runId");
            if (runId == null || runId.isEmpty()) {
                runId = LocalDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            }

            // Check for duplicate runId
            if (meta.getRun(runId) != null) {
                ctx.status(409).json(Map.of("error", "Run already exists: " + runId));
                return;
            }

            // Create working directory
            String runDir = workHome + File.separator + "runs" + File.separator + runId;
            createRunDirectory(runDir);

            // 拷贝 HOP 工程到实例的 flow 目录：
            //   hop-config.json / project-config.json / default/ / <projectType>/
            // 这样 HOP_CONFIG_FOLDER=<runHome>/flow 才能解析 --project 与 --environment
            copyHopProject(projectType, Paths.get(runDir, "flow"));

            // Create run record
            SchedulerRun run = new SchedulerRun();
            run.setRunId(runId);
            run.setProjectType(projectType);
            run.setStatus("CREATED");
            run.setWorkDir(runDir);
            run.setConfigJson(configJson);
            run.setCreateTime(LocalDateTime.now());
            run.setUpdateTime(LocalDateTime.now());
            meta.insertRun(run);

            // If inline tasks are provided, use them; otherwise load from project template
            List<JSONObject> inlineTasks = body.getJSONArray("tasks") != null
                    ? body.getJSONArray("tasks").toList(JSONObject.class)
                    : Collections.emptyList();

            int taskCount;
            if (!inlineTasks.isEmpty()) {
                taskCount = createTasksFromApi(runId, inlineTasks);
            } else {
                taskCount = loadDefaultTasks(runId, projectType);
            }

            logger.info("[SCHEDULER] Run created: runId={}, projectType={}, tasks={}", runId, projectType, taskCount);
            ctx.status(201).json(Map.of(
                    "runId", runId,
                    "projectType", projectType,
                    "status", "CREATED",
                    "workDir", runDir,
                    "taskCount", taskCount
            ));
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error creating run: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to create run: " + e.getMessage()));
        }
    }

    /**
     * POST /scheduler/run/{runId}/drop
     * Delete a Run and all its resources.
     */
    private void handleDropRun(Context ctx) {
        String runId = ctx.pathParam("runId");
        try {
            SchedulerRun run = meta.getRun(runId);
            if (run == null) {
                ctx.status(404).json(Map.of("error", "Run not found: " + runId));
                return;
            }

            // Stop all Quartz jobs
            quartzScheduler.unscheduleAllJobs(runId);

            // Delete tasks from DB
            meta.deleteTasksByRunId(runId);

            // Delete run from DB
            meta.deleteRun(runId);

            // Delete working directory
            deleteDirectory(run.getWorkDir());

            logger.info("[SCHEDULER] Run dropped: runId={}", runId);
            ctx.json(Map.of("message", "Run dropped successfully", "runId", runId));
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error dropping run: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to drop run: " + e.getMessage()));
        }
    }

    /**
     * POST /scheduler/run/{runId}/start
     * 启动 Run：按组/顺序编排（RUN_ONCE 串行执行，成功后激活该组的周期任务）。
     *
     * <p>编排在后台线程进行，接口立即返回；run 状态会迁移为 STARTING → STARTED（或 FAILED）。</p>
     */
    private void handleStartRun(Context ctx) {
        String runId = ctx.pathParam("runId");
        try {
            SchedulerRun run = meta.getRun(runId);
            if (run == null) {
                ctx.status(404).json(Map.of("error", "Run not found: " + runId));
                return;
            }
            if (orchestrator.isRunning(runId)) {
                ctx.status(409).json(Map.of("error", "Run is starting: " + runId));
                return;
            }

            List<SchedulerTask> tasks = meta.getTasksByRunId(runId);
            int enabled = 0;
            for (SchedulerTask task : tasks) {
                if (task.isTaskEnabled()) {
                    scheduleTask(run, task);   // 只登记定义（保持暂停），启动交给编排器
                    enabled++;
                }
            }

            orchestrator.start(runId, tasks);
            logger.info("[SCHEDULER] Run started: runId={}, tasksEnabled={}", runId, enabled);
            ctx.json(Map.of("message", "Run starting", "runId", runId,
                    "status", "STARTING", "tasksEnabled", enabled));
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error starting run: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to start run: " + e.getMessage()));
        }
    }

    /**
     * POST /scheduler/run/{runId}/stop
     * 停止 Run：暂停全部任务（保留定义，可再次 start）。
     */
    private void handleStopRun(Context ctx) {
        String runId = ctx.pathParam("runId");
        try {
            SchedulerRun run = meta.getRun(runId);
            if (run == null) {
                ctx.status(404).json(Map.of("error", "Run not found: " + runId));
                return;
            }

            orchestrator.cancel(runId);
            quartzScheduler.pauseAllJobs(runId);         // 暂停（不删除，可再次 start）
            meta.updateRunStatus(runId, "STOPPED");
            logger.info("[SCHEDULER] Run stopped: runId={}", runId);
            ctx.json(Map.of("message", "Run stopped", "runId", runId));
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error stopping run: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to stop run: " + e.getMessage()));
        }
    }

    /**
     * POST /scheduler/run/{runId}/abort
     * 中止 Run：中断正在执行的任务并暂停全部任务。
     */
    private void handleAbortRun(Context ctx) {
        String runId = ctx.pathParam("runId");
        try {
            SchedulerRun run = meta.getRun(runId);
            if (run == null) {
                ctx.status(404).json(Map.of("error", "Run not found: " + runId));
                return;
            }

            orchestrator.cancel(runId);
            quartzScheduler.abortAllJobs(runId);         // 中断 + 暂停
            meta.updateRunStatus(runId, "ABORTED");
            logger.info("[SCHEDULER] Run aborted: runId={}", runId);
            ctx.json(Map.of("message", "Run aborted", "runId", runId));
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error aborting run: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to abort run: " + e.getMessage()));
        }
    }

    /**
     * GET /scheduler/run/{runId}
     * Get Run details.
     */
    private void handleGetRun(Context ctx) {
        String runId = ctx.pathParam("runId");
        try {
            SchedulerRun run = meta.getRun(runId);
            if (run == null) {
                ctx.status(404).json(Map.of("error", "Run not found: " + runId));
                return;
            }
            ctx.json(run);
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error getting run: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to get run: " + e.getMessage()));
        }
    }

    /**
     * GET /scheduler/run/list
     * List all Runs.
     */
    private void handleListRuns(Context ctx) {
        try {
            List<SchedulerRun> runs = meta.listRuns();
            ctx.json(Map.of("runs", runs));
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error listing runs: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to list runs: " + e.getMessage()));
        }
    }

    // ========== Task Handlers ==========

    /**
     * POST /scheduler/run/{runId}/task/schedule
     * Schedule a task for execution.
     *
     * Body: { "taskName": "..." }
     */
    private void handleScheduleTask(Context ctx) {
        String runId = ctx.pathParam("runId");
        try {
            JSONObject body = ctx.bodyAsClass(JSONObject.class);
            String taskName = body.getString("taskName");

            if (taskName == null) {
                ctx.status(400).json(Map.of("error", "taskName is required"));
                return;
            }

            SchedulerRun run = meta.getRun(runId);
            if (run == null) {
                ctx.status(404).json(Map.of("error", "Run not found: " + runId));
                return;
            }

            List<SchedulerTask> tasks = meta.getTasksByRunId(runId);
            SchedulerTask task = tasks.stream()
                    .filter(t -> t.getTaskName().equals(taskName))
                    .findFirst().orElse(null);

            if (task == null) {
                ctx.status(404).json(Map.of("error", "Task not found: " + taskName));
                return;
            }

            scheduleTask(run, task);
            logger.info("[SCHEDULER] Task scheduled: runId={}, taskName={}", runId, taskName);
            ctx.json(Map.of("message", "Task scheduled", "runId", runId, "taskName", taskName));
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error scheduling task: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to schedule task: " + e.getMessage()));
        }
    }

    /**
     * POST /scheduler/run/{runId}/task/start
     * 启动单个任务：周期任务恢复调度；一次性任务立即触发一次。
     *
     * <p>不会改写任务定义（历史实现用 RUN_ONCE 覆盖同一 JobKey，会连带删掉周期触发器）。</p>
     */
    private void handleStartTask(Context ctx) {
        String runId = ctx.pathParam("runId");
        try {
            JSONObject body = ctx.bodyAsClass(JSONObject.class);
            String taskName = body.getString("taskName");

            if (taskName == null) {
                ctx.status(400).json(Map.of("error", "taskName is required"));
                return;
            }

            SchedulerRun run = meta.getRun(runId);
            if (run == null) {
                ctx.status(404).json(Map.of("error", "Run not found: " + runId));
                return;
            }

            SchedulerTask task = findTask(runId, taskName);
            if (task == null) {
                ctx.status(404).json(Map.of("error", "Task not found: " + taskName));
                return;
            }

            // 若尚未编排（例如服务重启后），先登记定义再启动
            if (!quartzScheduler.isJobScheduled(runId, taskName)) {
                scheduleTask(run, task);
            }
            String policy = task.getTaskRunPolicy() == null ? "RUN_ONCE" : task.getTaskRunPolicy();
            quartzScheduler.startJob(runId, taskName, policy);

            String action = "RUN_ONCE".equalsIgnoreCase(policy) ? "triggered once" : "resumed";
            logger.info("[SCHEDULER] Task started: runId={}, taskName={}, policy={} ({})",
                    runId, taskName, policy, action);
            ctx.json(Map.of("message", "Task started", "runId", runId,
                    "taskName", taskName, "policy", policy, "action", action));
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error starting task: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to start task: " + e.getMessage()));
        }
    }

    /**
     * POST /scheduler/run/{runId}/task/stop
     * 停止单个任务：暂停（保留定义与触发器，可再次 start）。
     */
    private void handleStopTask(Context ctx) {
        String runId = ctx.pathParam("runId");
        try {
            JSONObject body = ctx.bodyAsClass(JSONObject.class);
            String taskName = body.getString("taskName");

            if (taskName == null) {
                ctx.status(400).json(Map.of("error", "taskName is required"));
                return;
            }

            quartzScheduler.pauseJob(runId, taskName);
            ctx.json(Map.of("message", "Task stopped", "runId", runId, "taskName", taskName));
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error stopping task: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to stop task: " + e.getMessage()));
        }
    }

    private SchedulerTask findTask(String runId, String taskName) throws Exception {
        for (SchedulerTask task : meta.getTasksByRunId(runId)) {
            if (taskName.equals(task.getTaskName())) {
                return task;
            }
        }
        return null;
    }

    /**
     * POST /scheduler/run/{runId}/task/abort
     * Abort a specific task.
     *
     * Body: { "taskName": "..." }
     */
    private void handleAbortTask(Context ctx) {
        String runId = ctx.pathParam("runId");
        try {
            JSONObject body = ctx.bodyAsClass(JSONObject.class);
            String taskName = body.getString("taskName");

            if (taskName == null) {
                ctx.status(400).json(Map.of("error", "taskName is required"));
                return;
            }

            quartzScheduler.abortJob(runId, taskName);
            ctx.json(Map.of("message", "Task aborted", "runId", runId, "taskName", taskName));
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error aborting task: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to abort task: " + e.getMessage()));
        }
    }

    /**
     * GET /scheduler/run/{runId}/task/list
     * List all tasks for a run.
     */
    /**
     * GET /scheduler/run/{runId}/task/list
     * 任务列表：DB 里的定义 + Quartz 运行态（触发器状态/下次触发/上次结果/是否运行中）。
     */
    private void handleListTasks(Context ctx) {
        String runId = ctx.pathParam("runId");
        try {
            List<SchedulerTask> tasks = meta.getTasksByRunId(runId);
            Map<String, Map<String, Object>> scheduled = new HashMap<>();
            for (Map<String, Object> job : quartzScheduler.getJobsForRun(runId)) {
                Object taskName = job.get("taskName");
                if (taskName != null) {
                    scheduled.put(String.valueOf(taskName), job);
                }
            }

            List<Map<String, Object>> result = new ArrayList<>();
            for (SchedulerTask task : tasks) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("taskName", task.getTaskName());
                item.put("taskScript", task.getTaskScript());
                item.put("taskScriptType", task.getTaskScriptType());
                item.put("taskRunPolicy", task.getTaskRunPolicy());
                item.put("taskFailPolicy", task.getTaskFailPolicy());
                item.put("taskParallelPolicy", task.getTaskParallelPolicy());
                item.put("taskCrontabExpr", task.getTaskCrontabExpr());
                item.put("taskInterval", task.getTaskInterval());
                item.put("taskTimeout", task.getTaskTimeout());
                item.put("taskEnabled", task.isTaskEnabled());
                item.put("taskMandatory", task.isTaskMandatory());
                item.put("taskGroup", task.getTaskGroup());
                item.put("taskStartupOrder", task.getTaskStartupOrder());
                item.put("taskDescription", task.getTaskDescription());

                Map<String, Object> job = scheduled.get(task.getTaskName());
                item.put("scheduled", job != null);
                if (job != null) {
                    item.put("triggerState", job.get("state"));
                    item.put("nextFireTime", job.get("nextFireTime"));
                    item.put("previousFireTime", job.get("previousFireTime"));
                }
                // 运行态与上次结果（来自任务执行器）
                item.putAll(org.slackerdb.plugins.scheduler.quartz.QuartzJobStatusManager
                        .snapshot(runId + ":" + task.getTaskName()));
                result.add(item);
            }

            ctx.json(Map.of("runId", runId, "tasks", result));
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error listing tasks: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to list tasks: " + e.getMessage()));
        }
    }

    /**
     * GET /scheduler/run/{runId}/log?taskName=xxx&tail=200
     * 查看某个任务最近的执行日志（不传 taskName 时返回该实例全部日志文件清单）。
     */
    private void handleViewLog(Context ctx) {
        String runId = ctx.pathParam("runId");
        try {
            SchedulerRun run = meta.getRun(runId);
            if (run == null) {
                ctx.status(404).json(Map.of("error", "Run not found: " + runId));
                return;
            }
            Path logDir = Paths.get(run.getWorkDir(), "logs");
            String taskName = ctx.queryParam("taskName");
            int tail = parseIntOrDefault(ctx.queryParam("tail"), 200);

            if (taskName == null || taskName.isBlank()) {
                ctx.json(Map.of("runId", runId, "logDir", logDir.toString(),
                        "files", listLogFiles(logDir)));
                return;
            }

            Path latest = latestLogFile(logDir, taskName);
            if (latest == null) {
                ctx.status(404).json(Map.of("error", "No log file for task: " + taskName,
                        "logDir", logDir.toString()));
                return;
            }
            List<String> lines = Files.readAllLines(latest, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - tail);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("runId", runId);
            body.put("taskName", taskName);
            body.put("file", latest.toString());
            body.put("totalLines", lines.size());
            body.put("lines", lines.subList(from, lines.size()));
            ctx.json(body);
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error viewing log: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to read log: " + e.getMessage()));
        }
    }

    /**
     * GET /scheduler/run/{runId}/variables?taskName=xxx
     * 查看实例（可选叠加任务级）的变量，口令类键已脱敏。
     */
    private void handleRunVariables(Context ctx) {
        String runId = ctx.pathParam("runId");
        try {
            SchedulerRun run = meta.getRun(runId);
            if (run == null) {
                ctx.status(404).json(Map.of("error", "Run not found: " + runId));
                return;
            }
            String taskName = ctx.queryParam("taskName");
            SchedulerTask task = (taskName == null || taskName.isBlank()) ? null : findTask(runId, taskName);

            Path runHome = Paths.get(run.getWorkDir());
            Map<String, String> templateVars = org.slackerdb.plugins.scheduler.runner.VariableSet
                    .loadProperties(runHome.resolve("conf").resolve("variables.properties"));
            Map<String, String> runParams = parseFlatJson(run.getConfigJson(), "run.configJson");
            Map<String, String> taskVars = task == null ? Map.of()
                    : parseFlatJson(task.getVariablesJson(), "task.variables");

            org.slackerdb.plugins.scheduler.runner.VariableSet merged =
                    org.slackerdb.plugins.scheduler.runner.VariableSet
                            .resolve(globalVariables, templateVars, runParams, taskVars);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("runId", runId);
            body.put("taskName", taskName);
            body.put("variables", merged.masked());
            body.put("frameworkVariables", org.slackerdb.plugins.scheduler.runner.VariableSet.FRAMEWORK_KEYS);
            ctx.json(body);
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error reading variables: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to read variables: " + e.getMessage()));
        }
    }

    /**
     * GET /scheduler/describeCrontabExpr?expr=0+1/2+*+*+*+%3F+*
     * 校验 cron 表达式并给出未来若干次触发时间（Quartz 6/7 段格式）。
     */
    private void handleDescribeCrontabExpr(Context ctx) {
        String expr = ctx.queryParam("expr");
        if (expr == null || expr.isBlank()) {
            ctx.status(400).json(Map.of("error", "expr is required"));
            return;
        }
        try {
            org.quartz.CronExpression cron = new org.quartz.CronExpression(expr.trim());
            List<String> next = new ArrayList<>();
            java.util.Date cursor = new java.util.Date();
            java.time.format.DateTimeFormatter formatter =
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            for (int i = 0; i < 5; i++) {
                cursor = cron.getNextValidTimeAfter(cursor);
                if (cursor == null) {
                    break;
                }
                next.add(formatter.format(java.time.LocalDateTime.ofInstant(
                        cursor.toInstant(), java.time.ZoneId.systemDefault())));
            }
            ctx.json(Map.of("expr", expr.trim(), "valid", true, "nextFireTimes", next));
        } catch (java.text.ParseException e) {
            ctx.json(Map.of("expr", expr.trim(), "valid", false, "error", String.valueOf(e.getMessage())));
        }
    }

    private static int parseIntOrDefault(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<String> listLogFiles(Path logDir) throws IOException {
        if (!Files.isDirectory(logDir)) {
            return List.of();
        }
        try (var stream = Files.list(logDir)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
        }
    }

    /** 该任务最近的日志文件：优先 `logs/<taskName>.log`，其次 `logs/<taskName>_<ts>.log` 里最新的。 */
    private static Path latestLogFile(Path logDir, String taskName) throws IOException {
        if (!Files.isDirectory(logDir)) {
            return null;
        }
        Path exact = logDir.resolve(taskName + ".log");
        if (Files.isRegularFile(exact)) {
            return exact;
        }
        try (var stream = Files.list(logDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(taskName + "_"))
                    .max(java.util.Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        }
    }


    /**
     * GET /scheduler/run/{runId}/task/history
     * Get task execution history for a run.
     */
    private void handleTaskHistory(Context ctx) {
        String runId = ctx.pathParam("runId");
        try {
            List<TaskHistory> history = meta.getTaskHistoryByRunId(runId);
            ctx.json(Map.of("runId", runId, "history", history));
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error getting task history: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to get task history: " + e.getMessage()));
        }
    }

    // ========== Helper Methods ==========

    /**
     * 组装随 JobData 下发的附加字段：项目类型、变量快照、Hop 运行环境。
     *
     * <p>变量按框架契约分层覆盖：全局 {@code <scheduler.home>/variables.properties}
     * → 模板 {@code <runHome>/conf/variables.properties} → 实例 {@code configJson}（run params）
     * → 任务级 {@code variables}。框架变量（PROJECT_HOME/RUN_ID/...）在执行期由 QuartzJob 注入
     * （优先级最高，业务不可覆盖）。</p>
     */
    private java.util.Map<String, String> jobDataExtras(SchedulerRun run, SchedulerTask task) {
        java.util.Map<String, String> extras = new java.util.LinkedHashMap<>();
        extras.put("projectType", run.getProjectType() == null ? "" : run.getProjectType());
        extras.put("variablesJson", resolveVariablesJson(run, task));
        extras.put("hopRunScript", hopConfig.runScript == null ? "" : hopConfig.runScript);
        extras.put("hopJavaHome", hopConfig.javaHome == null ? "" : hopConfig.javaHome);
        extras.put("hopProjectName", hopConfig.projectName == null ? "" : hopConfig.projectName);
        extras.put("hopEnvironmentName", hopConfig.environmentName == null ? "" : hopConfig.environmentName);
        extras.put("hopRunConfig", hopConfig.runConfig == null ? "" : hopConfig.runConfig);
        extras.put("taskRetryTimes", String.valueOf(task.getTaskRetryTimes()));
        extras.put("taskRetryInterval", String.valueOf(task.getTaskRetryInterval()));
        return extras;
    }

    /** 业务变量层合并结果（JSON 对象字符串）。 */
    private String resolveVariablesJson(SchedulerRun run, SchedulerTask task) {
        java.nio.file.Path runHome = Paths.get(run.getWorkDir());
        java.util.Map<String, String> templateVars = org.slackerdb.plugins.scheduler.runner.VariableSet
                .loadProperties(runHome.resolve("conf").resolve("variables.properties"));

        java.util.Map<String, String> runParams = parseFlatJson(run.getConfigJson(),
                "run[" + run.getRunId() + "].configJson");
        java.util.Map<String, String> taskVars = task == null ? java.util.Map.of()
                : parseFlatJson(task.getVariablesJson(), "task[" + task.getTaskName() + "].variables");

        org.slackerdb.plugins.scheduler.runner.VariableSet merged =
                org.slackerdb.plugins.scheduler.runner.VariableSet
                        .resolve(globalVariables, templateVars, runParams, taskVars);
        return JSON.toJSONString(merged.asMap());
    }

    /** 把 JSON 对象字符串摊平成变量层（跳过嵌套结构）；非法 JSON 只记日志不抛。 */
    private java.util.Map<String, String> parseFlatJson(String json, String what) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            JSONObject object = JSON.parseObject(json);
            if (object == null) {
                return result;
            }
            for (String key : object.keySet()) {
                Object value = object.get(key);
                if (value == null || value instanceof JSONObject
                        || value instanceof com.alibaba.fastjson2.JSONArray) {
                    continue;
                }
                result.put(key, String.valueOf(value));
            }
        } catch (Exception e) {
            logger.warn("[SCHEDULER] {} 不是有效 JSON 对象，已忽略: {}", what, e.getMessage());
        }
        return result;
    }

    /**
     * Create tasks from API-provided task definitions.
     */
    private int createTasksFromApi(String runId, List<JSONObject> taskConfigs) throws Exception {
        int count = 0;
        for (JSONObject taskConfig : taskConfigs) {
            SchedulerTask task = new SchedulerTask();
            task.setRunId(runId);
            task.setTaskName(taskConfig.getString("taskName"));
            task.setTaskScript(taskConfig.getString("taskScript"));
            task.setTaskScriptType(taskConfig.getString("taskScriptType", "SQL"));
            task.setTaskRunPolicy(taskConfig.getString("taskRunPolicy", "RUN_ONCE"));
            task.setTaskFailPolicy(taskConfig.getString("taskFailPolicy", "CONTINUE"));
            task.setTaskParallelPolicy(taskConfig.getString("taskParallelPolicy", "PARALLEL"));
            task.setTaskCrontabExpr(taskConfig.getString("taskCrontabExpr"));
            task.setTaskInterval(taskConfig.getIntValue("taskInterval", 0));
            task.setTaskTimeout(taskConfig.getIntValue("taskTimeout", 0));
            task.setTaskEnabled(taskConfig.getBooleanValue("taskEnabled", true));
            task.setTaskGroup(taskConfig.getString("taskGroup"));
            task.setTaskStartupOrder(taskConfig.getIntValue("taskStartupOrder", 0));
            task.setTaskDescription(taskConfig.getString("taskDescription"));
            task.setConfigJson(taskConfig.getString("configJson"));
            task.setCreateTime(LocalDateTime.now());
            task.setUpdateTime(LocalDateTime.now());
            meta.insertTask(task);
            count++;
        }
        return count;
    }

    /**
     * Create the run directory structure.
     */
    private void createRunDirectory(String runDir) throws IOException {
        Files.createDirectories(Paths.get(runDir));
        Files.createDirectories(Paths.get(runDir, "logs"));
        Files.createDirectories(Paths.get(runDir, "data"));
        Files.createDirectories(Paths.get(runDir, "conf"));
        Files.createDirectories(Paths.get(runDir, "flow"));
        Files.createDirectories(Paths.get(runDir, "audit"));
    }

    /**
     * 把模板的 HOP 工程拷进实例的 flow 目录。
     *
     * <p>布局与 HOP 的"配置根 + 项目目录"模型一致：</p>
     * <pre>
     * &lt;runHome&gt;/flow/
     * ├── hop-config.json          ← 配置根（框架执行时写入本项目与环境条目）
     * ├── project-config.json
     * ├── default/                 ← 父项目
     * └── &lt;projectType&gt;/           ← 项目本身（*.hwf/*.hpl、metadata/、sql/、project-config.json）
     * </pre>
     *
     * <p>同时把模板级变量 {@code conf/variables.properties} 快照到 {@code <runHome>/conf/}，
     * 使实例目录自包含（变量解析只读实例目录，不再回读模板）。</p>
     */
    private void copyHopProject(String projectType, Path targetFlow) throws IOException {
        Path root = Paths.get(projectHome);
        Files.createDirectories(targetFlow);

        for (String name : new String[]{"hop-config.json", "project-config.json"}) {
            Path source = root.resolve(name);
            if (Files.isRegularFile(source)) {
                Files.copy(source, targetFlow.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        Path defaultProjectDir = root.resolve("default");
        if (Files.isDirectory(defaultProjectDir)) {
            copyDirectory(defaultProjectDir, targetFlow.resolve("default"));
        }

        Path typeDir = root.resolve(projectType);
        if (!Files.isDirectory(typeDir)) {
            throw new IOException("模板目录不存在: " + typeDir
                    + "（scheduler.projectHome 下应有 <projectType>/ 目录）");
        }
        copyDirectory(typeDir, targetFlow.resolve(projectType));

        // 模板级变量快照（项目目录优先，其次模板根）
        Path targetConf = targetFlow.getParent().resolve("conf");
        Files.createDirectories(targetConf);
        for (Path candidate : new Path[]{
                typeDir.resolve("conf").resolve("variables.properties"),
                root.resolve("conf").resolve("variables.properties")}) {
            if (Files.isRegularFile(candidate)) {
                Files.copy(candidate, targetConf.resolve("variables.properties"),
                        StandardCopyOption.REPLACE_EXISTING);
                break;
            }
        }
        logger.info("[SCHEDULER] 已拷贝 HOP 工程到 {}", targetFlow);

        // 模板自检：default 项目不能把 default 声明为自己的父项目，否则 Hop 会报
        // "There is a loop in the parent projects hierarchy: project default references itself"
        Path defaultProjectConfig = targetFlow.resolve("default").resolve("project-config.json");
        if (Files.isRegularFile(defaultProjectConfig)) {
            try {
                JSONObject config = JSON.parseObject(Files.readString(defaultProjectConfig, StandardCharsets.UTF_8));
                String parent = config == null ? null : config.getString("parentProjectName");
                if (parent != null && !parent.isBlank() && "default".equalsIgnoreCase(parent.trim())) {
                    logger.warn("[SCHEDULER] 模板自检失败: {} 的 parentProjectName=[default]，"
                            + "会导致 Hop 父项目自引用死循环，请改为空字符串", defaultProjectConfig);
                }
            } catch (Exception e) {
                logger.warn("[SCHEDULER] 模板自检无法解析 {}: {}", defaultProjectConfig, e.getMessage());
            }
        }
    }

    /** 递归拷贝目录（文件覆盖写）。 */
    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path targetFile = target.resolve(source.relativize(file).toString());
                Files.createDirectories(targetFile.getParent());
                Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 重启 reconcile。
     *
     * <p>Quartz 使用 RAMJobStore，重启后调度状态全部丢失，但数据库里 run 仍是 {@code STARTED}。</p>
     *
     * <p><b>策略</b>：把处于 {@code STARTING}/{@code STARTED} 的 run 一律置为 {@code STOPPED}，
     * 并把其启用任务的定义重新登记为"暂停"（不自动重跑 RUN_ONCE —— 一次性任务重跑通常是有害的）。
     * 人工 {@code run/start} 后由编排器按组与顺序重新推进。</p>
     */
    public void reconcileAfterRestart() {
        try {
            List<SchedulerRun> runs = meta.listRunsByStatus(List.of("STARTING", "STARTED"));
            if (runs.isEmpty()) {
                logger.info("[SCHEDULER] 重启 reconcile: 无需处理的 run");
                return;
            }
            for (SchedulerRun run : runs) {
                List<SchedulerTask> tasks = meta.getTasksByRunId(run.getRunId());
                int restored = 0;
                for (SchedulerTask task : tasks) {
                    if (!task.isTaskEnabled()) {
                        continue;
                    }
                    try {
                        scheduleTask(run, task);
                        restored++;
                    } catch (Exception e) {
                        logger.warn("[SCHEDULER] 重启 reconcile: run[{}] 任务[{}] 登记失败: {}",
                                run.getRunId(), task.getTaskName(), e.getMessage());
                    }
                }
                meta.updateRunStatus(run.getRunId(), "STOPPED");
                logger.warn("[SCHEDULER] 重启 reconcile: run[{}] 原状态[{}] → STOPPED，"
                                + "任务定义已重新登记 {} 个（保持暂停，需人工 start）",
                        run.getRunId(), run.getStatus(), restored);
            }
        } catch (Exception e) {
            logger.error("[SCHEDULER] 重启 reconcile 失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 从模板清单载入任务到实例。
     *
     * <p>清单文件名与格式见 {@link org.slackerdb.plugins.scheduler.meta.TaskTemplateLoader}；
     * <b>没有清单是正常状态</b>（该实例不带预置任务）。清单里校验不通过的任务会被跳过并记日志，
     * 不影响其它任务。</p>
     */
    private int loadDefaultTasks(String runId, String projectType) {
        org.slackerdb.plugins.scheduler.meta.TaskTemplateLoader.Loaded loaded;
        try {
            loaded = org.slackerdb.plugins.scheduler.meta.TaskTemplateLoader
                    .load(Paths.get(projectHome), projectType, runId);
        } catch (Exception e) {
            logger.error("[SCHEDULER] 模板任务清单解析失败: {}", e.getMessage(), e);
            return 0;
        }
        for (String warning : loaded.warnings()) {
            logger.warn("[SCHEDULER] 模板任务清单: {}", warning);
        }
        if (loaded.isEmpty()) {
            logger.info("[SCHEDULER] 实例[{}]未载入任务（{}）", runId, loaded.fileText());
            return 0;
        }

        int count = 0;
        try {
            for (SchedulerTask task : loaded.tasks()) {
                task.setCreateTime(LocalDateTime.now());
                task.setUpdateTime(LocalDateTime.now());
                meta.insertTask(task);
                count++;
            }
            logger.info("[SCHEDULER] 实例[{}]从 {} 载入 {} 个任务", runId, loaded.file(), count);
        } catch (Exception e) {
            logger.error("[SCHEDULER] 任务写入失败: {}", e.getMessage(), e);
        }
        return count;
    }

    /**
     * Schedule a single task for a run.
     */
    private void scheduleTask(SchedulerRun run, SchedulerTask task) throws Exception {
        if (!task.isTaskEnabled()) {
            return;
        }

        String runId = run.getRunId();
        String taskName = task.getTaskName();
        String scriptType = task.getTaskScriptType() != null ? task.getTaskScriptType() : "HOP";
        String script = task.getTaskScript() != null ? task.getTaskScript() : "";
        String logFile = "logs/" + taskName + ".log";
        String taskRunPolicy = task.getTaskRunPolicy() != null ? task.getTaskRunPolicy() : "RUN_ONCE";
        String taskFailPolicy = task.getTaskFailPolicy() != null ? task.getTaskFailPolicy() : "CONTINUE";
        int taskInterval = task.getTaskInterval();
        String taskParallelPolicy = task.getTaskParallelPolicy() != null ? task.getTaskParallelPolicy() : "PARALLEL";
        String taskCrontabExpr = task.getTaskCrontabExpr() != null ? task.getTaskCrontabExpr() : "";
        int taskTimeout = task.getTaskTimeout();
        String taskGroup = task.getTaskGroup() != null ? task.getTaskGroup() : "";
        int taskStartupOrder = task.getTaskStartupOrder();
        String configJson = task.getConfigJson() != null ? task.getConfigJson() : "";

        // 只登记定义（保持暂停态）；启动由 RunOrchestrator 按组/顺序推进
        quartzScheduler.scheduleJob(
                runId, taskName, scriptType, script, logFile,
                taskRunPolicy, taskFailPolicy, taskInterval,
                taskParallelPolicy, taskCrontabExpr, taskTimeout,
                taskGroup, taskStartupOrder, configJson,
                run.getWorkDir(),
                jobDataExtras(run, task)
        );
    }

    /**
     * Recursively delete a directory.
     */
    private void deleteDirectory(String path) {
        File dir = new File(path);
        if (!dir.exists()) return;

        try {
            Files.walkFileTree(dir.toPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            logger.info("[SCHEDULER] Deleted directory: {}", path);
        } catch (IOException e) {
            logger.error("[SCHEDULER] Error deleting directory {}: {}", path, e.getMessage());
        }
    }
}
