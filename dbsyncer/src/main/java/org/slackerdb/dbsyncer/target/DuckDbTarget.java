package org.slackerdb.dbsyncer.target;

import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 内嵌 DuckDB 目标：全局单写者。
 *
 * <p>所有作业的写入都提交到同一个线程，连接也由该线程独占。这样既避免 DuckDB 的写-写冲突，
 * 也保证"事务顺序 + 检查点"只有一个来源。</p>
 *
 * <p>连接上会设置 DuckDB 的 {@code max_execution_time}（毫秒，0 = 不限），
 * 让卡住的语句自己以 {@link java.sql.SQLTimeoutException} 中断，而不是把作业线程永久挂住。</p>
 */
public final class DuckDbTarget implements AutoCloseable {

    /** 单条语句默认上限：10 分钟（与 dbserver 的 client_timeout 默认 600s 对齐）。 */
    public static final long DEFAULT_QUERY_TIMEOUT_MS = 600_000;

    private final DuckDbHost host;
    private final Logger logger;
    private final long queryTimeoutMs;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "dbsync-writer");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Connection connection;
    private volatile boolean closed;

    public DuckDbTarget(DuckDbHost host, Logger logger) {
        this(host, logger, DEFAULT_QUERY_TIMEOUT_MS);
    }

    public DuckDbTarget(DuckDbHost host, Logger logger, long queryTimeoutMs) {
        this.host = host;
        this.logger = logger;
        this.queryTimeoutMs = Math.max(0, queryTimeoutMs);
    }

    public String catalog() {
        return host.catalog();
    }

    public <T> T call(Callable<T> action) throws SQLException {
        if (closed) {
            throw new SQLException("dbsync target is closed");
        }
        Future<T> future = writer.submit(() -> {
            ensureConnection();
            return action.call();
        });
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted while waiting for DuckDB writer", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new SQLException("DuckDB writer failed: " + cause.getMessage(), cause);
        }
    }

    public void run(SqlRunnable action) throws SQLException {
        call(() -> {
            action.run();
            return null;
        });
    }

    private void ensureConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            Connection opened = host.duplicate();
            opened.setAutoCommit(true);
            applyQueryTimeout(opened);
            connection = opened;
            logger.info("[DBSYNCER] DuckDB target connection opened (catalog={}, queryTimeoutMs={})",
                    host.catalog(), queryTimeoutMs);
        }
    }

    /**
     * DuckDB 的 {@code max_execution_time} 是会话级、单位为毫秒，超时语句被中断后连接仍可用。
     *
     * <p>旧版本 DuckDB 没有这个参数（{@code 3F000}）：只告警，不让整个模块因此不可用。</p>
     */
    private void applyQueryTimeout(Connection opened) {
        if (queryTimeoutMs <= 0) {
            return;
        }
        try (Statement statement = opened.createStatement()) {
            statement.execute("SET max_execution_time = " + queryTimeoutMs);
        } catch (SQLException e) {
            logger.warn("[DBSYNCER] cannot set max_execution_time={} ms on target connection: {}",
                    queryTimeoutMs, e.getMessage());
        }
    }

    /** 只在 writer 线程内调用。 */
    public Connection connection() throws SQLException {
        ensureConnection();
        return connection;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        writer.submit(() -> {
            if (connection != null) {
                try {
                    if (!connection.getAutoCommit()) {
                        connection.rollback();
                    }
                } catch (SQLException ignored) {
                }
                try {
                    connection.close();
                } catch (SQLException e) {
                    logger.warn("[DBSYNCER] Failed to close DuckDB target connection: {}", e.getMessage());
                }
                connection = null;
            }
            return null;
        });
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }

    @FunctionalInterface
    public interface SqlRunnable {
        void run() throws SQLException;
    }
}
