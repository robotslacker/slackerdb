package org.slackerdb.plugins.scheduler.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slackerdb.plugins.scheduler.entity.SchedulerRun;
import org.slackerdb.plugins.scheduler.entity.SchedulerTask;
import org.slackerdb.plugins.scheduler.entity.TaskHistory;
import org.slackerdb.plugins.scheduler.meta.SchedulerMeta;
import org.slackerdb.plugins.scheduler.meta.TaskTemplateLoader;
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

        // ========== 平台契约路由 ==========
        // 能力声明：客户端据此显隐 ETL 入口与按钮，而不是探测 /scheduler/status
        app.unsafe.routes.get("/scheduler/capabilities", this::handleCapabilities);
        // 调度丢失（定义还在、Quartz 里已没有）：重启后"一键恢复"的权威来源
        app.unsafe.routes.get("/scheduler/state/lost", this::handleLostSchedules);

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

    /**
     * GET /scheduler/capabilities
     *
     * <p>插件的能力自描述：客户端（控制台/CLI）按它决定显隐与降级，**不再探测端点或猜字段**。
     * 刻意做得便宜（不扫 Quartz 全量触发器）。</p>
     */
    private void handleCapabilities(Context ctx) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("contractVersion", 1);

            Map<String, Object> features = new LinkedHashMap<>();
            // 已具备：实例的创建/启动/停止/中止/删除
            features.put("runCrud", true);
            // 尚未提供的写能力一律如实声明 false —— 客户端据此隐藏按钮，而不是"点了才知道没有"
            features.put("taskCrud", false);       // 任务增/改/删、改 cron 与策略
            features.put("templateRead", true);    // 只能读项目模板目录
            features.put("templates", false);      // 模板写（上传/删除/zip 导入）
            features.put("globalHistory", false);  // 跨实例的执行流水
            features.put("sqlTask", false);        // 恒为 false：SQL 不是任务类型（写进 HOP 工作流的 SQL action）
            features.put("zipImport", false);
            body.put("features", features);

            Map<String, Object> quartz = new LinkedHashMap<>();
            quartz.put("started", quartzScheduler.isStarted());
            quartz.put("threads", quartzScheduler.getThreadCount());
            int jobCount = 0;
            for (org.slackerdb.plugins.scheduler.entity.SchedulerRun run : meta.listRuns()) {
                jobCount += quartzScheduler.getJobsForRun(run.getRunId()).size();
            }
            quartz.put("jobCount", jobCount);
            body.put("quartz", quartz);

            body.put("home", workHome);
            body.put("projectHome", projectHome);
            body.put("hop", hopReadiness());

            Map<String, Object> limits = new LinkedHashMap<>();
            limits.put("logTailMax", 10000);
            body.put("limits", limits);

            ctx.json(body);
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error building capabilities: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to build capabilities: " + e.getMessage()));
        }
    }

    /**
     * HOP 运行环境就绪判定。
     *
     * <p>把这个判断放在插件里（而不是控制台读服务器文件、或做一个 GUI 体检向导）的理由：
     * 规则只有插件最清楚，判断一次、所有客户端都能复用。</p>
     */
    private Map<String, Object> hopReadiness() {
        List<String> reasons = new ArrayList<>();
        boolean runScriptConfigured = hopConfig != null
                && hopConfig.runScript != null && !hopConfig.runScript.isBlank();
        if (!runScriptConfigured) {
            reasons.add("hop.runScript 未配置（HOP 类型任务无法执行）");
        } else {
            Path script = Paths.get(hopConfig.runScript);
            if (!Files.isRegularFile(script)) {
                reasons.add("hop.runScript 指向的文件不存在: " + hopConfig.runScript);
            } else if (!Files.isExecutable(script)) {
                reasons.add("hop.runScript 不可执行: " + hopConfig.runScript);
            }
        }
        if (hopConfig != null && hopConfig.javaHome != null && !hopConfig.javaHome.isBlank()
                && !Files.isDirectory(Paths.get(hopConfig.javaHome))) {
            reasons.add("hop.javaHome 不存在: " + hopConfig.javaHome);
        }
        Path defaultProject = Paths.get(projectHome, "default");
        if (!Files.isDirectory(defaultProject)) {
            reasons.add("项目模板缺少 default/ 父项目: " + defaultProject);
        }

        Map<String, Object> hop = new LinkedHashMap<>();
        hop.put("ready", reasons.isEmpty());
        hop.put("reasons", reasons);
        hop.put("runScript", hopConfig == null ? null : hopConfig.runScript);
        hop.put("projectName", hopConfig == null ? null : hopConfig.projectName);
        hop.put("environmentName", hopConfig == null ? null : hopConfig.environmentName);
        return hop;
    }

    /**
     * GET /scheduler/state/lost
     *
     * <p>"定义还在、调度已丢"的任务列表 —— 重启后一键恢复的**权威来源**。
     * 让控制台 diff {@code run/list} 与 {@code task/list} 自己推断是不可靠的：
     * 只有插件同时掌握 DuckDB 里的定义与 Quartz 里的登记情况。</p>
     */
    private void handleLostSchedules(Context ctx) {
        try {
            List<Map<String, Object>> lost = new ArrayList<>();
            for (org.slackerdb.plugins.scheduler.entity.SchedulerRun run : meta.listRuns()) {
                Map<String, Map<String, Object>> scheduled = new HashMap<>();
                for (Map<String, Object> job : quartzScheduler.getJobsForRun(run.getRunId())) {
                    Object taskName = job.get("taskName");
                    if (taskName != null) {
                        scheduled.put(String.valueOf(taskName), job);
                    }
                }
                for (org.slackerdb.plugins.scheduler.entity.SchedulerTask task
                        : meta.getTasksByRunId(run.getRunId())) {
                    if (!task.isTaskEnabled() || scheduled.containsKey(task.getTaskName())) {
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("runId", run.getRunId());
                    item.put("runStatus", run.getStatus());
                    item.put("taskName", task.getTaskName());
                    item.put("taskRunPolicy", task.getTaskRunPolicy());
                    item.put("taskGroup", task.getTaskGroup());
                    item.put("taskStartupOrder", task.getTaskStartupOrder());
                    lost.add(item);
                }
            }
            ctx.json(Map.of("lostSchedules", lost));
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error collecting lost schedules: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to collect lost schedules: " + e.getMessage()));
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
     *       "taskScriptType": "HOP|SHELL|COMMAND",
     *       "taskRunPolicy": "RUN_ONCE|CRONTAB|INTERVAL",
     *       "taskFailPolicy": "STOP|CONTINUE|RETRY",
     *       "taskRetryTimes": 0,
     *       "taskRetryInterval": 60,
     *       "taskParallelPolicy": "PARALLEL|SERIAL_DISCARD|SERIAL_DELAY|SERIAL_CATCHUP",
     *       "taskCrontabExpr": "...",
     *       "taskInterval": 0,
     *       "taskTimeout": 0,
     *       "taskEnabled": true,
     *       "taskMandatory": false,
     *       "taskGroup": "...",
     *       "taskStartupOrder": 0,
     *       "taskDescription": "...",
     *       "variables": { "KEY": "value" }
     *     }
     *   ]
     * }
     *
     * <p>字段集合与模板清单（{@link TaskTemplateLoader}）一致，包括 {@code taskRetryTimes} /
     * {@code taskRetryInterval} / {@code taskMandatory} / 任务级 {@code variables}（只支持扁平标量值）。</p>
     *
     * <p>顶层只接受 {@code projectType} / {@code configJson} / {@code runId} / {@code tasks}，且 {@code tasks}
     * 必须是数组；未知字段（例如把 {@code tasks} 拼成 {@code task}）直接 400 —— 否则会静默回落到模板任务。
     * 内联任务会**先校验后落地**：{@code taskScriptType} 只接受 {@code HOP}/{@code SHELL}/{@code COMMAND}
     * （默认 {@code HOP}），策略与必填项同样校验；**未知字段也直接拒绝**（字段名拼错不再被静默忽略）。
     * 任一条不合格则整单 400，响应形如 {@code {"error":"invalid tasks","problems":[...]}}，
     * 且不会创建实例目录与运行记录。</p>
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

            // 请求体顶层字段同样不许静默忽略：把 tasks 拼成 task 时会**静默回落到模板任务**，
            // 症状极像“我明明传了任务却没生效”。这里先校验请求形状，再谈别的。
            List<String> bodyProblems = validateRunBody(body);
            if (!bodyProblems.isEmpty()) {
                logger.warn("[SCHEDULER] 拒绝创建实例[{}]: 请求字段不合格: {}", runId, bodyProblems);
                ctx.status(400).json(Map.of("error", "invalid request", "problems", bodyProblems));
                return;
            }

            // Check for duplicate runId
            if (meta.getRun(runId) != null) {
                ctx.status(409).json(Map.of("error", "Run already exists: " + runId));
                return;
            }

            // 内联任务定义：**先校验、后落地**。API 不是模板清单，没有“跳过并告警”的余地 ——
            // 不合格就整单 400，不能留下“建了一半”的实例目录/运行记录。
            List<JSONObject> inlineTasks = body.getJSONArray("tasks") != null
                    ? body.getJSONArray("tasks").toList(JSONObject.class)
                    : Collections.emptyList();
            List<String> problems = validateInlineTasks(inlineTasks);
            if (!problems.isEmpty()) {
                logger.warn("[SCHEDULER] 拒绝创建实例[{}]: 任务定义不合格: {}", runId, problems);
                ctx.status(400).json(Map.of("error", "invalid tasks", "problems", problems));
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

            // 内联任务（已在上面校验过）；没有内联任务时从项目模板清单装载
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

                // 三态显式化：定义态（DuckDB）/ 调度态（Quartz 触发器）/ 运行态（内存快照）。
                // 这三者来源不同、丢失条件也不同（重启只丢后两者），客户端不该靠字段名猜。
                Map<String, Object> states = new LinkedHashMap<>();
                states.put("definition", task.isTaskEnabled() ? "ENABLED" : "DISABLED");
                Object triggerState = job == null ? null : job.get("state");
                states.put("trigger", triggerState == null ? "NONE" : String.valueOf(triggerState));
                states.put("runtime", isTruthy(item.get("running")) ? "RUNNING"
                        : (isTruthy(item.get("queued")) ? "QUEUED" : "IDLE"));
                item.put("states", states);

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
     * 查看实例（可选叠加任务级）的变量。
     *
     * <p><b>破坏性变更（已批准）</b>：过去这里返回脱敏后的值（{@code ******}），
     * 现在返回**原值**。理由：该接口目前没有实际外部消费方，而排障时看不到真实变量反而有害；
     * 平台是内部工具、无鉴权，脱敏不产生真实安全收益。需要遮蔽时由界面自行决定显示。</p>
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
            body.put("variables", merged.asMap());
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

    /** 宽松布尔判定：任务状态快照里的值可能是 Boolean，也可能是字符串。 */
    private static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(value).trim());
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
                        || value instanceof JSONArray) {
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
     * 写入 API 传入的任务定义（调用前必须已经过 {@link #validateInlineTasks(List)}）。
     *
     * <p>类型/策略一律按与模板装载相同的方式归一化（大写 + 默认值），默认类型与模板同源：
     * {@link TaskTemplateLoader#DEFAULT_SCRIPT_TYPE}。SQL 不在支持之列，写进这里会先被校验拒绝。</p>
     */
    private int createTasksFromApi(String runId, List<JSONObject> taskConfigs) throws Exception {
        int count = 0;
        for (JSONObject taskConfig : taskConfigs) {
            SchedulerTask task = new SchedulerTask();
            task.setRunId(runId);
            task.setTaskName(taskConfig.getString("taskName"));
            task.setTaskScript(taskConfig.getString("taskScript"));
            task.setTaskScriptType(upper(taskConfig.getString("taskScriptType"),
                    TaskTemplateLoader.DEFAULT_SCRIPT_TYPE));
            task.setTaskRunPolicy(upper(taskConfig.getString("taskRunPolicy"), "RUN_ONCE"));
            task.setTaskFailPolicy(upper(taskConfig.getString("taskFailPolicy"), "CONTINUE"));
            task.setTaskRetryTimes(taskConfig.getIntValue("taskRetryTimes", 0));
            task.setTaskRetryInterval(taskConfig.getIntValue("taskRetryInterval", 60));
            task.setTaskParallelPolicy(upper(taskConfig.getString("taskParallelPolicy"), "PARALLEL"));
            task.setTaskCrontabExpr(taskConfig.getString("taskCrontabExpr"));
            task.setTaskInterval(taskConfig.getIntValue("taskInterval", 0));
            task.setTaskTimeout(taskConfig.getIntValue("taskTimeout", 0));
            task.setTaskEnabled(taskConfig.getBooleanValue("taskEnabled", true));
            task.setTaskMandatory(taskConfig.getBooleanValue("taskMandatory", false));
            task.setTaskGroup(taskConfig.getString("taskGroup"));
            task.setTaskStartupOrder(taskConfig.getIntValue("taskStartupOrder", 0));
            task.setTaskDescription(taskConfig.getString("taskDescription"));
            task.setConfigJson(taskConfig.getString("configJson"));
            // 任务级变量：与模板清单共用同一份拍平逻辑（只收标量值）
            Map<String, String> variables = TaskTemplateLoader.flattenVariables(taskConfig.get("variables"));
            if (!variables.isEmpty()) {
                task.setVariablesJson(JSON.toJSONString(variables));
            }
            task.setCreateTime(LocalDateTime.now());
            task.setUpdateTime(LocalDateTime.now());
            meta.insertTask(task);
            count++;
        }
        return count;
    }

    /**
     * {@code POST /scheduler/run/create} 请求体允许的顶层字段。
     *
     * <p>为什么顶层也要白名单：把 {@code tasks} 拼成 {@code task} 时，旧实现会当成“没有内联任务”
     * 而**静默回落到模板任务**——调用方拿到 201 和一堆自己没传的任务，这是最难排查的一类现象。</p>
     */
    private static final Set<String> RUN_BODY_FIELDS = Set.of("projectType", "configJson", "runId", "tasks");

    /** 校验创建实例请求的形状；返回问题清单（空 = 合格）。 */
    private static List<String> validateRunBody(JSONObject body) {
        List<String> problems = new ArrayList<>();
        for (String key : body.keySet()) {
            if (!RUN_BODY_FIELDS.contains(key)) {
                problems.add("请求体含未知字段[" + key + "]（可用: " + RUN_BODY_FIELDS + "）");
            }
        }
        Object tasks = body.get("tasks");
        if (tasks != null && !(tasks instanceof Collection)) {
            problems.add("tasks 必须是数组（当前: " + tasks.getClass().getSimpleName() + "）");
        }
        return problems;
    }

    /**
     * 校验 API 传入的任务定义，返回问题清单（空 = 全部合格）。
     *
     * <p>规则与模板装载（{@link TaskTemplateLoader}）一致，白名单（类型/策略/字段名）也全部取自它，
     * 因此两条入口不会漂移；区别只在失败方式：模板是"跳过该任务 + 告警"，API 是"整单 400 + 逐条问题"，
     * 因为调用方拿不到模板那种告警通道，静默少建任务（或字段名拼错被忽略）比报错更难排查。</p>
     */
    private static List<String> validateInlineTasks(List<JSONObject> taskConfigs) {
        List<String> problems = new ArrayList<>();
        for (int index = 0; index < taskConfigs.size(); index++) {
            JSONObject config = taskConfigs.get(index);
            String taskName = config == null ? null : config.getString("taskName");
            String label = "tasks[" + index + "]" + (isBlank(taskName) ? "" : "(" + taskName + ")");

            // 未知字段一律拒绝：字段名拼错（taskTimeouts / taskRetryLimits…）以前会被静默忽略
            if (config != null) {
                for (String key : config.keySet()) {
                    if (!TaskTemplateLoader.TASK_FIELDS.contains(key)) {
                        problems.add(label + " 含未知字段[" + key + "]（可用: "
                                + TaskTemplateLoader.TASK_FIELDS + "）");
                    }
                }
            }

            if (isBlank(taskName)) {
                problems.add(label + " 缺少 taskName");
            }
            if (config == null || isBlank(config.getString("taskScript"))) {
                problems.add(label + " 缺少 taskScript");
                continue;
            }
            String scriptType = upper(config.getString("taskScriptType"),
                    TaskTemplateLoader.DEFAULT_SCRIPT_TYPE);
            if (!TaskTemplateLoader.SCRIPT_TYPES.contains(scriptType)) {
                problems.add(label + " 的 taskScriptType=[" + scriptType + "] 不受支持（可用: "
                        + TaskTemplateLoader.SCRIPT_TYPES + "）");
            }
            String runPolicy = upper(config.getString("taskRunPolicy"), "RUN_ONCE");
            if (!TaskTemplateLoader.RUN_POLICIES.contains(runPolicy)) {
                problems.add(label + " 的 taskRunPolicy=[" + runPolicy + "] 不受支持（可用: "
                        + TaskTemplateLoader.RUN_POLICIES + "）");
            } else if ("CRONTAB".equals(runPolicy) && isBlank(config.getString("taskCrontabExpr"))) {
                problems.add(label + " 的 taskRunPolicy=CRONTAB 但缺少 taskCrontabExpr");
            } else if ("INTERVAL".equals(runPolicy) && config.getIntValue("taskInterval", 0) <= 0) {
                problems.add(label + " 的 taskRunPolicy=INTERVAL 但 taskInterval<=0");
            }
            String failPolicy = upper(config.getString("taskFailPolicy"), "CONTINUE");
            if (!TaskTemplateLoader.FAIL_POLICIES.contains(failPolicy)) {
                problems.add(label + " 的 taskFailPolicy=[" + failPolicy + "] 不受支持（可用: "
                        + TaskTemplateLoader.FAIL_POLICIES + "）");
            }
            int retryTimes = config.getIntValue("taskRetryTimes", 0);
            int retryInterval = config.getIntValue("taskRetryInterval", 60);
            if (retryTimes < 0) {
                problems.add(label + " 的 taskRetryTimes 不能为负数，实际: " + retryTimes);
            }
            if (retryInterval < 0) {
                problems.add(label + " 的 taskRetryInterval 不能为负数，实际: " + retryInterval);
            }
            // RETRY 却不可重试：QuartzJob.handleRetry 会在运行期静默降级为 CONTINUE，这里提前拒绝
            if ("RETRY".equals(failPolicy) && retryTimes <= 0) {
                problems.add(label + " 的 taskFailPolicy=RETRY 但 taskRetryTimes<=0（运行期会退化成 CONTINUE）");
            }
            Object mandatory = config.get("taskMandatory");
            if (mandatory != null && !isBooleanValue(mandatory)) {
                problems.add(label + " 的 taskMandatory 必须是布尔值，实际: " + mandatory);
            }
            Object variablesValue = config.get("variables");
            if (variablesValue != null) {
                if (!(variablesValue instanceof Map<?, ?> variables)) {
                    problems.add(label + " 的 variables 必须是 JSON 对象（KEY→标量值）");
                } else {
                    for (Map.Entry<?, ?> entry : variables.entrySet()) {
                        Object value = entry.getValue();
                        if (value instanceof Map || value instanceof Collection) {
                            problems.add(label + " 的 variables[" + entry.getKey()
                                    + "] 是嵌套对象/数组（只支持标量值）");
                        }
                    }
                }
            }
            String parallelPolicy = upper(config.getString("taskParallelPolicy"), "PARALLEL");
            if (!TaskTemplateLoader.PARALLEL_POLICIES.contains(parallelPolicy)) {
                problems.add(label + " 的 taskParallelPolicy=[" + parallelPolicy + "] 不受支持（可用: "
                        + TaskTemplateLoader.PARALLEL_POLICIES + "）");
            }
        }
        return problems;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** JSON 布尔值（{@code true}/{@code false} 或文本 {@code "true"}/{@code "false"}）之外一律视为不合格。 */
    private static boolean isBooleanValue(Object value) {
        if (value instanceof Boolean) {
            return true;
        }
        String text = String.valueOf(value);
        return "true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text);
    }

    private static String upper(String value, String fallback) {
        if (isBlank(value)) {
            return fallback;
        }
        return value.trim().toUpperCase(Locale.ROOT);
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
