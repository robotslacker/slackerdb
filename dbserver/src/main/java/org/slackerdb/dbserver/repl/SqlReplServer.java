package org.slackerdb.dbserver.repl;

import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.duckdb.DuckDBConnection;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.sql.JsonValues;
import org.slackerdb.plsql.PlSqlEngine;
import org.slackerdb.plsql.detect.PlSqlDetector;
import org.slackerdb.plsql.detect.PlSqlScript;
import org.slackerdb.plsql.detect.PlSqlStatement;
import org.slackerdb.plsql.spi.DefaultJdbcHost;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class SqlReplServer {
    private final Map<String, SqlSession> sessions = new ConcurrentHashMap<>();
    /**
     * 连接 -> 该连接上创建的全部会话。
     *
     * <p>{@code WsContext} 是按事件构造的，{@code ctx.attribute(...)} 无法可靠地跨事件保存状态；
     * 更关键的是在本项目的 Javalin 7 + Jetty 12 组合下 <b>onClose 根本不会被回调</b>
     * （实测：客户端正常 sendClose 后等 120 秒也收不到 onClose，连接也不会被回收）。
     * 因此会话回收不能只依赖 onClose，这里按连接标识自行登记，并配合心跳做兜底回收。</p>
     */
    private final Map<String, List<String>> connectionSessions = new ConcurrentHashMap<>();
    private final ExecutorService sqlExecutor = Executors.newCachedThreadPool();
    private final ObjectMapper mapper = new ObjectMapper();
    /** 连接标识 -> 连接上第一次 onConnect 拿到的 WsContext（用于探活/ping）。 */
    private final Map<String, WsContext> activeConnections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean heartbeatStarted = new AtomicBoolean(false);
    private final Logger logger;
    private final Javalin managementApp;
    private final DBInstance dbInstance;

    public SqlReplServer(
        DBInstance dbInstance,
        Javalin managementApp,
        Logger logger
    )
    {
        this.dbInstance = dbInstance;
        this.logger = logger;
        this.managementApp = managementApp;
    }

    public void run() throws Exception {
        // 只注册 WebSocket 端点
        this.managementApp.unsafe.routes.ws("/sql/ws", ws -> {
            ws.onConnect(ctx -> {
                String key = connectionKey(ctx);
                if (key != null) {
                    activeConnections.put(key, ctx);
                }
                startHeartbeatIfNeeded();
                logger.debug("[REPL] connection opened: {}", key);
            });

            ws.onMessage(ctx -> {
                try {
                    // 发送心跳链接
                    if (ctx.message().equals("ping")) {
                        ctx.send("pong");
                        return;
                    }

                    Map<String, Object> msg = mapper.readValue(
                            ctx.message(),
                            new TypeReference<>() {}
                    );
                    handleWebSocketMessage(ctx, msg);
                } catch (Exception e) {
                    sendError(ctx, "Invalid message format: " + e.getMessage());
                }
            });


            ws.onClose(ctx -> {
                // onClose 在本项目使用的 Javalin/Jetty 组合下实测不会触发，
                // 保留它只是为了兼容将来版本能正常回调的情况；真正的回收由心跳兜底。
                logger.debug("[REPL] connection closed (onClose fired): {}", connectionKey(ctx));
                releaseConnection(connectionKey(ctx));
            });

            ws.onError(ctx -> {
                // 连接出错（含异常断开）是可靠的回收时机之一
                releaseConnection(connectionKey(ctx));
                // 记录错误，但过滤连接中断
                Throwable error = ctx.error();
                if (isConnectionInterruption(error)) {
                    // 连接中断是正常现象，不记录为错误
                    logger.debug("WebSocket connection interrupted: {}", error == null ? null : error.getMessage());
                } else {
                    logger.error("WebSocket error", error);
                }
            });
        });
    }

    private void handleWebSocketMessage(WsContext ctx, Map<String, Object> msg) {
        Object idObj = msg.get("id");
        Object typeObj = msg.get("type");
        if (!(idObj instanceof String id)) {
            sendError(ctx, "Field 'id' must be a string");
            return;
        }
        if (!(typeObj instanceof String type)) {
            sendError(ctx, "Field 'type' must be a string");
            return;
        }
        Object dataObj = msg.get("data");
        Map<String, Object> data;
        if (dataObj != null) {
            if (dataObj instanceof Map) {
                // 安全转换
                @SuppressWarnings("unchecked")
                Map<String, Object> typedData = (Map<String, Object>) dataObj;
                data = typedData;
            } else {
                sendError(ctx, "Field 'data' must be an object");
                return;
            }
        } else {
            data = Collections.emptyMap();
        }

        switch (type) {
            case "start":
                handleStart(ctx, id);
                break;
            case "exec":
                handleExec(ctx, id, data);
                break;
            case "fetch":
                handleFetch(ctx, id, data);
                break;
            case "cancel":
                handleCancel(ctx, id, data);
                break;
            case "close":
                handleClose(ctx, id, data);
                break;
            default:
                sendError(ctx, "Unknown type: " + type);
        }
    }

    private void handleStart(WsContext ctx, String requestId) {
        try {
            String sid = UUID.randomUUID().toString();
            Connection localConn =
                    ((DuckDBConnection) this.dbInstance.backendSysConnection).duplicate();
            localConn.setAutoCommit(true);

            SqlSession session = new SqlSession(sid, localConn);
            sessions.put(sid, session);
            trackSession(ctx, sid);

            Map<String, Object> response = new HashMap<>();
            response.put("id", requestId);
            response.put("type", "start");
            response.put("data", Map.of("sessionId", sid));
            sendJson(ctx, response);
        } catch (Exception e) {
            sendError(ctx, "Failed to start session: " + e.getMessage());
        }
    }

    /**
     * 连接标识：Javalin 的 WebSocket session id（同一连接的所有事件都相同）。
     * 极端情况下取不到时退化为 Jetty Session 的身份哈希。
     */
    private String connectionKey(WsContext ctx) {
        String id = ctx.sessionId();
        if (id != null && !id.isEmpty()) {
            return id;
        }
        return ctx.session == null ? null : "session@" + System.identityHashCode(ctx.session);
    }

    /** 登记连接上的会话，连接关闭时逐个释放。 */
    private void trackSession(WsContext ctx, String sessionId) {
        String key = connectionKey(ctx);
        if (key != null) {
            connectionSessions.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(sessionId);
        }
        logger.debug("[REPL] session {} tracked on connection {}", sessionId, key);
        // 兼容保留：最近一次创建的会话
        ctx.attribute("sessionId", sessionId);
    }

    /** 释放某个连接上登记的全部会话（幂等）。 */
    private void releaseConnection(String key) {
        if (key == null) {
            return;
        }
        activeConnections.remove(key);
        List<String> sessionIds = connectionSessions.remove(key);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return;
        }
        List<String> snapshot = new ArrayList<>(sessionIds);
        for (String id : snapshot) {
            SqlSession session = sessions.remove(id);
            if (session != null) {
                closeSessionQuietly(session);
            }
        }
        logger.debug("[REPL] released {} session(s) of connection {}", snapshot.size(), key);
    }

    /** 会话空闲回收后，把它从所有连接的登记表里摘掉。 */
    private void forgetSessionFromConnections(String sessionId) {
        for (List<String> ids : connectionSessions.values()) {
            ids.remove(sessionId);
        }
    }

    /** 静默释放一个会话持有的所有资源。 */
    private void closeSessionQuietly(SqlSession session) {
        if (session.taskResultSet != null) {
            try { session.taskResultSet.close(); } catch (Exception ignored) {}
            session.taskResultSet = null;
        }
        if (session.taskStatement != null) {
            try { session.taskStatement.close(); } catch (Exception ignored) {}
            session.taskStatement = null;
        }
        if (session.activeFuture != null) {
            session.activeFuture.cancel(true);
            session.activeFuture = null;
        }
        session.activeStatement = null;
        try {
            session.conn.close();
        } catch (Exception ignored) {}
    }

    /** 统一的响应发送：失败必须留痕，不能像历史实现那样静默吞掉。 */
    private void sendJson(WsContext ctx, Map<String, Object> response) {
        String payload;
        try {
            payload = mapper.writeValueAsString(response);
        } catch (Exception e) {
            logger.error("Failed to encode WebSocket response", e);
            sendError(ctx, "Failed to encode response: " + e.getMessage());
            return;
        }
        try {
            ctx.send(payload);
        } catch (Exception e) {
            logger.debug("Failed to send WebSocket response: {}", e.getMessage());
        }
    }

    private void handleExec(WsContext ctx, String requestId, Map<String, Object> data) {
        Object sidObj = data.get("sessionId");
        Object sqlObj = data.get("sql");
        Object fetchSizeObj = data.getOrDefault("fetchSize", 100);
        if (!(sidObj instanceof String sid)) {
            sendError(ctx, "Field 'sessionId' must be a string");
            return;
        }
        if (!(sqlObj instanceof String sql)) {
            sendError(ctx, "Field 'sql' must be a string");
            return;
        }
        int fetchSize = 100;
        if (fetchSizeObj instanceof Number) {
            fetchSize = ((Number) fetchSizeObj).intValue();
        } else if (fetchSizeObj instanceof String) {
            try {
                fetchSize = Integer.parseInt((String) fetchSizeObj);
            } catch (NumberFormatException e) {
                sendError(ctx, "Field 'fetchSize' must be a number");
                return;
            }
        } else if (fetchSizeObj != null) {
            sendError(ctx, "Field 'fetchSize' must be a number");
            return;
        }

        SqlSession session = sessions.get(sid);
        if (session == null) {
            sendError(ctx, "Invalid session");
            return;
        }
        session.touch();

        // 检查是否已有正在执行的任务
        if ("RUNNING".equals(session.taskStatus)) {
            sendError(ctx, "Another SQL is already running");
            return;
        }

        // 生成 taskId
        String taskId = UUID.randomUUID().toString();
        session.resetTask();
        session.taskId = taskId;
        session.taskStatus = "RUNNING";
        session.activeFuture = null;
        session.activeStatement = null;

        // 复制到 final 变量以便在 lambda 中使用
        final int finalFetchSize = fetchSize;
        final String finalSql = sql;

        // 提交任务到线程池
        session.activeFuture = sqlExecutor.submit(() -> runTask(session, finalSql, finalFetchSize));

        // 立即返回 taskId
        Map<String, Object> response = new HashMap<>();
        response.put("id", requestId);
        response.put("type", "exec");
        response.put("data", Map.of("taskId", taskId, "status", "running"));
        sendJson(ctx, response);
    }

    /**
     * 在会话连接上执行一个任务：普通 SQL、PL/SQL 匿名块，或多语句脚本。
     *
     * <p>历史实现把客户端原文直接丢给 JDBC，于是 {@code DO $$...$$} 这类 PL/SQL 块
     * 在 REPL 里必然语法错误——而 PG 协议路径早就走 {@code PlSqlDetector/PlSqlEngine} 了。
     * 这里复用同一套检测与引擎，两条入口能力对齐。</p>
     */
    private void runTask(SqlSession session, String sql, int fetchSize) {
        try {
            PlSqlScript script = PlSqlDetector.analyze(sql);
            if (script.hasError()) {
                session.taskStatus = "ERROR";
                session.error = script.error();
                return;
            }
            if (script.isSingleBlock() || script.isScript()) {
                // 匿名块 / 多语句脚本：逐条执行，只保留最后一条语句的结果集
                runScript(session, script);
                return;
            }
            if (script.isEmpty()) {
                // 空脚本 / 只有注释：不交给数据库
                session.totalRows = 0;
                session.hasMore = false;
                session.taskStatus = "COMPLETED";
                return;
            }

            Statement stmt = session.conn.createStatement();
            if (fetchSize > 0) {
                stmt.setFetchSize(fetchSize);
            }
            session.taskStatement = stmt;
            session.activeStatement = stmt;

            boolean hasResult = stmt.execute(sql);
            if (hasResult) {
                session.taskResultSet = stmt.getResultSet();
                session.taskStatus = "COMPLETED"; // 结果集就绪
                session.hasMore = true;
                // 总行数未知，暂不计算
            } else {
                // 更新语句，立即完成
                session.totalRows = stmt.getUpdateCount();
                session.taskStatus = "COMPLETED";
                session.hasMore = false;
                // 关闭 statement
                stmt.close();
                session.taskStatement = null;
                session.activeStatement = null;
            }
        } catch (SQLException e) {
            failTask(session, Objects.toString(e.getMessage(), ""));
        } catch (Throwable t) {
            failTask(session, "Internal error: " + Objects.toString(t.getMessage(), ""));
        }
    }

    /**
     * 顺序执行脚本中的每条语句。
     *
     * <p>语义（与简单查询协议一致）：每条语句都真的执行；
     * 前一条语句的结果集会被立即关闭（同一连接不能同时挂多个打开的结果集），
     * 因此最终 fetch 拿到的是**最后一条产出结果集的语句**的数据。</p>
     */
    private void runScript(SqlSession session, PlSqlScript script) {
        DefaultJdbcHost host = new DefaultJdbcHost(session.conn);
        session.plsqlHost = host;
        ResultSet lastResultSet = null;
        Statement lastStatement = null;
        long affectedRows = 0;
        try {
            for (PlSqlStatement statement : script.statements()) {
                if (lastResultSet != null) {
                    try { lastResultSet.close(); } catch (Exception ignored) {}
                    lastResultSet = null;
                }
                if (lastStatement != null) {
                    try { lastStatement.close(); } catch (Exception ignored) {}
                    lastStatement = null;
                }
                if (statement.isBlock()) {
                    // 匿名块交给 PL/SQL 引擎（与 PG 路径同一个引擎）
                    session.activeStatement = null;
                    PlSqlEngine.execute(host, statement.body());
                    continue;
                }
                Statement stmt = session.conn.createStatement();
                session.activeStatement = stmt;
                if (stmt.execute(statement.sql())) {
                    lastResultSet = stmt.getResultSet();
                    lastStatement = stmt;
                } else {
                    int updates = stmt.getUpdateCount();
                    if (updates > 0) {
                        affectedRows += updates;
                    }
                    stmt.close();
                }
            }
            if (lastResultSet != null) {
                session.taskResultSet = lastResultSet;
                session.taskStatement = lastStatement;
                session.activeStatement = lastStatement;
                session.hasMore = true;
                session.taskStatus = "COMPLETED";
            } else {
                session.totalRows = affectedRows > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) affectedRows;
                session.hasMore = false;
                session.taskStatus = "COMPLETED";
                session.taskStatement = null;
                session.activeStatement = null;
            }
        } catch (Throwable t) {
            if (lastResultSet != null) {
                try { lastResultSet.close(); } catch (Exception ignored) {}
            }
            if (lastStatement != null) {
                try { lastStatement.close(); } catch (Exception ignored) {}
            }
            String message = t.getMessage();
            if (t instanceof org.slackerdb.plsql.PlSqlException plSqlException) {
                message = plSqlException.getMessage();
            }
            failTask(session, Objects.toString(message, t.getClass().getSimpleName()));
        } finally {
            session.plsqlHost = null;
        }
    }

    /** 任务失败时的统一收尾。 */
    private void failTask(SqlSession session, String message) {
        session.taskStatus = "ERROR";
        session.error = message;
        if (session.taskStatement != null) {
            try { session.taskStatement.close(); } catch (Exception ignored) {}
            session.taskStatement = null;
        }
        session.activeStatement = null;
        session.taskResultSet = null;
    }

    private void handleFetch(WsContext ctx, String requestId, Map<String, Object> data) {
        Object sidObj = data.get("sessionId");
        Object taskIdObj = data.get("taskId");
        Object maxRowsObj = data.getOrDefault("maxRows", 100);
        if (!(sidObj instanceof String sid)) {
            sendError(ctx, "Field 'sessionId' must be a string");
            return;
        }
        if (!(taskIdObj instanceof String taskId)) {
            sendError(ctx, "Field 'taskId' must be a string");
            return;
        }
        int maxRows = 100;
        if (maxRowsObj instanceof Number) {
            maxRows = ((Number) maxRowsObj).intValue();
        } else if (maxRowsObj instanceof String) {
            try {
                maxRows = Integer.parseInt((String) maxRowsObj);
            } catch (NumberFormatException e) {
                sendError(ctx, "Field 'maxRows' must be a number");
                return;
            }
        } else if (maxRowsObj != null) {
            sendError(ctx, "Field 'maxRows' must be a number");
            return;
        }
        if (maxRows <= 0) {
            // 0/负数无法表达"取几行"：历史实现会让客户端陷入永远 hasMore=true 的死循环
            sendError(ctx, "Field 'maxRows' must be a positive number");
            return;
        }

        SqlSession session = sessions.get(sid);
        if (session == null) {
            sendError(ctx, "Invalid session");
            return;
        }
        session.touch();

        if (!taskId.equals(session.taskId)) {
            sendError(ctx, "Invalid taskId");
            return;
        }

        String status = session.taskStatus;
        Map<String, Object> response = new HashMap<>();
        response.put("id", requestId);
        response.put("type", "fetch");

        if ("RUNNING".equals(status)) {
            // 任务还在执行中，返回 running 状态
            response.put("data", Map.of("status", "running"));
        } else if ("ERROR".equals(status)) {
            response.put("data", Map.of("status", "error", "error", session.error));
        } else if ("COMPLETED".equals(status)) {
            // 检查是否有结果集
            ResultSet rs = session.taskResultSet;
            if (rs == null) {
                // 更新语句，返回 updateCount
                response.put("data", Map.of(
                        "status", "completed",
                        "updateCount", session.totalRows
                ));
            } else {
                // 从结果集读取一批行
                List<Map<String, Object>> rows = new ArrayList<>();
                ResultSetMetaData md;
                try {
                    md = rs.getMetaData();
                } catch (SQLException e) {
                    sendError(ctx, "Failed to get result set metadata: " + e.getMessage());
                    return;
                }
                int colCount;
                try {
                    colCount = md.getColumnCount();
                } catch (SQLException e) {
                    sendError(ctx, "Failed to get column count: " + e.getMessage());
                    return;
                }
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    try {
                        columns.add(md.getColumnLabel(i));
                    } catch (SQLException e) {
                        sendError(ctx, "Failed to get column label: " + e.getMessage());
                        return;
                    }
                }

                int fetched = 0;
                boolean more;
                try {
                    // 如果有缓冲行，先处理缓冲行
                    if (session.hasPendingRow && session.pendingRow != null) {
                        rows.add(session.pendingRow);
                        fetched++;
                        session.fetchedRowCount.incrementAndGet();
                        session.hasPendingRow = false;
                        session.pendingRow = null;
                    }
                    // 继续读取直到达到 maxRows 或结果集耗尽。
                    // 条件顺序必须是"先判配额、再取行"：写成 rs.next() && fetched < maxRows
                    // 会在配额已满时仍然把下一行读出来并丢弃（短路求值），
                    // 于是每翻一页静默丢一行——分页数据丢失的历史缺陷。
                    while (fetched < maxRows && rs.next()) {
                        rows.add(readRow(rs, columns));
                        fetched++;
                        session.fetchedRowCount.incrementAndGet();
                    }
                    // 判断是否还有更多行
                    if (fetched == maxRows) {
                        // 尝试再读一行，探测是否还有更多数据
                        if (rs.next()) {
                            // 还有一行，缓冲起来
                            session.pendingRow = readRow(rs, columns);
                            session.hasPendingRow = true;
                            more = true;
                        } else {
                            // 没有更多行
                            more = false;
                        }
                    } else {
                        // 读取的行数不足 maxRows，说明结果集已耗尽
                        more = false;
                    }
                } catch (SQLException e) {
                    sendError(ctx, "Failed to fetch rows: " + e.getMessage());
                    return;
                }

                session.hasMore = more;

                Map<String, Object> result = new HashMap<>();
                result.put("status", "completed");
                result.put("columns", columns);
                result.put("rows", rows);
                result.put("hasMore", more);
                result.put("fetched", session.fetchedRowCount.get());
                if (!more) {
                    // 没有更多行，关闭结果集和语句
                    try { rs.close(); } catch (Exception ignored) {}
                    try { session.taskStatement.close(); } catch (Exception ignored) {}
                    session.taskResultSet = null;
                    session.taskStatement = null;
                    session.activeStatement = null;
                    session.taskStatus = "IDLE";
                }
                response.put("data", result);
            }
        } else {
            // IDLE 或其他状态
            response.put("data", Map.of("status", "idle"));
        }

        sendJson(ctx, response);
    }

    /**
     * 读取一行并把每个列值转换成 JSON 安全形态。
     *
     * <p>不做这一步的话，DATE/TIME/TIMESTAMPTZ/BLOB/LIST 等列会让 Jackson 序列化失败，
     * 而失败又被静默吞掉，客户端永远收不到任何响应。</p>
     */
    private static Map<String, Object> readRow(ResultSet rs, List<String> columns) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String column : columns) {
            row.put(column, JsonValues.toJsonValue(rs.getObject(column)));
        }
        return row;
    }

    private void handleCancel(WsContext ctx, String requestId, Map<String, Object> data) {
        Object sidObj = data.get("sessionId");
        if (!(sidObj instanceof String sid)) {
            sendError(ctx, "Field 'sessionId' must be a string");
            return;
        }
        SqlSession session = sessions.get(sid);
        if (session == null) {
            sendError(ctx, "Invalid session");
            return;
        }
        session.touch();

        boolean canceled = false;
        String message = "";
        Future<?> future = session.activeFuture;
        if (future != null && !future.isDone()) {
            future.cancel(true); // 尝试中断线程
            canceled = true;
            message = "future canceled";
        }

        // 正在执行 PL/SQL 脚本时，通知引擎在语句之间停下（引擎只认这个取消信号）
        DefaultJdbcHost host = session.plsqlHost;
        if (host != null) {
            host.requestCancel();
            canceled = true;
            message = "plsql canceled";
        }

        Statement stmt = session.activeStatement;
        if (stmt != null) {
            try {
                stmt.cancel();
                canceled = true;
                message = "statement canceled";
            } catch (Exception e) {
                sendError(ctx, "Failed to cancel statement: " + e.getMessage());
                return;
            }
        }

        // 清理任务状态
        if (canceled) {
            session.resetTask();
            // 已取消的语句不能再被 cancel 第二次（否则会把 Statement was closed 当成错误回给客户端，
            // 而文档承诺此时应返回 no-running-statement）
            session.activeStatement = null;
            session.activeFuture = null;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("id", requestId);
        response.put("type", "cancel");
        response.put("data", Map.of("status", canceled ? "canceled" : "no-running-statement", "detail", message));
        sendJson(ctx, response);
    }

    private void handleClose(WsContext ctx, String requestId, Map<String, Object> data) {
        Object sidObj = data.get("sessionId");
        if (!(sidObj instanceof String sid)) {
            sendError(ctx, "Field 'sessionId' must be a string");
            return;
        }
        SqlSession session = sessions.remove(sid);
        if (session != null) {
            closeSessionQuietly(session);
            forgetTrackedSession(ctx, sid);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("id", requestId);
        response.put("type", "close");
        response.put("data", Map.of("status", "closed"));
        sendJson(ctx, response);
    }

    /** 会话被显式 close 后，从连接的登记表里摘掉，避免连接关闭时重复释放。 */
    private void forgetTrackedSession(WsContext ctx, String sessionId) {
        String key = connectionKey(ctx);
        List<String> sessionIds = key == null ? null : connectionSessions.get(key);
        if (sessionIds != null) {
            sessionIds.remove(sessionId);
        }
        if (sessionId.equals(ctx.attribute("sessionId"))) {
            ctx.attribute("sessionId", null);
        }
    }

    private void sendError(WsContext ctx, String error) {
        try {
            Map<String, Object> err = new HashMap<>();
            err.put("error", error);
            ctx.send(mapper.writeValueAsString(err));
        } catch (Exception e) {
            // 忽略
        }
    }

    private boolean isConnectionInterruption(Throwable error) {
        if (error == null) {
            return false;
        }
        // 检查异常类型是否为常见的连接中断异常
        if (error instanceof java.nio.channels.ClosedChannelException || error instanceof java.io.EOFException) {
            return true;
        }
        // 检查 SocketException 且消息包含 closed 或 reset
        if (error instanceof java.net.SocketException) {
            String msg = error.getMessage();
            if (msg != null) {
                msg = msg.toLowerCase();
                if (msg.contains("closed") || msg.contains("reset") || msg.contains("broken pipe")) {
                    return true;
                }
            }
        }
        String msg = error.getMessage();
        if (msg == null) {
            return false;
        }
        msg = msg.toLowerCase();
        return  msg.contains("connection reset") ||
                msg.contains("closed") ||
                msg.contains("interrupted") ||
                msg.contains("broken pipe") ||
                msg.contains("connection abort") ||
                msg.contains("stream closed") ||
                msg.contains("connection idle timeout") ;
    }

    private void startHeartbeatIfNeeded() {
        if (heartbeatStarted.compareAndSet(false, true)) {
            heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeat, 10, 10, TimeUnit.SECONDS);
            logger.trace("WebSocket heartbeat started (interval 10s)");
        }
    }

    /**
     * 心跳 + 回收。
     *
     * <p>两件事必须一起做，否则 REPL 会持续泄漏 DuckDB 连接：</p>
     * <ol>
     *   <li><b>连接探活</b>：连接已关闭或 ping 失败 -> 释放该连接上的全部会话
     *       （onClose 在本项目的 Javalin/Jetty 组合下不会回调，只能主动探）；</li>
     *   <li><b>会话空闲回收</b>：连接探活有极限（半开连接看起来仍然是 open），
     *       因此再加一道"空闲超过 client_timeout 就回收"的兜底，
     *       保证资源占用有明确上界。正在跑任务的会话不回收。</li>
     * </ol>
     */
    private void sendHeartbeat() {
        // 1) 连接级探活
        for (Map.Entry<String, WsContext> entry : activeConnections.entrySet()) {
            String key = entry.getKey();
            WsContext ctx = entry.getValue();
            boolean alive;
            try {
                alive = ctx.session != null && ctx.session.isOpen();
            } catch (Throwable t) {
                alive = false;
            }
            if (alive) {
                try {
                    ctx.sendPing();
                } catch (Throwable t) {
                    alive = false;
                }
            }
            if (!alive) {
                logger.debug("[REPL] connection {} is no longer alive; collecting its sessions", key);
                releaseConnection(key);
            }
        }

        // 2) 会话级空闲回收
        long idleTimeoutMillis = (long) Math.max(10, this.dbInstance.serverConfiguration.getClient_timeout()) * 1000L;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, SqlSession> entry : sessions.entrySet()) {
            SqlSession session = entry.getValue();
            if ("RUNNING".equals(session.taskStatus)) {
                continue;
            }
            long idle = now - session.lastActivityMillis;
            if (idle > idleTimeoutMillis) {
                if (sessions.remove(entry.getKey(), session)) {
                    logger.debug("[REPL] session {} idle for {}ms (> {}ms); releasing",
                            session.id, idle, idleTimeoutMillis);
                    closeSessionQuietly(session);
                    forgetSessionFromConnections(session.id);
                }
            }
        }
    }
}
