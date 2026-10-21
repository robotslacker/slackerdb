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
    @DisplayName("/scheduler/capabilities 声明契约版本、能力开关、Quartz 真实状态与 HOP 就绪")
    void capabilitiesDeclareContractAndReadiness() throws Exception {
        HttpResponse<String> response = get("/scheduler/capabilities");
        assertEquals(200, response.statusCode(), "GET /scheduler/capabilities 应为 200");

        JSONObject capabilities = JSONObject.parseObject(response.body());
        assertEquals(1, capabilities.getIntValue("contractVersion"), "契约版本应为 1");

        JSONObject features = capabilities.getJSONObject("features");
        assertNotNull(features, "必须声明 features");
        assertTrue(features.getBooleanValue("runCrud"), "实例操作已可用");
        assertFalse(features.getBooleanValue("taskCrud"), "任务写接口尚未提供，必须如实声明为 false");

        JSONObject quartzInfo = capabilities.getJSONObject("quartz");
        assertTrue(quartzInfo.getBooleanValue("started"), "Quartz 应报告为已启动（不再是硬编码常量）");
        assertEquals(4, quartzInfo.getIntValue("threads"));

        JSONObject hop = capabilities.getJSONObject("hop");
        assertNotNull(hop, "必须声明 HOP 就绪判定");
        assertFalse(hop.getBooleanValue("ready"), "未配置 hop.runScript 时 ready 必须为 false");
        JSONArray reasons = hop.getJSONArray("reasons");
        assertNotNull(reasons, "未就绪时必须给出原因");
        assertTrue(reasons.size() >= 1, "未就绪时 reasons 不能为空");
    }

    @Test
    @DisplayName("/scheduler/state/lost 报出'定义还在、调度已丢'的任务")
    void lostSchedulesReportsDefinitionWithoutTrigger() throws Exception {
        HttpResponse<String> response = get("/scheduler/state/lost");
        assertEquals(200, response.statusCode(), "GET /scheduler/state/lost 应为 200");

        JSONArray lost = JSONObject.parseObject(response.body()).getJSONArray("lostSchedules");
        assertNotNull(lost, "响应必须包含 lostSchedules 数组");

        boolean found = false;
        for (int i = 0; i < lost.size(); i++) {
            JSONObject item = lost.getJSONObject(i);
            if ("load_dim".equals(item.getString("taskName"))) {
                found = true;
                assertEquals("20260101000000", item.getString("runId"));
                assertEquals("dim", item.getString("taskGroup"));
            }
        }
        assertTrue(found, "启用了但没有登记到 Quartz 的任务必须出现在 lostSchedules 中；实际："
                + lost.toJSONString());
    }

    @Test
    @DisplayName("API 内联任务：不写 taskScriptType 时默认 HOP（与模板同源），小写类型归一化为大写")
    void inlineTasksDefaultToHopAndAreNormalized() throws Exception {
        String runId = "20260202000001";
        HttpResponse<String> response = post("/scheduler/run/create", """
                {
                  "runId": "%s",
                  "projectType": "demo",
                  "tasks": [
                    { "taskName": "无类型", "taskScript": "echo hi" },
                    { "taskName": "小写类型", "taskScript": "echo hi", "taskScriptType": "command" }
                  ]
                }
                """.formatted(runId));
        assertEquals(201, response.statusCode(), "合法任务应创建成功，实际响应: " + response.body());
        assertEquals(2, JSONObject.parseObject(response.body()).getIntValue("taskCount"));

        List<SchedulerTask> tasks = meta.getTasksByRunId(runId);
        assertEquals(2, tasks.size(), "两个任务都应入库");
        for (SchedulerTask task : tasks) {
            if ("无类型".equals(task.getTaskName())) {
                // 曾经这里默认落成 SQL（运行期必然被拒），现在与模板统一默认 HOP
                assertEquals("HOP", task.getTaskScriptType(),
                        "不写 taskScriptType 时应默认 HOP（与 TaskTemplateLoader 同源），实际: "
                                + task.getTaskScriptType());
            } else {
                assertEquals("COMMAND", task.getTaskScriptType(),
                        "小写类型应归一化为大写，实际: " + task.getTaskScriptType());
            }
        }
    }

    @Test
    @DisplayName("API 内联任务不合格时整单 400（含 SQL），且不留下实例目录/运行记录")
    void invalidInlineTasksAreRejectedWithoutSideEffects() throws Exception {
        String runId = "20260202000002";
        HttpResponse<String> response = post("/scheduler/run/create", """
                {
                  "runId": "%s",
                  "projectType": "demo",
                  "tasks": [
                    { "taskName": "sql任务", "taskScript": "select 1", "taskScriptType": "SQL" },
                    { "taskName": "缺cron", "taskScript": "x.hwf", "taskRunPolicy": "CRONTAB" }
                  ]
                }
                """.formatted(runId));
        assertEquals(400, response.statusCode(), "不合格的任务定义必须整单拒绝，实际响应: " + response.body());

        JSONObject body = JSONObject.parseObject(response.body());
        JSONArray problems = body.getJSONArray("problems");
        assertNotNull(problems, "拒绝时必须逐条给出问题，实际响应: " + response.body());
        assertEquals(2, problems.size(), "两条不合格任务应各报一条，实际: " + problems);
        String text = problems.toJSONString();
        assertTrue(text.contains("SQL"), "应指出 SQL 不受支持，实际: " + problems);
        assertTrue(text.contains("CRONTAB"), "应指出 CRONTAB 缺少表达式，实际: " + problems);

        // 关键：先校验后落地 —— 不能留下“建了一半”的实例
        assertNull(meta.getRun(runId), "任务定义不合格时不应写入运行记录");
        assertFalse(Files.exists(tempDir.resolve("work").resolve("runs").resolve(runId)),
                "任务定义不合格时不应创建实例目录");
    }

    @Test
    @DisplayName("API 内联任务支持与模板相同的字段：taskRetryTimes/Interval、taskMandatory、任务级 variables")
    void inlineTasksSupportRetryMandatoryAndVariables() throws Exception {
        String runId = "20260202000003";
        HttpResponse<String> response = post("/scheduler/run/create", """
                {
                  "runId": "%s",
                  "projectType": "demo",
                  "tasks": [
                    {
                      "taskName": "重试任务",
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
        assertEquals(201, response.statusCode(), "合法任务应创建成功，实际响应: " + response.body());

        List<SchedulerTask> tasks = meta.getTasksByRunId(runId);
        assertEquals(1, tasks.size());
        SchedulerTask task = tasks.get(0);
        assertEquals(3, task.getTaskRetryTimes(), "taskRetryTimes 必须落库（曾被静默丢弃）");
        assertEquals(15, task.getTaskRetryInterval(), "taskRetryInterval 必须落库（曾被静默丢弃）");
        assertTrue(task.isTaskMandatory(), "taskMandatory 必须落库（否则必选语义丢失）");

        JSONObject variables = JSONObject.parseObject(task.getVariablesJson());
        assertNotNull(variables, "任务级 variables 应被拍平写入 variables_json，实际: " + task.getVariablesJson());
        assertEquals("10.0.0.1", variables.getString("BIZ_HOST"));
        assertEquals("1521", variables.getString("BIZ_PORT"), "数字值应转成字符串");
    }

    @Test
    @DisplayName("RETRY 却 retryTimes<=0、嵌套 variables、非布尔 mandatory：整单 400")
    void invalidRetryAndVariablesAreRejected() throws Exception {
        String runId = "20260202000004";
        HttpResponse<String> response = post("/scheduler/run/create", """
                {
                  "runId": "%s",
                  "projectType": "demo",
                  "tasks": [
                    { "taskName": "空重试", "taskScript": "echo hi", "taskFailPolicy": "RETRY" },
                    { "taskName": "嵌套变量", "taskScript": "echo hi",
                      "variables": { "NESTED": { "a": 1 } } },
                    { "taskName": "坏必选", "taskScript": "echo hi", "taskMandatory": "yes" }
                  ]
                }
                """.formatted(runId));
        assertEquals(400, response.statusCode(),
                "不可重试的 RETRY 与非法 variables/mandatory 必须整单拒绝，实际响应: " + response.body());

        String problems = JSONObject.parseObject(response.body()).getJSONArray("problems").toJSONString();
        assertTrue(problems.contains("taskRetryTimes"),
                "应指出 RETRY 缺少 taskRetryTimes（运行期会静默降级），实际: " + problems);
        assertTrue(problems.contains("variables[NESTED]"), "应指出嵌套变量不受支持，实际: " + problems);
        assertTrue(problems.contains("taskMandatory"), "应指出 taskMandatory 必须是布尔值，实际: " + problems);
        assertNull(meta.getRun(runId), "拒绝时不应写入运行记录");
    }

    @Test
    @DisplayName("内联任务出现未知字段（拼错）时 400，不再静默忽略")
    void unknownInlineTaskFieldIsRejected() throws Exception {
        String runId = "20260202000005";
        HttpResponse<String> response = post("/scheduler/run/create", """
                {
                  "runId": "%s",
                  "projectType": "demo",
                  "tasks": [
                    { "taskName": "拼错字段", "taskScript": "echo hi", "taskTimeouts": 600 }
                  ]
                }
                """.formatted(runId));
        assertEquals(400, response.statusCode(), "未知字段必须被拒绝，实际响应: " + response.body());

        String problems = JSONObject.parseObject(response.body()).getJSONArray("problems").toJSONString();
        assertTrue(problems.contains("未知字段[taskTimeouts]"),
                "应指出拼错的字段名（taskTimeouts ≠ taskTimeout），实际: " + problems);
        assertNull(meta.getRun(runId), "拒绝时不应写入运行记录");
    }

    @Test
    @DisplayName("创建实例请求顶层字段拼错（task 而非 tasks）时 400，不静默回落到模板任务")
    void unknownRunBodyFieldIsRejected() throws Exception {
        String runId = "20260202000006";
        HttpResponse<String> response = post("/scheduler/run/create", """
                {
                  "runId": "%s",
                  "projectType": "demo",
                  "task": [ { "taskName": "被拼错的任务", "taskScript": "echo hi" } ]
                }
                """.formatted(runId));
        assertEquals(400, response.statusCode(),
                "顶层未知字段必须被拒绝（否则会静默回落到模板任务），实际响应: " + response.body());

        JSONObject body = JSONObject.parseObject(response.body());
        assertEquals("invalid request", body.getString("error"));
        assertTrue(body.getJSONArray("problems").toJSONString().contains("未知字段[task]"),
                "应指出顶层拼错的字段，实际: " + body.getJSONArray("problems"));
        assertNull(meta.getRun(runId), "拒绝时不应写入运行记录");
        assertFalse(Files.exists(tempDir.resolve("work").resolve("runs").resolve(runId)),
                "拒绝时不应创建实例目录");
    }

    @Test
    @DisplayName("tasks 不是数组时 400（而不是 500 或静默回落）")
    void nonArrayTasksAreRejected() throws Exception {
        String runId = "20260202000007";
        HttpResponse<String> response = post("/scheduler/run/create", """
                {
                  "runId": "%s",
                  "projectType": "demo",
                  "tasks": { "taskName": "对象形式", "taskScript": "echo hi" }
                }
                """.formatted(runId));
        assertEquals(400, response.statusCode(), "tasks 不是数组时应 400，实际响应: " + response.body());
        assertTrue(JSONObject.parseObject(response.body()).getJSONArray("problems").toJSONString()
                        .contains("tasks 必须是数组"),
                "应指出 tasks 必须是数组，实际: " + response.body());
        assertNull(meta.getRun(runId));
    }
}
