package org.slackerdb.dbserver.scheduler.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DBCLI 任务的"准备阶段"契约（不需要数据库）：连接串缺失、脚本缺失、脚本路径解析。
 *
 * <p>真正跑脚本的用例在 dbserver 的集成测试里（需要真实实例的数据服务端口）。</p>
 */
class DbcliTaskExecutorTest {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(DbcliTaskExecutorTest.class);

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("没有可连接的端口：明确失败，不静默假装成功")
    void missingConnectStringFailsFast() {
        TaskRequest request = baseRequest();
        request.dbcliConnect = null;

        TaskResult result = new DbcliTaskExecutor(LOGGER).execute(request);

        assertFalse(result.isSuccess());
        assertEquals(TaskResult.EXIT_START_FAILED, result.exitCode);
        assertTrue(result.errorMessage.contains("数据服务端口"), result.errorMessage);
    }

    @Test
    @DisplayName("脚本不存在：失败并报出解析后的路径")
    void missingScriptFails() {
        TaskRequest request = baseRequest();
        request.dbcliConnect = "jdbc:slackerdb://127.0.0.1:1/mem";
        request.script = "missing.sql";

        TaskResult result = new DbcliTaskExecutor(LOGGER).execute(request);

        assertFalse(result.isSuccess());
        assertTrue(result.errorMessage.contains("DBCLI 脚本不存在"), result.errorMessage);
        assertTrue(result.errorMessage.contains("missing.sql"), result.errorMessage);
    }

    @Test
    @DisplayName("相对脚本按 run 的 flow/<projectType> 解析；绝对路径原样使用")
    void scriptPathResolution() throws Exception {
        TaskRequest request = baseRequest();
        request.script = "etl/load.sql";
        Path resolved = DbcliTaskExecutor.resolveScript(request);
        assertEquals(tempDir.resolve("runs/R1/flow/demo/etl/load.sql").toAbsolutePath(), resolved);

        Path absolute = tempDir.resolve("elsewhere/load.sql").toAbsolutePath();
        request.script = absolute.toString();
        assertEquals(absolute, DbcliTaskExecutor.resolveScript(request));
    }

    @Test
    @DisplayName("空的 taskScript 按配置错误处理")
    void blankScriptIsRejected() {
        TaskRequest request = baseRequest();
        request.script = "  ";
        assertThrows(IllegalStateException.class, () -> DbcliTaskExecutor.resolveScript(request));
    }

    /** 一个只有框架变量的最小请求（runHome 指向临时目录，因此 flow 目录不必真实存在）。 */
    private TaskRequest baseRequest() {
        TaskRequest request = new TaskRequest();
        request.runId = "R1";
        request.projectType = "demo";
        request.taskName = "load";
        request.scriptType = "DBCLI";
        request.runHome = tempDir.resolve("runs/R1");
        request.workDir = request.runHome;
        request.logFile = request.runHome.resolve("logs/load.log");
        request.timeoutSeconds = 5;
        request.variables = VariableSet.resolve(Map.of(VariableSet.RUN_ID, "R1"));
        return request;
    }

    @SuppressWarnings("unused")
    private static void ensureDir(Path path) throws Exception {
        Files.createDirectories(path);
    }
}
