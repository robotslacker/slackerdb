package org.slackerdb.dbsyncer.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbsyncer.config.SyncConfig;
import org.slackerdb.dbsyncer.state.SyncStateStore;
import org.slackerdb.dbsyncer.target.DuckDbHost;
import org.slackerdb.dbsyncer.target.DuckDbTarget;
import org.slackerdb.dbsyncer.target.DuckDbWriter;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 作业注册表的热加载语义：新增/更新/删除、orphaned 作业复活。 */
class JobManagerTest {

    private Connection connection;
    private DuckDbTarget target;
    private SyncStateStore stateStore;
    private DuckDbWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        stateStore = new SyncStateStore(connection, "memory");
        stateStore.initialize();
        DuckDbHost host = new DuckDbHost() {
            @Override
            public Connection duplicate() {
                return connection;
            }

            @Override
            public String catalog() {
                return "memory";
            }
        };
        target = new DuckDbTarget(host, LoggerFactory.getLogger(JobManagerTest.class));
        writer = new DuckDbWriter(target, stateStore, LoggerFactory.getLogger(JobManagerTest.class));
    }

    @AfterEach
    void tearDown() {
        target.close();
    }

    @Test
    void reloadAddsUpdatesAndRemovesStoppedJobs() {
        JobManager manager = new JobManager(config("a", "b"), target, stateStore, writer,
                LoggerFactory.getLogger(JobManagerTest.class), 10);

        SyncConfig next = config("a", "c");
        next.jobs.get(0).mode = SyncConfig.JobMode.SNAPSHOT;
        JobManager.ReloadResult result = manager.reload(next);

        assertEquals(java.util.List.of("c"), result.added());
        assertEquals(java.util.List.of("a"), result.updated());
        assertEquals(java.util.List.of("b"), result.removed());
        assertNotNull(manager.job("a"));
        assertNotNull(manager.job("c"));
        assertNull(manager.job("b"));
        assertEquals(2, manager.jobs().size());
    }

    @Test
    void reloadKeepsUnchangedJobs() {
        JobManager manager = new JobManager(config("a"), target, stateStore, writer,
                LoggerFactory.getLogger(JobManagerTest.class), 10);
        JobManager.ReloadResult result = manager.reload(config("a"));
        assertTrue(result.added().isEmpty());
        assertTrue(result.updated().isEmpty());
        assertTrue(result.removed().isEmpty());
        assertFalse(manager.require("a").orphaned());
    }

    @Test
    void reloadRevivesOrphanedJobWhenItComesBack() {
        JobManager manager = new JobManager(config("a"), target, stateStore, writer,
                LoggerFactory.getLogger(JobManagerTest.class), 10);
        // 模拟"作业被删但还在运行"：被标记 orphaned 后不能 start。
        manager.require("a").markOrphaned();
        assertTrue(manager.require("a").orphaned());

        JobManager.ReloadResult result = manager.reload(config("a"));

        // 定义没变，但必须解除 orphan，否则重新加回配置的作业永远不能 start。
        assertEquals(java.util.List.of("a"), result.updated());
        assertFalse(manager.require("a").orphaned());
    }

    @Test
    void startUnknownJobRejectsWithoutTouchingRuntimes() {
        JobManager manager = new JobManager(config("a"), target, stateStore, writer,
                LoggerFactory.getLogger(JobManagerTest.class), 10);
        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> manager.start("missing", false));
        assertTrue(error.getMessage().contains("not found"), error.getMessage());
        assertEquals(1, manager.jobs().size());
    }

    /** 用 MySQL 类型的连接：清理复制对象是空操作，测试不需要外部数据库。 */
    private static SyncConfig config(String... jobNames) {
        SyncConfig config = new SyncConfig();
        SyncConfig.ConnectionConfig connection = new SyncConfig.ConnectionConfig();
        connection.type = SyncConfig.SourceType.MYSQL;
        connection.url = "jdbc:mysql://127.0.0.1:3306/appdb";
        connection.username = "sync_user";
        connection.password = "secret";
        config.connections.put("my", connection);

        SyncConfig.TargetConfig target = new SyncConfig.TargetConfig();
        target.schema = "sync";
        config.targets.put("duck", target);

        for (String name : jobNames) {
            SyncConfig.JobConfig job = new SyncConfig.JobConfig();
            job.name = name;
            job.source = "my";
            job.target = "duck";
            job.snapshot = config.defaults.snapshot.copy();
            SyncConfig.TableRule rule = new SyncConfig.TableRule();
            rule.match = "appdb." + name;
            rule.target = name;
            job.tables.include.add(rule);
            config.jobs.add(job);
        }
        return config;
    }
}
