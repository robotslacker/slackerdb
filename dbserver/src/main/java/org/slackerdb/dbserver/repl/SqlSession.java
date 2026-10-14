package org.slackerdb.dbserver.repl;

import org.slackerdb.plsql.spi.DefaultJdbcHost;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class SqlSession {
    public final String id;
    public final Connection conn;
    public volatile Statement activeStatement;  // 用于 cancel()
    public volatile Future<?> activeFuture;    // 用于取消任务
    /** 正在执行的 PL/SQL 宿主（仅脚本/匿名块任务期间非空，用于把取消信号转给引擎）。 */
    public volatile DefaultJdbcHost plsqlHost;
    /**
     * 最后一次活动时间（毫秒）。心跳据此回收长时间空闲的会话：
     * 连接可能以"半开"状态存在（客户端已断开但服务端看不出），只靠探活回收不了。
     */
    public volatile long lastActivityMillis = System.currentTimeMillis();

    /** 记录一次活动（收到该会话的任何请求时调用）。 */
    public void touch() {
        this.lastActivityMillis = System.currentTimeMillis();
    }

    // 任务相关字段
    public volatile String taskId;
    public volatile String taskStatus; // "RUNNING", "COMPLETED", "ERROR"
    public volatile ResultSet taskResultSet;
    public volatile Statement taskStatement;
    public volatile AtomicInteger fetchedRowCount = new AtomicInteger(0);
    public volatile int totalRows; // -1 表示未知
    public volatile String error;
    public volatile boolean hasMore;
    // 缓冲行，用于处理分页时多读的一行
    public volatile Map<String, Object> pendingRow;
    public volatile boolean hasPendingRow;

    public SqlSession(String id, Connection conn) {
        this.id = id;
        this.conn = conn;
        this.taskStatus = "IDLE";
        this.hasPendingRow = false;
        this.pendingRow = null;
    }

    public void resetTask() {
        this.taskId = null;
        this.taskStatus = "IDLE";
        if (this.taskResultSet != null) {
            try { this.taskResultSet.close(); } catch (Exception ignored) {}
            this.taskResultSet = null;
        }
        if (this.taskStatement != null) {
            try { this.taskStatement.close(); } catch (Exception ignored) {}
            this.taskStatement = null;
        }
        // 语句句柄与任务句柄必须一起清空：否则取消之后 activeStatement 仍指向已关闭的
        // Statement，第二次 cancel 会返回 "Statement was closed" 而不是文档承诺的
        // no-running-statement。
        this.activeStatement = null;
        this.activeFuture = null;
        this.plsqlHost = null;
        this.fetchedRowCount.set(0);
        this.totalRows = -1;
        this.error = null;
        this.hasMore = false;
        this.hasPendingRow = false;
        this.pendingRow = null;
    }
}
