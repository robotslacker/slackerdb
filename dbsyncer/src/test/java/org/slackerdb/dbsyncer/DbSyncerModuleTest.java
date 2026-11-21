package org.slackerdb.dbsyncer;

import io.javalin.Javalin;
import org.duckdb.DuckDBConnection;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbsyncer.target.DuckDbHost;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbSyncerModuleTest {

    @Test
    void rejectsUnknownPropertyBeforeTouchingTheDatabase() throws Exception {
        Connection base = DriverManager.getConnection("jdbc:duckdb:");
        DuckDbHost host = new DuckDbHost() {
            @Override
            public Connection duplicate() throws java.sql.SQLException {
                return ((DuckDBConnection) base).duplicate();
            }

            @Override
            public String catalog() {
                return "memory";
            }
        };
        Path config = Path.of("src/test/resources/dbsyncer-valid.yml");
        DbSyncerConfigException error = assertThrows(DbSyncerConfigException.class,
                () -> new DbSyncerModule(Javalin.create(),
                        LoggerFactory.getLogger(DbSyncerModuleTest.class), host,
                        Map.of(DbSyncerModule.KEY_PREFIX + "config", config.toString(),
                                DbSyncerModule.KEY_PREFIX + "autostart", "true")));
        assertTrue(error.getMessage().contains("unknown dbsyncer property"), error.getMessage());
        // 配置校验失败不应留下任何状态表
        try (Statement statement = base.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT count(*) FROM information_schema.schemata WHERE schema_name='sysaux'")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        } finally {
            base.close();
        }
    }

    @Test
    void rejectsOutOfRangeNumericPropertyBeforeTouchingTheDatabase() throws Exception {
        Connection base = DriverManager.getConnection("jdbc:duckdb:");
        DuckDbHost host = new DuckDbHost() {
            @Override
            public Connection duplicate() throws java.sql.SQLException {
                return ((DuckDBConnection) base).duplicate();
            }

            @Override
            public String catalog() {
                return "memory";
            }
        };
        Path config = Path.of("src/test/resources/dbsyncer-valid.yml");
        DbSyncerConfigException error = assertThrows(DbSyncerConfigException.class,
                () -> new DbSyncerModule(Javalin.create(),
                        LoggerFactory.getLogger(DbSyncerModuleTest.class), host,
                        Map.of(DbSyncerModule.KEY_PREFIX + "config", config.toString(),
                                DbSyncerModule.KEY_PREFIX + "maxJobs", "0")));
        assertTrue(error.getMessage().contains("between"), error.getMessage());
        try (Statement statement = base.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT count(*) FROM information_schema.schemata WHERE schema_name='sysaux'")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        } finally {
            base.close();
        }
    }

    @Test
    void defaultsAutoStartToFalseAndClosesStateConnection() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:duckdb:");
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
        Javalin app = Javalin.create();
        Path config = Path.of("src/test/resources/dbsyncer-valid.yml");
        DbSyncerModule module = new DbSyncerModule(app, LoggerFactory.getLogger(DbSyncerModuleTest.class),
                host, Map.of(DbSyncerModule.KEY_PREFIX + "config", config.toString()));
        assertNotNull(module.config());
        assertFalse(module.isStarted());
        assertFalse(module.status().getBoolean("autoStart"));
        module.stop();
        assertTrue(connection.isClosed());
    }

    @Test
    void commitsStateTablesEvenWhenHostConnectionIsInManualTransaction() throws Exception {
        Connection base = DriverManager.getConnection("jdbc:duckdb:");
        base.setAutoCommit(false);
        DuckDbHost host = new DuckDbHost() {
            @Override
            public Connection duplicate() throws java.sql.SQLException {
                return ((DuckDBConnection) base).duplicate();
            }

            @Override
            public String catalog() {
                return "memory";
            }
        };
        Javalin app = Javalin.create();
        Path config = Path.of("src/test/resources/dbsyncer-valid.yml");
        DbSyncerModule module = new DbSyncerModule(app, LoggerFactory.getLogger(DbSyncerModuleTest.class),
                host, Map.of(DbSyncerModule.KEY_PREFIX + "config", config.toString(),
                DbSyncerModule.KEY_PREFIX + "autoStart", "false"));
        try (Statement statement = base.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT count(*) FROM \"memory\".sysaux.v$dbsync_job")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
        module.stop();
        base.close();
    }
}
