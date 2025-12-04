package org.slackerdb.dbserver.server.repl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.duckdb.DuckDBConnection;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.Objects;

public class SqlReplServer {
    private static final Map<String, SqlSession> sessions = new ConcurrentHashMap<>();
    private static final ExecutorService sqlExecutor = Executors.newCachedThreadPool();
    private static final ObjectMapper mapper = new ObjectMapper();

    public void run(Javalin app, Connection conn) throws Exception {
        // 只注册 WebSocket 端点
        app.ws("/sql/ws", ws -> {
            ws.onConnect(ctx -> {
                // 连接建立时不做任何事，等待客户端发送 start 消息
            });

            ws.onMessage(ctx -> {
                try {
                    Map<String, Object> msg = mapper.readValue(
                            ctx.message(),
                            new TypeReference<>() {}
                    );
                    handleWebSocketMessage(ctx, msg, conn);
                } catch (Exception e) {
                    sendError(ctx, "Invalid message format: " + e.getMessage());
                }
            });

            ws.onClose(ctx -> {
                // 清理与该连接关联的会话
                String sessionId = ctx.attribute("sessionId");
                if (sessionId != null) {
                    SqlSession session = sessions.remove(sessionId);
                    if (session != null) {
                        try {
                            session.conn.close();
                        } catch (Exception ignored) {}
                    }
                }
            });

            ws.onError(ctx -> {
                // 记录错误
                System.err.println("WebSocket error: " + ctx.error());
            });
        });
    }

    private void handleWebSocketMessage(WsContext ctx, Map<String, Object> msg, Connection conn) {
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
                handleStart(ctx, id, conn);
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

    private void handleStart(WsContext ctx, String requestId, Connection conn) {
        try {
            String sid = UUID.randomUUID().toString();
            Connection localConn = ((DuckDBConnection) conn).duplicate();
            localConn.setAutoCommit(true);

            SqlSession session = new SqlSession(sid, localConn);
            sessions.put(sid, session);
            ctx.attribute("sessionId", sid);

            Map<String, Object> response = new HashMap<>();
            response.put("id", requestId);
            response.put("type", "start");
            response.put("data", Map.of("sessionId", sid));
            ctx.send(mapper.writeValueAsString(response));
        } catch (Exception e) {
            sendError(ctx, "Failed to start session: " + e.getMessage());
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
        session.activeFuture = sqlExecutor.submit(() -> {
            try {
                Statement stmt = session.conn.createStatement();
                if (finalFetchSize > 0) {
                    stmt.setFetchSize(finalFetchSize);
                }
                session.taskStatement = stmt;
                session.activeStatement = stmt;

                boolean hasResult = stmt.execute(finalSql);
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
                session.taskStatus = "ERROR";
                session.error = Objects.toString(e.getMessage(), "");
                // 清理资源
                if (session.taskStatement != null) {
                    try { session.taskStatement.close(); } catch (Exception ignored) {}
                    session.taskStatement = null;
                    session.activeStatement = null;
                }
                session.taskResultSet = null;
            } catch (Throwable t) {
                session.taskStatus = "ERROR";
                session.error = "Internal error: " + Objects.toString(t.getMessage(), "");
                // 清理资源
                if (session.taskStatement != null) {
                    try { session.taskStatement.close(); } catch (Exception ignored) {}
                    session.taskStatement = null;
                    session.activeStatement = null;
                }
                session.taskResultSet = null;
            }
        });

        // 立即返回 taskId
        Map<String, Object> response = new HashMap<>();
        response.put("id", requestId);
        response.put("type", "exec");
        response.put("data", Map.of("taskId", taskId, "status", "running"));
        try {
            ctx.send(mapper.writeValueAsString(response));
        } catch (Exception e) {
            // 忽略发送错误
        }
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

        SqlSession session = sessions.get(sid);
        if (session == null) {
            sendError(ctx, "Invalid session");
            return;
        }

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
                    while (rs.next() && fetched < maxRows) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (String c : columns) {
                            row.put(c, rs.getObject(c));
                        }
                        rows.add(row);
                        fetched++;
                        session.fetchedRowCount.incrementAndGet();
                    }
                    // 判断是否还有更多行
                    more = fetched == maxRows;
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

        try {
            ctx.send(mapper.writeValueAsString(response));
        } catch (Exception e) {
            // 忽略发送错误
        }
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

        boolean canceled = false;
        String message = "";
        Future<?> future = session.activeFuture;
        if (future != null && !future.isDone()) {
            future.cancel(true); // 尝试中断线程
            canceled = true;
            message = "future canceled";
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
        }

        Map<String, Object> response = new HashMap<>();
        response.put("id", requestId);
        response.put("type", "cancel");
        response.put("data", Map.of("status", canceled ? "canceled" : "no-running-statement", "detail", message));
        try {
            ctx.send(mapper.writeValueAsString(response));
        } catch (Exception e) {
            // 忽略发送错误
        }
    }

    private void handleClose(WsContext ctx, String requestId, Map<String, Object> data) {
        Object sidObj = data.get("sessionId");
        if (!(sidObj instanceof String sid)) {
            sendError(ctx, "Field 'sessionId' must be a string");
            return;
        }
        SqlSession session = sessions.remove(sid);
        if (session != null) {
            try {
                session.conn.close();
            } catch (Exception ignored) {}
        }

        Map<String, Object> response = new HashMap<>();
        response.put("id", requestId);
        response.put("type", "close");
        response.put("data", Map.of("status", "closed"));
        try {
            ctx.send(mapper.writeValueAsString(response));
        } catch (Exception e) {
            // 忽略发送错误
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
}
