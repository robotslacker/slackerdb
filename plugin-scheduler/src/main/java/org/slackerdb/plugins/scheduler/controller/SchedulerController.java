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
import java.util.List;
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
        // 静态路由必须在参数化路由**之前**注册：否则 /scheduler/project/list 会被 {projectType} 吃掉（实测 404）
        app.unsafe.routes.get("/scheduler/project/list", this::handleListProjects);
        app.unsafe.routes.get("/scheduler/project/{projectType}", this::handleGetProject);
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
        // 任务写接口（M2）：只改元数据；真正调度仍走上面的 schedule/start/stop/abort
        app.unsafe.routes.post("/scheduler/run/{runId}/task/create", this::handleCreateTask);
        app.unsafe.routes.post("/scheduler/run/{runId}/task/update", this::handleUpdateTask);
        app.unsafe.routes.post("/scheduler/run/{runId}/task/delete", this::handleDeleteTask);
        app.unsafe.routes.get("/scheduler/run/{runId}/task/list", this::handleListTasks);
        app.unsafe.routes.get("/scheduler/run/{runId}/task/history", this::handleTaskHistory);
        app.unsafe.routes.get("/scheduler/run/{runId}/log", this::handleViewLog);
        app.unsafe.routes.get("/scheduler/run/{runId}/variables", this::handleRunVariables);
        // 跨 run 的执行流水（M2：globalHistory）
        app.unsafe.routes.get("/scheduler/history", this::handleGlobalHistory);
        app.unsafe.routes.get("/scheduler/describeCrontabExpr", this::handleDescribeCrontabExpr);

        // ========== 模板写接口（M2）：项目模板目录的读/写/删 ==========
        app.unsafe.routes.get("/scheduler/project/{projectType}/file", this::handleReadTemplateFile);
        app.unsafe.routes.post("/scheduler/project/{projectType}/upload", this::handleUploadTemplate);
        app.unsafe.routes.post("/scheduler/project/{projectType}/delete", this::handleDeleteTemplate);
        app.unsafe.routes.post("/scheduler/project/{projectType}/import", this::handleImportZip);

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
            features.put("taskCrud", true);        // 任务增/改/删（/task/create|update|delete）
            features.put("templateRead", true);    // 只能读项目模板目录
            features.put("templates", true);       // 模板写（上传/删除）
            features.put("globalHistory", true);   // 跨 run 的执行流水（/scheduler/history）
            features.put("sqlTask", false);        // 恒为 false：SQL 不是任务类型（写进 HOP 工作流的 SQL action）
            features.put("zipImport", true);
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
            reasons.add("hop.runScript is not configured (HOP tasks cannot run)");
        } else {
            Path script = Paths.get(hopConfig.runScript);
            if (!Files.isRegularFile(script)) {
                reasons.add("hop.runScript points to a missing file: " + hopConfig.runScript);
            } else if (!Files.isExecutable(script)) {
                reasons.add("hop.runScript is not executable: " + hopConfig.runScript);
            }
        }
        if (hopConfig != null && hopConfig.javaHome != null && !hopConfig.javaHome.isBlank()
                && !Files.isDirectory(Paths.get(hopConfig.javaHome))) {
            reasons.add("hop.javaHome does not exist: " + hopConfig.javaHome);
        }
        Path defaultProject = Paths.get(projectHome, "default");
        if (!Files.isDirectory(defaultProject)) {
            reasons.add("project templates are missing the default/ parent project: " + defaultProject);
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

    // ========== 模板写接口（M2） ==========

    /** 项目类型只允许字母数字下划线点横线：它是目录名，先挡住奇怪的输入再谈路径安全。 */
    /** 导入上限：防止 zip 炸弹把磁盘写满。 */
    private static final long MAX_IMPORT_BYTES = 50L * 1024 * 1024;
    private static final int MAX_IMPORT_ENTRIES = 500;
    private static final java.util.regex.Pattern PROJECT_TYPE_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    /**
     * 把相对路径解析到 {@code <projectHome>/<projectType>/} 之下，**越界直接拒绝**。
     *
     * <p>只靠 {@code resolve().normalize()} 不够：{@code ../} 会穿出去，绝对路径会直接覆盖别处。
     * 这里先拒绝绝对路径，再校验规范化后的前缀。</p>
     */
    private Path resolveTemplatePath(String projectType, String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        Path root = Paths.get(projectHome, projectType).toAbsolutePath().normalize();
        Path candidate = Paths.get(relative);
        if (candidate.isAbsolute()) {
            throw new IllegalArgumentException("path must be relative to the project directory: " + relative);
        }
        Path target = root.resolve(candidate).toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("path escapes the project directory: " + relative);
        }
        return target;
    }

    /** GET /scheduler/project/{projectType}/file?path=conf/defaultSchedulerTask.json */
    private void handleReadTemplateFile(Context ctx) {
        String projectType = ctx.pathParam("projectType");
        try {
            if (!PROJECT_TYPE_PATTERN.matcher(projectType).matches()) {
                ctx.status(400).json(Map.of("error", "invalid project type: " + projectType));
                return;
            }
            String relative = ctx.queryParam("path");
            if (relative == null || relative.isBlank()) {
                relative = "conf/" + TaskTemplateLoader.FILE_NAME;
            }
            Path target = resolveTemplatePath(projectType, relative);
            if (!Files.isRegularFile(target)) {
                ctx.status(404).json(Map.of("error", "template file not found: " + relative));
                return;
            }
            ctx.json(Map.of("projectType", projectType, "path", relative,
                    "content", Files.readString(target, StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("[SCHEDULER] failed to read template: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to read template: " + e.getMessage()));
        }
    }

    /**
     * POST /scheduler/project/{projectType}/upload
     * Body: {@code {"path":"conf/defaultSchedulerTask.json","content":"<JSON 文本>"}}
     *
     * <p><b>先校验 JSON、后落盘</b>：写坏一份模板比不写更糟 —— 下次建 run 会整单失败，
     * 而且很难看出是哪一步坏的。落盘后再用 {@link TaskTemplateLoader} 读一遍，
     * 把 warnings（字段拼错、策略不支持等）原样返回给调用方。</p>
     */
    private void handleUploadTemplate(Context ctx) {
        String projectType = ctx.pathParam("projectType");
        try {
            if (!PROJECT_TYPE_PATTERN.matcher(projectType).matches()) {
                ctx.status(400).json(Map.of("error", "invalid project type: " + projectType));
                return;
            }
            JSONObject body = ctx.bodyAsClass(JSONObject.class);
            String relative = body.getString("path");
            if (relative == null || relative.isBlank()) {
                relative = "conf/" + TaskTemplateLoader.FILE_NAME;
            }
            String content = body.getString("content");
            if (content == null || content.isBlank()) {
                ctx.status(400).json(Map.of("error", "content is required"));
                return;
            }
            try {
                JSON.parseObject(content);
            } catch (Exception e) {
                ctx.status(400).json(Map.of("error", "template is not valid JSON: " + e.getMessage()));
                return;
            }
            Path target = resolveTemplatePath(projectType, relative);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);

            org.slackerdb.plugins.scheduler.meta.TaskTemplateLoader.Loaded loaded =
                    org.slackerdb.plugins.scheduler.meta.TaskTemplateLoader
                            .load(Paths.get(projectHome), projectType, null);
            logger.info("[SCHEDULER] template written: {} (tasks={})", target, loaded.tasks().size());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("retCode", 0);
            result.put("projectType", projectType);
            result.put("path", relative);
            result.put("file", target.toString());
            result.put("taskCount", loaded.tasks().size());
            result.put("warnings", loaded.warnings());
            ctx.json(result);
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("[SCHEDULER] failed to write template: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to upload template: " + e.getMessage()));
        }
    }

    /**
     * POST /scheduler/project/{projectType}/import
     * Body: {@code {"contentBase64":"<zip 的 base64>"}} —— 整包导入项目模板。
     *
     * <p>为什么用 base64 而不是 multipart：Javalin 的 multipart 需要额外的全局配置
     * （maxRequestSize 等），而控制台只需要传一个文件；base64 放进 JSON 就够了，
     * 也不用为一次导入改服务器配置。</p>
     *
     * <p>安全：每个条目都做**路径越界检查**（绝对路径/`..` 一律拒绝），并限制条目数与总大小，
     * 避免 zip 炸弹把磁盘写满。</p>
     */
    private void handleImportZip(Context ctx) {
        String projectType = ctx.pathParam("projectType");
        try {
            if (!PROJECT_TYPE_PATTERN.matcher(projectType).matches()) {
                ctx.status(400).json(Map.of("error", "invalid project type: " + projectType));
                return;
            }
            JSONObject body = ctx.bodyAsClass(JSONObject.class);
            String base64 = body.getString("contentBase64");
            if (base64 == null || base64.isBlank()) {
                ctx.status(400).json(Map.of("error", "contentBase64 is required"));
                return;
            }
            byte[] bytes;
            try {
                bytes = java.util.Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", "contentBase64 is not valid base64"));
                return;
            }
            if (bytes.length > MAX_IMPORT_BYTES) {
                ctx.status(400).json(Map.of("error", "zip exceeds the size limit of " + (MAX_IMPORT_BYTES / 1024 / 1024) + " MB"));
                return;
            }

            Path root = Paths.get(projectHome, projectType).toAbsolutePath().normalize();
            Files.createDirectories(root);
            List<String> written = new ArrayList<>();
            long total = 0;
            int entries = 0;
            try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                    new java.io.ByteArrayInputStream(bytes))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entries > MAX_IMPORT_ENTRIES) {
                        throw new IllegalArgumentException(
                                "too many entries in zip (limit " + MAX_IMPORT_ENTRIES + ")");
                    }
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String name = entry.getName().replace('\\', '/');
                    Path target = root.resolve(name).normalize();
                    if (!target.startsWith(root)) {
                        throw new IllegalArgumentException("zip entry escapes the project directory: " + name);
                    }
                    Files.createDirectories(target.getParent());
                    // 按上限"边读边判"，而不是 readAllBytes() 之后再判：单个条目的**解压后**大小
                    // 由压缩包决定，一个几十 KB 的 deflate 条目可以解压出几 GB。先整块读进内存再
                    // 比较上限，等于把这一步的判定白送给了攻击者（一次 POST 就能把堆打满）。
                    byte[] content = readEntryBounded(zip, MAX_IMPORT_BYTES - total);
                    total += content.length;
                    Files.write(target, content);
                    written.add(name);
                }
            }

            org.slackerdb.plugins.scheduler.meta.TaskTemplateLoader.Loaded loaded =
                    org.slackerdb.plugins.scheduler.meta.TaskTemplateLoader
                            .load(Paths.get(projectHome), projectType, null);
            logger.info("[SCHEDULER] template zip imported: project={} files={} tasks={}",
                    projectType, written.size(), loaded.tasks().size());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("retCode", 0);
            result.put("projectType", projectType);
            result.put("count", written.size());
            result.put("files", written);
            result.put("taskCount", loaded.tasks().size());
            result.put("warnings", loaded.warnings());
            ctx.json(result);
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("[SCHEDULER] failed to import template zip: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to import zip: " + e.getMessage()));
        }
    }

    /**
     * 读一个 zip 条目，最多 {@code budget} 字节；超了立刻放弃（而不是先全读进内存再比较）。
     *
     * <p>内存占用上限 = {@code budget}：调用方传的是"总量上限 - 已写字节数"，因此整个导入过程的
     * 峰值内存也与 {@link #MAX_IMPORT_BYTES} 同阶。</p>
     */
    private static byte[] readEntryBounded(java.util.zip.ZipInputStream zip, long budget) throws java.io.IOException {
        if (budget <= 0) {
            throw new IllegalArgumentException(oversizeMessage());
        }
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long read = 0;
        int count;
        while ((count = zip.read(chunk)) > 0) {
            read += count;
            if (read > budget) {
                throw new IllegalArgumentException(oversizeMessage());
            }
            buffer.write(chunk, 0, count);
        }
        return buffer.toByteArray();
    }

    /** 超上限的例外文本（只有一处，避免两条消息漂移）。 */
    private static String oversizeMessage() {
        return "zip exceeds the " + (MAX_IMPORT_BYTES / 1024 / 1024) + " MB limit after decompression";
    }

    /** POST /scheduler/project/{projectType}/delete，Body: {@code {"path":"conf/defaultSchedulerTask.json"}} */
    private void handleDeleteTemplate(Context ctx) {
        String projectType = ctx.pathParam("projectType");
        try {
            if (!PROJECT_TYPE_PATTERN.matcher(projectType).matches()) {
                ctx.status(400).json(Map.of("error", "invalid project type: " + projectType));
                return;
            }
            String relative = ctx.queryParam("path");
            if (relative == null || relative.isBlank()) {
                JSONObject body = ctx.bodyAsClass(JSONObject.class);
                relative = body.getString("path");
            }
            if (relative == null || relative.isBlank()) {
                relative = "conf/" + TaskTemplateLoader.FILE_NAME;
            }
            Path target = resolveTemplatePath(projectType, relative);
            boolean deleted = Files.deleteIfExists(target);
            if (!deleted) {
                ctx.status(404).json(Map.of("error", "template file not found: " + relative));
                return;
            }
            logger.info("[SCHEDULER] template deleted: {}", target);
            ctx.json(Map.of("retCode", 0, "projectType", projectType, "path", relative, "deleted", true));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("[SCHEDULER] failed to delete template: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to delete template: " + e.getMessage()));
        }
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
                logger.warn("[SCHEDULER] rejecting run creation [{}]: invalid request fields: {}", runId, bodyProblems);
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
                logger.warn("[SCHEDULER] rejecting run creation [{}]: invalid task definitions: {}", runId, problems);
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
     * POST /scheduler/run/{runId}/task/create
     * 新建任务（只写元数据表；要让 Quartz 跑起来还要调 /task/schedule）。
     *
     * <p>字段与 {@code /run/create} 里的内联任务一致，校验复用同一套
     * {@code validateInlineTasks} —— 两条入口的规则必须一样，否则"建实例能过、单独加任务过不了"。</p>
     */
    private void handleCreateTask(Context ctx) {
        String runId = ctx.pathParam("runId");
        try {
            if (meta.getRun(runId) == null) {
                ctx.status(404).json(Map.of("error", "Run not found: " + runId));
                return;
            }
            JSONObject body = ctx.bodyAsClass(JSONObject.class);
            String taskName = body.getString("taskName");
            if (taskName == null || taskName.isBlank()) {
                ctx.status(400).json(Map.of("error", "taskName is required"));
                return;
            }
            if (meta.getTask(runId, taskName) != null) {
                ctx.status(409).json(Map.of("error", "Task already exists: " + taskName));
                return;
            }
            List<String> problems = validateInlineTasks(List.of(body));
            if (!problems.isEmpty()) {
                ctx.status(400).json(Map.of("error", "invalid task", "problems", problems));
                return;
            }
            int created = createTasksFromApi(runId, List.of(body));
            logger.info("[SCHEDULER] task created: run={} task={}", runId, taskName);
            ctx.status(201).json(Map.of("retCode", 0, "runId", runId, "taskName", taskName, "created", created));
        } catch (Exception e) {
            logger.error("[SCHEDULER] create task failed: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to create task: " + e.getMessage()));
        }
    }

    /**
     * POST /scheduler/run/{runId}/task/update
     * 改任务：**只改请求里出现的字段**（局部更新），任务名本身不可改（改名 = 删了重建）。
     */
    private void handleUpdateTask(Context ctx) {
        String runId = ctx.pathParam("runId");
        try {
            JSONObject body = ctx.bodyAsClass(JSONObject.class);
            String taskName = body.getString("taskName");
            if (taskName == null || taskName.isBlank()) {
                ctx.status(400).json(Map.of("error", "taskName is required"));
                return;
            }
            SchedulerTask task = meta.getTask(runId, taskName);
            if (task == null) {
                ctx.status(404).json(Map.of("error", "Task not found: " + taskName));
                return;
            }
            List<String> problems = new ArrayList<>();
            applyTaskPatch(task, body, problems);
            if (!problems.isEmpty()) {
                ctx.status(400).json(Map.of("error", "invalid task", "problems", problems));
                return;
            }
            task.setUpdateTime(LocalDateTime.now());
            boolean updated = meta.updateTask(task);
            logger.info("[SCHEDULER] task updated: run={} task={} hit={}", runId, taskName, updated);
            ctx.json(Map.of("retCode", 0, "runId", runId, "taskName", taskName, "updated", updated));
        } catch (Exception e) {
            logger.error("[SCHEDULER] update task failed: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to update task: " + e.getMessage()));
        }
    }

    /** POST /scheduler/run/{runId}/task/delete（body: {taskName} 或 query ?taskName=） */
    private void handleDeleteTask(Context ctx) {
        String runId = ctx.pathParam("runId");
        String taskName = ctx.queryParam("taskName");
        try {
            if (taskName == null || taskName.isBlank()) {
                JSONObject body = ctx.bodyAsClass(JSONObject.class);
                taskName = body.getString("taskName");
            }
            if (taskName == null || taskName.isBlank()) {
                ctx.status(400).json(Map.of("error", "taskName is required"));
                return;
            }
            boolean deleted = meta.deleteTask(runId, taskName);
            if (!deleted) {
                ctx.status(404).json(Map.of("error", "Task not found: " + taskName));
                return;
            }
            logger.info("[SCHEDULER] task deleted: run={} task={}", runId, taskName);
            ctx.json(Map.of("retCode", 0, "runId", runId, "taskName", taskName, "deleted", true));
        } catch (Exception e) {
            logger.error("[SCHEDULER] delete task failed: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to delete task: " + e.getMessage()));
        }
    }

    /**
     * 把请求体里出现的字段打到任务上（局部更新）。枚举类字段统一大写；
     * 取值域与内联任务的校验保持一致（脚本类型 / 运行策略 / 失败策略）。
     */
    private void applyTaskPatch(SchedulerTask task, JSONObject body, List<String> problems) {
        if (body.containsKey("taskScript")) {
            task.setTaskScript(body.getString("taskScript"));
        }
        if (body.containsKey("taskScriptType")) {
            String type = upper(body.getString("taskScriptType"), TaskTemplateLoader.DEFAULT_SCRIPT_TYPE);
            if (!TaskTemplateLoader.SCRIPT_TYPES.contains(type)) {
                problems.add("taskScriptType is not supported: " + type + " (supported: " + TaskTemplateLoader.SCRIPT_TYPES + ")");
            }
            task.setTaskScriptType(type);
        }
        if (body.containsKey("taskRunPolicy")) {
            String policy = upper(body.getString("taskRunPolicy"), "RUN_ONCE");
            if (!Set.of("RUN_ONCE", "CRONTAB", "INTERVAL").contains(policy)) {
                problems.add("taskRunPolicy is not supported: " + policy + " (supported: RUN_ONCE/CRONTAB/INTERVAL)");
            }
            task.setTaskRunPolicy(policy);
        }
        if (body.containsKey("taskFailPolicy")) {
            String policy = upper(body.getString("taskFailPolicy"), "CONTINUE");
            if (!Set.of("STOP", "CONTINUE", "RETRY").contains(policy)) {
                problems.add("taskFailPolicy is not supported: " + policy + " (supported: STOP/CONTINUE/RETRY)");
            }
            task.setTaskFailPolicy(policy);
        }
        if (body.containsKey("taskRetryTimes")) {
            task.setTaskRetryTimes(body.getIntValue("taskRetryTimes", 0));
        }
        if (body.containsKey("taskRetryInterval")) {
            task.setTaskRetryInterval(body.getIntValue("taskRetryInterval", 60));
        }
        if (body.containsKey("taskParallelPolicy")) {
            task.setTaskParallelPolicy(upper(body.getString("taskParallelPolicy"), "PARALLEL"));
        }
        if (body.containsKey("taskCrontabExpr")) {
            task.setTaskCrontabExpr(body.getString("taskCrontabExpr"));
        }
        if (body.containsKey("taskInterval")) {
            task.setTaskInterval(body.getIntValue("taskInterval", 0));
        }
        if (body.containsKey("taskTimeout")) {
            task.setTaskTimeout(body.getIntValue("taskTimeout", 0));
        }
        if (body.containsKey("taskEnabled")) {
            task.setTaskEnabled(body.getBooleanValue("taskEnabled", true));
        }
        if (body.containsKey("taskMandatory")) {
            task.setTaskMandatory(body.getBooleanValue("taskMandatory", false));
        }
        if (body.containsKey("taskGroup")) {
            task.setTaskGroup(body.getString("taskGroup"));
        }
        if (body.containsKey("taskStartupOrder")) {
            task.setTaskStartupOrder(body.getIntValue("taskStartupOrder", 0));
        }
        if (body.containsKey("taskDescription")) {
            task.setTaskDescription(body.getString("taskDescription"));
        }
        if (body.containsKey("configJson")) {
            task.setConfigJson(body.getString("configJson"));
        }
        if (body.containsKey("variables")) {
            Map<String, String> variables = TaskTemplateLoader.flattenVariables(body.get("variables"));
            if (!variables.isEmpty()) {
                task.setVariablesJson(JSON.toJSONString(variables));
            }
        }
        // cron 策略必须带表达式，interval 策略必须带正数间隔
        if ("CRONTAB".equals(task.getTaskRunPolicy())
                && (task.getTaskCrontabExpr() == null || task.getTaskCrontabExpr().isBlank())) {
            problems.add("taskRunPolicy=CRONTAB requires taskCrontabExpr");
        }
        if ("INTERVAL".equals(task.getTaskRunPolicy()) && task.getTaskInterval() <= 0) {
            problems.add("taskRunPolicy=INTERVAL requires a positive taskInterval");
        }
    }

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

    /**
     * GET /scheduler/history?hours=24&runId=&taskName=&limit=200
     * 跨 run 的执行流水：默认最近 24 小时，`hours=0` 表示不限时间。
     *
     * <p>返回里带 {@code summary}（成功/失败/进行中），控制台不用自己数。</p>
     */
    private void handleGlobalHistory(Context ctx) {
        try {
            int hours = parseIntOrDefault(ctx.queryParam("hours"), 24);
            int limit = parseIntOrDefault(ctx.queryParam("limit"), 200);
            java.time.LocalDateTime to = java.time.LocalDateTime.now();
            java.time.LocalDateTime from = hours > 0 ? to.minusHours(hours) : null;

            List<org.slackerdb.plugins.scheduler.entity.TaskHistory> rows = meta.listHistory(
                    from, to, ctx.queryParam("runId"), ctx.queryParam("taskName"), limit);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("from", from == null ? null : from.toString());
            body.put("to", to.toString());
            body.put("count", rows.size());
            body.put("summary", meta.historySummary(from, to));
            body.put("history", rows);
            ctx.json(body);
        } catch (Exception e) {
            logger.error("[SCHEDULER] Error listing history: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to list history: " + e.getMessage()));
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
            logger.warn("[SCHEDULER] {} is not a valid JSON object, ignored: {}", what, e.getMessage());
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
                problems.add("request body has an unknown field [" + key + "] (available: " + RUN_BODY_FIELDS + ")");
            }
        }
        Object tasks = body.get("tasks");
        if (tasks != null && !(tasks instanceof Collection)) {
            problems.add("tasks must be an array (actual: " + tasks.getClass().getSimpleName() + ")");
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
                        problems.add(label + " has an unknown field [" + key + "] (available: "
                                + TaskTemplateLoader.TASK_FIELDS + ")");
                    }
                }
            }

            if (isBlank(taskName)) {
                problems.add(label + " is missing taskName");
            }
            if (config == null || isBlank(config.getString("taskScript"))) {
                problems.add(label + " is missing taskScript");
                continue;
            }
            String scriptType = upper(config.getString("taskScriptType"),
                    TaskTemplateLoader.DEFAULT_SCRIPT_TYPE);
            if (!TaskTemplateLoader.SCRIPT_TYPES.contains(scriptType)) {
                problems.add(label + " has an unsupported taskScriptType=[" + scriptType + "] (available: "
                        + TaskTemplateLoader.SCRIPT_TYPES + ")");
            }
            String runPolicy = upper(config.getString("taskRunPolicy"), "RUN_ONCE");
            if (!TaskTemplateLoader.RUN_POLICIES.contains(runPolicy)) {
                problems.add(label + " has an unsupported taskRunPolicy=[" + runPolicy + "] (available: "
                        + TaskTemplateLoader.RUN_POLICIES + ")");
            } else if ("CRONTAB".equals(runPolicy) && isBlank(config.getString("taskCrontabExpr"))) {
                problems.add(label + " has taskRunPolicy=CRONTAB but no taskCrontabExpr");
            } else if ("INTERVAL".equals(runPolicy) && config.getIntValue("taskInterval", 0) <= 0) {
                problems.add(label + " has taskRunPolicy=INTERVAL but taskInterval<=0");
            }
            String failPolicy = upper(config.getString("taskFailPolicy"), "CONTINUE");
            if (!TaskTemplateLoader.FAIL_POLICIES.contains(failPolicy)) {
                problems.add(label + " has an unsupported taskFailPolicy=[" + failPolicy + "] (available: "
                        + TaskTemplateLoader.FAIL_POLICIES + ")");
            }
            int retryTimes = config.getIntValue("taskRetryTimes", 0);
            int retryInterval = config.getIntValue("taskRetryInterval", 60);
            if (retryTimes < 0) {
                problems.add(label + " taskRetryTimes must not be negative, actual: " + retryTimes);
            }
            if (retryInterval < 0) {
                problems.add(label + " taskRetryInterval must not be negative, actual: " + retryInterval);
            }
            // RETRY 却不可重试：QuartzJob.handleRetry 会在运行期静默降级为 CONTINUE，这里提前拒绝
            if ("RETRY".equals(failPolicy) && retryTimes <= 0) {
                problems.add(label + " has taskFailPolicy=RETRY but taskRetryTimes<=0 (degrades to CONTINUE at runtime)");
            }
            Object mandatory = config.get("taskMandatory");
            if (mandatory != null && !isBooleanValue(mandatory)) {
                problems.add(label + " taskMandatory must be a boolean, actual: " + mandatory);
            }
            Object variablesValue = config.get("variables");
            if (variablesValue != null) {
                if (!(variablesValue instanceof Map<?, ?> variables)) {
                    problems.add(label + " variables must be a JSON object (key -> scalar value)");
                } else {
                    for (Map.Entry<?, ?> entry : variables.entrySet()) {
                        Object value = entry.getValue();
                        if (value instanceof Map || value instanceof Collection) {
                            problems.add(label + " variables[" + entry.getKey()
                                    + "] is a nested object/array (only scalar values are supported)");
                        }
                    }
                }
            }
            String parallelPolicy = upper(config.getString("taskParallelPolicy"), "PARALLEL");
            if (!TaskTemplateLoader.PARALLEL_POLICIES.contains(parallelPolicy)) {
                problems.add(label + " has an unsupported taskParallelPolicy=[" + parallelPolicy + "] (available: "
                        + TaskTemplateLoader.PARALLEL_POLICIES + ")");
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
            throw new IOException("template project directory not found: " + typeDir
                    + " (expected <scheduler.projectHome>/<projectType>)");
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
        logger.info("[SCHEDULER] HOP project copied to {}", targetFlow);

        // 模板自检：default 项目不能把 default 声明为自己的父项目，否则 Hop 会报
        // "There is a loop in the parent projects hierarchy: project default references itself"
        Path defaultProjectConfig = targetFlow.resolve("default").resolve("project-config.json");
        if (Files.isRegularFile(defaultProjectConfig)) {
            try {
                JSONObject config = JSON.parseObject(Files.readString(defaultProjectConfig, StandardCharsets.UTF_8));
                String parent = config == null ? null : config.getString("parentProjectName");
                if (parent != null && !parent.isBlank() && "default".equalsIgnoreCase(parent.trim())) {
                    logger.warn("[SCHEDULER] template self-check failed: {} has parentProjectName=[default], "
                            + "which makes the Hop parent project reference itself in a loop; use an empty string instead", defaultProjectConfig);
                }
            } catch (Exception e) {
                logger.warn("[SCHEDULER] template self-check cannot parse {}: {}", defaultProjectConfig, e.getMessage());
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
                logger.info("[SCHEDULER] restart reconcile: no run needs handling");
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
                        logger.warn("[SCHEDULER] restart reconcile: run[{}] task[{}] registration failed: {}",
                                run.getRunId(), task.getTaskName(), e.getMessage());
                    }
                }
                meta.updateRunStatus(run.getRunId(), "STOPPED");
                logger.warn("[SCHEDULER] restart reconcile: run[{}] previous state[{}] -> STOPPED, "
                                + "{} task definition(s) re-registered (kept paused, start them manually)",
                        run.getRunId(), run.getStatus(), restored);
            }
        } catch (Exception e) {
            logger.error("[SCHEDULER] restart reconcile failed: {}", e.getMessage(), e);
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
            logger.error("[SCHEDULER] failed to parse template task list: {}", e.getMessage(), e);
            return 0;
        }
        for (String warning : loaded.warnings()) {
            logger.warn("[SCHEDULER] template task list: {}", warning);
        }
        if (loaded.isEmpty()) {
            logger.info("[SCHEDULER] instance[{}] loaded no tasks ({})", runId, loaded.fileText());
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
            logger.info("[SCHEDULER] instance[{}] loaded {} task(s) from {}", runId, count, loaded.file());
        } catch (Exception e) {
            logger.error("[SCHEDULER] failed to write tasks: {}", e.getMessage(), e);
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
