package org.slackerdb.plugins.scheduler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import org.slackerdb.plugins.scheduler.entity.SchedulerTask;
import org.slackerdb.plugins.scheduler.entity.TaskHistory;
import org.slackerdb.plugins.scheduler.meta.SchedulerMeta;
import org.slackerdb.plugins.scheduler.meta.TaskTemplateLoader;
import org.slackerdb.plugins.scheduler.quartz.DynamicQuartzScheduler;
import org.slackerdb.plugins.scheduler.quartz.RunOrchestrator;
import org.slackerdb.plugins.scheduler.runner.HopCommandBuilder;
import org.slackerdb.plugins.scheduler.runner.ProcessTaskExecutor;
import org.slackerdb.plugins.scheduler.runner.TaskRequest;
import org.slackerdb.plugins.scheduler.runner.TaskResult;
import org.slackerdb.plugins.scheduler.runner.VariableSet;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 调度引擎回归测试（不依赖数据库服务器，直接驱动引擎内部构件）。
 *
 * <p>覆盖四层：变量机制、模板契约、HOP 运行环境（命令行方案）、Quartz 调度生命周期与元数据。
 * 其中 {@link #realHopRunsWorkflowWithInjectedVariables} 需要本机安装 Hop，未安装时自动跳过
 * （可用 {@code -Dscheduler.test.hopRunScript=<path>} 指定 hop-run 脚本位置）。</p>
 */
class SchedulerEngineTest {

    private static final String HOP_RUN_SCRIPT =
            System.getProperty("scheduler.test.hopRunScript", "C:/Softwares/hop/hop-run.bat");

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // ==================================================================
    // 1. 变量机制
    // ==================================================================

    @Test
    void variableSetMergesExpandsAndMasks() {
        Map<String, String> global = new LinkedHashMap<>();
        global.put("DB_HOST", "1.2.3.4");
        global.put("DB_PASSWORD", "s3cret");
        Map<String, String> template = new LinkedHashMap<>();
        template.put("DB_PORT", "5432");
        template.put("DB_HOST", "9.9.9.9");                 // 模板层覆盖全局层
        Map<String, String> runParams = new LinkedHashMap<>();
        runParams.put("TAG", "${RUN_ID}-${PROJECT_TYPE}");
        runParams.put("SELF", "${TAG}/x");                  // 二层引用
        Map<String, String> framework = new LinkedHashMap<>();
        framework.put(VariableSet.RUN_ID, "R1");
        framework.put(VariableSet.PROJECT_TYPE, "demo");

        VariableSet vars = VariableSet.resolve(global, template, runParams, framework);

        verify("模板层覆盖全局层", "9.9.9.9", vars.get("DB_HOST"));
        verify("新增键保留", "5432", vars.get("DB_PORT"));
        verify("引用框架变量展开", "R1-demo", vars.get("TAG"));
        verify("二层引用展开", "R1-demo/x", vars.get("SELF"));
        verify("口令脱敏", "******", vars.masked().get("DB_PASSWORD"));
        verify("脱敏不影响原值", "s3cret", vars.get("DB_PASSWORD"));
        verify("未定义引用保持原样", "${NOPE}", VariableSet.resolve(Map.of("A", "${NOPE}")).get("A"));

        Map<String, String> parsed = VariableSet.parseProperties(
                List.of("# 注释", "A=1", "  B = 2 ", "", "novalue"));
        assertEquals(2, parsed.size(), "properties 解析应跳过注释/空行/无等号行");
        verify("properties 去空白", "2", parsed.get("B"));
    }

    // ==================================================================
    // 2. 模板任务清单契约
    // ==================================================================

    @Test
    void templateLoaderParsesKeyedAndArrayForms(@TempDir Path tempDir) throws Exception {
        Path projectHome = tempDir.resolve("projects");

        // 主格式：按任务名 keyed
        Path demoConf = projectHome.resolve("demo/conf");
        Files.createDirectories(demoConf);
        Files.writeString(demoConf.resolve(TaskTemplateLoader.FILE_NAME), """
                {
                  "主流程": {
                    "taskScript": "主流程.hwf",
                    "taskScriptType": "HOP",
                    "taskRunPolicy": "CRONTAB",
                    "taskCrontabExpr": "0 1/2 * * * ? *",
                    "taskParallelPolicy": "SERIAL_DISCARD",
                    "taskFailPolicy": "CONTINUE",
                    "taskTimeout": 600,
                    "taskGroup": "MAIN_GROUP",
                    "taskMandatory": true,
                    "variables": { "ODS_HOST": "10.0.0.1", "ODS_PORT": 5432 }
                  },
                  "报表": { "taskScript": "报表.hwf" }
                }
                """, StandardCharsets.UTF_8);

        TaskTemplateLoader.Loaded keyed = TaskTemplateLoader.load(projectHome, "demo", "R1");
        assertNotNull(keyed.file(), "应找到模板任务清单");
        assertEquals(2, keyed.tasks().size());
        SchedulerTask main = keyed.tasks().get(0);
        verify("key 即任务名", "主流程", main.getTaskName());
        verify("注入 runId", "R1", main.getRunId());
        verify("cron 解析", "0 1/2 * * * ? *", main.getTaskCrontabExpr());
        assertTrue(main.isTaskMandatory(), "taskMandatory 应解析");
        verify("分组解析", "MAIN_GROUP", main.getTaskGroup());
        assertNotNull(main.getVariablesJson(), "任务级变量应保留");
        assertTrue(main.getVariablesJson().contains("ODS_HOST") && main.getVariablesJson().contains("10.0.0.1"),
                "任务级变量内容应包含 ODS_HOST=10.0.0.1，实际: " + main.getVariablesJson());
        SchedulerTask report = keyed.tasks().get(1);
        verify("默认运行策略", "RUN_ONCE", report.getTaskRunPolicy());
        verify("默认并发策略", "PARALLEL", report.getTaskParallelPolicy());
        verify("默认失败策略", "CONTINUE", report.getTaskFailPolicy());
        verify("默认脚本类型", "HOP", report.getTaskScriptType());

        // 兼容数组形式
        Path arrConf = projectHome.resolve("arr/conf");
        Files.createDirectories(arrConf);
        Files.writeString(arrConf.resolve(TaskTemplateLoader.FILE_NAME), """
                {
                  "tasks": [
                    { "taskName": "A", "taskScript": "a.hwf", "taskRunPolicy": "INTERVAL", "taskInterval": 30 },
                    { "taskScript": "no-name.hwf" }
                  ]
                }
                """, StandardCharsets.UTF_8);
        TaskTemplateLoader.Loaded array = TaskTemplateLoader.load(projectHome, "arr", "R2");
        assertEquals(1, array.tasks().size(), "数组形式只应保留带 taskName 的任务");
        verify("INTERVAL 策略", "INTERVAL", array.tasks().get(0).getTaskRunPolicy());
        assertEquals(1, array.warnings().size(), "缺 taskName 应产生一条警告");

        // 项目目录无清单 → 回落到模板根 conf/
        Path rootConf = projectHome.resolve("conf");
        Files.createDirectories(rootConf);
        Files.writeString(rootConf.resolve(TaskTemplateLoader.FILE_NAME),
                "{ \"共享任务\": { \"taskScript\": \"shared.hwf\" } }", StandardCharsets.UTF_8);
        Files.createDirectories(projectHome.resolve("bare"));
        TaskTemplateLoader.Loaded fallback = TaskTemplateLoader.load(projectHome, "bare", "R3");
        assertTrue(fallback.file() != null
                        && fallback.file().startsWith(projectHome.resolve("conf")),
                "应回落到模板根 conf/，实际: " + fallback.fileText());
        assertEquals(1, fallback.tasks().size());

        // 完全没有清单 → 空且不报错
        TaskTemplateLoader.Loaded empty =
                TaskTemplateLoader.load(tempDir.resolve("none"), "x", "R4");
        assertTrue(empty.isEmpty() && empty.file() == null, "缺清单应为空清单而不是错误");
    }

    @Test
    void templateLoaderValidatesEntries(@TempDir Path tempDir) throws Exception {
        Path conf = tempDir.resolve("projects/bad/conf");
        Files.createDirectories(conf);
        Files.writeString(conf.resolve(TaskTemplateLoader.FILE_NAME), """
                {
                  "缺脚本": { "taskRunPolicy": "RUN_ONCE" },
                  "坏策略": { "taskScript": "x.hwf", "taskRunPolicy": "SOMETIMES" },
                  "坏类型": { "taskScript": "x.hwf", "taskScriptType": "SQL" },
                  "缺cron": { "taskScript": "x.hwf", "taskRunPolicy": "CRONTAB" },
                  "好任务": { "taskScript": "ok.hwf" },
                  "坏并发": { "taskScript": "y.hwf", "taskParallelPolicy": "SEQUENTIAL" }
                }
                """, StandardCharsets.UTF_8);

        TaskTemplateLoader.Loaded loaded = TaskTemplateLoader.load(tempDir.resolve("projects"), "bad", "R5");
        List<String> names = loaded.tasks().stream().map(SchedulerTask::getTaskName).toList();
        assertEquals(2, names.size(), "只有合法任务应保留，实际: " + names);
        assertTrue(names.contains("好任务") && names.contains("坏并发"), "保留项不符: " + names);
        assertEquals(5, loaded.warnings().size(), "跳过原因应逐条记录: " + loaded.warnings());
        SchedulerTask downgraded = loaded.tasks().stream()
                .filter(t -> t.getTaskName().equals("坏并发")).findFirst().orElseThrow();
        verify("非法并发策略降级为 PARALLEL", "PARALLEL", downgraded.getTaskParallelPolicy());
    }

    @Test
    void templateLoaderNormalizesGroupOrder(@TempDir Path tempDir) throws Exception {
        Path conf = tempDir.resolve("projects/grp/conf");
        Files.createDirectories(conf);
        Files.writeString(conf.resolve(TaskTemplateLoader.FILE_NAME), """
                {
                  "groups": ["SETUP_GROUP", "MAIN_GROUP", "OTHER_GROUP"],
                  "早期杂项": { "taskScript": "x.hwf", "taskGroup": "OTHER_GROUP" },
                  "初始化":   { "taskScript": "x.hwf", "taskGroup": "SETUP_GROUP" },
                  "初始化2":  { "taskScript": "x.hwf", "taskGroup": "SETUP_GROUP" },
                  "主流程":   { "taskScript": "x.hwf", "taskGroup": "MAIN_GROUP" }
                }
                """, StandardCharsets.UTF_8);

        TaskTemplateLoader.Loaded loaded = TaskTemplateLoader.load(tempDir.resolve("projects"), "grp", "R6");
        assertEquals(List.of("SETUP_GROUP", "MAIN_GROUP", "OTHER_GROUP"), loaded.groups());
        List<String> actual = loaded.tasks().stream()
                .map(t -> t.getTaskGroup() + ":" + t.getTaskStartupOrder()).toList();
        assertEquals(List.of("SETUP_GROUP:0", "SETUP_GROUP:1", "MAIN_GROUP:1000", "OTHER_GROUP:2000"),
                actual, "组顺序应按 groups 声明归一化进 taskStartupOrder");

        // 编排器按归一化顺序切块
        List<List<SchedulerTask>> groups = RunOrchestrator.groupsInOrder(loaded.tasks());
        assertEquals(3, groups.size());
        assertEquals(2, groups.get(0).size());
        verify("第一组是 SETUP_GROUP", "SETUP_GROUP", groups.get(0).get(0).getTaskGroup());
        verify("第二组是 MAIN_GROUP", "MAIN_GROUP", groups.get(1).get(0).getTaskGroup());
    }

    // ==================================================================
    // 3. HOP 运行环境（方案 A：命令行）
    // ==================================================================

    @Test
    void hopLayoutWritesConfigAndVariablesFile(@TempDir Path tempDir) throws Exception {
        Path projectHome = tempDir.resolve("projects");
        Files.createDirectories(projectHome.resolve("default/metadata/workflow-run-configuration"));
        Files.createDirectories(projectHome.resolve("demo/metadata/rdbms"));
        Files.writeString(projectHome.resolve("demo/主流程.hwf"), "<workflow/>", StandardCharsets.UTF_8);
        Files.writeString(projectHome.resolve("hop-config.json"), """
                {
                  "variables": [ { "name": "keep-me", "value": "1" } ],
                  "guiProperties": { "theme": "dark" },
                  "projectsConfig": { "enabled": true, "projectConfigurations": [], "lifecycleEnvironments": [] }
                }
                """, StandardCharsets.UTF_8);

        // 模拟 create：模板根 → 实例 flow
        Path runHome = tempDir.resolve("runs/R9");
        Path flow = runHome.resolve("flow");
        Files.createDirectories(flow);
        Files.copy(projectHome.resolve("hop-config.json"), flow.resolve("hop-config.json"));
        copyDirectory(projectHome.resolve("default"), flow.resolve("default"));
        copyDirectory(projectHome.resolve("demo"), flow.resolve("demo"));
        Files.createDirectories(runHome.resolve("conf"));
        Files.writeString(runHome.resolve("conf/variables.properties"), "BIZ_HOST=from-template\n",
                StandardCharsets.UTF_8);

        Path fakeRun = tempDir.resolve("hop/hop-run.bat");
        Files.createDirectories(fakeRun.getParent());
        Files.writeString(fakeRun, "@echo off\r\n", StandardCharsets.UTF_8);

        TaskRequest request = hopRequest(runHome, flow, fakeRun, "主流程", "10.0.0.1");
        List<String> command = HopCommandBuilder.buildCommand(request);
        String rendered = String.join(" ", command);

        if (isWindows()) {
            verify("Windows 下经 cmd /c 包装", "cmd /c", command.get(0) + " " + command.get(1));
        } else {
            verify("非 Windows 下用 sh", "sh", command.get(0));
        }
        assertTrue(rendered.contains(flow.resolve("demo").resolve("主流程.hwf").toAbsolutePath().toString()),
                "--file 应指向 <flow>/<type>/<task>，实际: " + rendered);
        assertTrue(rendered.contains("--project biz-[demo]"), "--project 应展开模板: " + rendered);
        assertTrue(rendered.contains("--environment R9-主流程"), "--environment 应每任务唯一: " + rendered);

        Map<String, String> env = HopCommandBuilder.environment(request);
        verify("HOP_CONFIG_FOLDER 指向 <runHome>/flow",
                flow.toAbsolutePath().toString(), env.get("HOP_CONFIG_FOLDER"));
        verify("HOP_AUDIT_FOLDER", runHome.resolve("audit").toAbsolutePath().toString(),
                env.get("HOP_AUDIT_FOLDER"));

        // 变量文件必须是 Hop 的 JSON 形状（写成 KEY=VALUE 会静默不生效）
        Path variablesFile = HopCommandBuilder.variablesFile(request);
        String content = Files.readString(variablesFile, StandardCharsets.UTF_8);
        assertTrue(content.contains("\"variables\""), "变量文件应为 JSON: " + content);
        assertTrue(content.contains("\"name\":\"RUN_ID\"") && content.contains("\"value\":\"R9\""),
                "变量文件应含 RUN_ID=R9: " + content);
        assertTrue(content.contains("10.0.0.1"), "变量文件应含业务变量: " + content);

        JSONObject hopConfig = JSON.parseObject(Files.readString(flow.resolve("hop-config.json"),
                StandardCharsets.UTF_8));
        assertNotNull(hopConfig.getJSONArray("variables"), "未触碰的键必须保留");
        verify("未触碰的键保留", "dark", hopConfig.getJSONObject("guiProperties").getString("theme"));

        JSONObject projectsConfig = hopConfig.getJSONObject("projectsConfig");
        JSONObject projectEntry = findByKey(projectsConfig.getJSONArray("projectConfigurations"),
                "projectName", "biz-[demo]");
        assertNotNull(projectEntry, "应写入项目条目: " + projectsConfig);
        verify("项目 projectHome", flow.resolve("demo").toAbsolutePath().toString(),
                projectEntry.getString("projectHome"));
        JSONObject envEntry = findByKey(projectsConfig.getJSONArray("lifecycleEnvironments"),
                "name", "R9-主流程");
        assertNotNull(envEntry, "应写入环境条目: " + projectsConfig);
        verify("环境 configurationFiles", variablesFile.toAbsolutePath().toString(),
                envEntry.getJSONArray("configurationFiles").getString(0));

        // 幂等 + 并发安全
        TaskRequest second = hopRequest(runHome, flow, fakeRun, "报表", "10.0.0.2");
        Thread t1 = new Thread(() -> HopCommandBuilder.buildCommand(request));
        Thread t2 = new Thread(() -> HopCommandBuilder.buildCommand(second));
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        HopCommandBuilder.buildCommand(request);

        JSONObject after = JSON.parseObject(Files.readString(flow.resolve("hop-config.json"),
                StandardCharsets.UTF_8));
        assertEquals(2, after.getJSONObject("projectsConfig")
                .getJSONArray("lifecycleEnvironments").size(), "环境条目应幂等（每任务一条）");
        assertEquals(2, after.getJSONObject("projectsConfig")
                .getJSONArray("projectConfigurations").size(), "项目条目应幂等（业务 + default）");
        assertTrue(!Files.exists(flow.resolve("hop-config.json.tmp")), "不应残留临时文件");
    }

    // ==================================================================
    // 4. 外部进程执行器
    // ==================================================================

    @Test
    void processExecutorRunsTimesOutAndCancels(@TempDir Path tempDir) throws Exception {
        var logger = LoggerFactory.getLogger("test-executor");
        Path runHome = tempDir.resolve("runs/R10");
        Files.createDirectories(runHome);

        TaskResult ok = new ProcessTaskExecutor(logger).execute(shell(runHome, "ok", "echo HELLO-FROM-TASK", 30));
        assertTrue(ok.isSuccess(), "正常执行应成功: " + ok.describe());
        assertTrue(Files.readString(Path.of(ok.logFile), StandardCharsets.UTF_8).contains("HELLO-FROM-TASK"),
                "stdout 应落到日志文件");

        TaskResult failed = new ProcessTaskExecutor(logger).execute(shell(runHome, "bad", "exit /b 7", 30));
        assertEquals(7, failed.exitCode, "退出码应透传");

        long start = System.currentTimeMillis();
        TaskResult timedOut = new ProcessTaskExecutor(logger)
                .execute(shell(runHome, "slow", "ping -n 30 127.0.0.1 > nul", 3));
        long elapsed = System.currentTimeMillis() - start;
        assertEquals(TaskResult.EXIT_TIMEOUT, timedOut.exitCode, "超时应返回 -2: " + timedOut.describe());
        assertTrue(elapsed <= 15_000, "超时应在预期时间内终止，实际 " + elapsed + "ms");
        assertTrue(Files.readString(Path.of(timedOut.logFile), StandardCharsets.UTF_8).contains("超时"),
                "超时说明应写入日志");
        Thread.sleep(1500);
        long leftover = ProcessHandle.allProcesses()
                .filter(p -> p.info().commandLine().orElse("").contains("ping -n 30")).count();
        assertEquals(0, leftover, "不应残留子孙进程");

        AtomicReference<TaskResult> canceled = new AtomicReference<>();
        ProcessTaskExecutor executor = new ProcessTaskExecutor(logger);
        Thread worker = new Thread(() -> canceled.set(executor.execute(
                shell(runHome, "cancel", "ping -n 30 127.0.0.1 > nul", 600))));
        worker.start();
        Thread.sleep(3000);
        executor.cancel();
        worker.join(30_000);
        assertTrue(!worker.isAlive(), "取消后执行线程应结束");
        assertNotNull(canceled.get());
        assertEquals(TaskResult.EXIT_CANCELED, canceled.get().exitCode, "取消应返回 -3");

        TaskRequest missing = new TaskRequest();
        missing.runId = "R10";
        missing.taskName = "missing";
        missing.projectType = "demo";
        missing.scriptType = "HOP";
        missing.script = "no-such.hwf";
        missing.runHome = runHome;
        missing.logFile = runHome.resolve("logs/missing.log");
        missing.hop = new TaskRequest.HopConfig();
        missing.hop.runScript = tempDir.resolve("hop/hop-run.bat").toString();
        assertEquals(TaskResult.EXIT_START_FAILED,
                new ProcessTaskExecutor(logger).execute(missing).exitCode, "工作流缺失应返回 -1");
    }

    // ==================================================================
    // 5. 真实 Hop 端到端（未安装 Hop 时跳过）
    // ==================================================================

    @Test
    void realHopRunsWorkflowWithInjectedVariables(@TempDir Path tempDir) throws Exception {
        Path hopRunScript = Path.of(HOP_RUN_SCRIPT);
        assumeTrue(Files.isRegularFile(hopRunScript),
                "未找到 Hop（" + hopRunScript + "），跳过真实 Hop 端到端用例");

        var logger = LoggerFactory.getLogger("test-hop-e2e");
        Path projectHome = tempDir.resolve("projects");
        writeHopRunConfigMetadata(projectHome.resolve("default/metadata"));
        writeHopRunConfigMetadata(projectHome.resolve("e2e/metadata"));
        String defaultProjectConfig = """
                { "metadataBaseFolder": "${PROJECT_HOME}/metadata",
                  "unitTestsBasePath": "${PROJECT_HOME}",
                  "dataSetsCsvFolder": "${PROJECT_HOME}/datasets",
                  "enforcingExecutionInHome": true,
                  "parentProjectName": "",
                  "config": { "variables": [] } }
                """;
        String projectConfig = defaultProjectConfig.replace("\"\"", "\"default\"");
        Files.writeString(projectHome.resolve("project-config.json"), defaultProjectConfig, StandardCharsets.UTF_8);
        Files.writeString(projectHome.resolve("default/project-config.json"), defaultProjectConfig,
                StandardCharsets.UTF_8);
        Files.writeString(projectHome.resolve("e2e/project-config.json"), projectConfig, StandardCharsets.UTF_8);
        Files.writeString(projectHome.resolve("e2e/e2e.hwf"), minimalWorkflowXml(), StandardCharsets.UTF_8);

        // 模拟 create：模板根 → 实例 flow
        Path runHome = tempDir.resolve("runs/E2E1");
        Path flow = runHome.resolve("flow");
        Files.createDirectories(flow);
        Files.copy(projectHome.resolve("project-config.json"), flow.resolve("project-config.json"));
        copyDirectory(projectHome.resolve("default"), flow.resolve("default"));
        copyDirectory(projectHome.resolve("e2e"), flow.resolve("e2e"));
        Files.createDirectories(runHome.resolve("conf"));

        TaskRequest request = new TaskRequest();
        request.runId = "E2E1";
        request.projectType = "e2e";
        request.taskName = "e2e";
        request.scriptType = "HOP";
        request.script = "e2e.hwf";
        request.runHome = runHome;
        request.logFile = runHome.resolve("logs/e2e.log");
        request.timeoutSeconds = 300;
        request.variables = VariableSet.resolve(
                Map.of("E2E_VAR", "hello-e2e", "E2E_BIZ", "biz-value"),
                Map.of(VariableSet.RUN_ID, "E2E1", VariableSet.PROJECT_TYPE, "e2e",
                        VariableSet.TASK_NAME, "e2e",
                        VariableSet.PROJECT_HOME, flow.resolve("e2e").toString()));
        request.hop = new TaskRequest.HopConfig();
        request.hop.runScript = hopRunScript.toString();
        request.hop.projectName = "${PROJECT_TYPE}";
        request.hop.environmentName = "${RUN_ID}";

        TaskResult result = new ProcessTaskExecutor(logger).execute(request);
        String log = Files.exists(Path.of(result.logFile))
                ? Files.readString(Path.of(result.logFile), StandardCharsets.UTF_8) : "";

        assertTrue(result.isSuccess(), "hop-run 应成功: " + result.describe() + tail(log, 15));
        assertTrue(log.contains("E2E-VAR=hello-e2e"), "框架变量应到达工作流: " + tail(log, 20));
        assertTrue(log.contains("E2E-BIZ=biz-value"), "业务变量应到达工作流: " + tail(log, 20));
        assertTrue(log.contains("PROJECT_HOME=" + flow.resolve("e2e").toAbsolutePath()),
                "PROJECT_HOME 应解析到 <flow>/<type>: " + tail(log, 20));
    }

    // ==================================================================
    // 6. Quartz 调度生命周期
    // ==================================================================

    @Test
    void quartzResumeTriggerAndPauseSemantics(@TempDir Path tempDir) throws Exception {
        var logger = LoggerFactory.getLogger("test-quartz");
        Path runHome = tempDir.resolve("runs/Q1");
        Files.createDirectories(runHome.resolve("logs"));

        DynamicQuartzScheduler quartz = new DynamicQuartzScheduler(logger, 5);
        quartz.start();
        try {
            quartz.scheduleJob("Q1", "cron", "SHELL", "echo CRON-TICK", "logs/cron.log",
                    "CRONTAB", "CONTINUE", 0, "PARALLEL", "0/2 * * * * ?", 30,
                    "MAIN_GROUP", 1000, "", runHome.toString(), Map.of("projectType", "demo"));

            assertTrue(quartz.isJobScheduled("Q1", "cron"), "编排后任务应存在");
            Map<String, Object> scheduled = quartz.getJobsForRun("Q1").get(0);
            verify("编排后处于 PAUSED（等 start）", "PAUSED", String.valueOf(scheduled.get("state")));
            Path cronLog = runHome.resolve("logs/cron.log");
            assertTrue(!Files.exists(cronLog) || Files.size(cronLog) == 0, "暂停的任务不应触发");

            // resume 后必须真的按周期触发（历史实现里 CRONTAB 永远不会触发）
            quartz.startJob("Q1", "cron", "CRONTAB");
            Thread.sleep(5000);
            assertTrue(Files.exists(cronLog) && Files.size(cronLog) > 0,
                    "resume 后周期任务应开始触发（5 秒内无输出）");
            long sizeAfterResume = Files.size(cronLog);

            quartz.pauseJob("Q1", "cron");
            Thread.sleep(3000);
            assertEquals(sizeAfterResume, Files.size(cronLog), "暂停后不应继续触发");

            // RUN_ONCE 触发一次，且不得破坏周期触发器
            quartz.startJob("Q1", "cron", "RUN_ONCE");
            Thread.sleep(4000);
            assertTrue(Files.size(cronLog) > sizeAfterResume, "RUN_ONCE 应实际执行一次");
            Map<String, Object> afterOnce = quartz.getJobsForRun("Q1").get(0);
            assertNotNull(afterOnce.get("nextFireTime"),
                    "RUN_ONCE 不应删除周期触发器: " + afterOnce);

            quartz.startJob("Q1", "cron", "CRONTAB");
            Thread.sleep(3000);
            quartz.pauseAllJobs("Q1");
            assertTrue(quartz.getJobsForRun("Q1").stream()
                            .allMatch(j -> "PAUSED".equals(String.valueOf(j.get("state")))),
                    "pauseAllJobs 应暂停全部任务");
        } finally {
            quartz.shutdown();
        }
    }

    // ==================================================================
    // 7. 元数据：历史主键与老表迁移
    // ==================================================================

    @Test
    void historyPrimaryKeyAvoidsCollisionAndMigratesLegacyTable(@TempDir Path tempDir) throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA IF NOT EXISTS sysaux");
            }
            SchedulerMeta meta = new SchedulerMeta(connection, LoggerFactory.getLogger("test-meta"));
            meta.initializeTables();

            int count = 20;   // 同一毫秒内连续写入，验证 task_id 不冲突
            for (int i = 0; i < count; i++) {
                TaskHistory history = new TaskHistory();
                history.setTaskId(0L);      // 由存储层分配
                history.setRunId("R");
                history.setTaskName("t" + i);
                history.setRunIdRef("R");
                history.setRetCode(0);
                history.setRetMsg("ok");
                history.setStartTime(LocalDateTime.now());
                history.setEndTime(LocalDateTime.now());
                history.setLogFile("C:/tmp/t" + i + ".log");
                meta.insertTaskHistory(history);
            }
            List<TaskHistory> loaded = meta.getTaskHistoryByRunId("R");
            assertEquals(count, loaded.size(), "历史应全部落库");
            assertEquals(count, loaded.stream().map(TaskHistory::getTaskId).distinct().count(),
                    "task_id 应互不相同");

            // 老表（缺列）应被 initializeTables 幂等补齐
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE sysaux.v$scheduler_task");
                statement.execute("CREATE TABLE sysaux.v$scheduler_task (run_id VARCHAR, task_name VARCHAR, "
                        + "task_script VARCHAR, PRIMARY KEY (run_id, task_name))");
            }
            meta.initializeTables();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT count(*) FROM information_schema.columns WHERE table_name='v$scheduler_task' "
                                 + "AND column_name IN ('task_mandatory','variables_json')")) {
                rs.next();
                assertEquals(2, rs.getInt(1), "老表应补齐 task_mandatory / variables_json 两列");
            }
        }
    }

    // ==================================================================
    // 8. 失败重试 与 延迟排队（不占工作线程）
    // ==================================================================

    @Test
    void retryPolicyTriggersSecondAttempt(@TempDir Path tempDir) throws Exception {
        var logger = LoggerFactory.getLogger("test-retry");
        Path runHome = tempDir.resolve("runs/R1");
        Files.createDirectories(runHome.resolve("logs"));

        DynamicQuartzScheduler quartz = new DynamicQuartzScheduler(logger, 5);
        quartz.start();
        try {
            String failing = isWindows() ? "echo RETRY-TRY & exit /b 5" : "echo RETRY-TRY; exit 5";
            Map<String, String> extras = new LinkedHashMap<>();
            extras.put("projectType", "demo");
            extras.put("taskRetryTimes", "1");
            extras.put("taskRetryInterval", "1");

            quartz.scheduleJob("R1", "retry", "SHELL", failing, "logs/retry.log",
                    "RUN_ONCE", "RETRY", 0, "PARALLEL", "", 60, "G", 0, "",
                    runHome.toString(), extras);
            quartz.startJob("R1", "retry", "RUN_ONCE");

            Path logFile = runHome.resolve("logs/retry.log");
            boolean retried = awaitUntil(() -> countOccurrences(logFile, "RETRY-TRY") >= 2, 30_000);
            int attempts = countOccurrences(logFile, "RETRY-TRY");
            assertTrue(retried, "RETRY 策略应触发第二次执行，实际执行次数=" + attempts);

            // 重试次数用尽后必须停下（不能再无限重试）
            Thread.sleep(4000);
            assertEquals(attempts, countOccurrences(logFile, "RETRY-TRY"),
                    "重试次数用尽后不应继续重试");
            assertTrue(!quartz.hasDeferred("R1", "retry"), "用尽后不应再登记延迟实例");
        } finally {
            quartz.shutdown();
        }
    }

    @Test
    void stopPolicyPausesCronTaskOnFailure(@TempDir Path tempDir) throws Exception {
        var logger = LoggerFactory.getLogger("test-stop-policy");
        Path runHome = tempDir.resolve("runs/R3");
        Files.createDirectories(runHome.resolve("logs"));

        DynamicQuartzScheduler quartz = new DynamicQuartzScheduler(logger, 5);
        quartz.start();
        try {
            String failing = isWindows() ? "echo STOP-TRY & exit /b 9" : "echo STOP-TRY; exit 9";
            quartz.scheduleJob("R3", "cronfail", "SHELL", failing, "logs/cronfail.log",
                    "CRONTAB", "STOP", 0, "PARALLEL", "0/1 * * * * ?", 30, "G", 0, "",
                    runHome.toString(), Map.of("projectType", "demo"));
            quartz.startJob("R3", "cronfail", "CRONTAB");

            Path logFile = runHome.resolve("logs/cronfail.log");
            assertTrue(awaitUntil(() -> countOccurrences(logFile, "STOP-TRY") >= 1, 20_000),
                    "CRON 任务应触发一次");
            assertTrue(awaitUntil(() -> {
                try {
                    return quartz.getJobsForRun("R3").stream()
                            .anyMatch(j -> "PAUSED".equals(String.valueOf(j.get("state"))));
                } catch (Exception e) {
                    return false;
                }
            }, 15_000), "STOP 策略应在失败后暂停该任务");

            int afterPause = countOccurrences(logFile, "STOP-TRY");
            Thread.sleep(3000);
            assertEquals(afterPause, countOccurrences(logFile, "STOP-TRY"),
                    "暂停后不应再触发");
        } finally {
            quartz.shutdown();
        }
    }

    @Test
    void serialDelayQueuesWithoutBlockingWorker(@TempDir Path tempDir) throws Exception {
        var logger = LoggerFactory.getLogger("test-serial-delay");
        Path runHome = tempDir.resolve("runs/R2");
        Files.createDirectories(runHome.resolve("logs"));

        DynamicQuartzScheduler quartz = new DynamicQuartzScheduler(logger, 5);
        quartz.start();
        try {
            String sleeping = isWindows() ? "ping -n 8 127.0.0.1 > nul" : "sleep 7";
            quartz.scheduleJob("R2", "delayed", "SHELL", sleeping, "logs/delayed.log",
                    "RUN_ONCE", "CONTINUE", 0, "SERIAL_DELAY", "", 120, "G", 0, "",
                    runHome.toString(), Map.of("projectType", "demo"));

            quartz.startJob("R2", "delayed", "RUN_ONCE");
            Thread.sleep(2000);   // 让第一次执行进入运行态

            long start = System.currentTimeMillis();
            quartz.startJob("R2", "delayed", "RUN_ONCE");   // 第二次：应登记延迟实例后立即返回
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(elapsed < 3000,
                    "SERIAL_DELAY 排队不应阻塞工作线程（实际 " + elapsed + "ms，历史实现是 sleep 轮询）");
            assertTrue(quartz.hasDeferred("R2", "delayed"), "应登记延迟实例排队");
        } finally {
            quartz.shutdown();
        }
    }

    // ==================================================================
    // 9. 保留策略：历史清理
    // ==================================================================

    @Test
    void retentionDeletesExpiredHistory(@TempDir Path tempDir) throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA IF NOT EXISTS sysaux");
            }
            SchedulerMeta meta = new SchedulerMeta(connection, LoggerFactory.getLogger("test-retention"));
            meta.initializeTables();

            insertHistory(meta, "old-1", LocalDateTime.now().minusDays(40));
            insertHistory(meta, "old-2", LocalDateTime.now().minusDays(31));
            insertHistory(meta, "fresh", LocalDateTime.now().minusDays(1));

            int deleted = meta.deleteHistoryBefore(LocalDateTime.now().minusDays(30));
            assertEquals(2, deleted, "应删除 2 条过期历史");

            List<TaskHistory> left = meta.getTaskHistoryByRunId("R");
            assertEquals(1, left.size(), "应保留 1 条未过期历史");
            verify("保留的是最新记录", "fresh", left.get(0).getTaskName());
        }
    }

    private static void insertHistory(SchedulerMeta meta, String taskName, LocalDateTime endTime)
            throws Exception {
        TaskHistory history = new TaskHistory();
        history.setTaskId(0L);
        history.setRunId("R");
        history.setTaskName(taskName);
        history.setRunIdRef("R");
        history.setRetCode(0);
        history.setRetMsg("ok");
        history.setStartTime(endTime);
        history.setEndTime(endTime);
        history.setLogFile("C:/tmp/" + taskName + ".log");
        meta.insertTaskHistory(history);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private interface Condition {
        boolean test() throws Exception;
    }

    /** 轮询等待条件成立（用于验证异步的 Quartz 行为）。 */
    private static boolean awaitUntil(Condition condition, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.test()) {
                return true;
            }
            Thread.sleep(300);
        }
        return condition.test();
    }

    private static int countOccurrences(Path file, String token) {
        try {
            if (!Files.isRegularFile(file)) {
                return 0;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int count = 0;
            int index = content.indexOf(token);
            while (index >= 0) {
                count++;
                index = content.indexOf(token, index + token.length());
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 运行环境元数据：runconfig=local（HOP 需要）。 */
    private static void writeHopRunConfigMetadata(Path metadataDir) throws Exception {
        Path workflowRunConfig = metadataDir.resolve("workflow-run-configuration");
        Path pipelineRunConfig = metadataDir.resolve("pipeline-run-configuration");
        Files.createDirectories(workflowRunConfig);
        Files.createDirectories(pipelineRunConfig);
        Files.writeString(workflowRunConfig.resolve("local.json"), """
                { "engineRunConfiguration": { "Local": { "safe_mode": false } },
                  "name": "local", "description": "", "defaultSelection": true }
                """, StandardCharsets.UTF_8);
        Files.writeString(pipelineRunConfig.resolve("local.json"), """
                { "engineRunConfiguration": { "Local": { "safe_mode": false, "transactional": false } },
                  "name": "local", "description": "", "defaultSelection": true }
                """, StandardCharsets.UTF_8);
    }

    /** 最小 HOP workflow：Start → WriteToLog(打印变量) → Success，不依赖任何数据库。 */
    private static String minimalWorkflowXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <workflow>
                  <name>e2e</name>
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
                      <name>Log</name>
                      <description/>
                      <type>WRITE_TO_LOG</type>
                      <attributes/>
                      <loglevel>Basic</loglevel>
                      <logmessage>E2E-VAR=${E2E_VAR} E2E-BIZ=${E2E_BIZ} PROJECT_HOME=${PROJECT_HOME} RUN=${RUN_ID}</logmessage>
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
                    <hop><from>Start</from><to>Log</to><enabled>Y</enabled><evaluation>Y</evaluation><unconditional>Y</unconditional></hop>
                    <hop><from>Log</from><to>Success</to><enabled>Y</enabled><evaluation>Y</evaluation><unconditional>Y</unconditional></hop>
                  </hops>
                  <notepads/>
                  <attributes/>
                </workflow>
                """;
    }

    private static TaskRequest hopRequest(Path runHome, Path flow, Path runScript,
                                          String taskName, String businessValue) {
        TaskRequest request = new TaskRequest();
        request.runId = "R9";
        request.projectType = "demo";
        request.taskName = taskName;
        request.scriptType = "HOP";
        request.script = "主流程.hwf";
        request.runHome = runHome;
        request.logFile = runHome.resolve("logs/" + taskName + ".log");
        request.variables = VariableSet.resolve(
                Map.of("BIZ_HOST", businessValue),
                Map.of(VariableSet.RUN_ID, "R9", VariableSet.PROJECT_TYPE, "demo",
                        VariableSet.TASK_NAME, taskName,
                        VariableSet.PROJECT_HOME, flow.resolve("demo").toString()));
        request.hop = new TaskRequest.HopConfig();
        request.hop.runScript = runScript.toString();
        request.hop.projectName = "biz-[${PROJECT_TYPE}]";
        request.hop.environmentName = "${RUN_ID}-${TASK_NAME}";
        return request;
    }

    private static TaskRequest shell(Path runHome, String taskName, String command, int timeoutSeconds) {
        TaskRequest request = new TaskRequest();
        request.runId = "R10";
        request.projectType = "demo";
        request.taskName = taskName;
        request.scriptType = "SHELL";
        request.script = command;
        request.runHome = runHome;
        request.workDir = runHome;
        request.logFile = runHome.resolve("logs/" + taskName + ".log");
        request.timeoutSeconds = timeoutSeconds;
        request.variables = VariableSet.resolve(Map.of(VariableSet.RUN_ID, "R10"));
        return request;
    }

    private static JSONObject findByKey(JSONArray array, String key, String value) {
        if (array == null) {
            return null;
        }
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (item != null && value.equals(item.getString(key))) {
                return item;
            }
        }
        return null;
    }

    private static void copyDirectory(Path source, Path target) throws Exception {
        if (!Files.isDirectory(source)) {
            Files.createDirectories(target);
            return;
        }
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

    private static String tail(String text, int lines) {
        String[] all = text.split("\n");
        int from = Math.max(0, all.length - lines);
        return String.join(" | ", new ArrayList<>(List.of(all).subList(from, all.length)));
    }

    /** 断言字面值相等，失败信息带上检查项名称与实际值。 */
    private static void verify(String name, Object expected, Object actual) {
        assertEquals(expected, actual, name);
    }
}
