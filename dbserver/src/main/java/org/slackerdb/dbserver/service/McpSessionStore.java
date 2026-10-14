package org.slackerdb.dbserver.service;

import ch.qos.logback.classic.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 跨调用状态的载体：<b>服务端铸造的显式 handle</b>。
 *
 * <p>2026-07-28 取消了协议级会话（{@code Mcp-Session-Id} 与 {@code initialize} 都没了），
 * 规范要求需要跨调用状态的服务器把该状态变成"服务端铸造的 handle，作为普通工具参数传递"，
 * 并且明确举了"一个数据库事务"作为动机案例。本类就是那个 handle 的存放处。</p>
 *
 * <p><b>为什么需要它</b>：{@code run_sql} 允许多条语句在同一个 DuckDB 连接上执行，
 * 因此临时表、{@code BEGIN} 事务、以及会话级设置都能跨调用存活。
 * 没有 handle 时每次调用都拿一条新连接，这些状态无从表达。</p>
 *
 * <p><b>生命周期</b>：handle 是随机的 UUID 字符串，只在内存中；
 * 空闲超过 {@code idleTimeoutMillis} 会被清理线程关闭并释放连接。
 * 连接走 {@link ConnectionFactory}（正常路径是 dbserver 的连接池），因此
 * handle 不会绕过 {@code max_connections}。</p>
 */
final class McpSessionStore {

    /** handle 的铸造来源：正常路径用 dbserver 的连接池，测试可注入自建连接。 */
    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    /** 一个会话 handle 的全部状态。 */
    static final class Session {
        final String handle;
        final Connection connection;
        final boolean readOnly;
        final long createdAtMillis;
        final AtomicLong statements = new AtomicLong();
        volatile long lastUsedMillis;

        Session(String handle, Connection connection, boolean readOnly) {
            this.handle = handle;
            this.connection = connection;
            this.readOnly = readOnly;
            this.createdAtMillis = System.currentTimeMillis();
            this.lastUsedMillis = this.createdAtMillis;
        }

        void touch() {
            this.lastUsedMillis = System.currentTimeMillis();
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final ConnectionFactory connectionFactory;
    private final Logger logger;

    /** 单个会话的默认语句超时（毫秒）。0 表示不设。 */
    private final int defaultStatementTimeoutMillis;

    /** 空闲多久后回收。 */
    private final long idleTimeoutMillis;

    /** 同时最多允许多少个会话 handle，防止无人回收时无限增长。 */
    private final int maxSessions;

    McpSessionStore(ConnectionFactory connectionFactory,
                    Logger logger,
                    int defaultStatementTimeoutMillis,
                    long idleTimeoutMillis,
                    int maxSessions) {
        this.connectionFactory = connectionFactory;
        this.logger = logger;
        this.defaultStatementTimeoutMillis = defaultStatementTimeoutMillis;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.maxSessions = maxSessions;
    }

    int defaultStatementTimeoutMillis() {
        return defaultStatementTimeoutMillis;
    }

    long idleTimeoutMillis() {
        return idleTimeoutMillis;
    }

    /**
     * 借一条短生命周期连接（不进 handle 表）。
     *
     * <p>只读内省类工具（{@code list_tables} / {@code describe_table} / {@code sample_rows}）
     * 以及无 handle 的 {@code run_sql} 用它：这些调用不需要跨调用状态，
     * 若也去铸造 handle 就会在无人 close 时把会话表撑满。</p>
     */
    Connection borrow() throws SQLException {
        reapIdle();
        return connectionFactory.open();
    }

    int size() {
        return sessions.size();
    }

    /**
     * 铸造一个新 handle。
     *
     * @param readOnly 该会话是否只读（只读会话会拒绝任何写语句）
     */
    Session create(boolean readOnly) throws SQLException {
        reapIdle();
        if (sessions.size() >= maxSessions) {
            throw new SQLException("Too many open MCP sessions (limit " + maxSessions
                    + "). Close one with tool 'close_session' or wait for idle expiry.");
        }
        Connection connection = connectionFactory.open();
        String handle = "mcp-" + UUID.randomUUID();
        Session session = new Session(handle, connection, readOnly);
        sessions.put(handle, session);
        logger.debug("[SERVER][MCP] session handle created: {} (readOnly={})", handle, readOnly);
        return session;
    }

    /** 按 handle 取会话；不存在则抛出让调用方翻成 -32602。 */
    Session require(String handle) {
        Session session = handle == null ? null : sessions.get(handle);
        if (session == null) {
            throw new McpProtocol.McpParamException(
                    "Unknown session handle '" + handle + "'. Create one with tool 'open_session'.");
        }
        session.touch();
        return session;
    }

    /** 关闭并释放一个 handle；幂等。 */
    boolean close(String handle) {
        Session session = handle == null ? null : sessions.remove(handle);
        if (session == null) {
            return false;
        }
        closeQuietly(session.connection);
        logger.debug("[SERVER][MCP] session handle closed: {}", handle);
        return true;
    }

    /** 关闭全部 handle（服务端停止时调用）。 */
    void closeAll() {
        for (Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
            Session session = it.next().getValue();
            it.remove();
            closeQuietly(session.connection);
        }
    }

    /** 回收空闲超时的 handle。 */
    void reapIdle() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
            Session session = it.next().getValue();
            if (now - session.lastUsedMillis > idleTimeoutMillis) {
                it.remove();
                closeQuietly(session.connection);
                logger.info("[SERVER][MCP] idle session handle expired: {} (statements={})",
                        session.handle, session.statements.get());
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 关闭失败不能掩盖业务异常
        }
    }

    /**
     * 给语句施加超时。
     *
     * <p>DuckDB 没有 per-query 超时（官方建议"在应用层做"），JDBC 的
     * {@code setQueryTimeout} 在 duckdb_jdbc 上也不保证生效。所以超时的<b>真正</b>执行
     * 由 {@link McpToolkit} 里的看门狗调用 {@link PreparedStatement#cancel()} 完成；
     * 这里只是尽力把值透传给驱动。</p>
     */
    static void applyQueryTimeout(PreparedStatement statement, int timeoutMillis) {
        if (timeoutMillis <= 0) {
            return;
        }
        try {
            statement.setQueryTimeout(Math.max(1, timeoutMillis / 1000));
        } catch (SQLException ignored) {
            // 驱动不支持时不阻断执行，由看门狗兜底
        }
    }
}
