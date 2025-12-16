package org.slackerdb.dbserver.repl;

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
        this.fetchedRowCount.set(0);
        this.totalRows = -1;
        this.error = null;
        this.hasMore = false;
        this.hasPendingRow = false;
        this.pendingRow = null;
    }
}
