package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.duckdb.DuckDBConnection;
import org.pf4j.PluginManager;
import org.slackerdb.common.discovery.MulticastAddress;
import org.slackerdb.common.discovery.MulticastAdvertiser;
import org.slackerdb.common.discovery.NetworkAddressUtil;
import org.slackerdb.common.utils.BoundedQueue;
import org.slackerdb.common.utils.OSUtil;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.logger.AppLogger;
import org.slackerdb.common.utils.Sleeper;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbserver.entity.SQLHistoryRecord;
import org.slackerdb.dbserver.sql.SQLReplacer;
import org.slackerdb.dbserver.sql.SqlCommentStripper;
import org.slackerdb.plugin.DBPluginContext;
import org.slackerdb.plugin.DBPluginManager;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 数据库实例管理类，负责管理数据库实例的生命周期。
 * 每个数据库实例对应一个物理或内存数据库，支持多客户端并发访问。
 *
 */
public class DBInstance {
    // 服务器启动模式
    // 是否为独占模式，默认否
    // 当程序为独占模式的时候，退出端口的同时程序也将退出
    private boolean exclusiveMode = false;

    // 记录服务器启动的时间，用作日志显示
    public LocalDateTime bootTime = null;

    // 服务器配置参数
    public final ServerConfiguration serverConfiguration;

    // 服务器对应的PG协议转发器
    private PostgresServer protocolServer;

    // 实例对应的日志句柄
    public Logger logger;

    // 实例的名称
    public String instanceName;

    /** DuckDB 内存主 catalog 的名字，也是 fake catalog 的唯一归属地。 */
    private static final String MEMORY_CATALOG = "memory";

    /**
     * 承载 fake catalog（{@code duck_catalog}）的 catalog 名，也是 SQL 改写与会话
     * {@code search_path} 使用的限定前缀。
     *
     * <p><b>为什么必须有唯一来源</b>：客户端 SQL 里的 {@code pg_catalog.X} 会被
     * {@link SQLReplacer} 改写成 {@code <本字段>.duck_catalog.X}，而会话的
     * {@code search_path}（见 {@code StartupRequest}）也要指向同一个位置。
     * 两处必须逐字一致，否则改写出的表名解析不到。</p>
     *
     * <p><b>为什么恒为 {@code memory}</b>：{@link SlackerCatalog#createFakeCatalog}
     * 的第一条语句就是 {@code use memory}，之后才建 {@code duck_catalog}；而它在
     * {@link #attachDatabase} 里是在 {@code ATTACH} <b>之前</b>被调用的。因此无论
     * {@code data_dir} 是 {@code :memory:} 还是磁盘目录、无论实例库叫什么名字，
     * fake catalog 都只存在于内存主 catalog（DuckDB 默认的 {@code memory}）里。</p>
     *
     * <p><b>踩过的坑（不要重犯）</b>：这里曾经按配置推导成"文件模式 + 指定 data → 用实例库名"。
     * 但磁盘模式（{@code data_dir=<目录>} + {@code data=<库名>}，也就是真实部署形态）下
     * DuckDB 里根本没有 {@code <库名>.duck_catalog} 这个 schema，于是：
     * <ul>
     *   <li>会话下发 {@code SET search_path = '<库名>.duck_catalog,...'}——DuckDB 对 search_path 中
     *       不存在的 catalog+schema 是<b>直接报错</b>而非忽略：
     *       {@code Catalog Error: SET search_path: No catalog + schema named "xxx.duck_catalog" found.}
     *       于是每个新建连接都在握手期失败，服务端等于拒收全部客户端；</li>
     *   <li>{@code pg_catalog.X} 被改写成指向不存在的 {@code <库名>.duck_catalog.X}，
     *       客户端元数据查询同样失败
     *       （{@code Catalog Error: Table with name "duck_catalog.pg_type" does not exist}）。</li>
     * </ul>
     * 两条都由 {@code memory} 前缀解决（实测 {@code SET search_path='memory.duck_catalog,<库名>'}
     * 可正常解析、{@code pg_catalog.pg_type} 改写后也能查到）。</p>
     *
     * <p>如果将来真要把 fake catalog 建到实例库里，必须<b>同一次改动</b>先改
     * {@link SlackerCatalog#createFakeCatalog}（去掉 {@code use memory}，或改到 ATTACH 之后再建），
     * 再改 {@link #refreshCatalogName()}——两处必须一起改，否则又会分叉。</p>
     */
    public String catalogName = MEMORY_CATALOG;

    /**
     * 重算 {@link #catalogName}。
     *
     * <p>当前实现恒取 {@code memory}，原因见字段说明。保留这个方法的目的是让
     * "fake catalog 在哪个 catalog" 只有一个决定点：{@link #attachDatabase} 收尾会调用它，
     * 测试与嵌入场景也可以显式调用它把改写前缀对齐到实际位置。</p>
     */
    public void refreshCatalogName() {
        this.catalogName = MEMORY_CATALOG;
    }

    // DuckDB的数据库连接池
    public DBDataSourcePool dbDataSourcePool = null;

    // SQL/API 历史记录队列的容量
    public static final int HISTORY_QUEUE_CAPACITY = 10 * 1000;

    /**
     * 对外声明的 PostgreSQL 兼容版本号。
     *
     * <p><b>只能有这一个来源。</b>同一个版本号会从两个地方报给客户端，两者必须一致：</p>
     * <ul>
     *   <li>{@code ParameterStatus.server_version}（握手期下发）—— libpq 的 {@code PQserverVersion}、
     *       pgjdbc 的 {@code getServerVersion} 都用它做整数化的特性判定；</li>
     *   <li>{@code select pg_catalog.version()}（由 {@code SQLReplacer} 改写后返回）——
     *       SQLAlchemy 用 {@code ^(?:PostgreSQL|EnterpriseDB) (\d+)\.?(\d+)?} 解析它，
     *       并据此决定反射 SQL 的形态（例如是否使用 {@code pg_index.indnullsnotdistinct}）。</li>
     * </ul>
     *
     * <p>写成 {@code 15.0} 而不是 {@code 15}：libpq 解析 {@code server_version} 时要求至少
     * "主版本.次版本" 两段，PG 自身也下发 {@code 15.4} 这种形式。</p>
     */
    public static final String PG_COMPAT_VERSION = "15.0";

    // SQL历史记录并不会直接操作，而是会放到队列中，由其他线程来完成处理
    public BoundedQueue<SQLHistoryRecord>  sqlHistoryList
            = new BoundedQueue<>(HISTORY_QUEUE_CAPACITY);

    // 资源文件，记录各种消息，以及日后可能的翻译信息
    public final ResourceBundle resourceBundle;

    // 实例的状态
    public String instanceState = "IDLE";

    // 标记数据库是否正在等待输入密钥
    public boolean instanceSuspendForSecretKey = false;

    // DuckDB对应的后端长数据库连接
    public Connection backendSysConnection;

    // DuckDB对应的后端数据库连接参数
    private final Properties backendConnectProperties = new Properties();

    // SqlHistoryId 当前SQL历史的主键ID
    public final AtomicLong backendSqlHistoryId = new AtomicLong(1);

    // 系统活动的会话数，指保持在DB侧正在执行语句的会话数
    public final  AtomicInteger  activeSessions = new AtomicInteger(0);

    // 磁盘检查相关字段
    private volatile long lastDiskCheckTime = 0;
    private volatile boolean diskSpaceValid = true; // true表示空间足够
    private static final long DISK_CHECK_INTERVAL = 120000; // 2分钟，单位毫秒

    // 监听器（Netty）启动的最长等待时间。正常情况下 1 秒内就绪；60 秒是给
    // "起不来但也没立刻报错"的情况兜底，保证 start() 最终一定会返回而不是永久挂住。
    private static final long LISTENER_STARTUP_TIMEOUT_MILLIS = 60 * 1000;

    // 管理端口服务
    public DBInstanceX dbInstanceX = null;

    // 标记数据库是否为第一次打开
    private boolean databaseFirstOpened = false;

    // 插件管理器
    private PluginManager dbPluginManager = null;

    // 从资源文件中获取消息，为未来的多语言做准备
    private String getMessage(String code, Object... contents) {
        StringBuilder content;
        String pattern;
        try {
            pattern = this.resourceBundle.getString(code);
            content = new StringBuilder(MessageFormat.format(pattern, contents));
        } catch (MissingResourceException me)
        {
            content = new StringBuilder("MSG-" + code + ":");
            for (Object object : contents) {
                if (object != null) {
                    content.append(object).append("|");
                }
                else {
                    content.append("null|");
                }
            }
        }
        return content.toString();
    }

    // 为每个连接创建一个会话ID
    private final AtomicInteger maxSessionId = new AtomicInteger(100000);

    // 记录连接会话列表
    public final ConcurrentHashMap<Integer, DBSession> dbSessions = new ConcurrentHashMap<>();

    // PID进程锁信息
    private RandomAccessFile pidRandomAccessFile;
    private FileLock pidFileLockHandler;

    // 监控线程
    class DBInstanceMonitorThread extends Thread
    {
        @Override
        public void run()
        {
            // 设置线程名称
            setName("Session-Mon");

            // 每10秒钟检测一下当前负载状况
            while (!isInterrupted())
            {
                // 检查服务器是否过载
                if (getRegisteredConnectionsCount() > serverConfiguration.getMax_Workers()) {
                    logger.warn(
                            "[SERVER] The system is overloaded. " +
                                    "The current number of connection requests is {} " +
                                    "and the current maximum worker setting of the system is {}.",
                            getRegisteredConnectionsCount(), serverConfiguration.getMax_Workers());
                }

                // 检查磁盘空间剩余情况
                checkDiskSpace();

                // 每10秒钟循环一次
                try {
                    Sleeper.sleep(10 * 1000);
                }
                catch (InterruptedException interruptedException)
                {
                    this.interrupt();
                }
            }
        }
    }
    private DBInstanceMonitorThread dbInstanceMonitorThread = null;

    /**
     * 组播通告器。{@code advertise_address} 为空时始终为 {@code null}——
     * "不配置就是不启用"：不建 socket、不起线程、不产生任何周期性动作。
     */
    private MulticastAdvertiser advertiser = null;

    // SQL历史记录线程
    class DBInstanceSQLHistoryThread extends Thread
    {
        // 上一次告警时记录的丢弃数量，用于避免重复刷屏
        private long lastReportedBlocked = 0;

        @Override
        public void run()
        {
            // 设置线程名称
            setName("Session-SQLHistory");

            String historyInsertSQL = """
                    Insert INTO sysaux.SQL_HISTORY(
                        ID, ServerID, SessionId, ClientIP, SQL, SqlId, StartTime, EndTime,
                        SQLCode, AffectedRows, ErrorMsg)
                        VALUES(?,?, ?,?,?,?, ?,? , ?, ?, ?)
                    """;
            String historyUpdateSQL = """
                    Update  sysaux.SQL_HISTORY
                    SET     EndTime = ?,
                            SqlCode = ?,
                            AffectedRows = ?,
                            ErrorMsg = ?
                    WHERE ID = ?
                    """;

            // 外层循环：任何一次写入异常都不允许让消费线程退出。
            // 历史写入失败（磁盘满、DuckDB 写冲突、sysaux.SQL_HISTORY 被外部改动等）时，
            // 记录日志、丢弃当前这批、退避后重建连接继续消费。
            // 注意：如果这里因为异常而退出线程，队列将永远不再被排空，
            // 生产者会在队列满之后持续丢数据（改造前是阻塞 —— 整个服务挂死）。
            while (!isInterrupted()) {
                Connection sqlHistoryConn = null;
                PreparedStatement historyInsertStmt = null;
                PreparedStatement historyUpdateStmt = null;
                int nProcessedRows = 0;

                try {
                    sqlHistoryConn = ((DuckDBConnection) backendSysConnection).duplicate();
                    sqlHistoryConn.createStatement().execute("use memory");
                    sqlHistoryConn.setAutoCommit(false);
                    historyInsertStmt = sqlHistoryConn.prepareStatement(historyInsertSQL);
                    historyUpdateStmt = sqlHistoryConn.prepareStatement(historyUpdateSQL);

                    while (!isInterrupted()) {
                        while (!sqlHistoryList.isEmpty() && !isInterrupted()) {
                            SQLHistoryRecord sqlHistoryRecord = sqlHistoryList.poll();
                            if (sqlHistoryRecord == null) {
                                // 极小概率下被其他消费者取走，直接跳过，避免空指针
                                continue;
                            }
                            if (sqlHistoryRecord.type().equalsIgnoreCase("INSERT")) {
                                historyInsertStmt.setLong(1, sqlHistoryRecord.ID());
                                historyInsertStmt.setLong(2, sqlHistoryRecord.ServerID());
                                historyInsertStmt.setLong(3, sqlHistoryRecord.SessionId());
                                historyInsertStmt.setString(4, sqlHistoryRecord.ClientIP());
                                historyInsertStmt.setString(5, sqlHistoryRecord.SQL());
                                historyInsertStmt.setLong(6, sqlHistoryRecord.SqlId());
                                if (sqlHistoryRecord.StartTime() != null) {
                                    historyInsertStmt.setTimestamp(7, Timestamp.valueOf(sqlHistoryRecord.StartTime()));
                                }
                                else
                                {
                                    historyInsertStmt.setTimestamp(7, null);
                                }
                                if (sqlHistoryRecord.EndTime() != null) {
                                    historyInsertStmt.setTimestamp(8, Timestamp.valueOf(sqlHistoryRecord.EndTime()));
                                }
                                else
                                {
                                    historyInsertStmt.setTimestamp(8, null);
                                }
                                historyInsertStmt.setInt(9, sqlHistoryRecord.SQLCode());
                                historyInsertStmt.setLong(10, sqlHistoryRecord.AffectedRows());
                                historyInsertStmt.setString(11, sqlHistoryRecord.ErrorMsg());
                                historyInsertStmt.execute();
                                nProcessedRows = nProcessedRows + 1;
                            } else if (sqlHistoryRecord.type().equalsIgnoreCase("UPDATE")) {
                                if (sqlHistoryRecord.EndTime() != null) {
                                    historyUpdateStmt.setTimestamp(1, Timestamp.valueOf(sqlHistoryRecord.EndTime()));
                                }
                                else
                                {
                                    historyUpdateStmt.setTimestamp(1, null);
                                }
                                historyUpdateStmt.setInt(2, sqlHistoryRecord.SQLCode());
                                historyUpdateStmt.setLong(3, sqlHistoryRecord.AffectedRows());
                                historyUpdateStmt.setString(4, sqlHistoryRecord.ErrorMsg());
                                historyUpdateStmt.setLong(5, sqlHistoryRecord.ID());
                                historyUpdateStmt.execute();
                                nProcessedRows = nProcessedRows + 1;
                            }
                            if (nProcessedRows % 1000 == 0) {
                                sqlHistoryConn.commit();
                                nProcessedRows = 0;
                            }
                        }
                        if (nProcessedRows != 0)
                        {
                            sqlHistoryConn.commit();
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
                    logger.error("[SQLHistory] Save sql history failed. Will reconnect and retry.", historyException);
                    rollbackQuietly(sqlHistoryConn);
                }
                finally {
                    closeQuietly(historyInsertStmt);
                    closeQuietly(historyUpdateStmt);
                    closeQuietly(sqlHistoryConn);
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
         * <p>入队按设计"满了就阻塞、绝不丢弃"，所以这里唯一需要告警的就是
         * <b>生产者被迫等待</b>：说明消费端跟不上，业务线程已经被拖慢
         * （数据没丢，但不能一直这样）。</p>
         */
        private void reportQueuePressure() {
            long blocked = sqlHistoryList.getBlockedTotal();
            if (blocked > lastReportedBlocked) {
                logger.warn("[SQLHistory] History queue is full. Producers blocked {} time(s), {} ms in total "
                                + "(queue capacity = {}, current size = {}). The consumer is slower than the producers; "
                                + "audit records are NOT dropped, the producers wait instead.",
                        blocked, sqlHistoryList.getBlockedMillis(),
                        HISTORY_QUEUE_CAPACITY, sqlHistoryList.size());
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
    private DBInstanceSQLHistoryThread dbInstanceSQLHistoryThread = null;

    /**
     * 解析并启动组播通告。
     *
     * <p>这条路径替代了原来"周期性向 remote_listener 发 TCP REGISTER"的做法。区别在于：
     * 不再需要预先知道代理的地址、不再维护一条注册连接；并且进程被强杀时，代理侧能靠租约超时
     * 自动摘除条目，而不是留下一个永远指向死进程的目标。</p>
     *
     * <p>{@code advertise_address} 为空时<b>什么都不做</b>：不解析网卡、不建 socket、不起线程。</p>
     *
     * @throws ServerException 取值指向不存在的网卡、本机没有可用网卡、或 socket 建不起来
     */
    private void startAdvertise() throws ServerException
    {
        if (!serverConfiguration.isAdvertiseEnabled()) {
            logger.info("[SERVER][DISCOVERY  ] Service discovery is disabled (advertise_address is not set).");
            return;
        }

        MulticastAddress multicastAddress = serverConfiguration.getAdvertiseAddress();
        try {
            // 选本机地址的优先级：advertise_nic 显式指定 > bind 是具体地址 > 自动选一块可用网卡
            NetworkAddressUtil.Candidate candidate = NetworkAddressUtil.resolveForAdvertise(
                    serverConfiguration.getAdvertiseNic(), serverConfiguration.getBindHost());
            // 记下实际生效的地址，供 status 查询与排障使用
            serverConfiguration.setAdvertiseHost(candidate.getAddress().getHostAddress());

            MulticastAdvertiser.Options options = new MulticastAdvertiser.Options(
                    multicastAddress,
                    candidate.getNic(),
                    candidate.getAddress(),
                    serverConfiguration.getData(),
                    serverConfiguration.getPort(),
                    serverConfiguration.getPortX())
                    // 别名沿用库名，与旧 REGISTER 的 "AS <data>" 完全一致：客户端连接串不用改
                    .version(readVersion());

            advertiser = new MulticastAdvertiser(logger, options);
            advertiser.start();
        }
        catch (IllegalArgumentException | IOException e) {
            throw new ServerException("SLACKERDB-00021", Utils.getMessage("SLACKERDB-00021",
                    serverConfiguration.getAdvertiseNic().isEmpty()
                            ? "auto" : serverConfiguration.getAdvertiseNic(),
                    e.getMessage()));
        }
    }

    /**
     * 停止组播通告。
     *
     * <p>{@link MulticastAdvertiser#close()} 会先停周期线程、再连发两条 BYE，最后关 socket。
     * 代理收到 BYE 会立即摘除条目，不必等租约超时——否则停机窗口内客户端还会被路由到一个
     * 已经关闭的地址上。</p>
     */
    private void stopAdvertise()
    {
        if (advertiser != null) {
            advertiser.close();
            advertiser = null;
        }
    }

    /** 读取版本号，仅用于通告报文里的排障字段；读不到就留空，不影响功能。 */
    private static String readVersion()
    {
        try (InputStream inputStream = DBInstance.class.getResourceAsStream("/version.properties")) {
            if (inputStream == null) {
                return "";
            }
            Properties properties = new Properties();
            properties.load(inputStream);
            return properties.getProperty("version", "");
        }
        catch (IOException ioe) {
            return "";
        }
    }

    // 终止会话
    // 默认回滚所有会话
    // 幂等：会话不存在时静默返回（取消连接、重复断开等场景下会被调用到）
    public void abortSession(int sessionId) throws SQLException {
        // 销毁会话保持的数据库信息
        DBSession dbSession = dbSessions.get(sessionId);
        if (dbSession != null) {
            // 首先移除会话信息
            dbSessions.remove(sessionId);
            // 放弃会话
            dbSession.abortSession();
        }
    }

    // 关闭会话
    // closeSession默认提交所有未提交内容
    // 幂等：会话不存在时静默返回
    public void closeSession(int sessionId) throws SQLException {
        // 销毁会话保持的数据库信息
        DBSession dbSession = dbSessions.get(sessionId);
        if (dbSession != null) {
            // 移除会话信息
            dbSessions.remove(sessionId);
            // 关闭会话
            dbSession.closeSession();
        }
    }

    /**
     * 终止指定会话（供管理端 KILL SESSION 使用），可从任意线程调用。
     *
     * <p>这里刻意<b>不</b>直接调用目标会话的 {@code abortSession()}——那会从当前线程去改另一个会话的
     * 连接、语句以及 {@code parsedStatements}，与目标会话自己的线程竞争；而且它既不会把会话从
     * {@code dbSessions} 中摘除，也不会断开客户端，会留下一个"僵尸会话"：它的物理连接已被归还
     * 连接池，但会话仍在册，后续查询会用到已被别的会话占用的连接。</p>
     *
     * <p>正确做法分两步：先取消它正在执行的语句（打断阻塞中的查询），再关闭它的连接。
     * 关闭会在目标会话自己的线程上触发 {@code channelInactive -> abortSession()}，
     * 由那条线程完成"摘除注册 + 回滚 + 释放连接"。</p>
     *
     * @return {@code true} 表示会话存在且已发出终止请求
     */
    public boolean killSession(int sessionId) {
        DBSession dbSession = dbSessions.get(sessionId);
        if (dbSession == null) {
            return false;
        }
        dbSession.cancelRunningStatements();
        dbSession.closeChannel();
        return true;
    }

    // 执行指定的脚本
    private void executeScript(String scriptFileName) throws IOException, SQLException {
        logger.info("[SERVER][STARTUP    ] Executing script {} ...", scriptFileName);

        // 从文件中读取所有内容到字符串
        String sqlFileContents = new String(Files.readAllBytes(Path.of(scriptFileName)));

        // 按分号分隔语句
        Statement stmt = backendSysConnection.createStatement();
        List<String> sqlItems = SQLReplacer.splitSQLWithSemicolon(sqlFileContents);
        for (String sqlItem : sqlItems) {
            String sql = SqlCommentStripper.stripComments(sqlItem);
            if (!sql.trim().isEmpty()) {
                try {
                    logger.debug("[SERVER][STARTUP    ]   Executing sql: {} ;", sql);
                    stmt.execute(sql);
                } catch (SQLException e) {
                    logger.error("  SQL Error: {}", sql, e);
                    throw e;
                }
            }
        }
        stmt.close();
    }

    // 挂载数据库， 如果数据库的密钥为空，则不对数据库进行加密
    public void attachDatabase(String databaseEncryptKey)
    {
        // 如果数据库开启了加密，但是没有设置环境变量，则不要进行Attach
        // 如果指定了Data，则需要Attach到指定的数据库上
        Statement stmt = null;
        ResultSet rs = null;
        try {
            // 虚构一些PG的数据字典，以满足后续各种工具对数据字典的查找
            SlackerCatalog.createFakeCatalog(this, backendSysConnection);

            // 校验数据库参数
            // 注意：SQLReplacer 的改写规则是按 catalogName 生成的静态规则表，而 catalogName
            // 由 refreshCatalogName() 决定；规则表因此统一放到本方法的 finally 里加载一次，
            // 保证无论走哪条分支（data 为空 / 等密钥挂起 / 正常挂载）规则都与最终前缀一致。
            String instanceName = this.serverConfiguration.getData();
            if (instanceName.isEmpty())
            {
                // 没有数据库名，则不需要Attach操作，直接跳过
                return;
            }
            if (!Character.isLetter(instanceName.charAt(0)))
            {
                // 数据库名首字母必须为字母
                throw new ServerException("Instance name [" + instanceName + "] must start with a letter!");
            }
            for (int i = 0; i < instanceName.length(); i++) {
                char c = instanceName.charAt(i);
                if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') {
                    // 数据库名必须是数字或者字母包括，也包括_和-
                    throw new ServerException("Instance name [" + instanceName + "] can't not include special character!");
                }
            }
            if (List.of("MEMORY","SYS").contains(instanceName.toUpperCase()))
            {
                // 数据库名必须是数字或者字母包括，也包括_和-
                throw new ServerException("Instance name [" + instanceName + "] is not valid. It is reserved keyword!");
            }

            // 处理数据库密钥
            if (serverConfiguration.getDataEncrypt())
            {
                if (databaseEncryptKey == null && System.getenv("SLACKERDB_" + instanceName.toUpperCase() + "_KEY") != null)
                {
                    databaseEncryptKey = System.getenv("SLACKERDB_" + instanceName.toUpperCase() + "_KEY");
                }
                if (databaseEncryptKey == null && System.getProperty("SLACKERDB_" + instanceName.toUpperCase() + "_KEY") != null)
                {
                    databaseEncryptKey = System.getProperty("SLACKERDB_" + instanceName.toUpperCase() + "_KEY");
                }
                if (databaseEncryptKey == null)
                {
                    // 如果数据库被加密，且未在环境变量中提供密钥信息，则不再继续操作
                    logger.info("[SERVER][STARTUP    ] Instance suspend and waiting for encrypt key ...");
                    this.instanceSuspendForSecretKey = true;
                    return;
                }
            }

            String attachString;
            String dataFilePath;
            if (serverConfiguration.getData_Dir().trim().equalsIgnoreCase(":memory:")) {
                attachString = "ATTACH ':memory:' AS \"" + instanceName + "\"";
                dataFilePath = ":memory:";
                databaseFirstOpened = true;
            } else {
                File dataFile = new File(serverConfiguration.getData_Dir(), instanceName + ".db");
                if (!dataFile.canRead() && serverConfiguration.getAccess_mode().equalsIgnoreCase("READ_ONLY")) {
                    throw new ServerException("Data [" + dataFile.getAbsolutePath() + "] can't be read!!");
                }
                if (!dataFile.exists() && !new File(serverConfiguration.getData_Dir()).canWrite()) {
                    throw new ServerException("Data [" + dataFile.getAbsolutePath() + "] can't be create!!");
                }
                if (dataFile.exists() && !dataFile.canWrite() && serverConfiguration.getAccess_mode().equalsIgnoreCase("READ_WRITE")) {
                    throw new ServerException("Data [" + dataFile.getAbsolutePath() + "] can't be write!!");
                }
                if (!dataFile.exists()) {
                    // 文件的第一次被使用
                    databaseFirstOpened = true;
                }
                dataFilePath = dataFile.getAbsolutePath();
                attachString = "ATTACH '" + dataFile.getAbsolutePath() + "' AS \"" + instanceName + "\"";
            }
            String attachOptions = "(";
            if (serverConfiguration.getAccess_mode().equals("READ_ONLY")) {
                attachOptions = attachOptions + "READ_ONLY";
            }
            if (serverConfiguration.getDataEncrypt())
            {
                if (attachOptions.equalsIgnoreCase("(")) {
                    attachOptions = attachOptions + "ENCRYPTION_KEY '" + databaseEncryptKey + "'";
                }
                else
                {
                    attachOptions = attachOptions + ", ENCRYPTION_KEY '" + databaseEncryptKey + "'";
                }
            }
            attachOptions = attachOptions + ")";
            if (attachOptions.equalsIgnoreCase("()"))
            {
                attachOptions = "";
            }

            stmt = backendSysConnection.createStatement();
            if (serverConfiguration.getDataEncrypt())
            {
                // 自动加载HttpFS组件，来提交加密的效率
                try {
                    stmt.execute("load httpfs");
                    backendSysConnection.commit();
                }
                catch (SQLException ignored) {
                    // HttpFS组件加载错误，也不会进行任何干预
                    logger.warn("[SERVER] Load httpfs extension failed. This will have low performance in data encrypt.");
                    try
                    {
                        this.backendSysConnection.rollback();
                    }
                    catch (SQLException ignored2) {}
                }
                finally {
                    try {
                        if (!stmt.isClosed())
                        {
                            stmt.close();
                        }
                    }
                    catch (SQLException ignored) {}
                }
            }
            stmt = backendSysConnection.createStatement();
            stmt.execute(attachString + " " + attachOptions);
            stmt.close();
            stmt = null;
            // 走到这里说明实例库 ATTACH 成功。
            //
            // 注意：fake catalog（duck_catalog）**不在**这个实例库里 ——
            // createFakeCatalog 的第一条语句是 "use memory"，且它在本方法开头（ATTACH 之前）
            // 就被调用了，所以 duck_catalog 恒在内存主 catalog。不要再根据"实例库已挂载"
            // 去改 catalogName / 改写前缀，否则磁盘模式下所有连接都会在 SET search_path 阶段失败
            // （详见 catalogName 字段说明）。
            if (serverConfiguration.getData_Dir().trim().equalsIgnoreCase(":memory:")) {
                if (this.serverConfiguration.getDataEncrypt())
                {
                    logger.info("[SERVER][STARTUP    ] Instance mounted with encrypted feature at ':memory:{}' successful.", instanceName);
                }
                else {
                    logger.info("[SERVER][STARTUP    ] Instance mounted at ':memory:{}'.", instanceName);
                }
            }
            else
            {
                if (this.serverConfiguration.getDataEncrypt())
                {
                    logger.info("[SERVER][STARTUP    ] Instance mounted with encrypted feature at '{}' successful.", dataFilePath);
                }
                else {
                    logger.info("[SERVER][STARTUP    ] Instance mounted at '{}'.", dataFilePath);
                }
            }

            // 数据库已经被打开
            this.instanceSuspendForSecretKey = false;

            if (!serverConfiguration.getAccess_mode().equals("READ_ONLY")) {
                // 执行初始化脚本，如果有必要的话
                // 只有内存数据库或者文件数据库第一次启动的时候需要执行
                List<String> initScriptFiles = new ArrayList<>();
                if (databaseFirstOpened && !serverConfiguration.getInit_script().trim().isEmpty()) {
                    if (new File(serverConfiguration.getInit_script()).isFile()) {
                        initScriptFiles.add(new File(serverConfiguration.getInit_script()).getAbsolutePath());
                    } else if (new File(serverConfiguration.getInit_script()).isDirectory()) {
                        File[] files = new File(serverConfiguration.getInit_script()).listFiles();
                        if (files != null) {
                            for (File file : files) {
                                if (file.isFile() && file.getName().endsWith(".sql")) {
                                    initScriptFiles.add(file.getAbsolutePath());
                                }
                            }
                        }
                    } else {
                        logger.warn("[SERVER][STARTUP    ] Init script(s) [{}] does not exist!", serverConfiguration.getInit_script());
                    }
                }
                // 脚本按照名称来排序
                Collections.sort(initScriptFiles);
                for (String initScriptFile : initScriptFiles) {
                    executeScript(initScriptFile);
                }
                // 尝试提交，语句中可能不包含需要提交的部分，所以忽略Commit的错误
                try
                {
                    this.backendSysConnection.commit();
                }
                catch (SQLException ignored) {}
                logger.debug("[SERVER][STARTUP    ] Init {} script(s) execute completed.", initScriptFiles.size());

                // 执行系统启动脚本，如果有必要的话
                // 每次启动都要执行的部分
                List<String> startupScriptFiles = new ArrayList<>();
                if (!serverConfiguration.getStartup_script().trim().isEmpty()) {
                    if (new File(serverConfiguration.getStartup_script()).isFile()) {
                        startupScriptFiles.add(new File(serverConfiguration.getStartup_script()).getAbsolutePath());
                    } else if (new File(serverConfiguration.getStartup_script()).isDirectory()) {
                        File[] files = new File(serverConfiguration.getStartup_script()).listFiles();
                        if (files != null) {
                            for (File file : files) {
                                if (file.isFile() && file.getName().endsWith(".sql")) {
                                    startupScriptFiles.add(file.getAbsolutePath());
                                }
                            }
                        }
                    } else {
                        logger.warn("[SERVER][STARTUP    ] Startup script(s) [{}] does not exist!", serverConfiguration.getStartup_script());
                    }
                }
                // 脚本按照名称来排序
                Collections.sort(startupScriptFiles);
                for (String startupScriptFile : startupScriptFiles) {
                    executeScript(startupScriptFile);
                }
                logger.debug("[SERVER][STARTUP    ] Startup {} script(s) execute completed.", startupScriptFiles.size());

                // 检查模板文件是否存在
                if (databaseFirstOpened && !serverConfiguration.getTemplate().trim().isEmpty()) {
                    String templateFileName = serverConfiguration.getTemplate().trim();
                    File templateFile = new File(templateFileName);
                    if (!templateFile.exists() || !templateFile.canRead()) {
                        throw new ServerException("Template file [" + templateFile.getAbsolutePath() + "] does not exist or no permission!");
                    }
                    logger.info("[SERVER][STARTUP    ] Copy template database [{}] in ...", templateFile.getAbsolutePath());
                    stmt = backendSysConnection.createStatement();
                    stmt.execute("ATTACH '" + templateFile.getAbsolutePath() + "' AS _imp_db (READ_ONLY)");
                    rs = stmt.executeQuery("SELECT current_catalog()");
                    rs.next();
                    stmt.execute("COPY FROM DATABASE _imp_db TO " + rs.getString(1));
                    stmt.execute("DETACH DATABASE _imp_db");
                    stmt.close();
                    stmt = null;
                    logger.info("[SERVER][STARTUP    ] Copy template database completed.");
                }
            }
        }
        catch (SQLException | IOException exception)
        {
            throw new ServerException("Init backend connection error. Attach failed.", exception);
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ignored) {}
            }
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException ignored) {}
            }
            // 无论走了哪条分支（data 为空直接返回 / 加密模式第一段等密钥挂起 / 正常挂载 /
            // 挂载失败抛出），都在这里把 catalog 名与改写规则对齐：改写规则是按 catalogName
            // 生成的静态规则表，加载早了就会得到错误前缀（历史缺陷见 catalogName 字段说明）。
            //
            // 加密模式启动分两段：第一段（等密钥）会从这里返回，客户端随后发
            // ALTER DATABASE ... SET ENCRYPT KEY 会再次进入本方法；两次都走这里，
            // 因此无需再去记忆"实例库到底挂上了没有"。
            refreshCatalogName();
            SQLReplacer.load(this);
        }
    }

    /**
     * 构造函数，根据服务器配置创建数据库实例。
     *
     * @param pServerConfiguration 服务器配置对象，包含数据库连接参数、监听端口等配置信息
     * @throws ServerException 如果实例名包含非法字符或配置无效
     */
    public DBInstance(ServerConfiguration pServerConfiguration) throws ServerException
    {
        // 加载数据库的配置信息
        serverConfiguration = pServerConfiguration;

        // 设置消息语言集
        resourceBundle = ResourceBundle.getBundle("message", serverConfiguration.getLocale());

        // 检查是否包含路径分隔符
        String instanceName = serverConfiguration.getData();
        if (instanceName.contains("/") || instanceName.contains("\\")) {
            throw new ServerException("Invalid character. Invalid instance name [" + instanceName + "]");
        }
        // 检查是否包含不合法字符
        if (Pattern.compile("[\\\\/:*?\"<>|]").matcher(instanceName).find()) {
            throw new ServerException("Invalid character. Invalid instance name [" + instanceName + "]");
        }
        // 检查文件名长度（假设文件系统限制为255字符）
        if (instanceName.length() > 255) {
            throw new ServerException("Instance name is too long(>255). Invalid instance name [" + instanceName + "]");
        }
        this.instanceName = instanceName;
    }

    public synchronized void start(org.slf4j.Logger appLogger) throws ServerException {
        String instanceName = serverConfiguration.getData();

        // 使用外部的日志，而不是自己创建日志
        this.logger = (ch.qos.logback.classic.Logger) appLogger;
        if (this.logger.getLevel() == null)
        {
            this.logger.setLevel(Level.INFO);
        }

        // 禁用OSHI的日志信息
        ch.qos.logback.classic.Logger oshiLogger;
        oshiLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("oshi.software.os.windows.WindowsOperatingSystem");
        oshiLogger.setLevel(ch.qos.logback.classic.Level.OFF);
        oshiLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("oshi.util.platform.windows.PerfCounterQuery");
        oshiLogger.setLevel(ch.qos.logback.classic.Level.OFF);

        // 关闭pf4j的日志, 如果不是在trace下
        Logger pf4jLogger = (Logger) LoggerFactory.getLogger("org.pf4j");
        if (!this.logger.getLevel().equals(Level.TRACE)) {
            pf4jLogger.setLevel(Level.OFF);
        }

        // 只有在停止的状态下才能启动
        if (!this.instanceState.equalsIgnoreCase("IDLE"))
        {
            throw new ServerException("SLACKERDB-00006", getMessage("SLACKERDB-00006"));
        }

        // 服务器开始启动
        this.instanceState = "STARTING";
        this.bootTime = LocalDateTime.now();

        // 检查文件目录的可用性
        if (!serverConfiguration.getData_Dir().trim().equalsIgnoreCase(":memory:")) {
            if (!new File(serverConfiguration.getData_Dir()).isDirectory()) {
                throw new ServerException("Data directory [" + serverConfiguration.getData_Dir() + "] does not exist!");
            }

            // 检查文件系统剩余空间，如果少于128MB则拒绝启动
            long requiredFreeSpace = 128L * 1024 * 1024; // 128MB
            long freeSpace = OSUtil.getFreeDiskSpace(serverConfiguration.getData_Dir());
            if (freeSpace < requiredFreeSpace) {
                String freeSpaceMB = freeSpace >= 0 ? String.format("%.2f", freeSpace / (1024.0 * 1024.0)) : "unknown";
                throw new ServerException(
                        "Insufficient disk space on data directory [" + serverConfiguration.getData_Dir() + "]. " +
                                "Required: 128MB, Available: " + freeSpaceMB + "MB"
                );
            }
        }

        // 检查PID文件
        if (!this.serverConfiguration.getPid().isEmpty())
        {
            File pidParentFile = new File(this.serverConfiguration.getPid()).getParentFile();
            // 如果PID文件目录不存在，则建立这个目录
            if (!pidParentFile.exists())
            {
                var ignored = pidParentFile.mkdirs();
            }
            File pidFile = new File(this.serverConfiguration.getPid());
            // 尝试锁定文件
            try {
                pidRandomAccessFile = new RandomAccessFile(pidFile, "rw");
                if (!OSUtil.isWindows()) {
                    // Windows上锁定后，会导致其他进程无法查看PID文件
                    pidFileLockHandler = pidRandomAccessFile.getChannel().tryLock();
                    if (pidFileLockHandler == null) {
                        throw new ServerException(
                                "SLACKERDB-00013",
                                Utils.getMessage("SLACKERDB-00013", this.serverConfiguration.getPid()));
                    }
                }
                pidRandomAccessFile.setLength(0);
                pidRandomAccessFile.writeBytes(String.valueOf(ProcessHandle.current().pid()));
            } catch (IOException e) {
                throw new ServerException(
                        "SLACKERDB-00013",
                        Utils.getMessage("SLACKERDB-00013", this.serverConfiguration.getPid()));
            }
        }

        // DuckDB对应的后端数据库链接字符串
        String backendConnectString = "jdbc:duckdb:memory:db" + UUID.randomUUID().toString().replace("-", "");

        // 默认容许未签名的扩展, 该参数无法启动后设置，只能依赖链接参数传入
        backendConnectProperties.setProperty("allow_unsigned_extensions", "true");
        try {
            // 初始化数据库链接
            backendSysConnection = DriverManager.getConnection(backendConnectString, backendConnectProperties);
            backendSysConnection.setAutoCommit(false);

            // 设置相关参数
            Statement stmt = backendSysConnection.createStatement();
            if (!serverConfiguration.getTemp_dir().isEmpty()) {
                logger.debug("[SERVER][STARTUP    ] SET temp_directory to '{}'", serverConfiguration.getTemp_dir());
                stmt.execute("SET temp_directory to '" + serverConfiguration.getTemp_dir() + "'");
            }
            if (!serverConfiguration.getExtension_dir().isEmpty()) {
                logger.debug("[SERVER][STARTUP    ] SET extension_directory to '{}'", serverConfiguration.getExtension_dir());
                stmt.execute("SET extension_directory to '" + serverConfiguration.getExtension_dir() + "'");
            }
            if (!serverConfiguration.getMemory_limit().isEmpty()) {
                logger.debug("[SERVER][STARTUP    ] SET Memory_limit to '{}'", serverConfiguration.getMemory_limit());
                stmt.execute("SET memory_limit to '" + serverConfiguration.getMemory_limit() + "'");
            }
            if (serverConfiguration.getThreads() != 0) {
                logger.debug("[SERVER][STARTUP    ] SET threads to {}", serverConfiguration.getThreads());
                stmt.execute("SET threads to " + serverConfiguration.getThreads());
            }
            // 默认用后台线程来异步清除未完成的内存分配
            stmt.execute("set allocator_background_threads to true");
            // 禁用插件的自动安装机制，减少不必要的外网访问
            stmt.execute("set autoinstall_known_extensions to false");
            // 程序关闭的时候确保进行WAL回收
            stmt.execute("PRAGMA enable_checkpoint_on_shutdown");
            // 如果启用了quack，则打开quack
            if (serverConfiguration.getQuackAddr() != null)
            {
                logger.info("[SERVER] Enable quack remote server on {}.", serverConfiguration.getQuackAddr());
                ResultSet rs =
                        stmt.executeQuery("CALL quack_serve('quack:" + serverConfiguration.getQuackAddr() + "', allow_other_hostname => true);");
                if (rs.next())
                {
                    String authToken = rs.getString("auth_token");
                    logger.info("[SERVER] Quack auth token [{}].", authToken);
                }
                rs.close();
            }
            stmt.close();
        }
        catch (SQLException sqlException)
        {
            throw new ServerException("Init backend connection error. ", sqlException);
        }
        logger.info("[SERVER][STARTUP    ] Instance started successful.");

        // 打开数据库
        this.attachDatabase(null);

        try {
            // 初始化数据库连接池
            DBDataSourcePoolConfig dbDataSourcePoolConfig = new DBDataSourcePoolConfig();
            dbDataSourcePoolConfig.setMinimumIdle(serverConfiguration.getConnection_pool_minimum_idle());
            dbDataSourcePoolConfig.setMaximumIdle(serverConfiguration.getConnection_pool_maximum_idle());
            dbDataSourcePoolConfig.setMaximumLifeCycleTime(serverConfiguration.getConnection_pool_maximum_lifecycle_time());
            dbDataSourcePoolConfig.setMaximumPoolSize(serverConfiguration.getMax_connections());
            dbDataSourcePoolConfig.setJdbcURL(backendConnectString);
            dbDataSourcePoolConfig.setConnectProperties(backendConnectProperties);
            try {
                this.dbDataSourcePool = new DBDataSourcePool("DATABASE", dbDataSourcePoolConfig, logger);
            }
            catch (SQLException sqlException)
            {
                throw new ServerException("Init connection pool error [" + instanceName + "]", sqlException);
            }

            // 初始化SQLHistory
            if (!serverConfiguration.getAccess_mode().equals("READ_ONLY") &&
                    serverConfiguration.getSqlHistory().equalsIgnoreCase("ON")) {

                // SQL History会保存在数据库内部。
                Statement sqlHistoryStmt = backendSysConnection.createStatement();
                sqlHistoryStmt.execute("use memory");
                sqlHistoryStmt.execute("CREATE SCHEMA IF NOT EXISTS sysaux");
                sqlHistoryStmt.execute(
                        """
                                CREATE TABLE IF NOT EXISTS SYSAUX.SQL_HISTORY
                                (
                                    ID             BIGINT PRIMARY KEY,
                                    ServerID       INT,
                                    SessionID      INT,
                                    ClientIP       TEXT,
                                    StartTime      DateTime,
                                    EndTime        DateTime,
                                    Elapsed        INT GENERATED ALWAYS AS (DATEDIFF('SECOND', StartTime, EndTime)),
                                    SqlID          INT,
                                    SQL            TEXT,
                                    SqlCode        INT,
                                    AffectedRows   BIGINT,
                                    ErrorMsg       TEXT
                                );""");
                sqlHistoryStmt.close();
                logger.info("[SERVER][STARTUP    ] Sql history feature enabled.");

                // 获取之前最大的SqlHistory的ID
                String sql = "Select Max(ID) From sysaux.SQL_HISTORY";
                Statement statement = backendSysConnection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql);
                if (resultSet.next())
                {
                    this.backendSqlHistoryId.set(resultSet.getLong(1) + 1);
                }
                resultSet.close();
                statement.close();
                backendSysConnection.commit();

                // 开启后台异步线程，用来同步SQL历史
                if (dbInstanceSQLHistoryThread == null)
                {
                    dbInstanceSQLHistoryThread = new DBInstanceSQLHistoryThread();
                    // 守护线程：消费端即使卡住也不能阻止 JVM 退出（必须在 start() 之前设置）
                    dbInstanceSQLHistoryThread.setDaemon(true);
                    dbInstanceSQLHistoryThread.start();
                }
            }
        }
        catch(SQLException e){
            throw new ServerException("Init backend connection error. ", e);
        }

        // 标记服务已经挂载成功
        this.instanceState = "MOUNTED";

        // 启动PG的协议处理程序
        String socketPath = serverConfiguration.getSocket();
        if (serverConfiguration.getPort() != -1 || (socketPath != null && !socketPath.isEmpty())) {
            protocolServer = new PostgresServer();
            protocolServer.setLogger(logger);
            protocolServer.setBindHostAndPort(serverConfiguration.getBindHost(), serverConfiguration.getPort());
            protocolServer.setSocketPath(socketPath);
            protocolServer.setServerTimeout(serverConfiguration.getClient_timeout(), serverConfiguration.getClient_timeout(), serverConfiguration.getClient_timeout());
            protocolServer.setNioEventThreads(serverConfiguration.getMax_Workers());
            protocolServer.setBusinessThreads(serverConfiguration.resolveBusiness_threads());
            protocolServer.setDBInstance(this);
            protocolServer.start();
            try {
                // 等待 Netty 就绪。监听器启动失败（典型：端口被占用）或长时间无进展时
                // 直接失败 —— 不能无限等待，否则 start() 会永远挂住，调用方只看到"卡死"。
                protocolServer.waitUntilReady(LISTENER_STARTUP_TIMEOUT_MILLIS);
            } catch (Exception listenerException) {
                logger.error("[SERVER][STARTUP    ] Instance listener startup failed, rolling back startup.", listenerException);
                // 回滚已经完成的挂载：停掉监听器/后台线程/连接池，把实例置回 IDLE，
                // 否则失败的实例会留下线程与文件句柄（历史上正是这样把端口和内存一直占着）。
                try {
                    stop();
                } catch (Exception stopException) {
                    logger.warn("[SERVER][STARTUP    ] Cleanup after failed startup also failed.", stopException);
                }
                if (listenerException instanceof ServerException serverException) {
                    // 已是带明确原因（端口被占用/超时）的启动失败，直接上抛，不要套娃
                    throw serverException;
                }
                throw new ServerException("Start listener error. " + listenerException.getMessage(), listenerException);
            }
            if (serverConfiguration.getPort() != -1) {
                logger.info("[SERVER][STARTUP    ] Instance opened at {}:{} successful.",
                        serverConfiguration.getBindHost(), serverConfiguration.getPort());
            }
            if (socketPath != null && !socketPath.isEmpty()) {
                logger.info("[SERVER][STARTUP    ] Instance opened UDS at {} successful.", socketPath);
            }
        }
        else
        {
            logger.info("[SERVER][STARTUP    ] Instance opened without listener successful.");
        }

        // 启动监控线程
        if (dbInstanceMonitorThread == null)
        {
            dbInstanceMonitorThread = new DBInstanceMonitorThread();
            dbInstanceMonitorThread.start();
        }

        // 启动组播通告：未配置 advertise_address 时完全跳过（不建 socket、不起线程）
        startAdvertise();

        // 如果需要，启动管理端口
        if (serverConfiguration.getPortX() != -1) {
            // 关闭Javalin, 如果不是在trace下
            Logger javalinLogger = (Logger) LoggerFactory.getLogger("io.javalin.Javalin");
            Logger jettyLogger = (Logger) LoggerFactory.getLogger("org.eclipse.jetty");
            if (!this.logger.getLevel().equals(Level.TRACE)) {
                javalinLogger.setLevel(Level.OFF);
                jettyLogger.setLevel(Level.OFF);
            }
            this.dbInstanceX = new DBInstanceX(this);
        }
        else
        {
            logger.info("[SERVER][STARTUP    ] Management server listener has been disabled.");
        }

        // 加载数据库插件, 但是不要启动
        DBPluginContext dbPluginContext = new DBPluginContext();
        dbPluginContext.setDbBackendConn(this.backendSysConnection);
        dbPluginContext.setLogger(this.logger);
        dbPluginContext.setPluginProperties(serverConfiguration.getPluginProperties());
        if (this.dbInstanceX != null) {
            dbPluginContext.setJavalin(this.dbInstanceX.getManagementApp());
        }
        else
        {
            dbPluginContext.setJavalin(null);
        }
        logger.info("[SERVER][PLUGIN     ] Plugin manager started ...");
        if (!serverConfiguration.getPlugins_dir().isEmpty() && Path.of(serverConfiguration.getPlugins_dir()).toFile().isDirectory())
        {
            logger.info("[SERVER][PLUGIN     ] Will scan directory [{}] for plugin ...", Path.of(serverConfiguration.getPlugins_dir()));
            this.dbPluginManager = new DBPluginManager(Path.of(serverConfiguration.getPlugins_dir()), dbPluginContext);

            try {
                dbPluginManager.loadPlugins();
            } catch (Exception e) {
                logger.error("[SERVER][PLUGIN     ] load plugins failed: {}", e.getMessage(), e);
            }
            for (int i=0; i< dbPluginManager.getPlugins().size(); i++)
            {
                String pluginId = dbPluginManager.getPlugins().get(i).getPluginId();
                logger.info("[SERVER][PLUGIN     ] start plugin [{}] ...", pluginId);
                try {
                    dbPluginManager.startPlugin(pluginId);
                } catch (Exception e) {
                    // 插件启动失败（依赖缺失、配置错误等）由插件自己负责，
                    // 不应连带把数据库服务器本身拖垮
                    logger.error("[SERVER][PLUGIN     ] start plugin [{}] failed: {}", pluginId, e.getMessage(), e);
                }
            }
        }
        else
        {
            this.dbPluginManager = new DBPluginManager(dbPluginContext);
        }

        // 标记服务已经启动完成
        this.instanceState = "RUNNING";
    }

    /**
     * 启动数据库实例。
     */
    public synchronized void start() throws ServerException {
        // 初始化日志服务
        Logger appLogger = AppLogger.createLogger(
                serverConfiguration.getData(),
                serverConfiguration.getLog_level().levelStr,
                serverConfiguration.getLog());

        // 启动应用
        this.start(appLogger);
    }

    /**
     * 停止数据库实例。
     * 该方法执行以下操作：
     * <ol>
     *   <li>检查实例状态，如果已经是IDLE状态则直接返回</li>
     *   <li>设置实例状态为SHUTTING DOWN</li>
     *   <li>停止管理服务</li>
     *   <li>停止监控线程</li>
     *   <li>停止SQL历史记录处理线程</li>
     *   <li>停止PostgreSQL协议服务器</li>
     *   <li>关闭数据库连接池</li>
     *   <li>释放文件锁</li>
     * </ol>
     *
     * @throws ServerException 如果停止过程中发生错误
     */
    public synchronized void stop() throws ServerException
    {
        if (this.instanceState.equalsIgnoreCase("IDLE"))
        {
            // 已经在停止中，不需要重复停止
            return;
        }

        this.instanceState = "SHUTTING DOWN";

        // 停止管理服务
        if (this.dbInstanceX != null)
        {
            this.dbInstanceX.stop();
            this.dbInstanceX = null;
        }

        // 关闭监控线程
        if (dbInstanceMonitorThread != null) {
            interruptAndJoin(dbInstanceMonitorThread, 10, "Session-Mon");
            dbInstanceMonitorThread = null;
        }
        // 关闭SQLHistory的后台进程
        if (dbInstanceSQLHistoryThread != null) {
            interruptAndJoin(dbInstanceSQLHistoryThread, 10, "Session-SQLHistory");
            dbInstanceSQLHistoryThread = null;
        }

        // 停止数据库对外网络服务
        if (protocolServer != null) {
            protocolServer.stop();
        }

        // 停止组播通告（会连发 BYE，让代理立刻摘除本实例，而不是等租约超时）
        stopAdvertise();

        // 关闭数据库连接池
        if (this.dbDataSourcePool != null) {
            this.dbDataSourcePool.shutdown();
        }

        try {
            if (backendSysConnection != null && !backendSysConnection.isClosed() && !backendSysConnection.isReadOnly()) {
                // 退出前回滚所有事务
                try { backendSysConnection.rollback();} catch (SQLException ignored) {}

                Statement stmt = backendSysConnection.createStatement();

                // 如果quack服务在运行，需要停止
                if (serverConfiguration.getQuackAddr() != null)
                {
                    stmt.execute("CALL quack_stop('quack:" + serverConfiguration.getQuackAddr() + "')");
                }
                // 数据库强制进行检查点操作
                stmt.execute("FORCE CHECKPOINT");
                stmt.close();
            }

            // 关闭BackendSysConnection
            if (backendSysConnection != null && !backendSysConnection.isClosed()) {
                backendSysConnection.close();
            }
        }
        catch (SQLException e) {
            logger.error("Force checkpoint failed.", e);
        }

        // 删除PID文件
        if (pidRandomAccessFile != null )
        {
            try {
                if (pidFileLockHandler != null) {
                    pidFileLockHandler.release();
                    pidFileLockHandler = null;
                }
                pidRandomAccessFile.close();
                File pidFile = new File(this.serverConfiguration.getPid());
                if (pidFile.exists()) {
                    var ignored = pidFile.delete();
                }
                pidRandomAccessFile = null;
            }
            catch (IOException ioException) {
                logger.warn("[SERVER][STARTUP    ] Remove pid file failed, reason unknown!", ioException);
            }
        }

        // 数据库标记为空闲
        this.instanceState = "IDLE";
    }

    public int getRegisteredConnectionsCount()
    {
        if (this.protocolServer == null) {
            return 0;
        }
        return this.protocolServer.getRegisteredConnectionsCount();
    }

    /**
     * SQL 历史消费线程是否存活。
     * 为 false 表示历史记录已不再落库（生产者会在队列满之后持续丢弃记录），
     * 用于运维侧观测，见 /status 的 usage.sqlHistoryThreadAlive。
     */
    public boolean isSqlHistoryThreadAlive()
    {
        return this.dbInstanceSQLHistoryThread != null && this.dbInstanceSQLHistoryThread.isAlive();
    }

    /**
     * 中断后台线程并等待其退出（最多等待 timeoutSeconds 秒），仍在运行则记录告警后继续。
     * 这样既能尽快回收资源，又不会因为某个后台线程卡住而让 stop() 永久阻塞。
     *
     * @param thread         目标线程，允许为 null
     * @param timeoutSeconds 最长等待时间（秒）
     * @param threadRole     线程角色描述，仅用于日志
     */
    void interruptAndJoin(Thread thread, long timeoutSeconds, String threadRole)
    {
        if (thread == null || !thread.isAlive()) {
            return;
        }
        thread.interrupt();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (thread.isAlive()) {
            try {
                thread.join(100);
            }
            catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            if (System.nanoTime() >= deadline) {
                // 只告警不阻塞：后台线程为守护线程，不会阻止进程退出
                if (this.logger != null) {
                    this.logger.warn("[SERVER] {} thread did not terminate within {} seconds, proceeding anyway.",
                            threadRole, timeoutSeconds);
                }
                return;
            }
        }
    }

    // 初始化一个新的数据库会话
    public int newSession(DBSession dbSession)
    {
        int currentSessionId = maxSessionId.incrementAndGet();
        dbSessions.put(currentSessionId, dbSession);
        return currentSessionId;
    }

    // 根据SessionID获取对应的Session信息
    public DBSession getSession(int sessionId)
    {
        return dbSessions.get(sessionId);
    }

    // 返回程序为独占模式
    public boolean isExclusiveMode()
    {
        return this.exclusiveMode;
    }

    // 设置程序运行的模式，是否为独占模式
    public void setExclusiveMode(boolean exclusiveMode)
    {
        this.exclusiveMode = exclusiveMode;
    }

    // 获取插件管理器
    public PluginManager getPluginManager()
    {
        return this.dbPluginManager;
    }

    // 检查磁盘空间，更新状态字段
    private void checkDiskSpace() {
        // 如果是内存数据库，无需检查
        if (serverConfiguration.getData_Dir().trim().equalsIgnoreCase(":memory:")) {
            diskSpaceValid = true;
            lastDiskCheckTime = System.currentTimeMillis();
            return;
        }

        long currentTime = System.currentTimeMillis();

        // 检查是否需要执行磁盘空间检查
        if (currentTime - lastDiskCheckTime > DISK_CHECK_INTERVAL) {
            long requiredFreeSpace = (long) 1024 * 1024 * 1024; // 1GB
            long freeSpace = OSUtil.getFreeDiskSpace(serverConfiguration.getData_Dir());
            diskSpaceValid = (freeSpace >= requiredFreeSpace);
            lastDiskCheckTime = currentTime;
            
            if (!diskSpaceValid) {
                String freeSpaceMB = freeSpace >= 0 ? String.format("%.2f", freeSpace / (1024.0 * 1024.0)) : "unknown";
                logger.warn("[SERVER] Insufficient disk space on data directory [{}]. Required: 128MB, Available: {}MB",
                        serverConfiguration.getData_Dir(), freeSpaceMB);
            }
        }
    }
}
