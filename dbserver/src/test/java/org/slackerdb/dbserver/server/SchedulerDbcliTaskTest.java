package org.slackerdb.dbserver.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.scheduler.runner.DbcliTaskExecutor;
import org.slackerdb.dbserver.scheduler.runner.TaskRequest;
import org.slackerdb.dbserver.scheduler.runner.TaskResult;
import org.slackerdb.dbserver.scheduler.runner.VariableSet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DBCLI 任务的端到端契约：在 dbserver 进程内运行一个 dbcli 脚本，脚本以普通客户端身份
 * 连回本实例的数据服务端口，并真实修改数据库。
 *
 * <p>这条用例覆盖了"依赖 dbcli + 驱动能否连回自己"这个最容易出问题的环节
 * （连接串、驱动发现、变量替换、退出码）。</p>
 */
class SchedulerDbcliTaskTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("DBCLI 脚本在进程内执行、连回本实例，并真实落库（含 $${变量} 替换）")
    void dbcliScriptRunsInProcessAgainstLocalInstance() throws Exception {
        ServerConfiguration configuration = baseConfiguration();
        DBInstance instance = new DBInstance(configuration);
        instance.start();
        try {
            Path script = tempDir.resolve("load.sql");
            Files.writeString(script, """
                    create table if not exists dbcli_probe(v varchar);
                    delete from dbcli_probe;
                    insert into dbcli_probe values ('${RUN_ID}');
                    """);

            TaskRequest request = baseRequest(instance, configuration, script);
            TaskResult result = new DbcliTaskExecutor(LoggerFactory.getLogger(SchedulerDbcliTaskTest.class))
                    .execute(request);

            assertTrue(result.isSuccess(), result.describe() + System.lineSeparator()
                    + readLog(request));

            try (Connection connection = instance.dbDataSourcePool.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "select v from " + instance.instanceName + ".dbcli_probe")) {
                assertTrue(rows.next(), "the dbcli script should have inserted one row");
                assertEquals("dbcli-run-1", rows.getString(1),
                        "variables must be substituted by the dbcli engine");
            }
        } finally {
            instance.stop();
        }
    }

    @Test
    @DisplayName("脚本里的 _EXIT n 会原样成为任务退出码（非 0 即失败）")
    void explicitExitCodeIsPropagated() throws Exception {
        ServerConfiguration configuration = baseConfiguration();
        DBInstance instance = new DBInstance(configuration);
        instance.start();
        try {
            Path script = tempDir.resolve("fail.sql");
            Files.writeString(script, """
                    _EXIT 7
                    """);

            TaskRequest request = baseRequest(instance, configuration, script);
            TaskResult result = new DbcliTaskExecutor(LoggerFactory.getLogger(SchedulerDbcliTaskTest.class))
                    .execute(request);

            assertEquals(7, result.exitCode, result.describe());
        } finally {
            instance.stop();
        }
    }

    private TaskRequest baseRequest(DBInstance instance, ServerConfiguration configuration, Path script) {
        TaskRequest request = new TaskRequest();
        request.runId = "dbcli-run-1";
        request.projectType = "demo";
        request.taskName = "load";
        request.scriptType = "DBCLI";
        request.script = script.toAbsolutePath().toString();
        request.runHome = tempDir;
        request.workDir = tempDir;
        request.logFile = tempDir.resolve("logs/load.log");
        request.timeoutSeconds = 60;
        request.variables = VariableSet.resolve(Map.of(VariableSet.RUN_ID, "dbcli-run-1"));
        request.dbcliConnect = "jdbc:slackerdb://127.0.0.1:" + configuration.getPort()
                + "/" + instance.instanceName;
        return request;
    }

    private static ServerConfiguration baseConfiguration() {
        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setPort(0);
        configuration.setPortX(-1);
        configuration.setData("mem");
        configuration.setLog_level("INFO");
        configuration.setSqlHistory("OFF");
        configuration.setDataServiceHistory("OFF");
        return configuration;
    }

    private static String readLog(TaskRequest request) {
        try {
            return Files.readString(request.logFile);
        } catch (Exception e) {
            return "<no log: " + e.getMessage() + ">";
        }
    }
}
