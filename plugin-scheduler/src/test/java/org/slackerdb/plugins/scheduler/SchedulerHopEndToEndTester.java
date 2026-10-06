package org.slackerdb.plugins.scheduler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slackerdb.plugins.scheduler.meta.SchedulerMeta;
import org.slackerdb.plugins.scheduler.quartz.DynamicQuartzScheduler;
import org.slackerdb.plugins.scheduler.runner.TaskRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 端到端验收程序（<b>手动运行</b>，不在 {@code mvn test} 中执行）：真实启动 HTTP 接口 + Quartz + 元数据，
 * 用一个真实的 HOP 工作流跑一次打印任务，并校验日志、历史与接口返回值。
 *
 * <p>与自动化测试的区别：本程序要求本机装有 HOP，且会留下可人工检查的工作目录
 * （{@code plugin-scheduler/target/e2e-home}）。</p>
 *
 * <p><b>运行方式</b>：</p>
 * <pre>
 * mvn -pl plugin-scheduler -am -DskipTests test-compile
 * mvn -pl plugin-scheduler exec:java -Dexec.args="C:/Softwares/hop/hop-run.bat"
 * </pre>
 * <p>参数为 {@code hop-run.bat}/{@code hop-run.sh} 的路径，省略时用系统属性
 * {@code -Dscheduler.hop.runScript=...}，再省略则用默认值 {@value #DEFAULT_HOP_RUN_SCRIPT}。</p>
 */
public final class SchedulerHopEndToEndTester {

    private static final int PORT = 19871;
    private static final String BASE_URL = "http://127.0.0.1:" + PORT;
    private static final String DEFAULT_HOP_RUN_SCRIPT = "C:/Softwares/hop/hop-run.bat";

    /** 模板名（也是 HOP 的项目名）。 */
    private static final String PROJECT_TYPE = "e2e";
    /** 任务名（任务清单里的 key）。 */
    private static final String TASK_NAME = "打印任务";
    /** 工作流文件名。 */
    private static final String WORKFLOW_FILE = "print.hwf";
    /** 变量名与期望打印内容（同时验证变量链路）。 */
    private static final String MESSAGE_VAR = "E2E_MESSAGE";
    private static final String MESSAGE_FROM_TEMPLATE = "来自模板变量（应被实例参数覆盖）";
    private static final String MESSAGE_FROM_RUN = "Hello from slackerdb scheduler E2E";

    private static int passed = 0;
    private static int failed = 0;

    private static Path home;
    private static Path projectHome;
    private static Connection connection;
    private static Javalin app;
    private static DynamicQuartzScheduler quartzScheduler;
    private static SchedulerMeta meta;
    private static HttpClient httpClient;
    private static String hopRunScript;

    public static void main(String[] args) {
        try {
            hopRunScript = resolveHopRunScript(args);
            System.out.println("============================================================");
            System.out.println(" 调度器端到端验收（真实 HOP）");
            System.out.println("============================================================");
            System.out.println(" hop-run   : " + hopRunScript);
            System.out.println(" 工作目录   : " + Paths.get("target/e2e-home").toAbsolutePath());
            System.out.println();

            prepareTemplate();
            startEnvironment();
            runScenario();
        } catch (Throwable t) {
            failed++;
            System.out.println();
            System.out.println("!! 执行失败: " + t);
            t.printStackTrace(System.out);
        } finally {
            teardown();
            System.out.println();
            System.out.println("============================================================");
            System.out.println(" 结果: 通过 " + passed + " 项，失败 " + failed + " 项");
            System.out.println("============================================================");
            System.exit(failed == 0 ? 0 : 1);
        }
    }

    // ==================================================================
    // 准备：模板与运行环境
    // ==================================================================

    /**
     * 定位工作目录：优先 {@code -De2e.home=...}，否则按当前工作目录判断
     * （在仓库根运行 → {@code plugin-scheduler/target/e2e-home}；在模块内运行 → {@code target/e2e-home}）。
     */
    private static Path resolveHome() {
        String override = System.getProperty("e2e.home");
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath();
        }
        Path cwd = Paths.get("").toAbsolutePath();
        if ("plugin-scheduler".equals(String.valueOf(cwd.getFileName()))) {
            return cwd.resolve("target/e2e-home");
        }
        return cwd.resolve("plugin-scheduler/target/e2e-home");
    }

    /** 解析 hop-run 路径：命令行参数 > 系统属性 > 默认值。 */    private static String resolveHopRunScript(String[] args) {
        String script = args != null && args.length > 0 && !args[0].isBlank()
                ? args[0].trim()
                : System.getProperty("scheduler.hop.runScript", DEFAULT_HOP_RUN_SCRIPT);
        Path path = Paths.get(script);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("找不到 hop-run 脚本: " + path
                    + "\n请把 HOP 发行版的路径作为参数传入，例如："
                    + "\n  mvn -pl plugin-scheduler exec:java -Dexec.args=\"D:/hop/hop-run.bat\"");
        }
        return path.toAbsolutePath().toString();
    }

    /** 生成一个最小项目模板：一个 RUN_ONCE 任务 + 一个只做打印的 HOP 工作流。 */
    private static void prepareTemplate() throws Exception {
        home = resolveHome();
        deleteRecursively(home);
        projectHome = home.resolve("projects");
        // 模板目录本身就是 HOP 工程目录（与真实模板一致）
        Path template = projectHome.resolve(PROJECT_TYPE);
        Path conf = template.resolve("conf");
        Files.createDirectories(conf);
        Files.createDirectories(template.resolve("metadata/workflow-run-configuration"));
        Files.createDirectories(template.resolve("metadata/pipeline-run-configuration"));

        step("1. 准备项目模板（" + template + "）");

        // 任务清单：一个 RUN_ONCE 打印任务
        Files.writeString(conf.resolve("defaultSchedulerTask.json"), """
                {
                  "groups": ["MAIN_GROUP"],
                  "%s": {
                    "taskScript": "%s",
                    "taskScriptType": "HOP",
                    "taskRunPolicy": "RUN_ONCE",
                    "taskFailPolicy": "STOP",
                    "taskTimeout": 600,
                    "taskGroup": "MAIN_GROUP",
                    "taskMandatory": true,
                    "taskDescription": "端到端验收：打印一行日志"
                  }
                }
                """.formatted(TASK_NAME, WORKFLOW_FILE), StandardCharsets.UTF_8);

        // 模板级变量（会被实例级 configJson 覆盖，用于验证变量优先级）
        Files.writeString(conf.resolve("variables.properties"),
                MESSAGE_VAR + "=" + MESSAGE_FROM_TEMPLATE + "\n", StandardCharsets.UTF_8);

        // HOP 工程：项目配置 + runconfig + 只做打印的工作流（无父项目）
        Files.writeString(template.resolve("project-config.json"), """
                { "metadataBaseFolder": "${PROJECT_HOME}/metadata",
                  "unitTestsBasePath": "${PROJECT_HOME}",
                  "dataSetsCsvFolder": "${PROJECT_HOME}/datasets",
                  "enforcingExecutionInHome": true,
                  "parentProjectName": "",
                  "config": { "variables": [] } }
                """, StandardCharsets.UTF_8);
        Files.writeString(template.resolve("metadata/workflow-run-configuration/local.json"), """
                { "engineRunConfiguration": { "Local": { "safe_mode": false } },
                  "name": "local", "description": "", "defaultSelection": true }
                """, StandardCharsets.UTF_8);
        Files.writeString(template.resolve("metadata/pipeline-run-configuration/local.json"), """
                { "engineRunConfiguration": { "Local": { "safe_mode": false, "transactional": false } },
                  "name": "local", "description": "", "defaultSelection": true }
                """, StandardCharsets.UTF_8);
        Files.writeString(template.resolve(WORKFLOW_FILE), workflowXml(), StandardCharsets.UTF_8);

        System.out.println("   模板目录  : " + template);
        System.out.println("   任务清单  : " + conf.resolve("defaultSchedulerTask.json"));
        System.out.println("   工作流    : " + template.resolve(WORKFLOW_FILE));
        pass("项目模板已生成");
    }

    /** 启动元数据、Quartz 与 HTTP 接口（与插件运行时的装配一致）。 */
    private static void startEnvironment() throws Exception {
        step("2. 启动调度环境（元数据 + Quartz + HTTP 接口，端口 " + PORT + "）");

        Class.forName("org.duckdb.DuckDBDriver");
        connection = DriverManager.getConnection("jdbc:duckdb:");
        connection.setAutoCommit(true);
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS sysaux");
        }
        Logger logger = LoggerFactory.getLogger("e2e");
        meta = new SchedulerMeta(connection, logger);
        meta.initializeTables();
        System.out.println("   元数据表  : sysaux.v$scheduler_run / task / task_history");

        quartzScheduler = new DynamicQuartzScheduler(logger, 10, meta);
        quartzScheduler.start();

        app = Javalin.create(cfg -> cfg.startup.showJavalinBanner = false);
        app.start(PORT);

        TaskRequest.HopConfig hopConfig = new TaskRequest.HopConfig();
        hopConfig.runScript = hopRunScript;
        hopConfig.projectName = "${PROJECT_TYPE}";
        hopConfig.environmentName = "${RUN_ID}-${TASK_NAME}";
        new org.slackerdb.plugins.scheduler.controller.SchedulerController(
                app, meta, quartzScheduler, logger,
                home.toString(), projectHome.toString(), hopConfig).registerRoutes();

        httpClient = HttpClient.newHttpClient();
        pass("环境已启动");
    }

    // ==================================================================
    // 场景：创建实例 → 启动 → 校验
    // ==================================================================

    private static void runScenario() throws Exception {
        step("3. 创建实例（POST /scheduler/run/create）");
        JSONObject createBody = new JSONObject();
        createBody.put("projectType", PROJECT_TYPE);
        // 实例级变量：覆盖模板里的同名变量
        createBody.put("configJson", "{\"" + MESSAGE_VAR + "\":\"" + MESSAGE_FROM_RUN + "\"}");
        HttpResponse<String> createResponse = post("/scheduler/run/create", createBody.toJSONString());
        System.out.println("   HTTP " + createResponse.statusCode() + " -> " + createResponse.body());
        if (createResponse.statusCode() != 201) {
            fail("创建实例失败");
            return;
        }
        String runId = JSON.parseObject(createResponse.body()).getString("runId");
        pass("实例已创建: " + runId);

        Path runHome = home.resolve("runs").resolve(runId);
        System.out.println("   实例目录  : " + runHome);

        step("4. 检查实例目录与任务清单");
        check("HOP 工程已拷贝", Files.isRegularFile(runHome.resolve("flow").resolve(PROJECT_TYPE)
                .resolve(WORKFLOW_FILE)), runHome.resolve("flow").resolve(PROJECT_TYPE).toString());
        check("项目配置已拷贝", Files.isRegularFile(runHome.resolve("flow").resolve(PROJECT_TYPE)
                .resolve("project-config.json")));
        String tasks = get("/scheduler/run/" + runId + "/task/list").body();
        check("任务清单已载入", tasks.contains(TASK_NAME), tasks);

        step("5. 启动实例（POST /scheduler/run/" + runId + "/start）");
        HttpResponse<String> startResponse = post("/scheduler/run/" + runId + "/start", "{}");
        System.out.println("   HTTP " + startResponse.statusCode() + " -> " + startResponse.body());
        check("启动接口已接受", startResponse.statusCode() == 200);

        step("6. 等待任务执行完成（真实调用 hop-run，最多等 180 秒）");
        String finalStatus = waitForRunStatus(runId, 180);
        System.out.println("   实例最终状态: " + finalStatus);
        check("实例状态为 STARTED", "STARTED".equalsIgnoreCase(finalStatus));

        step("7. 校验执行结果");
        String history = get("/scheduler/run/" + runId + "/task/history").body();
        System.out.println("   历史记录  : " + history);
        check("历史中有该任务的执行记录", history.contains(TASK_NAME));
        check("执行结果为成功（retCode=0）", history.contains("\"retCode\":0"), history);

        String taskList = get("/scheduler/run/" + runId + "/task/list").body();
        check("任务列表显示上次执行成功", taskList.contains("\"lastRetCode\":0"), shorten(taskList, 400));

        Path logFile = runHome.resolve("logs").resolve(TASK_NAME + ".log");
        check("任务日志文件已生成", Files.isRegularFile(logFile), logFile.toString());
        String logContent = Files.isRegularFile(logFile)
                ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
        System.out.println("   ---- 任务日志（尾部）----");
        for (String line : tail(logContent, 8).split("\n")) {
            System.out.println("   | " + line);
        }
        System.out.println("   ------------------------");
        check("日志中出现工作流打印的内容", logContent.contains(MESSAGE_FROM_RUN),
                "期望包含: " + MESSAGE_FROM_RUN);
        check("变量优先级正确（实例参数覆盖模板变量）",
                logContent.contains(MESSAGE_FROM_RUN) && !logContent.contains(MESSAGE_FROM_TEMPLATE));

        String logApi = get("/scheduler/run/" + runId + "/log?taskName=" + TASK_NAME + "&tail=50").body();
        check("日志查询接口可用", logApi.contains(MESSAGE_FROM_RUN), shorten(logApi, 300));

        String variables = get("/scheduler/run/" + runId + "/variables").body();
        check("变量查询接口可用", variables.contains(MESSAGE_VAR), shorten(variables, 300));

        String cron = get("/scheduler/describeCrontabExpr?expr=" + encode("0 1/2 * * * ? *")).body();
        check("cron 校验接口可用", cron.contains("\"valid\":true"), shorten(cron, 300));

        step("8. 停止并删除实例");
        check("停止接口可用", post("/scheduler/run/" + runId + "/stop", "{}").statusCode() == 200);
        // 删除会连运行目录一起清掉，先把本次执行的产物留一份供人工检查
        Path kept = home.resolve("kept").resolve(runId);
        copyDirectory(runHome, kept);
        System.out.println("   已留存本次产物: " + kept);
        check("删除接口可用", post("/scheduler/run/" + runId + "/drop", "{}").statusCode() == 200);
        check("删除后运行目录已清理", !Files.exists(runHome));
    }

    /** 轮询实例状态，直到进入终态（STARTED/FAILED）或超时。 */
    private static String waitForRunStatus(String runId, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        String status = "UNKNOWN";
        int lastPrinted = -1;
        while (System.currentTimeMillis() < deadline) {
            status = JSON.parseObject(get("/scheduler/run/" + runId).body()).getString("status");
            if ("STARTED".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)) {
                return status;
            }
            int elapsed = (int) ((System.currentTimeMillis() - (deadline - timeoutSeconds * 1000L)) / 1000);
            if (elapsed / 10 != lastPrinted) {
                lastPrinted = elapsed / 10;
                System.out.println("   ... 当前状态 " + status + "，已等待 " + elapsed + " 秒");
            }
            Thread.sleep(1000);
        }
        return status;
    }

    // ==================================================================
    // HTTP 与输出工具
    // ==================================================================

    private static HttpResponse<String> get(String path) throws Exception {
        return httpClient.send(HttpRequest.newBuilder().uri(URI.create(BASE_URL + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ");
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + " ...";
    }

    private static String tail(String text, int lines) {
        String[] all = text.split("\n");
        int from = Math.max(0, all.length - lines);
        return String.join("\n", List.of(all).subList(from, all.length));
    }

    private static void step(String title) {
        System.out.println();
        System.out.println("--- " + title + " ---");
    }

    private static void pass(String message) {
        pass(message, null);
    }

    /** 条件断言：条件成立记"通过"，否则记"失败"。 */
    private static void check(String message, boolean ok) {
        check(message, ok, null);
    }

    /** 条件断言（带补充信息，失败时打印）。 */
    private static void check(String message, boolean ok, String detail) {
        if (ok) {
            pass(message, detail);
        } else {
            fail(message + (detail == null ? "" : "  :: " + shorten(detail, 300)));
        }
    }

    private static void pass(String message, String detail) {
        passed++;
        System.out.println("   [OK]   " + message + (detail == null ? "" : "  :: " + shorten(detail, 200)));
    }

    private static void fail(String message) {
        failed++;
        System.out.println("   [FAIL] " + message);
    }

    private static void teardown() {
        System.out.println();
        System.out.println("--- 清理运行环境 ---");
        try {
            if (app != null) {
                app.stop();
            }
        } catch (Exception ignored) {
        }
        try {
            if (quartzScheduler != null) {
                quartzScheduler.shutdown();
            }
        } catch (Exception ignored) {
        }
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (Exception ignored) {
        }
        if (home != null) {
            System.out.println("   工作目录保留供人工检查: " + home);
        }
    }

    /** 最小 HOP 工作流：Start → WriteToLog（打印变量）→ Success，不依赖任何数据库。 */
    private static String workflowXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <workflow>
                  <name>%s</name>
                  <name_sync_with_filename>N</name_sync_with_filename>
                  <description/>
                  <extended_description/>
                  <workflow_version/>
                  <created_user>-</created_user>
                  <created_date>2025/01/01 00:00:00.000</created_date>
                  <modified_user>-</modified_user>
                  <modified_date>2025/01/01 00:00:00.000</modified_date>
                  <parameters/>
                  <actions>
                    <action>
                      <name>Start</name>
                      <description/>
                      <type>SPECIAL</type>
                      <attributes/>
                      <DayOfMonth>1</DayOfMonth><doNotWaitOnFirstExecution>N</doNotWaitOnFirstExecution>
                      <hour>12</hour><intervalMinutes>60</intervalMinutes><intervalSeconds>0</intervalSeconds>
                      <minutes>0</minutes><repeat>N</repeat><schedulerType>0</schedulerType><weekDay>1</weekDay>
                      <parallel>N</parallel><xloc>160</xloc><yloc>240</yloc><attributes_hac/>
                    </action>
                    <action>
                      <name>打印</name>
                      <description/>
                      <type>WRITE_TO_LOG</type>
                      <attributes/>
                      <loglevel>Basic</loglevel>
                      <logmessage>E2E-PRINT: ${%s} (run=${RUN_ID} task=${TASK_NAME})</logmessage>
                      <logsubject/>
                      <parallel>N</parallel><xloc>320</xloc><yloc>240</yloc><attributes_hac/>
                    </action>
                    <action>
                      <name>Success</name>
                      <description/>
                      <type>SUCCESS</type>
                      <attributes/>
                      <parallel>N</parallel><xloc>480</xloc><yloc>240</yloc><attributes_hac/>
                    </action>
                  </actions>
                  <hops>
                    <hop><from>Start</from><to>打印</to><enabled>Y</enabled><evaluation>Y</evaluation><unconditional>Y</unconditional></hop>
                    <hop><from>打印</from><to>Success</to><enabled>Y</enabled><evaluation>Y</evaluation><unconditional>Y</unconditional></hop>
                  </hops>
                  <notepads/>
                  <attributes/>
                </workflow>
                """.formatted(WORKFLOW_FILE.replace(".hwf", ""), MESSAGE_VAR);
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }

    /** 供将来复用：拷贝目录（当前模板不需要，保留以对齐其他 Tester 的实现风格）。 */
    @SuppressWarnings("unused")
    private static void copyDirectory(Path source, Path target) throws Exception {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
