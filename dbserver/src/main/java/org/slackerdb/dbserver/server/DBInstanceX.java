package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.plugin.bundled.CorsPluginConfig;
import org.duckdb.DuckDBConnection;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.utils.BoundedQueue;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.entity.APIHistoryRecord;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class DBInstanceX {
    private final Logger logger;
    private final Javalin managementApp;
    private final Connection backendSysConnection;
    private final DBInstance dbInstance;
    private final AtomicLong backendApiHistoryId = new AtomicLong(1);
    private final SchedulerService schedulerService;
    public BoundedQueue<APIHistoryRecord> apiHistoryList
            = new BoundedQueue<>(10*1000);

    private String getClientIp(Context ctx) {
        String xff = ctx.header("X-Forwarded-For");
        return (xff != null && !xff.isEmpty())
                ? xff.split(",")[0].trim()
                : ctx.ip();
    }

    // SQL历史记录线程
    class DBInstanceXAPIHistoryThread extends Thread
    {
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
            PreparedStatement historyInsertStmt;
            PreparedStatement historyUpdateStmt;
            try {
                Connection apiHistoryConn = ((DuckDBConnection) backendSysConnection).duplicate();
                apiHistoryConn.setAutoCommit(false);
                int nProcessedRows = 0;
                historyInsertStmt = apiHistoryConn.prepareStatement(historyInsertSQL);
                historyUpdateStmt = apiHistoryConn.prepareStatement(historyUpdateSQL);
                while (!isInterrupted()) {
                    while (!apiHistoryList.isEmpty() && !isInterrupted()) {
                        APIHistoryRecord apiHistoryRecord = apiHistoryList.poll();
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
                    try {
                        TimeUnit.SECONDS.sleep(1);
                    }
                    catch (InterruptedException ignored) {
                        return;
                    }
                }
            }
            catch (SQLException sqlException)
            {
                logger.error("[APIHistory] Save api history failed.", sqlException);
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
                dbInstanceXAPIHistoryThread.start();
            }
        }

        // 启动Javalin应用
        this.managementApp = Javalin
                .create(config ->
                        {
                            // 不显示Javalin的启动提示信息
                            config.showJavalinBanner = false;
                            // 添加静态文件
                            config.staticFiles.add("/web", Location.CLASSPATH);
                            // 支持跨域
                            config.bundledPlugins.enableCors(
                                    cors -> cors.addRule(CorsPluginConfig.CorsRule::anyHost));
                        }
                )
                .start(
                        serverConfiguration.getBindHost(),
                        serverConfiguration.getPortX()
                );

        // 自定义404界面
        ClassPathResource page404Resource = new ClassPathResource("web/404.html");
        this.managementApp.error(404, ctx -> ctx.html(Files.readString(Path.of(page404Resource.getURI()))));

        // 需要在记录器之前添加的过滤器
        this.managementApp.before(ctx -> {
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
        this.managementApp.after(ctx -> {
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
                                    ctx.statusCode(),
                                    null
                            );
                    this.apiHistoryList.offer(apiHistoryRecord);
                }
            }
        });

        // 默认页面
        ClassPathResource indexResource = new ClassPathResource("web/index.html");
        this.managementApp.get("/" + serverConfiguration.getData() + "/",
                ctx -> {
                    ctx.contentType("text/html");
                    ctx.result(Files.readString(Path.of(indexResource.getURI())));
                }
        );

        // 系统数据备份
        this.managementApp.post("/backup", ctx -> {
            JSONObject bodyObject;
            try {
                bodyObject = JSONObject.parseObject(ctx.body());
            } catch (JSONException ignored) {
                ctx.json(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. Incomplete or format error in backup request.")
                );
                return;
            }
            if (bodyObject == null)
            {
                ctx.json(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. Incomplete or format error in backup request. missing backupTag.")
                );
                return;
            }
            String backupTag = bodyObject.getString("backupTag");
            File backupDir = new File("backup");
            if (!backupDir.exists())
            {
                var ignored = backupDir.mkdirs();
            }
            String backupDbFile = String.valueOf(
                    Path.of(backupDir.toString(), this.dbInstance.serverConfiguration.getData() + "_" + backupTag + ".db"));
            if (new File(backupDbFile).exists())
            {
                // 如果之前有文件，就先删除该文件
                var ignored = new File(backupDbFile).delete();
            }
            Connection backupConnection = ((DuckDBConnection)backendSysConnection).duplicate();
            String currentDatabaseName;
            if (this.dbInstance.serverConfiguration.getData_Dir().equalsIgnoreCase(":memory:"))
            {
                currentDatabaseName = "memory";
            }
            else
            {
                currentDatabaseName = this.dbInstance.serverConfiguration.getData();
            }
            String backupDBName = "backup_" + backupTag;
            Statement statement = backendSysConnection.createStatement();
            statement.execute("DETACH DATABASE IF EXISTS " + backupDBName);
            statement.execute("ATTACH '" + backupDbFile + "' as " + backupDBName);
            statement.execute("COPY FROM DATABASE \"" + currentDatabaseName + "\" TO " + backupDBName);
            statement.execute("DETACH DATABASE " + backupDBName);
            statement.close();
            backupConnection.close();
            ctx.json(Map.of(
                    "retCode", 0,
                    "retMsg", "Successful. Backup file has placed to [" + new File(backupDbFile).getAbsolutePath() + "].")
            );
        });

        // 文件下载服务
        this.managementApp.get("/download", FileHandlerHelper::handleFileDownload);

        // 文件上传服务
        this.managementApp.post("/upload", FileHandlerHelper::handleFileUpload);

        // 查看日志服务
        this.managementApp.get("/viewLog", FileHandlerHelper::handleFileView);

        // API服务处理
        new APIService(dbInstance, this.managementApp, dbInstance.logger);

        // 调度服务
        this.schedulerService =
                new SchedulerService(dbInstance, this.managementApp, dbInstance.logger);

        // 异常处理
        this.managementApp.exception(Exception.class, (e, ctx) -> {
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

    public void stop()
    {
        // 关闭服务器
        this.managementApp.stop();

        // 关闭调度作业
        this.schedulerService.stop();

        // 关闭API历史记录
        if (this.dbInstanceXAPIHistoryThread != null && this.dbInstanceXAPIHistoryThread.isAlive())
        {
            this.dbInstanceXAPIHistoryThread.interrupt();
        }
    }
}
