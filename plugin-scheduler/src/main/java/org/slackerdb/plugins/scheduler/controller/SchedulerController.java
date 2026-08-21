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

    /** Hop 工作目录（用于嵌入式 HopService，null 则使用外部命令） */
    private final String hopWorkDirectory;

    /** Hop 插件目录 */
    private final String hopPluginDirectory;

    public SchedulerController(Javalin app, SchedulerMeta meta,
                               DynamicQuartzScheduler quartzScheduler,
                               Logger logger, String workHome, String projectHome) {
        this(app, meta, quartzScheduler, logger, workHome, projectHome, null, null);
    }

    public SchedulerController(Javalin app, SchedulerMeta meta,
                               DynamicQuartzScheduler quartzScheduler,
                               Logger logger, String workHome, String projectHome,
                               String hopWorkDirectory, String hopPluginDirectory) {
        this.app = app;
        this.meta = meta;
        this.quartzScheduler = quartzScheduler;
        this.logger = logger;
        this.workHome = workHome;
        this.projectHome = projectHome;
        this.hopWorkDirectory = hopWorkDirectory;
        this.hopPluginDirectory = hopPluginDirectory;
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
        String configPath = projectHome + File.separator
                + projectType + File.separator + "conf"
                + File.separator + "defaultSchedulerTask_" + projectType + ".json";

        File configFile = new File(configPath);
        if (!configFile.exists()) {
            ctx.status(404).json(Map.of("error", "Project not found: " + projectType));
            return;
        }

        try {
            String content = new String(Files.readAllBytes(configFile.toPath()));
            JSONObject config = JSON.parseObject(content);
            ctx.json(Map.of(
                    "projectType", projectType,
                    "config", config
            ));
        } catch (IOException e) {
            logger.error("[SCHEDULER] Error reading project config: {}", e.getMessage());
            ctx.status(500).json(Map.of("error", "Failed to read project config"));
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

            // Copy flow files from project template (from projectHome)
            String projectFlowDir = projectHome + File.separator
                    + projectType + File.separator + "flow";
            copyFlowFiles(projectFlowDir, runDir + File.separator + "flow");

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
     * Start a Run (schedule all enabled tasks).
     */
    private void handleStartRun(Context ctx) {
        String runId = ctx.pathParam("runId");
        try {
            SchedulerRun run = meta.getRun(runId);
            if (run == null) {
                ctx.status(404).json(Map.of("error", "Run not found: " + runId));
                return;
            }

            List<SchedulerTask> tasks = meta.getTasksByRunId(runId);
            int scheduled = 0;
            for (SchedulerTask task : tasks) {
                if (task.isTaskEnabled()) {
                    scheduleTask(run, task);
                    scheduled++;
                }
            }

            meta.updateRunStatus(runId, "STARTED");
            logger.info("[SCHEDULER] Run started: runId={}, tasksScheduled={}", runId, scheduled);
            ctx.json(Map.of("message", "Run started", "runId", runId, "tasksScheduled", scheduled));
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error starting run: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to start run: " + e.getMessage()));
        }
    }

    /**
     * POST /scheduler/run/{runId}/stop
     * Stop a Run (unschedule all tasks).
     */
    private void handleStopRun(Context ctx) {
        String runId = ctx.pathParam("runId");
        try {
            SchedulerRun run = meta.getRun(runId);
            if (run == null) {
                ctx.status(404).json(Map.of("error", "Run not found: " + runId));
                return;
            }

            quartzScheduler.unscheduleAllJobs(runId);
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
     * Abort a Run (force stop).
     */
    private void handleAbortRun(Context ctx) {
        String runId = ctx.pathParam("runId");
        try {
            SchedulerRun run = meta.getRun(runId);
            if (run == null) {
                ctx.status(404).json(Map.of("error", "Run not found: " + runId));
                return;
            }

            quartzScheduler.unscheduleAllJobs(runId);
            meta.updateRunStatus(runId, "FAILED");
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
     * Start a specific task immediately (one-time execution).
     *
     * Body: { "taskName": "..." }
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

            // Get task definition from DB
            List<SchedulerTask> tasks = meta.getTasksByRunId(runId);
            SchedulerTask task = tasks.stream()
                    .filter(t -> t.getTaskName().equals(taskName))
                    .findFirst().orElse(null);

            if (task == null) {
                ctx.status(404).json(Map.of("error", "Task not found: " + taskName));
                return;
            }

            // Schedule as RUN_ONCE and start immediately
            quartzScheduler.scheduleJob(
                    runId,
                    taskName,
                    task.getTaskScriptType() != null ? task.getTaskScriptType() : "SQL",
                    task.getTaskScript() != null ? task.getTaskScript() : "",
                    "logs/" + taskName + ".log",
                    "RUN_ONCE",
                    task.getTaskFailPolicy() != null ? task.getTaskFailPolicy() : "CONTINUE",
                    task.getTaskInterval(),
                    task.getTaskParallelPolicy() != null ? task.getTaskParallelPolicy() : "PARALLEL",
                    task.getTaskCrontabExpr(),
                    task.getTaskTimeout(),
                    task.getTaskGroup() != null ? task.getTaskGroup() : "",
                    task.getTaskStartupOrder(),
                    task.getConfigJson() != null ? task.getConfigJson() : "",
                    run.getWorkDir(),
                    hopWorkDirectory,
                    hopPluginDirectory
            );

            // Start the job immediately
            quartzScheduler.startJob(runId, taskName);

            ctx.json(Map.of("message", "Task started", "runId", runId, "taskName", taskName));
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error starting task: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to start task: " + e.getMessage()));
        }
    }

    /**
     * POST /scheduler/run/{runId}/task/stop
     * Stop a specific task.
     *
     * Body: { "taskName": "..." }
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

            quartzScheduler.unscheduleJob(runId, taskName);
            ctx.json(Map.of("message", "Task stopped", "runId", runId, "taskName", taskName));
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error stopping task: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to stop task: " + e.getMessage()));
        }
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
    private void handleListTasks(Context ctx) {
        String runId = ctx.pathParam("runId");
        try {
            List<SchedulerTask> tasks = meta.getTasksByRunId(runId);
            List<Map<String, Object>> scheduledJobs = quartzScheduler.getJobsForRun(runId);

            // Build a set of scheduled task names
            Set<String> scheduledNames = scheduledJobs.stream()
                    .map(j -> (String) j.get("taskName"))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            List<Map<String, Object>> result = tasks.stream().map(task -> {
                Map<String, Object> item = new HashMap<>();
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
                item.put("taskGroup", task.getTaskGroup());
                item.put("taskStartupOrder", task.getTaskStartupOrder());
                item.put("taskDescription", task.getTaskDescription());
                item.put("scheduled", scheduledNames.contains(task.getTaskName()));
                return item;
            }).collect(Collectors.toList());

            ctx.json(Map.of("runId", runId, "tasks", result));
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error listing tasks: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to list tasks: " + e.getMessage()));
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
     * Copy flow files from project template to run directory.
     */
    private void copyFlowFiles(String sourceDir, String targetDir) {
        File source = new File(sourceDir);
        if (!source.exists() || !source.isDirectory()) {
            logger.warn("[SCHEDULER] Project flow directory not found: {}", sourceDir);
            return;
        }

        try {
            Files.walkFileTree(source.toPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path targetFile = Paths.get(targetDir, source.toPath().relativize(file).toString());
                    Files.createDirectories(targetFile.getParent());
                    Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
            logger.info("[SCHEDULER] Copied flow files from {} to {}", sourceDir, targetDir);
        } catch (IOException e) {
            logger.error("[SCHEDULER] Error copying flow files: {}", e.getMessage());
        }
    }

    /**
     * Load default task configuration from project template and create task records.
     */
    private int loadDefaultTasks(String runId, String projectType) {
        String configPath = projectHome + File.separator
                + projectType + File.separator + "conf"
                + File.separator + "defaultSchedulerTask_" + projectType + ".json";

        File configFile = new File(configPath);
        if (!configFile.exists()) {
            logger.warn("[SCHEDULER] Default task config not found: {}", configPath);
            return 0;
        }

        int count = 0;
        try {
            String content = new String(Files.readAllBytes(configFile.toPath()));
            JSONObject config = JSON.parseObject(content);

            // Parse tasks array from config
            List<JSONObject> tasks = config.getJSONArray("tasks").toList(JSONObject.class);
            for (JSONObject taskConfig : tasks) {
                SchedulerTask task = new SchedulerTask();
                task.setRunId(runId);
                task.setTaskName(taskConfig.getString("taskName"));
                task.setTaskScript(taskConfig.getString("taskScript"));
                task.setTaskScriptType(taskConfig.getString("taskScriptType"));
                task.setTaskRunPolicy(taskConfig.getString("taskRunPolicy"));
                task.setTaskFailPolicy(taskConfig.getString("taskFailPolicy"));
                task.setTaskParallelPolicy(taskConfig.getString("taskParallelPolicy"));
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
            logger.info("[SCHEDULER] Loaded {} default tasks for run {} from project {}", count, runId, projectType);
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error loading default tasks: {}", e.getMessage());
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
        String scriptType = task.getTaskScriptType() != null ? task.getTaskScriptType() : "SQL";
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

        // Schedule the job with full parameters including workDir and Hop environment
        quartzScheduler.scheduleJob(
                runId, taskName, scriptType, script, logFile,
                taskRunPolicy, taskFailPolicy, taskInterval,
                taskParallelPolicy, taskCrontabExpr, taskTimeout,
                taskGroup, taskStartupOrder, configJson,
                run.getWorkDir(),
                hopWorkDirectory,
                hopPluginDirectory
        );

        // For RUN_ONCE, start immediately
        if ("RUN_ONCE".equalsIgnoreCase(taskRunPolicy)) {
            quartzScheduler.startJob(runId, taskName);
        }
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
