package org.slackerdb.plugins.scheduler;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;

import org.slackerdb.plugins.scheduler.controller.SchedulerController;
import org.slackerdb.plugins.scheduler.entity.SchedulerRun;
import org.slackerdb.plugins.scheduler.entity.SchedulerTask;
import org.slackerdb.plugins.scheduler.meta.SchedulerMeta;
import org.slackerdb.plugins.scheduler.quartz.DynamicQuartzScheduler;
import org.slackerdb.plugins.scheduler.runner.TaskRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调度器"平台契约端点"的集成测试：{@code /scheduler/capabilities} 与 {@code /scheduler/state/lost}。
 *
 * <p>这两个端点是控制台（以及任何客户端）**不靠探测猜能力**的基础：
 * 前者声明契约版本、能力开关、Quartz 真实状态与 HOP 就绪判定；
 * 后者给出"定义还在、调度已丢"的权威列表（重启后一键恢复的依据）。</p>
 *
 * <p>不依赖外部数据库服务：用内存 DuckDB + 随机端口的 Javalin 起一套最小环境。</p>
 */
class SchedulerContractApiTest {

    @TempDir
    static Path tempDir;

    static Javalin app;
    static Connection connection;
    static SchedulerMeta meta;
    static DynamicQuartzScheduler quartz;
    static String baseUrl;

    @BeforeAll
    static void initAll() throws Exception {
        Path workHome = tempDir.resolve("work");
        Path projectHome = tempDir.resolve("projects");
        Files.createDirectories(workHome);
        Files.createDirectories(projectHome.resolve("demo"));
        // 故意不建 default/ 父项目：用来验证 hop.reasons 会报出来
        Files.createDirectories(projectHome.resolve("demo").resolve("conf"));

        // 与既有调度器测试保持一致：显式装载驱动、打开自动提交，并**先建 schema**。
        // 少了这一步，initializeTables 里的探测语句会在内存 DuckDB 上留下 pending result，
        // 后续 DDL 全部报 "Attempting to execute an unsuccessful or closed pending query result"。
        Class.forName("org.duckdb.DuckDBDriver");
        connection = DriverManager.getConnection("jdbc:duckdb:");
        connection.setAutoCommit(true);
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS sysaux");
        }
        meta = new SchedulerMeta(connection, LoggerFactory.getLogger("test-contract-meta"));
        meta.initializeTables();

        // 一个实例 + 一个"启用但未登记到 Quartz"的任务 → 应被 /state/lost 报出来
        SchedulerRun run = new SchedulerRun();
        run.setRunId("20260101000000");
        run.setProjectType("demo");
        run.setStatus("STOPPED");
        run.setWorkDir(workHome.resolve("runs").resolve("20260101000000").toString());
        run.setCreateTime(LocalDateTime.now());
        run.setUpdateTime(LocalDateTime.now());
        meta.insertRun(run);

        SchedulerTask task = new SchedulerTask();
        task.setRunId("20260101000000");
        task.setTaskName("load_dim");
        task.setTaskScript("load_dim.hwf");
        task.setTaskScriptType("HOP");
        task.setTaskRunPolicy("CRONTAB");
        task.setTaskCrontabExpr("0 0 2 * * ?");
        task.setTaskEnabled(true);
        task.setTaskGroup("dim");
        task.setTaskStartupOrder(10);
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        meta.insertTask(task);

        quartz = new DynamicQuartzScheduler(LoggerFactory.getLogger("test-contract-quartz"), 4, meta);
        quartz.start();

        app = Javalin.create(config -> config.startup.showJavalinBanner = false).start(0);
        baseUrl = "http://127.0.0.1:" + app.port();

        // HOP 未配置：hop.ready 必须为 false 且给出原因
        TaskRequest.HopConfig hopConfig = new TaskRequest.HopConfig();
        SchedulerController controller = new SchedulerController(
                app, meta, quartz, LoggerFactory.getLogger("test-contract-controller"),
                workHome.toString(), projectHome.toString(), hopConfig);
        controller.registerRoutes();
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (app != null) {
            app.stop();
        }
        if (quartz != null) {
            quartz.shutdown();
        }
        if (connection != null) {
            connection.close();
        }
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("/scheduler/capabilities declares contract version, feature switches, real Quartz state and HOP readiness")
    void capabilitiesDeclareContractAndReadiness() throws Exception {
        HttpResponse<String> response = get("/scheduler/capabilities");
        assertEquals(200, response.statusCode(), "GET /scheduler/capabilities should return 200");

        JSONObject capabilities = JSONObject.parseObject(response.body());
        assertEquals(1, capabilities.getIntValue("contractVersion"), "contract version should be 1");

        JSONObject features = capabilities.getJSONObject("features");
        assertNotNull(features, "features must be declared");
        assertTrue(features.getBooleanValue("runCrud"), "run operations are available");
        assertTrue(features.getBooleanValue("taskCrud"), "the task write API exists (M2), so the capability must be declared true");

        JSONObject quartzInfo = capabilities.getJSONObject("quartz");
        assertTrue(quartzInfo.getBooleanValue("started"), "Quartz should report started (no longer a hard-coded constant)");
        assertEquals(4, quartzInfo.getIntValue("threads"));

        JSONObject hop = capabilities.getJSONObject("hop");
        assertNotNull(hop, "HOP readiness must be declared");
        assertFalse(hop.getBooleanValue("ready"), "ready must be false when hop.runScript is not configured");
        JSONArray reasons = hop.getJSONArray("reasons");
        assertNotNull(reasons, "reasons must be given when not ready");
        assertTrue(reasons.size() >= 1, "reasons must not be empty when not ready");
    }

    @Test
    @DisplayName("/scheduler/state/lost reports tasks whose definition survived but whose schedule is gone")
    void lostSchedulesReportsDefinitionWithoutTrigger() throws Exception {
        HttpResponse<String> response = get("/scheduler/state/lost");
        assertEquals(200, response.statusCode(), "GET /scheduler/state/lost should return 200");

        JSONArray lost = JSONObject.parseObject(response.body()).getJSONArray("lostSchedules");
        assertNotNull(lost, "the response must contain the lostSchedules array");

        boolean found = false;
        for (int i = 0; i < lost.size(); i++) {
            JSONObject item = lost.getJSONObject(i);
            if ("load_dim".equals(item.getString("taskName"))) {
                found = true;
                assertEquals("20260101000000", item.getString("runId"));
                assertEquals("dim", item.getString("taskGroup"));
            }
        }
        assertTrue(found, "a task that is enabled but not registered with Quartz must appear in lostSchedules; actual: "
                + lost.toJSONString());
    }

    @Test
    @DisplayName("inline tasks: taskScriptType defaults to HOP (same rule as templates) and lower-case types are normalised")
    void inlineTasksDefaultToHopAndAreNormalized() throws Exception {
        String runId = "20260202000001";
        HttpResponse<String> response = post("/scheduler/run/create", """
                {
                  "runId": "%s",
                  "projectType": "demo",
                  "tasks": [
                    { "taskName": "no-type", "taskScript": "echo hi" },
                    { "taskName": "lower-type", "taskScript": "echo hi", "taskScriptType": "command" }
                  ]
                }
                """.formatted(runId));
        assertEquals(201, response.statusCode(), "valid tasks should be created, actual response: " + response.body());
        assertEquals(2, JSONObject.parseObject(response.body()).getIntValue("taskCount"));

        List<SchedulerTask> tasks = meta.getTasksByRunId(runId);
        assertEquals(2, tasks.size(), "both tasks should be persisted");
        for (SchedulerTask task : tasks) {
            if ("no-type".equals(task.getTaskName())) {
                // 曾经这里默认落成 SQL（运行期必然被拒），现在与模板统一默认 HOP
                assertEquals("HOP", task.getTaskScriptType(),
                        "taskScriptType should default to HOP (same rule as TaskTemplateLoader), actual: "
                                + task.getTaskScriptType());
            } else {
                assertEquals("COMMAND", task.getTaskScriptType(),
                        "a lower-case type should be normalised to upper case, actual: " + task.getTaskScriptType());
            }
        }
    }

    @Test
    @DisplayName("invalid inline tasks reject the whole run with 400 (including SQL) and leave no run directory or record")
    void invalidInlineTasksAreRejectedWithoutSideEffects() throws Exception {
        String runId = "20260202000002";
        HttpResponse<String> response = post("/scheduler/run/create", """
                {
                  "runId": "%s",
                  "projectType": "demo",
                  "tasks": [
                    { "taskName": "sql-task", "taskScript": "select 1", "taskScriptType": "SQL" },
                    { "taskName": "missing-cron", "taskScript": "x.hwf", "taskRunPolicy": "CRONTAB" }
                  ]
                }
                """.formatted(runId));
        assertEquals(400, response.statusCode(), "invalid task definitions must reject the whole run, actual response: " + response.body());

        JSONObject body = JSONObject.parseObject(response.body());
        JSONArray problems = body.getJSONArray("problems");
        assertNotNull(problems, "each problem must be listed on rejection, actual response: " + response.body());
        assertEquals(2, problems.size(), "each of the two invalid tasks should report one problem, actual: " + problems);
        String text = problems.toJSONString();
        assertTrue(text.contains("SQL"), "should point out that SQL is not supported, actual: " + problems);
        assertTrue(text.contains("CRONTAB"), "should point out that CRONTAB lacks an expression, actual: " + problems);

        // 关键：先校验后落地 —— 不能留下“建了一半”的实例
        assertNull(meta.getRun(runId), "invalid task definitions must not create a run record");
        assertFalse(Files.exists(tempDir.resolve("work").resolve("runs").resolve(runId)),
                "invalid task definitions must not create a run directory");
    }

    @Test
    @DisplayName("inline tasks support the same fields as templates: taskRetryTimes/Interval, taskMandatory, task-level variables")
    void inlineTasksSupportRetryMandatoryAndVariables() throws Exception {
        String runId = "20260202000003";
        HttpResponse<String> response = post("/scheduler/run/create", """
                {
                  "runId": "%s",
                  "projectType": "demo",
                  "tasks": [
                    {
                      "taskName": "retry-task",
                      "taskScript": "echo hi",
                      "taskScriptType": "SHELL",
                      "taskFailPolicy": "RETRY",
                      "taskRetryTimes": 3,
                      "taskRetryInterval": 15,
                      "taskMandatory": true,
                      "variables": { "BIZ_HOST": "10.0.0.1", "BIZ_PORT": 1521 }
                    }
                  ]
                }
                """.formatted(runId));
        assertEquals(201, response.statusCode(), "valid tasks should be created, actual response: " + response.body());

        List<SchedulerTask> tasks = meta.getTasksByRunId(runId);
        assertEquals(1, tasks.size());
        SchedulerTask task = tasks.get(0);
        assertEquals(3, task.getTaskRetryTimes(), "taskRetryTimes must be persisted (it used to be dropped silently)");
        assertEquals(15, task.getTaskRetryInterval(), "taskRetryInterval must be persisted (it used to be dropped silently)");
        assertTrue(task.isTaskMandatory(), "taskMandatory must be persisted (otherwise the mandatory semantic is lost)");

        JSONObject variables = JSONObject.parseObject(task.getVariablesJson());
        assertNotNull(variables, "task-level variables should be flattened into variables_json, actual: " + task.getVariablesJson());
        assertEquals("10.0.0.1", variables.getString("BIZ_HOST"));
        assertEquals("1521", variables.getString("BIZ_PORT"), "numeric values should become strings");
    }

    @Test
    @DisplayName("RETRY with retryTimes<=0, nested variables, non-boolean mandatory: the whole run is rejected with 400")
    void invalidRetryAndVariablesAreRejected() throws Exception {
        String runId = "20260202000004";
        HttpResponse<String> response = post("/scheduler/run/create", """
                {
                  "runId": "%s",
                  "projectType": "demo",
                  "tasks": [
                    { "taskName": "empty-retry", "taskScript": "echo hi", "taskFailPolicy": "RETRY" },
                    { "taskName": "nested-vars", "taskScript": "echo hi",
                      "variables": { "NESTED": { "a": 1 } } },
                    { "taskName": "bad-mandatory", "taskScript": "echo hi", "taskMandatory": "yes" }
                  ]
                }
                """.formatted(runId));
        assertEquals(400, response.statusCode(),
                "a non-retryable RETRY plus invalid variables/mandatory must reject the whole run, actual response: " + response.body());

        String problems = JSONObject.parseObject(response.body()).getJSONArray("problems").toJSONString();
        assertTrue(problems.contains("taskRetryTimes"),
                "should point out that RETRY lacks taskRetryTimes (it would silently degrade at runtime), actual: " + problems);
        assertTrue(problems.contains("variables[NESTED]"), "should point out that nested variables are unsupported, actual: " + problems);
        assertTrue(problems.contains("taskMandatory"), "should point out that taskMandatory must be a boolean, actual: " + problems);
        assertNull(meta.getRun(runId), "a rejected request must not create a run record");
    }

    @Test
    @DisplayName("an unknown (misspelled) inline task field returns 400 instead of being ignored silently")
    void unknownInlineTaskFieldIsRejected() throws Exception {
        String runId = "20260202000005";
        HttpResponse<String> response = post("/scheduler/run/create", """
                {
                  "runId": "%s",
                  "projectType": "demo",
                  "tasks": [
                    { "taskName": "typo-field", "taskScript": "echo hi", "taskTimeouts": 600 }
                  ]
                }
                """.formatted(runId));
        assertEquals(400, response.statusCode(), "unknown fields must be rejected, actual response: " + response.body());

        String problems = JSONObject.parseObject(response.body()).getJSONArray("problems").toJSONString();
        assertTrue(problems.contains("unknown field [taskTimeouts]"),
                "should point out the misspelled field (taskTimeouts is not taskTimeout), actual: " + problems);
        assertNull(meta.getRun(runId), "a rejected request must not create a run record");
    }

    @Test
    @DisplayName("a misspelled top-level run field (task instead of tasks) returns 400 instead of falling back to template tasks")
    void unknownRunBodyFieldIsRejected() throws Exception {
        String runId = "20260202000006";
        HttpResponse<String> response = post("/scheduler/run/create", """
                {
                  "runId": "%s",
                  "projectType": "demo",
                  "task": [ { "taskName": "misplaced-task", "taskScript": "echo hi" } ]
                }
                """.formatted(runId));
        assertEquals(400, response.statusCode(),
                "top-level unknown fields must be rejected (otherwise it silently falls back to template tasks), actual response: " + response.body());

        JSONObject body = JSONObject.parseObject(response.body());
        assertEquals("invalid request", body.getString("error"));
        assertTrue(body.getJSONArray("problems").toJSONString().contains("unknown field [task]"),
                "should point out the misspelled top-level field, actual: " + body.getJSONArray("problems"));
        assertNull(meta.getRun(runId), "a rejected request must not create a run record");
        assertFalse(Files.exists(tempDir.resolve("work").resolve("runs").resolve(runId)),
                "a rejected request must not create a run directory");
    }

    @Test
    @DisplayName("a non-array tasks value returns 400 (not 500 and no silent fallback)")
    void nonArrayTasksAreRejected() throws Exception {
        String runId = "20260202000007";
        HttpResponse<String> response = post("/scheduler/run/create", """
                {
                  "runId": "%s",
                  "projectType": "demo",
                  "tasks": { "taskName": "object-form", "taskScript": "echo hi" }
                }
                """.formatted(runId));
        assertEquals(400, response.statusCode(), "a non-array tasks value should return 400, actual response: " + response.body());
        assertTrue(JSONObject.parseObject(response.body()).getJSONArray("problems").toJSONString()
                        .contains("tasks must be an array"),
                "should point out that tasks must be an array, actual: " + response.body());
        assertNull(meta.getRun(runId));
    }

    // ========== 任务写接口（M2 桶 A） ==========

    private static void insertStoppedRun(String runId) throws Exception {
        SchedulerRun run = new SchedulerRun();
        run.setRunId(runId);
        run.setProjectType("demo");
        run.setStatus("STOPPED");
        run.setWorkDir(tempDir.resolve("work").resolve("runs").resolve(runId).toString());
        run.setCreateTime(LocalDateTime.now());
        run.setUpdateTime(LocalDateTime.now());
        meta.insertRun(run);
    }

    @Test
    @DisplayName("task write API: create -> list -> update -> delete end to end")
    void taskCrudRoundTrip() throws Exception {
        String runId = "20260202000010";
        insertStoppedRun(runId);

        HttpResponse<String> created = post("/scheduler/run/" + runId + "/task/create", """
                {
                  "taskName": "load_fact",
                  "taskScript": "load_fact.hwf",
                  "taskScriptType": "HOP",
                  "taskRunPolicy": "CRONTAB",
                  "taskCrontabExpr": "0 0 3 * * ?",
                  "taskFailPolicy": "STOP",
                  "taskStartupOrder": 20,
                  "taskGroup": "fact"
                }
                """);
        assertEquals(201, created.statusCode(), "creating a task should return 201, actual: " + created.body());
        assertNotNull(meta.getTask(runId, "load_fact"), "the task should be persisted");

        String listBody = get("/scheduler/run/" + runId + "/task/list").body();
        assertTrue(listBody.contains("load_fact"), "the task list should contain the new task, actual: " + listBody);

        HttpResponse<String> updated = post("/scheduler/run/" + runId + "/task/update", """
                { "taskName": "load_fact", "taskCrontabExpr": "0 30 3 * * ?", "taskEnabled": false }
                """);
        assertEquals(200, updated.statusCode(), "updating a task should return 200, actual: " + updated.body());
        SchedulerTask afterUpdate = meta.getTask(runId, "load_fact");
        assertEquals("0 30 3 * * ?", afterUpdate.getTaskCrontabExpr(), "the cron expression should be updated");
        assertFalse(afterUpdate.isTaskEnabled(), "taskEnabled should be updated");
        assertEquals("load_fact.hwf", afterUpdate.getTaskScript(), "fields that were not sent keep their value (partial update)");

        HttpResponse<String> deleted = post("/scheduler/run/" + runId + "/task/delete",
                """
                        { "taskName": "load_fact" }
                        """);
        assertEquals(200, deleted.statusCode(), "deleting a task should return 200, actual: " + deleted.body());
        assertNull(meta.getTask(runId, "load_fact"), "the task should disappear from storage");

        HttpResponse<String> again = post("/scheduler/run/" + runId + "/task/delete",
                """
                        { "taskName": "load_fact" }
                        """);
        assertEquals(404, again.statusCode(), "deleting a second time should return 404, actual: " + again.body());
    }

    @Test
    @DisplayName("task write API validation: type / policy / cron / duplicate / unknown run")
    void taskCrudValidation() throws Exception {
        String runId = "20260202000011";
        insertStoppedRun(runId);

        HttpResponse<String> badType = post("/scheduler/run/" + runId + "/task/create", """
                { "taskName": "t1", "taskScript": "a.sql", "taskScriptType": "SQL" }
                """);
        assertEquals(400, badType.statusCode(), "SQL is not a task type, so 400 is expected, actual: " + badType.body());
        assertTrue(badType.body().contains("taskScriptType"), "should point at the script type, actual: " + badType.body());

        HttpResponse<String> badCron = post("/scheduler/run/" + runId + "/task/create", """
                { "taskName": "t2", "taskScript": "a.hwf", "taskRunPolicy": "CRONTAB" }
                """);
        assertEquals(400, badCron.statusCode(), "CRONTAB without an expression should return 400, actual: " + badCron.body());

        HttpResponse<String> ok = post("/scheduler/run/" + runId + "/task/create", """
                { "taskName": "t3", "taskScript": "a.hwf" }
                """);
        assertEquals(201, ok.statusCode(), "the minimal field set should be accepted, actual: " + ok.body());

        HttpResponse<String> duplicate = post("/scheduler/run/" + runId + "/task/create", """
                { "taskName": "t3", "taskScript": "a.hwf" }
                """);
        assertEquals(409, duplicate.statusCode(), "a duplicate name should return 409, actual: " + duplicate.body());

        HttpResponse<String> unknownRun = post("/scheduler/run/no-such-run/task/create", """
                { "taskName": "t4", "taskScript": "a.hwf" }
                """);
        assertEquals(404, unknownRun.statusCode(), "an unknown run should return 404, actual: " + unknownRun.body());

        HttpResponse<String> caps = get("/scheduler/capabilities");
        assertTrue(caps.body().contains("\"taskCrud\":true"),
                "the capability taskCrud should be true, actual: " + caps.body());
    }

    // ========== 模板写接口（M2） ==========

    @Test
    @DisplayName("template upload -> read back -> delete end to end, re-validated right after upload")
    void templateUploadReadDelete() throws Exception {
        String projectType = "itpl";
        String template = """
                {
                  "tasks": [
                    { "taskName": "t1", "taskScript": "a.hwf", "taskRunPolicy": "CRONTAB",
                      "taskCrontabExpr": "0 0 5 * * ?" }
                  ]
                }
                """;
        JSONObject uploadPayload = new JSONObject();
        uploadPayload.put("path", "conf/defaultSchedulerTask.json");
        uploadPayload.put("content", template);
        String uploadBody = uploadPayload.toJSONString();

        HttpResponse<String> response = post("/scheduler/project/" + projectType + "/upload", uploadBody);
        assertEquals(200, response.statusCode(), "uploading a template should return 200, actual: " + response.body());
        JSONObject body = JSONObject.parseObject(response.body());
        assertEquals(1, body.getIntValue("taskCount"), "one task should be parsed right after upload, actual: " + response.body());

        HttpResponse<String> project = get("/scheduler/project/" + projectType);
        assertTrue(project.body().contains("t1"), "the project detail should contain the template task, actual: " + project.body());

        HttpResponse<String> file = get("/scheduler/project/" + projectType
                + "/file?path=conf/defaultSchedulerTask.json");
        assertEquals(200, file.statusCode(), "reading a template file should return 200, actual: " + file.body());
        assertTrue(file.body().contains("t1"), "the content read back should contain the task name, actual: " + file.body());

        HttpResponse<String> deleted = post("/scheduler/project/" + projectType + "/delete",
                "{\"path\":\"conf/defaultSchedulerTask.json\"}");
        assertEquals(200, deleted.statusCode(), "deleting a template should return 200, actual: " + deleted.body());

        HttpResponse<String> gone = get("/scheduler/project/" + projectType
                + "/file?path=conf/defaultSchedulerTask.json");
        assertEquals(404, gone.statusCode(), "reading after deletion should return 404, actual: " + gone.body());
    }

    @Test
    @DisplayName("template API rejects escaping paths and bad JSON and truthfully declares templates=true")
    void templateRejectsTraversalAndBadJson() throws Exception {
        String projectType = "itpl2";

        HttpResponse<String> traversal = post("/scheduler/project/" + projectType + "/upload",
                "{\"path\":\"../../evil.json\",\"content\":\"{}\"}");
        assertEquals(400, traversal.statusCode(), "an escaping path should return 400, actual: " + traversal.body());

        HttpResponse<String> absolute = get("/scheduler/project/" + projectType + "/file?path=/etc/passwd");
        assertEquals(400, absolute.statusCode(), "an absolute path should return 400, actual: " + absolute.body());

        HttpResponse<String> badJson = post("/scheduler/project/" + projectType + "/upload",
                "{\"path\":\"conf/x.json\",\"content\":\"{ not json\"}");
        assertEquals(400, badJson.statusCode(), "bad JSON should return 400 (validated before writing), actual: " + badJson.body());

        HttpResponse<String> caps = get("/scheduler/capabilities");
        assertTrue(caps.body().contains("\"templates\":true"),
                "the capability templates should be true, actual: " + caps.body());
    }

    @Test
    @DisplayName("/scheduler/project/list is not swallowed by the {projectType} route")
    void projectListRouteIsNotShadowed() throws Exception {
        HttpResponse<String> response = get("/scheduler/project/list");
        assertEquals(200, response.statusCode(),
                "static routes must be registered before parameterised ones, actual: " + response.body());
        assertTrue(response.body().contains("projects"),
                "a projects array is expected, actual: " + response.body());
        assertFalse(response.body().contains("Project not found"),
                "it must not fall through to handleGetProject, actual: " + response.body());
    }

    // ========== 跨 run 执行流水（globalHistory） ==========

    private static void seedHistory(String runId, String taskName, int retCode,
                                    java.time.LocalDateTime start, java.time.LocalDateTime end) throws Exception {
        org.slackerdb.plugins.scheduler.entity.TaskHistory row =
                new org.slackerdb.plugins.scheduler.entity.TaskHistory();
        row.setRunId(runId);
        row.setTaskName(taskName);
        row.setRetCode(retCode);
        row.setRetMsg(retCode == 0 ? "ok" : "boom");
        row.setStartTime(start);
        row.setEndTime(end);
        meta.insertTaskHistory(row);
    }

    @Test
    @DisplayName("globalHistory: newest first across runs, filterable, with success/failure summary")
    void globalHistoryAcrossRuns() throws Exception {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        seedHistory("20260202000020", "t_ok", 0, now.minusMinutes(10), now.minusMinutes(9));
        seedHistory("20260202000021", "t_fail", 1, now.minusMinutes(5), now.minusMinutes(4));

        HttpResponse<String> response = get("/scheduler/history?hours=24&limit=50");
        assertEquals(200, response.statusCode(), "listing history should return 200, actual: " + response.body());
        JSONObject body = JSONObject.parseObject(response.body());
        JSONArray history = body.getJSONArray("history");
        assertNotNull(history, "the history array must be returned");
        assertTrue(history.size() >= 2, "at least two history rows are expected, actual: " + history.size());
        assertEquals("t_fail", history.getJSONObject(0).getString("taskName"),
                "rows should be sorted newest first, actual: " + history.getJSONObject(0));

        JSONObject summary = body.getJSONObject("summary");
        assertEquals(1, summary.getIntValue("success"), "the success count should be 1, actual: " + summary);
        assertEquals(1, summary.getIntValue("failed"), "the failed count should be 1, actual: " + summary);

        HttpResponse<String> filtered = get("/scheduler/history?hours=24&runId=20260202000020");
        assertTrue(JSONObject.parseObject(filtered.body()).getJSONArray("history").toJSONString()
                        .contains("t_ok"),
                "filtering by runId should leave only t_ok, actual: " + filtered.body());

        HttpResponse<String> limited = get("/scheduler/history?hours=24&limit=1");
        assertEquals(1, JSONObject.parseObject(limited.body()).getJSONArray("history").size(),
                "limit should take effect");

        HttpResponse<String> caps = get("/scheduler/capabilities");
        assertTrue(caps.body().contains("\"globalHistory\":true"),
                "the capability globalHistory should be true, actual: " + caps.body());
    }

    // ========== 模板 zip 导入（zipImport） ==========

    private static String zipBase64(java.util.Map<String, String> files) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (java.util.Map.Entry<String, String> entry : files.entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    @Test
    @DisplayName("zipImport: import a whole template package and use it right away")
    void zipImportProject() throws Exception {
        String projectType = "itzip";
        String taskList = """
                { "tasks": [ { "taskName": "zip_task", "taskScript": "scripts/z.hwf" } ] }
                """;
        String base64 = zipBase64(java.util.Map.of(
                "conf/defaultSchedulerTask.json", taskList,
                "scripts/z.hwf", "// hello from zip"));

        JSONObject payload = new JSONObject();
        payload.put("contentBase64", base64);
        HttpResponse<String> response = post("/scheduler/project/" + projectType + "/import", payload.toJSONString());
        assertEquals(200, response.statusCode(), "importing a zip should return 200, actual: " + response.body());
        JSONObject body = JSONObject.parseObject(response.body());
        assertEquals(2, body.getIntValue("count"), "two files should be written, actual: " + response.body());
        assertEquals(1, body.getIntValue("taskCount"), "one task should be parsed right after import, actual: " + response.body());

        HttpResponse<String> project = get("/scheduler/project/" + projectType);
        assertTrue(project.body().contains("zip_task"), "the project detail should contain the imported task, actual: " + project.body());

        HttpResponse<String> caps = get("/scheduler/capabilities");
        assertTrue(caps.body().contains("\"zipImport\":true"), "the capability zipImport should be true, actual: " + caps.body());
    }

    @Test
    @DisplayName("zipImport: rejects escaping entries and bad base64")
    void zipImportRejectsTraversal() throws Exception {
        String projectType = "itzip2";
        String base64 = zipBase64(java.util.Map.of(
                "../evil.txt", "pwned",
                "conf/defaultSchedulerTask.json", "{\"tasks\":[]}"));

        JSONObject payload = new JSONObject();
        payload.put("contentBase64", base64);
        HttpResponse<String> response = post("/scheduler/project/" + projectType + "/import", payload.toJSONString());
        assertEquals(400, response.statusCode(), "an escaping entry should return 400, actual: " + response.body());
        assertFalse(Files.exists(tempDir.resolve("projects").resolve("evil.txt")),
                "an escaping file must never be written outside the project directory");

        HttpResponse<String> badBase64 = post("/scheduler/project/" + projectType + "/import",
                "{\"contentBase64\":\"not-base64!!\"}");
        assertEquals(400, badBase64.statusCode(), "bad base64 should return 400, actual: " + badBase64.body());
    }

    @Test
    @DisplayName("zipImport: a single huge entry is stopped while decompressing (not after filling memory)")
    void zipImportRejectsOversizedEntry() throws Exception {
        // 51 MB 的零字节：deflate 后只有几十 KB，能轻松通过 base64 上传；解压却是 51 MB。
        // 改造前是 readAllBytes() 之后再判大小，这条请求足以把服务端堆打满。
        byte[] oversized = new byte[51 * 1024 * 1024];
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("conf/defaultSchedulerTask.json"));
            zip.write(oversized);
            zip.closeEntry();
        }
        JSONObject payload = new JSONObject();
        payload.put("contentBase64", java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()));

        HttpResponse<String> response = post("/scheduler/project/itzip3/import", payload.toJSONString());
        assertEquals(400, response.statusCode(), "exceeding the decompression limit should return 400, actual: " + response.body());
        assertTrue(response.body().contains("50 MB"), "the error should state the size limit, actual: " + response.body());
        assertFalse(Files.exists(tempDir.resolve("projects").resolve("itzip3")
                        .resolve("conf").resolve("defaultSchedulerTask.json")),
                "a rejected entry must not be written (writing happens after the whole entry is read)");
    }

    // ========== 契约夹具（W0）：字段只增不减 ==========

    /** 递归比较：夹具里的字段必须在实际响应里存在（多出来的新字段允许）。 */
    private static void assertContainsAll(Object expected, Object actual, String path, List<String> missing) {
        if (!(expected instanceof JSONObject eo) || !(actual instanceof JSONObject ao)) {
            return;
        }
        for (String key : eo.keySet()) {
            if (key.startsWith("_")) {
                continue;
            }
            String where = path.isEmpty() ? key : path + "." + key;
            if (!ao.containsKey(key)) {
                missing.add(where);
                continue;
            }
            assertContainsAll(eo.get(key), ao.get(key), where, missing);
        }
    }

    @Test
    @DisplayName("contract fixture: /scheduler/capabilities fields only grow, never shrink")
    void capabilitiesMatchFixture() throws Exception {
        java.nio.file.Path fixture = java.nio.file.Paths.get(
                "..", "dbdesk-docs", "contract", "scheduler-capabilities.v1.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(fixture),
                "no contract fixture (module only checkout?), skipping");

        JSONObject expected = JSONObject.parseObject(
                java.nio.file.Files.readString(fixture, java.nio.charset.StandardCharsets.UTF_8));
        JSONObject actual = JSONObject.parseObject(get("/scheduler/capabilities").body());

        List<String> missing = new java.util.ArrayList<>();
        assertContainsAll(expected, actual, "", missing);
        assertTrue(missing.isEmpty(), "the actual response is missing contract fields (breaking change): " + missing);
    }
}
