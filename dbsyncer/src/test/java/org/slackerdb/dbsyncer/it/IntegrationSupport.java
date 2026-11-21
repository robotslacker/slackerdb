package org.slackerdb.dbsyncer.it;

import io.javalin.Javalin;
import org.duckdb.DuckDBConnection;
import org.slackerdb.dbsyncer.DbSyncerModule;
import org.slackerdb.dbsyncer.target.DuckDbHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * 集成测试公共设施：把一个 YAML 装成真实的 {@link DbSyncerModule}，目标用内存 DuckDB，
 * 源库用外部 PostgreSQL / MySQL。
 *
 * <p>这些测试默认不进入回归（Surefire 里按 {@code integration} tag 排除），
 * 运行方式见 {@code devGuide.md} / {@code README.md}。</p>
 */
final class IntegrationSupport implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(IntegrationSupport.class);

    private final DbSyncerModule module;
    private final Connection duckDb;
    private final Path configFile;

    private IntegrationSupport(DbSyncerModule module, Connection duckDb, Path configFile) {
        this.module = module;
        this.duckDb = duckDb;
        this.configFile = configFile;
    }

    static IntegrationSupport start(String yaml) throws Exception {
        Path file = Files.createTempFile("dbsyncer-it-", ".yml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        Connection base = DriverManager.getConnection("jdbc:duckdb:");
        DuckDbHost host = new DuckDbHost() {
            @Override
            public Connection duplicate() throws SQLException {
                return ((DuckDBConnection) base).duplicate();
            }

            @Override
            public String catalog() {
                return "memory";
            }
        };
        DbSyncerModule module = new DbSyncerModule(Javalin.create(), LOGGER, host,
                Map.of(DbSyncerModule.KEY_PREFIX + "config", file.toString()));
        module.start();
        return new IntegrationSupport(module, base, file);
    }

    DbSyncerModule module() {
        return module;
    }

    Connection duckDb() {
        return duckDb;
    }

    void startJob(String name, boolean resync) {
        module.jobManager().start(name, resync);
    }

    /** 等作业进入指定状态；超时直接失败并带上最近的 lastError。 */
    void awaitState(String jobName, String expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String state = module.jobManager().require(jobName).state().name();
            if (expected.equals(state)) {
                return;
            }
            if ("FAILED".equals(state) || "PAUSED".equals(state)) {
                throw new AssertionError("job [" + jobName + "] entered " + state + " instead of "
                        + expected + ": " + module.jobManager().require(jobName).lastError());
            }
            sleep(100);
        }
        throw new AssertionError("job [" + jobName + "] did not reach " + expected + " within "
                + timeoutMs + "ms, current state=" + module.jobManager().require(jobName).state());
    }

    /** 允许抛受检异常的轮询条件。 */
    @FunctionalInterface
    interface Check {
        boolean ok() throws Exception;
    }

    /** 轮询直到条件成立；用于等一次 flush 落到目标库。 */
    void await(String description, long timeoutMs, Check condition) {
        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        Throwable last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.ok()) {
                    System.out.println("[IT] " + description + " ok after "
                            + (System.currentTimeMillis() - start) + "ms");
                    return;
                }
            } catch (Exception e) {
                last = e;
            }
            sleep(100);
        }
        throw new AssertionError("condition not met within " + timeoutMs + "ms: " + description, last);
    }

    @Override
    public void close() {
        try {
            module.stop();
        } finally {
            try {
                duckDb.close();
            } catch (SQLException e) {
                throw new IllegalStateException("cannot close verification connection", e);
            } finally {
                try {
                    Files.deleteIfExists(configFile);
                } catch (IOException e) {
                    System.out.println("[IT] cannot delete " + configFile + ": " + e.getMessage());
                }
            }
        }
    }

    // ------------------------------------------------------------------ SQL helpers

    static Connection connect(String url, String user, String password) throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    static int queryInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    static boolean tableExists(Connection connection, String catalog, String schema, String table)
            throws SQLException {
        String sql = "SELECT count(*) FROM information_schema.tables WHERE table_catalog='"
                + catalog + "' AND table_schema='" + schema + "' AND table_name='" + table + "'";
        return queryInt(connection, sql) > 0;
    }

    static boolean columnExists(Connection connection, String schema, String table, String column)
            throws SQLException {
        String sql = "SELECT count(*) FROM information_schema.columns WHERE table_schema='"
                + schema + "' AND table_name='" + table + "' AND column_name='" + column + "'";
        return queryInt(connection, sql) > 0;
    }

    static String suffix() {
        return Long.toString(System.nanoTime() % 1_000_000L);
    }

    static String property(String name, String fallback) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}
