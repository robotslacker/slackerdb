package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.plugin.bundled.CorsPluginConfig;
import org.duckdb.DuckDBConnection;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.utils.BoundedQueue;
import org.slackerdb.dbserver.BuildInfo;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.service.APIService;
import org.slackerdb.dbserver.service.BackupService;
import org.slackerdb.dbserver.service.PluginService;
import org.slackerdb.dbserver.entity.APIHistoryRecord;
import org.slackerdb.dbserver.repl.SqlReplServer;
import org.slackerdb.dbserver.scheduler.SchedulerConfigException;
import org.slackerdb.dbserver.scheduler.SchedulerModule;
import org.slackerdb.dbserver.service.McpServer;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.sql.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.io.IOException;
import java.lang.management.ThreadMXBean;
import com.alibaba.fastjson2.JSONArray;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class DBInstanceX {
    private final Logger logger;
    private final Javalin managementApp;
    private final Connection backendSysConnection;
    private final DBInstance dbInstance;
    private final AtomicLong backendApiHistoryId = new AtomicLong(1);
    private final APIService apiService;
    /** M5 定义存储服务（构造时注册 /definitions/* 路由）。 */
    private org.slackerdb.dbserver.service.DefinitionService definitionService;
    /** M6 门户访问日志服务。 */
    private org.slackerdb.dbserver.service.PortalAccessService portalAccessService;
    private final PluginService pluginService;
    private final McpServer mcpServer;
    private SqlReplServer sqlReplServer;
    /**
     * 内置调度能力（ETL）。{@code null} 表示本实例没有这个能力：
     * 要么没配（home/projectHome 缺失），要么是只读实例。
     */
    private SchedulerModule schedulerModule;
    /** 没有启用调度能力的原因，只用于日志与 /status，不参与逻辑判断。 */
    private String schedulerDisabledReason;
    private BackupService backupService;
    public BoundedQueue<APIHistoryRecord> apiHistoryList
            = new BoundedQueue<>(DBInstance.HISTORY_QUEUE_CAPACITY);

    private String getClientIp(Context ctx) {
        String xff = ctx.header("X-Forwarded-For");
        return (xff != null && !xff.isEmpty())
                ? xff.split(",")[0].trim()
                : ctx.ip();
    }

    // SQL历史记录线程
    class DBInstanceXAPIHistoryThread extends Thread
    {
        // 上一次告警时记录的丢弃数量，用于避免重复刷屏
        private long lastReportedBlocked = 0;

        @Override
        public void run()
        {
            // 设置线程名称
            setName("Session-APIHistory");

            String historyInsertSQL = """
                    Insert INTO sysaux.API_HISTORY(
                        ID, ServerID, ClientIP, Method, Path,
                        RequestHeader, RequestBody, StartTime, EndTime, AffectedRows,
                        Cached, RetCode, ErrorMsg)
                        VALUES(?,?,?,?,?,  ?,?,?,?,?,  ?,?,?)
                    """;
            String historyUpdateSQL = """
                    Update  sysaux.API_HISTORY
                    SET     EndTime = ?,
                            RetCode = ?,
                            AffectedRows = ?,
                            Cached = ?,
                            ErrorMsg = ?
                    WHERE ID = ?
                    """;

            // 外层循环：任何一次写入异常都不允许让消费线程退出。
            // 否则队列永远不会被排空，生产者会持续丢弃记录
            // （改造前 offer 是阻塞的，会直接把 Jetty/业务线程挂住）。
            while (!isInterrupted()) {
                Connection apiHistoryConn = null;
                PreparedStatement historyInsertStmt = null;
                PreparedStatement historyUpdateStmt = null;
                int nProcessedRows = 0;

                try {
                    apiHistoryConn = ((DuckDBConnection) backendSysConnection).duplicate();
                    apiHistoryConn.setAutoCommit(false);
                    historyInsertStmt = apiHistoryConn.prepareStatement(historyInsertSQL);
                    historyUpdateStmt = apiHistoryConn.prepareStatement(historyUpdateSQL);

                    while (!isInterrupted()) {
                        while (!apiHistoryList.isEmpty() && !isInterrupted()) {
                            APIHistoryRecord apiHistoryRecord = apiHistoryList.poll();
                            if (apiHistoryRecord == null) {
                                // 极小概率下被其他消费者取走，直接跳过，避免空指针
                                continue;
                            }
                            if (apiHistoryRecord.type().equalsIgnoreCase("INSERT")) {
                                historyInsertStmt.setLong(1, apiHistoryRecord.ID());
                                historyInsertStmt.setLong(2, apiHistoryRecord.ServerID());
                                historyInsertStmt.setString(3, apiHistoryRecord.ClientIP());
                                historyInsertStmt.setString(4, apiHistoryRecord.Method());
                                historyInsertStmt.setString(5, apiHistoryRecord.Path());
                                historyInsertStmt.setString(6, apiHistoryRecord.RequestHeader());
                                historyInsertStmt.setString(7, apiHistoryRecord.RequestBody());
                                if (apiHistoryRecord.StartTime() != null) {
                                    historyInsertStmt.setTimestamp(8, Timestamp.valueOf(apiHistoryRecord.StartTime()));
                                }
                                else
                                {
                                    historyInsertStmt.setTimestamp(8, null);
                                }
                                if (apiHistoryRecord.EndTime() != null) {
                                    historyInsertStmt.setTimestamp(9, Timestamp.valueOf(apiHistoryRecord.EndTime()));
                                }
                                else
                                {
                                    historyInsertStmt.setTimestamp(9, null);
                                }
                                historyInsertStmt.setLong(10, apiHistoryRecord.AffectedRows());
                                historyInsertStmt.setBoolean(11, apiHistoryRecord.Cached());
                                historyInsertStmt.setInt(12, apiHistoryRecord.RetCode());
                                historyInsertStmt.setString(13, apiHistoryRecord.ErrorMsg());
                                historyInsertStmt.execute();
                                nProcessedRows = nProcessedRows + 1;
                            } else if (apiHistoryRecord.type().equalsIgnoreCase("UPDATE")) {
                                if (apiHistoryRecord.EndTime() != null) {
                                    historyUpdateStmt.setTimestamp(1, Timestamp.valueOf(apiHistoryRecord.EndTime()));
                                }
                                else
                                {
                                    historyUpdateStmt.setTimestamp(1, null);
                                }
                                historyUpdateStmt.setInt(2, apiHistoryRecord.RetCode());
                                historyUpdateStmt.setLong(3, apiHistoryRecord.AffectedRows());
                                historyUpdateStmt.setBoolean(4, apiHistoryRecord.Cached());
                                historyUpdateStmt.setString(5, apiHistoryRecord.ErrorMsg());
                                historyUpdateStmt.setLong(6, apiHistoryRecord.ID());
                                historyUpdateStmt.execute();
                                nProcessedRows = nProcessedRows + 1;
                            }
                            if (nProcessedRows % 1000 == 0) {
                                apiHistoryConn.commit();
                                nProcessedRows = 0;
                            }
                        }
                        if (nProcessedRows != 0)
                        {
                            apiHistoryConn.commit();
                            nProcessedRows = 0;
                        }
                        reportQueuePressure();
                        try {
                            TimeUnit.SECONDS.sleep(1);
                        }
                        catch (InterruptedException ignored) {
                            return;
                        }
                    }
                }
                catch (SQLException | RuntimeException historyException)
                {
                    // 单次失败不能让消费线程退出，否则队列永远不会被排空
                    logger.error("[APIHistory] Save api history failed. Will reconnect and retry.", historyException);
                    rollbackQuietly(apiHistoryConn);
                    nProcessedRows = 0;
                }
                finally {
                    closeQuietly(historyInsertStmt);
                    closeQuietly(historyUpdateStmt);
                    closeQuietly(apiHistoryConn);
                }

                if (isInterrupted()) {
                    return;
                }

                // 退避，避免后端持续失败时空转刷日志
                try {
                    TimeUnit.SECONDS.sleep(5);
                }
                catch (InterruptedException ignored) {
                    return;
                }
            }
        }

        /**
         * 给出历史队列的背压信号（每个排空周期最多一条，避免刷屏）。
         *
         * <p>入队按设计"满了就阻塞、绝不丢弃"，所以唯一需要告警的是<b>生产者被迫等待</b>。</p>
         */
        private void reportQueuePressure() {
            long blocked = apiHistoryList.getBlockedTotal();
            if (blocked > lastReportedBlocked) {
                logger.warn("[APIHistory] History queue is full. Producers blocked {} time(s), {} ms in total "
                                + "(queue capacity = {}, current size = {}). The consumer is slower than the producers; "
                                + "audit records are NOT dropped, the producers wait instead.",
                        blocked, apiHistoryList.getBlockedMillis(),
                        DBInstance.HISTORY_QUEUE_CAPACITY, apiHistoryList.size());
                lastReportedBlocked = blocked;
            }
        }

        private void rollbackQuietly(Connection connection) {
            if (connection == null) {
                return;
            }
            try {
                connection.rollback();
            }
            catch (SQLException ignored) {
            }
        }

        private void closeQuietly(AutoCloseable closeable) {
            if (closeable == null) {
                return;
            }
            try {
                closeable.close();
            }
            catch (Exception ignored) {
            }
        }
    }
    private DBInstanceXAPIHistoryThread dbInstanceXAPIHistoryThread = null;

    public DBInstanceX(
            DBInstance dbInstance
    ) {
        this.logger = dbInstance.logger;
        this.dbInstance = dbInstance;

        try {
            // 复刻一个链接，用来记录SQL日志
            this.backendSysConnection = ((DuckDBConnection) this.dbInstance.backendSysConnection).duplicate();
        } catch (SQLException sqlException) {
            this.logger.error("[SERVER][STARTUP    ] Management server failed. Can't init sql connection. ", sqlException);
            throw new ServerException("Management server failed. Can't init sql connection. ", sqlException);
        }

        // 读取服务配置
        ServerConfiguration serverConfiguration = this.dbInstance.serverConfiguration;

        // 关闭Javalin的日志, 如果不是在trace下
        Logger javalinLogger = (Logger) LoggerFactory.getLogger("io.javalin.Javalin");
        Logger jettyLogger = (Logger) LoggerFactory.getLogger("org.eclipse.jetty");
        if (!this.logger.getLevel().equals(Level.TRACE)) {
            javalinLogger.setLevel(Level.OFF);
            jettyLogger.setLevel(Level.OFF);
        }

        // 创建API的日志信息表，如果有必要
        // 初始化APIHistory
        if (!serverConfiguration.getAccess_mode().equals("READ_ONLY") &&
                serverConfiguration.getDataServiceHistory().equalsIgnoreCase("ON")) {
            try {
                // API History会保存在数据库内部。
                Statement sqlHistoryStmt = backendSysConnection.createStatement();
                sqlHistoryStmt.execute("CREATE SCHEMA IF NOT EXISTS sysaux");
                sqlHistoryStmt.execute(
                        """
                                CREATE TABLE IF NOT EXISTS SYSAUX.API_HISTORY
                                (
                                    ID             BIGINT PRIMARY KEY,
                                    ServerID       INT,
                                    ClientIP       VARCHAR,
                                    StartTime      DateTime,
                                    EndTime        DateTime,
                                    Elapsed        INT GENERATED ALWAYS AS (DATEDIFF('SECOND', StartTime, EndTime)),
                                    Method         VARCHAR,
                                    Path           VARCHAR,
                                    RequestHeader  VARCHAR,
                                    RequestBody    VARCHAR,
                                    AffectedRows   Long,
                                    Cached         INT,
                                    RetCode        INT,
                                    ErrorMsg       VARCHAR
                                );""");
                sqlHistoryStmt.close();
                logger.info("[SERVER][STARTUP    ] Sql history feature enabled.");

                // 获取之前最大的SqlHistory的ID
                String sql = "Select Max(ID) From sysaux.API_HISTORY";
                Statement statement = backendSysConnection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql);
                if (resultSet.next()) {
                    this.backendApiHistoryId.set(resultSet.getLong(1) + 1);
                }
                resultSet.close();
                statement.close();
                try {
                    this.backendSysConnection.commit();
                }
                catch (SQLException ignored) {}
            } catch (SQLException sqlException) {
                this.logger.error("[SERVER][STARTUP    ] Management server failed. Can't init api history table.", sqlException);
                throw new ServerException("Management server failed. Can't init sql history table. ", sqlException);
            }

            // 开启后台异步线程，用来同步SQL历史
            if (dbInstanceXAPIHistoryThread == null) {
                dbInstanceXAPIHistoryThread = new DBInstanceXAPIHistoryThread();
                // 守护线程：消费端即使卡住也不能阻止 JVM 退出（必须在 start() 之前设置）
                dbInstanceXAPIHistoryThread.setDaemon(true);
                dbInstanceXAPIHistoryThread.start();
            }
        }

        // 启动Javalin应用
        this.managementApp = Javalin
                .create(config ->
                        {
                            // 不显示Javalin的启动提示信息
                            config.startup.showJavalinBanner = false;
                            // 支持跨域
                            config.bundledPlugins.enableCors(
                                    cors -> cors.addRule(CorsPluginConfig.CorsRule::anyHost));
                        }
                )
                .start(
                        serverConfiguration.getBindHost(),
                        serverConfiguration.getPortX()
                );

        // 管理口只提供 JSON API，不再返回任何 HTML 页面。
        //
        // 404 的 JSON 信封**只由这里产生**，不要再注册 get("/{...}*") 之类的通配兜底路由：
        // Javalin 7 的路由匹配是"按注册顺序取第一个匹配"（PathMatcher.findFirstEntry），
        // 兜底路由一旦注册在插件/模块路由之前，之后注册的所有 GET 路由都会被它遮蔽
        // （历史回归 b90f7a9：加了兜底之后，plugin-scheduler 的 GET 端点全部 404）。
        //
        // 这里要区分两种 404：
        //   1) 没有任何路由命中 —— Javalin 已经写了一份默认 body（随 Accept 是 text/plain 或 JSON），
        //      必须覆盖成统一信封；
        //   2) 路由命中、并自己写了 404 响应体（例如 /api 的 "not registered"、会话不存在）—— 保持原样。
        this.managementApp.unsafe.routes.error(404, ctx -> {
            String existing = ctx.result();
            if (ctx.endpoints().matchedHttpEndpoint() != null
                    && existing != null && !existing.isEmpty()) {
                return;
            }
            ctx.status(404).json(Map.of(
                    "retCode", 404,
                    "retMsg", ctx.path() + " is not found."));
        });

        // 需要在记录器之前添加的过滤器
        this.managementApp.unsafe.routes.before(ctx -> {
            // 设置请求开始时间作为属性
            ctx.attribute("startTime", System.currentTimeMillis());
            if (!this.dbInstance.serverConfiguration.getAccess_mode().equals("READ_ONLY") &&
                    this.dbInstance.serverConfiguration.getDataServiceHistory().equalsIgnoreCase("ON")) {
                long apiHistoryId = this.backendApiHistoryId.incrementAndGet();
                APIHistoryRecord apiHistoryRecord =
                        new APIHistoryRecord(
                                "INSERT",
                                apiHistoryId,
                                ProcessHandle.current().pid(),
                                getClientIp(ctx),
                                LocalDateTime.now(),
                                null,
                                ctx.method().toString(),
                                ctx.path(),
                                ctx.headerMap().toString(),
                                ctx.body(),
                                0,
                                false,
                                0,
                                null
                        );
                this.apiHistoryList.offer(apiHistoryRecord);
                ctx.attribute("trackHistoryId", apiHistoryId);
            }
        });

        // 在请求结束后记录响应信息
        this.managementApp.unsafe.routes.after(ctx -> {
            Long startTime = ctx.attribute("startTime");
            long duration = -1;
            if (startTime != null) {
                duration = System.currentTimeMillis() - startTime;
            }
            logger.trace("Response: {} {} from {} - Status: {} - Time: {}ms",
                    ctx.method(), ctx.path(), this.getClientIp(ctx), ctx.status(), duration);
            if (!this.dbInstance.serverConfiguration.getAccess_mode().equals("READ_ONLY") &&
                    this.dbInstance.serverConfiguration.getDataServiceHistory().equalsIgnoreCase("ON")) {
                Map<String, Object> ctxAttributeMap = ctx.attributeMap();
                long trackingHistoryId = 0;
                boolean cached = false;
                int affectedRows = 0;
                if (ctxAttributeMap.containsKey("trackHistoryId")) {
                    trackingHistoryId = (Long) (ctxAttributeMap.get("trackHistoryId"));
                }
                if (ctxAttributeMap.containsKey("cached")) {
                    cached = (Boolean) (ctxAttributeMap.get("cached"));
                }
                if (ctxAttributeMap.containsKey("affectedRows")) {
                    affectedRows = (int) (ctxAttributeMap.get("affectedRows"));
                }
                if (trackingHistoryId != 0) {
                    APIHistoryRecord apiHistoryRecord =
                            new APIHistoryRecord(
                                    "UPDATE",
                                    trackingHistoryId,
                                    0,
                                    null,
                                    null,
                                    LocalDateTime.now(),
                                    null,
                                    null,
                                    null,
                                    null,
                                    affectedRows,
                                    cached,
                                    ctx.status().getCode(),
                                    null
                            );
                    this.apiHistoryList.offer(apiHistoryRecord);
                }
            }
        });

        /*
          顺序要求：APIService 必须在 McpServer 之前构造。
          MCP 的工具面复用语义层那一份服务注册表（不建第二份），因此它需要
          在 run() 时就能拿到 APIService 的只读视图。
        */
        this.apiService = new APIService(dbInstance, this.managementApp, dbInstance.logger);

        // M5：定义存储（数据集/图表/仪表板/门户）。路由必须在这里注册完，
        // 且不能晚于下面的通配处理器（管理口有 "/{...}" 的兜底路由）。
        this.definitionService = new org.slackerdb.dbserver.service.DefinitionService(
                dbInstance, this.managementApp, dbInstance.logger);
        this.portalAccessService = new org.slackerdb.dbserver.service.PortalAccessService(
                dbInstance, this.managementApp, dbInstance.logger);

        // 异步备份任务：DbDesk 通过 /backups 轮询并下载，避免长备份占住一个 HTTP 请求。
        this.backupService = new BackupService(dbInstance, this.managementApp, dbInstance.logger);

        // 初始化SQLREPL服务,MCP服务
        try {
            // SQL REPL 是独立的异步 SQL API，不随 Web Console UI 一起移除。
            this.sqlReplServer = new SqlReplServer(dbInstance, this.managementApp, dbInstance.logger);
            this.mcpServer = new McpServer(dbInstance, this.managementApp, dbInstance.logger);
            this.mcpServer.setServiceSupplier(this.apiService::describeRegisteredServices);

            mcpServer.run();
            this.sqlReplServer.run();
        } catch (Exception e) {
            this.logger.error("[SERVER][STARTUP    ] Management server failed. Can't init ws service. ", e);
            throw new ServerException("Management server failed. Can't init ws service. ", e);
        }

        // 内置调度能力（ETL）：
        //   缺配置（home/projectHome）= 没有这个能力，不建表、不起 Quartz、不注册路由；
        //   配置错（有值但不可用）    = 拒绝启动；
        //   只读实例                  = 没有这个能力，且不校验配置。
        startSchedulerModule(serverConfiguration);

        // 系统数据备份
        this.managementApp.unsafe.routes.post("/backup", ctx -> {
            JSONObject bodyObject;
            try {
                bodyObject = JSONObject.parseObject(ctx.body());
            } catch (JSONException ignored) {
                ctx.json(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. Incomplete or format error in backup request.",
                        "success", false,
                        "error", "Rejected. Incomplete or format error in backup request.")
                );
                return;
            }
            if (bodyObject == null)
            {
                ctx.json(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. Incomplete or format error in backup request. missing backupTag.",
                        "success", false,
                        "error", "Rejected. Incomplete or format error in backup request. missing backupTag.")
                );
                return;
            }
            String backupTag = bodyObject.getString("backupTag");
            // Validate backup tag
            if (backupTag == null || backupTag.trim().isEmpty()) {
                ctx.json(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. Backup tag is empty.",
                        "success", false,
                        "error", "Rejected. Backup tag is empty.")
                );
                return;
            }
            // Disallow path symbols and dots (same as frontend validation)
            if (backupTag.matches(".*[<>:\"|?*\\\\/.].*")) {
                ctx.json(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. Backup tag contains invalid characters (path symbols or dots).",
                        "success", false,
                        "error", "Rejected. Backup tag contains invalid characters (path symbols or dots).")
                );
                return;
            }
            File backupDir = new File("backup");
            if (!backupDir.exists())
            {
                var ignored = backupDir.mkdirs();
            }
            // 生成文件名，确保以 .db 结尾
            String filename = backupTag;
            if (!filename.toLowerCase().endsWith(".db")) {
                filename = filename + ".db";
            }
            String backupDbFile = String.valueOf(Path.of(backupDir.toString(), filename));
            if (new File(backupDbFile).exists())
            {
                // 如果之前有文件，就先删除该文件
                var ignored = new File(backupDbFile).delete();
            }
            try {
                Connection backupConnection = ((DuckDBConnection)backendSysConnection).duplicate();
                // 备份语句序列（DETACH/ATTACH/COPY/DETACH）必须各自独立提交。
                // 复用的连接默认 autoCommit=false，DuckDB 会因为"当前事务里还有未完成的工作"
                // 拒绝 DETACH，实测备份接口 100% 失败。
                backupConnection.setAutoCommit(true);
                // 实例库名走唯一来源：没有 --data 时是 memory，内存模式带库名时是那个库名
                // （旧代码按 data_dir 判断，带 --data 的内存模式会 COPY 到错的库）。
                String currentDatabaseName = this.dbInstance.instanceName;
                String backupDBName = "backup_" + backupTag;
                Statement statement = backupConnection.createStatement();
                statement.execute("DETACH DATABASE IF EXISTS " + backupDBName);
                statement.execute("ATTACH '" + backupDbFile + "' as " + backupDBName);
                statement.execute("COPY FROM DATABASE \"" + currentDatabaseName + "\" TO " + backupDBName);
                statement.execute("DETACH DATABASE " + backupDBName);
                statement.close();

                backupConnection.close();
                JSONObject result = new JSONObject();
                result.put("retCode", 0);
                result.put("retMsg", "Successful.");
                result.put("success", true);
                result.put("error", null);
                result.put("filename", "backup/" + filename);
                ctx.json(result);
            } catch (SQLException e) {
                logger.error("Backup failed due to SQL error", e);
                ctx.json(Map.of(
                        "retCode", -1,
                        "retMsg", "Backup failed: " + e.getMessage(),
                        "success", false,
                        "error", "Backup failed: " + e.getMessage()
                ));
            } catch (Exception e) {
                logger.error("Backup failed due to unexpected error", e);
                ctx.json(Map.of(
                        "retCode", -1,
                        "retMsg", "Backup failed: " + e.getMessage(),
                        "success", false,
                        "error", "Backup failed: " + e.getMessage()
                ));
            }
        });

        // 文件下载服务
        this.managementApp.unsafe.routes.get("/download", FileHandlerHelper::handleFileDownload);

        // 文件上传服务
        this.managementApp.unsafe.routes.post("/upload", FileHandlerHelper::handleFileUpload);

        // 查看日志服务
        this.managementApp.unsafe.routes.get("/viewLog", FileHandlerHelper::handleFileView);

        // 日志文件服务（返回纯文本）
        this.managementApp.unsafe.routes.get("/logfile", ctx -> {
            Path file = resolveLogFile();
            if (file == null) {
                ctx.result("Logging to console, no log file.");
                return;
            }
            try {
                String linesParam = ctx.queryParam("lines");
                if (linesParam != null && !linesParam.isBlank()) {
                    int lines;
                    try {
                        lines = Integer.parseInt(linesParam.trim());
                    } catch (NumberFormatException nfe) {
                        ctx.status(400).result("lines must be a positive number.");
                        return;
                    }
                    if (lines <= 0) {
                        ctx.status(400).result("lines must be a positive number.");
                        return;
                    }
                    int capped = Math.min(lines, FileHandlerHelper.MAX_LINES);
                    List<String> tail = FileHandlerHelper.readLastNLines(file.toFile(), capped);
                    ctx.contentType("text/plain; charset=utf-8");
                    ctx.result(String.join(System.lineSeparator(), tail));
                    return;
                }
                String content = Files.readString(file);
                ctx.result(content);
            } catch (IOException e) {
                logger.error("Failed to read log file", e);
                ctx.status(500).result("Failed to read log file: " + e.getMessage());
            } catch (Exception e) {
                logger.error("Failed to read log file tail", e);
                ctx.status(500).result("Failed to read log file: " + e.getMessage());
            }
        });

        // 状态服务
        this.managementApp.unsafe.routes.get("/status", ctx -> ctx.json(getStatusJson()));

        // 能力声明：客户端（控制台/CLI）据此决定显示哪些功能，避免逐个端点探测
        this.managementApp.unsafe.routes.get("/capabilities", ctx -> ctx.json(getCapabilitiesJson()));

        // 轻量存活检查：供启动器与客户端轮询，刻意不做 /status 那套重逻辑
        this.managementApp.unsafe.routes.get("/health", ctx -> ctx.json(getHealthJson()));

        // 终止会话（HTTP 版）：UI/自动化使用；TCP 管理协议的 KILL 保留给 CLI/运维脚本
        this.managementApp.unsafe.routes.post("/sessions/{sessionId}/kill", ctx -> {
            String rawSessionId = ctx.pathParam("sessionId");
            int sessionId;
            try {
                sessionId = Integer.parseInt(rawSessionId);
            } catch (NumberFormatException nfe) {
                ctx.status(400).json(Map.of(
                        "retCode", 400,
                        "retMsg", "Invalid session id [" + rawSessionId + "]; expected an integer.",
                        "errorClass", "VALIDATION"));
                return;
            }
            if (this.dbInstance.killSession(sessionId)) {
                ctx.json(Map.of("killed", true, "sessionId", sessionId));
            } else {
                ctx.status(404).json(Map.of(
                        "retCode", 404,
                        "retMsg", "Session [" + sessionId + "] not found.",
                        "errorClass", "NOT_FOUND"));
            }
        });

        // API服务已在上面提前构造（MCP 的工具面要复用它的服务注册表）

        // Plugin服务处理
        this.pluginService = new PluginService(dbInstance, this.managementApp, dbInstance.logger);

        // 管理口只提供 JSON API：没有页面入口，未知路径的 JSON 404 由上面的 error(404) 统一给出。
        // 注意：**不要**在这里补 "/{...}*" 一类的通配兜底路由。它注册得比插件/模块的路由早，
        // 会把它们之后注册的所有 GET 端点遮蔽掉（Javalin 按注册顺序匹配，见 error(404) 处的说明）。
        this.managementApp.unsafe.routes.get("/", this::notFound);

        // 异常处理
        this.managementApp.unsafe.routes.exception(Exception.class, (e, ctx) -> {
            logger.error("Error occurred while processing request: {} {} - {}", ctx.method(), ctx.path(), e.getMessage(), e);
            ctx.status(500).result("Internal Server Error");
        });

        logger.info("[SERVER][STARTUP    ] Management server listening on {}:{}.",
                serverConfiguration.getBindHost(), serverConfiguration.getPortX());
        if (!this.dbInstance.serverConfiguration.getAccess_mode().equals("READ_ONLY") &&
                this.dbInstance.serverConfiguration.getDataServiceHistory().equalsIgnoreCase("ON")) {
            logger.info("[SERVER][STARTUP    ] Data service history feature enabled.");
        }
    }

    /** 管理口没有 HTML 页面，未知路径统一返回 JSON 信封。 */
    private void notFound(Context ctx) {
        ctx.status(404).json(Map.of(
                "retCode", 404,
                "retMsg", ctx.path() + " is not found."));
    }

    /**
     * 按配置装配内置调度能力。
     *
     * <ul>
     *   <li>没有配置 {@code plugin-scheduler.home} / {@code projectHome}：能力不存在，
     *       只记一条 INFO（带上原因，便于运维判断"为什么没有"）；</li>
     *   <li>只读实例：同上，且**不校验**配置（只读优先于校验，建表本来就做不了）；</li>
     *   <li>配置有值但不可用：抛 {@link ServerException}，让启动失败（拒绝启动）；</li>
     *   <li>装配/启动过程失败（建表、Quartz、路由）：同样拒绝启动。</li>
     * </ul>
     */
    private void startSchedulerModule(ServerConfiguration serverConfiguration) {
        this.schedulerDisabledReason = SchedulerModule.disabledReason(
                serverConfiguration.getPluginProperties(),
                serverConfiguration.getAccess_mode().equalsIgnoreCase("READ_ONLY"));
        if (this.schedulerDisabledReason != null) {
            this.logger.info("[SERVER][SCHEDULER  ] Scheduler capability is not available: {}",
                    this.schedulerDisabledReason);
            return;
        }

        // 调度器用自己的连接（与 DBPlugin 时期一致：backendSysConnection 的 duplicate），
        // 不与 /status、备份等共享同一个 Connection 对象。
        Connection schedulerConnection = null;
        SchedulerModule module = null;
        try {
            schedulerConnection = ((DuckDBConnection) this.backendSysConnection).duplicate();
            module = new SchedulerModule(this.managementApp, this.logger,
                    schedulerConnection, serverConfiguration.getPluginProperties(),
                    resolveDbcliConnect(serverConfiguration));
            module.start();
            this.schedulerModule = module;
        } catch (SchedulerConfigException e) {
            abortSchedulerStartup(module, schedulerConnection);
            this.logger.error("[SERVER][SCHEDULER  ] Scheduler configuration is invalid: {}", e.getMessage());
            throw new ServerException("Scheduler configuration error: " + e.getMessage(), e);
        } catch (Exception e) {
            abortSchedulerStartup(module, schedulerConnection);
            this.logger.error("[SERVER][SCHEDULER  ] Scheduler startup failed.", e);
            throw new ServerException("Scheduler startup failed: " + e.getMessage(), e);
        }
    }

    /** 调度器装配失败时的收尾：先让模块自己关（含连接），模块还没建成就直接关连接。 */
    private void abortSchedulerStartup(SchedulerModule module, Connection connection) {
        if (module != null) {
            try {
                module.stop();
                return;
            } catch (Exception e) {
                this.logger.warn("[SERVER][SCHEDULER  ] Failed to clean up a partially started scheduler: {}",
                        e.getMessage());
            }
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * DBCLI 任务连回本实例的连接串：默认按数据服务端口推导，可用
     * {@code plugin-scheduler.dbcli.connect} 覆盖（例如端口只监听在内网地址、或要走代理）。
     *
     * <p>返回 {@code null} 表示本实例没有可供 DBCLI 连接的 TCP 端口（{@code port_x=-1} 或
     * 仅 UDS）：DBCLI 任务会在执行时明确失败，而不是悄悄连到一个不存在的库。</p>
     */
    private String resolveDbcliConnect(ServerConfiguration configuration) {
        String override = configuration.getPluginProperties()
                .get(org.slackerdb.dbserver.scheduler.runner.DbcliTaskExecutor.CONNECT_KEY);
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        int port = configuration.getPort();
        if (port <= 0) {
            return null;
        }
        String host = configuration.getBindHost();
        if (host == null || host.isBlank() || "0.0.0.0".equals(host) || "::".equals(host)) {
            host = "127.0.0.1";
        }
        String database = this.dbInstance.instanceName;
        return "jdbc:slackerdb://" + host + ":" + port + "/" + (database == null ? "" : database);
    }

    /**
     * 管理 API 契约版本（{@code /capabilities.apiVersion}）。只在破坏性变更时递增。
     */
    private static final int MANAGEMENT_API_VERSION = org.slackerdb.common.api.ApiVersion.MANAGEMENT;

    /**
     * 能力声明。
     *
     * <p>刻意做得**便宜**：只读启动期配置与插件注册表，不查库、不扫会话、不算 CPU
     * （那些是 {@code /status} 的职责，调用方每 2 秒轮询的是 {@code /health}）。</p>
     */
    private JSONObject getCapabilitiesJson() {
        JSONObject capabilities = new JSONObject();
        capabilities.put("apiVersion", MANAGEMENT_API_VERSION);

        ServerConfiguration serverConfiguration = this.dbInstance.serverConfiguration;
        JSONObject server = new JSONObject();
        server.put("name", this.dbInstance.instanceName);
        server.put("version", readServerVersion());
        server.put("pid", ProcessHandle.current().pid());
        capabilities.put("server", server);

        JSONObject ports = new JSONObject();
        ports.put("pg", serverConfiguration.getPort());
        ports.put("management", serverConfiguration.getPortX());
        ports.put("managementEnabled", true);
        capabilities.put("ports", ports);

        capabilities.put("accessMode", serverConfiguration.getAccess_mode());

        JSONObject features = new JSONObject();
        features.put("dataService", true);
        features.put("mcp", true);
        features.put("plugins", this.dbInstance.getPluginManager() != null);
        features.put("sqlRepl", true);
        // 调度能力是否在本实例上存在（由配置决定，见 SchedulerModule.disabledReason）。
        // dbDesk 只认这个标志来决定是否显示 ETL 菜单。
        features.put("etl", this.schedulerModule != null);
        features.put("copyOut", true);
        features.put("definitions", true);
        features.put("aiChat", serverConfiguration.getMcpLlmServer() != null
                && !serverConfiguration.getMcpLlmServer().trim().isEmpty());
        features.put("backupTasks", true);
        features.put("logTail", true);
        features.put("sqlHistory", "ON".equalsIgnoreCase(serverConfiguration.getSqlHistory()));
        features.put("apiHistory", "ON".equalsIgnoreCase(serverConfiguration.getDataServiceHistory()));
        capabilities.put("features", features);

        // 插件及其自描述的能力：客户端据此显隐入口，而不是探测 /scheduler/* 之类的端点
        JSONArray plugins = new JSONArray();
        org.pf4j.PluginManager pluginManager = this.dbInstance.getPluginManager();
        if (pluginManager != null) {
            for (org.pf4j.PluginWrapper plugin : pluginManager.getPlugins()) {
                JSONObject info = new JSONObject();
                info.put("id", plugin.getPluginId());
                info.put("version", plugin.getDescriptor().getVersion());
                info.put("state", plugin.getPluginState().toString());
                if (plugin.getPlugin() instanceof org.slackerdb.plugin.DBPlugin dbPlugin) {
                    org.slackerdb.plugin.PluginContribution contribution = dbPlugin.contributes();
                    if (contribution != null) {
                        JSONObject contributes = new JSONObject();
                        contributes.put("routePrefix", contribution.routePrefix());
                        contributes.put("contractVersion", contribution.contractVersion());
                        contributes.put("capabilities", contribution.capabilities());
                        info.put("contributes", contributes);
                    }
                }
                plugins.add(info);
            }
        }
        capabilities.put("plugins", plugins);

        JSONObject contracts = new JSONObject();
        contracts.put("managementApi", MANAGEMENT_API_VERSION);
        contracts.put("mcp", org.slackerdb.common.api.ApiVersion.MCP);
        if (this.schedulerModule != null) {
            contracts.put("scheduler", SchedulerModule.CONTRACT_VERSION);
        }
        capabilities.put("contracts", contracts);

        return capabilities;
    }

    /**
     * 轻量存活检查：只读实例状态，不做任何 IO。
     */
    private JSONObject getHealthJson() {
        String instanceState = this.dbInstance.instanceState;
        boolean running = instanceState != null && instanceState.equalsIgnoreCase("RUNNING");
        JSONObject health = new JSONObject();
        health.put("status", running ? "UP" : "DEGRADED");
        health.put("database", running ? "UP" : "DEGRADED");
        health.put("management", "UP");
        health.put("instanceState", instanceState == null ? "" : instanceState);
        health.put("ts", System.currentTimeMillis() / 1000L);
        return health;
    }

    /**
     * 服务端版本号（构建期生成的 {@code META-INF/build-info.properties}）；读不到时是 {@code unknown}。
     */
    private String readServerVersion() {
        return BuildInfo.version();
    }

    /**
     * 找到当前进程实际写入的日志文件。先按配置路径找；配置写成 console 时回退到
     * logback 已挂载的 FileAppender，避免“日志文件明明存在、界面却只显示 console”。
     */
    private Path resolveLogFile() {
        String logConfig = this.dbInstance.serverConfiguration.getLog();
        if (logConfig != null) {
            for (String part : logConfig.split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("CONSOLE")) {
                    continue;
                }
                Path candidate = Path.of(trimmed).toAbsolutePath();
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        if (logger instanceof Logger logbackLogger
                && logbackLogger.getLoggerContext() instanceof LoggerContext context) {
            for (Logger candidateLogger : context.getLoggerList()) {
                java.util.Iterator<Appender<ch.qos.logback.classic.spi.ILoggingEvent>> appenders =
                        candidateLogger.iteratorForAppenders();
                while (appenders.hasNext()) {
                    Appender<?> appender = appenders.next();
                    if (appender instanceof FileAppender<?> fileAppender) {
                        String file = fileAppender.getFile();
                        if (file != null && !file.isBlank()) {
                            Path candidate = Path.of(file).toAbsolutePath();
                            if (Files.isRegularFile(candidate)) {
                                return candidate;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private JSONObject getStatusJson() {
        JSONObject status = new JSONObject();
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        try {
            conn = ((org.duckdb.DuckDBConnection) this.backendSysConnection).duplicate();

            LocalDateTime currentTime = LocalDateTime.now();

            // 获取数据库的一些基本信息
            String database_size = "";
            String memory_usage = "";
            String wal_size = "";
            String database_version = "";
            stmt = conn.createStatement();
            rs = stmt.executeQuery(
                    "select  version() as version,* " +
                            "from    pragma_database_size() " +
                            "where   database_name = current_database()");
            if (rs.next()) {
                database_size = rs.getString("database_size");
                memory_usage = rs.getString("memory_usage");
                database_version = rs.getString("version");
                wal_size = rs.getString("wal_size");
            }

            // 版本与构建时刻来自构建期生成的 META-INF/build-info.properties
            String version = BuildInfo.version();
            String localBuildDate = BuildInfo.buildTime();

            // 构建 server 对象
            JSONObject server = new JSONObject();
            server.put("status", this.dbInstance.instanceState);
            server.put("version", version);
            server.put("build", localBuildDate);
            server.put("pid", ProcessHandle.current().pid());
            server.put("now", currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            if (this.dbInstance.bootTime != null) {
                server.put("bootTime", this.dbInstance.bootTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                Duration duration = Duration.between(this.dbInstance.bootTime, currentTime);
                long days = duration.toDays();
                long hours = duration.minusDays(days).toHours();
                long minutes = duration.minusDays(days).minusHours(hours).toMinutes();
                long seconds = duration.minusDays(days).minusHours(hours).minusMinutes(minutes).getSeconds();
                String readableTimeDifference = String.format("%d day%s, %d hour%s, %d minute%s, and %d second%s",
                        days, days == 1 ? "" : "s",
                        hours, hours == 1 ? "" : "s",
                        minutes, minutes == 1 ? "" : "s",
                        seconds, seconds == 1 ? "" : "s"
                );
                server.put("runTime", readableTimeDifference);
            }
            status.put("server", server);

            // 构建 database 对象
            JSONObject database = new JSONObject();
            database.put("version", database_version);
            database.put("size", database_size);
            database.put("memoryUsage", memory_usage);
            database.put("walSize", wal_size);
            status.put("database", database);

            // 构建 parameters 对象
            JSONObject parameters = new JSONObject();
            ServerConfiguration config = this.dbInstance.serverConfiguration;
            parameters.put("bindHost", config.getBindHost());
            parameters.put("port", config.getPort());
            parameters.put("portX", config.getPortX());
            parameters.put("advertiseAddress", config.isAdvertiseEnabled()
                    ? config.getAdvertiseAddress().toString() : "");
            parameters.put("advertiseNic", config.getAdvertiseNic());
            parameters.put("advertiseHost", config.getAdvertiseHost());
            parameters.put("data", config.getInstanceName());
            parameters.put("dataDir", config.getData_Dir());
            parameters.put("tempDir", config.getTemp_dir());
            parameters.put("dataEncrypt", config.getDataEncrypt());
            parameters.put("sqlHistory", config.getSqlHistory());
            parameters.put("template", config.getTemplate());
            parameters.put("initScript", config.getInit_script());
            parameters.put("startupScript", config.getStartup_script());
            parameters.put("extensionDir", config.getExtension_dir());
            parameters.put("pluginsDir", config.getPlugins_dir());
            parameters.put("threads", config.getThreads());
            parameters.put("memoryLimit", config.getMemory_limit());
            parameters.put("maxExecutionTime", config.getMax_execution_time());
            parameters.put("maxWorkers", config.getMax_Workers());
            parameters.put("accessMode", config.getAccess_mode());
            parameters.put("log", config.getLog());
            parameters.put("logLevel", config.getLog_level().levelStr);
            parameters.put("locale", config.getLocale());
            parameters.put("clientTimeout", config.getClient_timeout());
            parameters.put("queryResultCacheSize", org.slackerdb.common.utils.Utils.formatBytes(config.getQuery_result_cache_size()));
            parameters.put("dataServiceSchema", config.getData_service_schema());
            parameters.put("mcpConfig", config.getMcpConfig());
            parameters.put("mcpLlmKey", config.getMcpLlmKey());
            status.put("parameters", parameters);

            // 构建 usage 对象
            JSONObject usage = new JSONObject();
            usage.put("maxConnections", this.dbInstance.dbDataSourcePool.getHighWaterMark());
            usage.put("currentConnections", this.dbInstance.dbDataSourcePool.getUsedConnectionPoolSize());
            usage.put("idleConnections", this.dbInstance.dbDataSourcePool.getIdleConnectionPoolSize());
            usage.put("activeSessions", this.dbInstance.activeSessions);
            usage.put("activeChannels", this.dbInstance.getRegisteredConnectionsCount());
            // 历史落库的可观测性：
            //   queued*/capacity* —— 队列当前占用与上限（满了生产者会阻塞等待，不会丢数据）
            //   blocked*          —— 生产者因队列满而被迫等待的次数/总时长，"消费端跟不上"的信号
            //   *_HistoryThreadAlive 为 false 说明历史已完全停止落库（此时生产者会被背压永久拖住）
            usage.put("queuedSqlHistory", this.dbInstance.sqlHistoryList.size());
            usage.put("queuedApiHistory", this.apiHistoryList.size());
            usage.put("capacitySqlHistory", this.dbInstance.sqlHistoryList.getCapacity());
            usage.put("capacityApiHistory", this.apiHistoryList.getCapacity());
            usage.put("blockedSqlHistory", this.dbInstance.sqlHistoryList.getBlockedTotal());
            usage.put("blockedApiHistory", this.apiHistoryList.getBlockedTotal());
            usage.put("blockedSqlHistoryMillis", this.dbInstance.sqlHistoryList.getBlockedMillis());
            usage.put("blockedApiHistoryMillis", this.apiHistoryList.getBlockedMillis());
            usage.put("sqlHistoryThreadAlive", this.dbInstance.isSqlHistoryThreadAlive());
            usage.put("apiHistoryThreadAlive",
                    this.dbInstanceXAPIHistoryThread != null && this.dbInstanceXAPIHistoryThread.isAlive());

            OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            usage.put("cpuLoad", String.format("%.2f%%", osBean.getProcessCpuLoad() * 100));
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            usage.put("activeThreads", threadBean.getThreadCount());
            usage.put("heapMemory", org.slackerdb.common.utils.Utils.formatBytes(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()));
            usage.put("nonHeapMemory", org.slackerdb.common.utils.Utils.formatBytes(ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed()));
            status.put("usage", usage);

            // 扩展列表
            JSONArray extensions = new JSONArray();
            stmt = conn.createStatement();
            rs = stmt.executeQuery("select extension_name from duckdb_extensions() where installed = TRUE");
            while (rs.next()) {
                extensions.add(rs.getString(1));
            }
            rs.close();
            stmt.close();
            status.put("extensions", extensions);

            // 插件列表
            JSONArray plugins = new JSONArray();
            org.pf4j.PluginManager pluginManager = this.dbInstance.getPluginManager();
            if (pluginManager != null) {
                java.util.List<org.pf4j.PluginWrapper> pluginList = pluginManager.getPlugins();
                for (org.pf4j.PluginWrapper plugin : pluginList) {
                    JSONObject pluginInfo = new JSONObject();
                    pluginInfo.put("id", plugin.getPluginId());
                    pluginInfo.put("filename", plugin.getPluginPath().getFileName().toString());
                    pluginInfo.put("filepath", plugin.getPluginPath().toString());
                    pluginInfo.put("state", plugin.getPluginState().toString());
                    pluginInfo.put("version", plugin.getDescriptor().getVersion());
                    pluginInfo.put("description", plugin.getDescriptor().getPluginDescription());
                    
                    // 挂载时间 - 从DBPlugin实例获取
                    String mountTime = "";
                    if (plugin.getPlugin() instanceof org.slackerdb.plugin.DBPlugin dbPlugin) {
                        mountTime = dbPlugin.getMountTimeFormatted();
                    }
                    pluginInfo.put("mountTime", mountTime);
                    plugins.add(pluginInfo);
                }
            }
            status.put("plugins", plugins);

            // 内置调度能力的状态：没有这个能力时必须给出原因，
            // 否则"菜单里没有 ETL"会被当成故障而不是配置结果。
            JSONObject scheduler = new JSONObject();
            scheduler.put("active", this.schedulerModule != null);
            if (this.schedulerModule != null) {
                scheduler.put("state", this.schedulerModule.isStarted() ? "ACTIVE" : "STOPPED");
                scheduler.put("hopReady", this.schedulerModule.isHopReady());
            } else {
                scheduler.put("state", "DISABLED");
                scheduler.put("reason", this.schedulerDisabledReason);
            }
            status.put("scheduler", scheduler);

            // 会话列表
            JSONArray sessions = new JSONArray();
            for (Integer sessionId : this.dbInstance.dbSessions.keySet()) {
                // 会话可能在遍历过程中被关闭并从 dbSessions 摘除（keySet 是弱一致视图），必须判空
                org.slackerdb.dbserver.server.DBSession dbSession = this.dbInstance.getSession(sessionId);
                if (dbSession == null) {
                    continue;
                }
                JSONObject session = new JSONObject();
                session.put("id", sessionId);
                // 会话类型：目前只列 pgwire 会话；WS/REPL 会话尚未纳入（见契约文档的偏差记录）
                session.put("type", "pgwire");
                session.put("connectedTime", dbSession.connectedTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                session.put("clientIp", dbSession.clientAddress);
                // application_name 由客户端上报：优先用 SET application_name 截下来的值
                // （pgjdbc 系驱动走这条路），其次看握手参数（libpq/psql 会在 startup packet 里带）
                Map<String, String> startupOptions = dbSession.startupOptions;
                String applicationName = dbSession.applicationName;
                if (applicationName == null || applicationName.isEmpty()) {
                    applicationName = startupOptions == null ? null : startupOptions.get("application_name");
                }
                String sessionDatabase = startupOptions == null ? null : startupOptions.get("database");
                session.put("applicationName", applicationName == null ? "" : applicationName);
                session.put("database", sessionDatabase == null ? "" : sessionDatabase);
                session.put("status", dbSession.status);
                session.put("executingFunction", dbSession.executingFunction);
                session.put("executingSql", dbSession.executingSQL);
                if (dbSession.executingTime != null) {
                    session.put("executingTime", dbSession.executingTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    session.put("executingDurationSeconds", Duration.between(dbSession.executingTime, currentTime).getSeconds());
                }
                sessions.add(session);
            }
            status.put("sessions", sessions);

        } catch (SQLException se) {
            status.put("error", "Failed to get database info: " + se.getMessage());
        } finally {
            try {
                if (rs != null && !rs.isClosed()) {
                    rs.close();
                }
            } catch (SQLException ignored) {}
            try {
                if (stmt != null && !stmt.isClosed()) {
                    stmt.close();
                }
            } catch (SQLException ignored) {}
            try {
                if (conn != null && !conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException ignored) {}
        }
        return status;
    }

    public void stop()
    {
        // 先停调度器：Quartz 线程与任务执行可能还在用数据库连接，
        // 必须在管理口和连接池关闭之前收尾（它也会关闭自己那条 Connection）。
        if (this.schedulerModule != null)
        {
            this.schedulerModule.stop();
            this.schedulerModule = null;
        }

        // 先关闭 SQL REPL 的 WebSocket 会话与线程池，避免管理口停止后仍有非守护线程存活。
        if (this.sqlReplServer != null)
        {
            this.sqlReplServer.stop();
        }

        if (this.backupService != null)
        {
            this.backupService.stop();
        }

        // 关闭服务器
        this.managementApp.stop();

        // 关闭API历史记录：中断并等待消费线程退出，确保收尾提交有机会完成
        if (this.dbInstanceXAPIHistoryThread != null)
        {
            this.dbInstance.interruptAndJoin(this.dbInstanceXAPIHistoryThread, 10, "Session-APIHistory");
            this.dbInstanceXAPIHistoryThread = null;
        }

        // 关闭API服务
        if (this.apiService != null)
        {
            this.apiService.stop();
        }

        // 关闭MCP服务器
        if (this.mcpServer != null) {
            this.mcpServer.shutdown();
        }
    }

    public Javalin getManagementApp()
    {
        return this.managementApp;
    }
}
