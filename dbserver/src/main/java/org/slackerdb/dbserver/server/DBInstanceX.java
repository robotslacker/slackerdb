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
import org.slackerdb.dbserver.repl.SqlReplServer;
import org.slackerdb.dbserver.mcp.McpServer;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.io.InputStream;
import java.sql.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.io.IOException;
import java.lang.management.ThreadMXBean;
import com.alibaba.fastjson2.JSONArray;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
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

        // 控制台页面
        ClassPathResource consoleResource = new ClassPathResource("web/console.html");
        this.managementApp.get("/console", ctx -> {
            ctx.contentType("text/html");
            ctx.result(Files.readString(Path.of(consoleResource.getURI())));
        });

        // 初始化SQLREPL服务,MCP服务
        try {
            SqlReplServer sqlReplServer = new SqlReplServer(this.logger);
            McpServer mcpServer = new McpServer(this.logger);

            mcpServer.run(this.managementApp, dbInstance.backendSysConnection);
            sqlReplServer.run(this.managementApp, dbInstance.backendSysConnection);
        } catch (Exception e) {
            this.logger.error("[SERVER][STARTUP    ] Management server failed. Can't init ws service. ", e);
            throw new ServerException("Management server failed. Can't init ws service. ", e);
        }

        // 系统数据备份
        this.managementApp.post("/backup", ctx -> {
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
                Statement statement = backupConnection.createStatement();
                statement.execute("DETACH DATABASE IF EXISTS " + backupDBName);
                statement.execute("ATTACH '" + backupDbFile + "' as " + backupDBName);
                statement.execute("COPY FROM DATABASE \"" + currentDatabaseName + "\" TO " + backupDBName);
                statement.execute("DETACH DATABASE " + backupDBName);
                statement.close();

                backupConnection.commit();
                backupConnection.close();
                JSONObject result = new JSONObject();
                result.put("retCode", 0);
                result.put("retMsg", "Successful. Backup file has placed to [" + new File(backupDbFile).getAbsolutePath() + "].");
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
        this.managementApp.get("/download", FileHandlerHelper::handleFileDownload);

        // 文件上传服务
        this.managementApp.post("/upload", FileHandlerHelper::handleFileUpload);

        // 查看日志服务
        this.managementApp.get("/viewLog", FileHandlerHelper::handleFileView);

        // 日志文件服务（返回纯文本）
        this.managementApp.get("/logfile", ctx -> {
            String logConfig = this.dbInstance.serverConfiguration.getLog();
            // 分割逗号分隔的配置
            String[] parts = logConfig.split(",");
            String filePath = null;
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.equalsIgnoreCase("CONSOLE") && !trimmed.isEmpty()) {
                    filePath = trimmed;
                    break;
                }
            }
            if (filePath == null) {
                ctx.result("Logging to console, no log file.");
                return;
            }
            Path file = Path.of(filePath).toAbsolutePath();
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                ctx.status(404).result("Log file not found: " + filePath);
                return;
            }
            try {
                String content = Files.readString(file);
                ctx.result(content);
            } catch (IOException e) {
                logger.error("Failed to read log file", e);
                ctx.status(500).result("Failed to read log file: " + e.getMessage());
            }
        });

        // 状态服务
        this.managementApp.get("/status", ctx -> ctx.json(getStatusJson()));

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

    private JSONObject getStatusJson() {
        JSONObject status = new JSONObject();
        Connection conn = null;
        try {
            conn = ((org.duckdb.DuckDBConnection) this.backendSysConnection).duplicate();

            LocalDateTime currentTime = LocalDateTime.now();

            // 获取数据库的一些基本信息
            String database_size = "";
            String memory_usage = "";
            String wal_size = "";
            String database_version = "";
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(
                    "select  version() as version,* " +
                            "from    pragma_database_size() " +
                            "where   database_name = current_database()");
            if (rs.next()) {
                database_size = rs.getString("database_size");
                memory_usage = rs.getString("memory_usage");
                database_version = rs.getString("version");
                wal_size = rs.getString("wal_size");
            }
            rs.close();
            stmt.close();

            // 从资源信息中读取系统的版本号
            String version, localBuildDate;
            try {
                InputStream inputStream = this.getClass().getResourceAsStream("/version.properties");
                Properties properties = new Properties();
                properties.load(inputStream);
                version = properties.getProperty("version", "{project.version}");
                String buildTimestamp = properties.getProperty("build.timestamp", "${build.timestamp}");

                // 转换编译的时间格式
                try {
                    ZonedDateTime zdt = ZonedDateTime.parse(buildTimestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
                    LocalDateTime localDateTime = LocalDateTime.ofInstant(zdt.toInstant(), ZoneId.systemDefault());
                    localBuildDate =
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").format(localDateTime) + " " +
                                    TimeZone.getTimeZone(ZoneId.systemDefault()).getID();
                } catch (DateTimeParseException ex) {
                    localBuildDate = buildTimestamp;
                }
            } catch (IOException ioe) {
                version = "{project.version}";
                localBuildDate = "${build.timestamp}";
            }

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
            parameters.put("remoteListener", config.getRemoteListener());
            parameters.put("data", config.getData());
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
            parameters.put("maxWorkers", config.getMax_Workers());
            parameters.put("accessMode", config.getAccess_mode());
            parameters.put("log", config.getLog());
            parameters.put("logLevel", config.getLog_level().levelStr);
            parameters.put("locale", config.getLocale());
            parameters.put("clientTimeout", config.getClient_timeout());
            parameters.put("queryResultCacheSize", org.slackerdb.common.utils.Utils.formatBytes(config.getQuery_result_cache_size()));
            status.put("parameters", parameters);

            // 构建 usage 对象
            JSONObject usage = new JSONObject();
            usage.put("maxConnections", this.dbInstance.dbDataSourcePool.getHighWaterMark());
            usage.put("currentConnections", this.dbInstance.dbDataSourcePool.getUsedConnectionPoolSize());
            usage.put("idleConnections", this.dbInstance.dbDataSourcePool.getIdleConnectionPoolSize());
            usage.put("activeSessions", this.dbInstance.activeSessions);
            usage.put("activeChannels", this.dbInstance.getRegisteredConnectionsCount());
            usage.put("queuedSqlHistory", this.dbInstance.sqlHistoryList.size());

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

            // 会话列表
            JSONArray sessions = new JSONArray();
            for (Integer sessionId : this.dbInstance.dbSessions.keySet()) {
                org.slackerdb.dbserver.server.DBSession dbSession = this.dbInstance.getSession(sessionId);
                JSONObject session = new JSONObject();
                session.put("id", sessionId);
                session.put("connectedTime", dbSession.connectedTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                session.put("clientIp", dbSession.clientAddress);
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
                if (conn != null && !conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException ignored) {}
        }
        return status;
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
