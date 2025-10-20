package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import org.duckdb.DuckDBConnection;
import org.slackerdb.common.utils.BoundedQueue;
import org.slackerdb.common.utils.OSUtil;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.logger.AppLogger;
import org.slackerdb.common.utils.Sleeper;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbserver.entity.SQLHistoryRecord;
import org.slackerdb.dbserver.message.request.AdminClientRequest;
import org.slackerdb.dbserver.sql.SQLReplacer;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.sql.*;
import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public class DBInstance {
    // 服务器启动模式
    // 是否为独占模式，默认否
    // 当程序为独占模式的时候，退出端口，意味着程序也将退出
    private boolean exclusiveMode = false;

    // 服务器启动的时间
    public LocalDateTime bootTime = null;

    // 服务器配置参数
    public final ServerConfiguration serverConfiguration;

    // 服务器对应的PG协议转发器
    private PostgresServer protocolServer;

    // 实例对应的日志句柄
    public Logger logger;

    // 实例的名称
    public String instanceName;

    // DuckDB的数据库连接池
    public DBDataSourcePool dbDataSourcePool = null;

    // SQL历史记录并不会直接操作，而是会放到队列中，由其他线程来完成处理
    public BoundedQueue<SQLHistoryRecord>  sqlHistoryList
            = new BoundedQueue<>(10*1000);

    // 资源文件，记录各种消息，以及日后可能的翻译信息
    public final ResourceBundle resourceBundle;

    // 实例的状态
    public String instanceState = "IDLE";

    // DuckDB对应的后端长数据库连接
    public Connection backendSysConnection;
    public String backendConnectString;
    public Properties backendConnectProperties = new Properties();

    // SqlHistoryId 当前SQL历史的主键ID
    public final AtomicLong backendSqlHistoryId = new AtomicLong(1);

    // 系统活动的会话数，指保持在DB侧正在执行语句的会话数
    public final  AtomicInteger  activeSessions = new AtomicInteger(0);

    // 系统最后活跃时间
    public long lastActiveTime = System.currentTimeMillis();

    // 管理端口服务
    public DBInstanceX dbInstanceX = null;

    // 自动挂载的数据库清单
    public List<String> autoloadDatabase = new ArrayList<>();

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

    // 记录会话列表
    public final ConcurrentHashMap<Integer, DBSession> dbSessions = new ConcurrentHashMap<>();

    // PID进程锁信息
    private RandomAccessFile pidRandomAccessFile;
    private FileLock pidFileLockHandler;

    // 监控线程
    class DBInstanceMonitorThread extends Thread
    {
        private static String lastRegisterMessage = "";

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

                // 如果配置了外部监听地址，则定期发送本服务器信息到监听地址上
                if (!serverConfiguration.getRemoteListener().isEmpty())
                {
                    logger.trace("[SERVER] Will register current service to [{}] ...", serverConfiguration.getRemoteListener());
                    String remoteListener = serverConfiguration.getRemoteListener();
                    String remoteListenerHost = remoteListener.substring(0, remoteListener.lastIndexOf(':'));
                    int remoteListenerPort = Integer.parseInt(remoteListener.substring(remoteListener.indexOf(':') + 1));
                    try {
                        talkWithRemoteListener("REGISTER", remoteListenerHost, remoteListenerPort);
                    }
                    catch (ServerException serverException)
                    {
                        logger.warn("[SERVER] Try register current service to [{}] failed. {}",
                                serverConfiguration.getRemoteListener(), serverException.getErrorMessage());
                    }
                }

                // 如果启用了自动挂载，则定期检查data_dir下的内容
                if (
                        serverConfiguration.getAutoload().equalsIgnoreCase("ON") &&
                                !serverConfiguration.getData_Dir().equalsIgnoreCase(":MEMORY"))
                {
                    File[] files = new File(serverConfiguration.getData_Dir()).listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (!file.isFile()) {
                                // 不查看子目录
                                continue;
                            }
                            if (!file.getName().endsWith(".db"))
                            {
                                // 不查看非db文件
                                continue;
                            }
                            if (autoloadDatabase.contains(file.getName()))
                            {
                                // 如果之前已经挂载，则不再处理
                                continue;
                            }
                            // 尝试独占锁定该文件，如果失败，则跳过
                            try
                            {
                                long fileLength1;
                                long fileLength2;
                                FileTime lastFileModifiedTime1;
                                FileTime lastFileModifiedTime2;

                                if (serverConfiguration.getAutoload_access_mode().equalsIgnoreCase("READ_WRITE"))
                                {
                                   // 尝试锁定文件
                                    RandomAccessFile randomAccessFile1
                                            = new RandomAccessFile(Path.of(serverConfiguration.getData_Dir(), file.getName()).toString(), "rw");
                                    FileChannel channel1 = randomAccessFile1.getChannel();
                                    FileLock lock1 = channel1.tryLock();
                                    if (lock1 != null)
                                    {
                                        // 已经锁定了该文件
                                        lock1.release();
                                        channel1.close();
                                        randomAccessFile1.close();
                                    }
                                    else
                                    {
                                        // 无法锁定文件
                                        channel1.close();
                                        randomAccessFile1.close();
                                        continue;
                                    }
                                }
                                fileLength1 = Path.of(serverConfiguration.getData_Dir(), file.getName()).toFile().length();
                                lastFileModifiedTime1 = Files.readAttributes(Path.of(serverConfiguration.getData_Dir(), file.getName()), BasicFileAttributes.class).lastModifiedTime();

                                // 10S后再次观察文件
                                try {
                                    Sleeper.sleep(10 * 1000);
                                }
                                catch (InterruptedException ignored) {}

                                // 重新观察文件
                                if (serverConfiguration.getAutoload_access_mode().equalsIgnoreCase("READ_WRITE")) {
                                    RandomAccessFile randomAccessFile2
                                            = new RandomAccessFile(Path.of(serverConfiguration.getData_Dir(), file.getName()).toString(), "rw");
                                    FileChannel channel2 = randomAccessFile2.getChannel();
                                    FileLock lock2 = channel2.tryLock();
                                    if (lock2 != null) {
                                        // 已经锁定了该文件
                                        lock2.release();
                                        channel2.close();
                                        randomAccessFile2.close();
                                    } else {
                                        // 无法锁定文件
                                        channel2.close();
                                        randomAccessFile2.close();
                                        continue;
                                    }
                                }
                                fileLength2 = Path.of(serverConfiguration.getData_Dir(), file.getName()).toFile().length();
                                lastFileModifiedTime2 = Files.readAttributes(Path.of(serverConfiguration.getData_Dir(), file.getName()), BasicFileAttributes.class).lastModifiedTime();

                                // 比对两次锁定的文件情况
                                if (fileLength1 != fileLength2 || !lastFileModifiedTime1.equals(lastFileModifiedTime2))
                                {
                                    // 文件发生了变化
                                    continue;
                                }

                                // 挂载该文件, 如果参数为只读，或者文件为只读文件，则用只读方式挂载
                                try {
                                    Connection conn = ((DuckDBConnection) backendSysConnection).duplicate();
                                    Statement stmt = conn.createStatement();
                                    String sql = "ATTACH OR REPLACE '" + Path.of(serverConfiguration.getData_Dir(), file.getName()) + "' AS \"" + file.getName().replace(".db","") + "\"";
                                    if (serverConfiguration.getAutoload_access_mode().equalsIgnoreCase("READ_ONLY"))
                                    {
                                        sql = sql + " (READ_ONLY)";
                                    }
                                    stmt.execute(sql);
                                    conn.close();
                                    logger.info("[SERVER] autoload database [{}] as [{}], attached successful.",
                                            Path.of(serverConfiguration.getData_Dir(), file.getName()) ,
                                            file.getName().replace(".db","") );
                                    // 追加到文件记录中
                                    autoloadDatabase.add(file.getName());
                                }
                                catch (SQLException sqlException)
                                {
                                    logger.error("[SERVER] Unable to load file [{}]. ",
                                            Path.of(serverConfiguration.getData_Dir(), file.getName()).toAbsolutePath(), sqlException);
                                }
                            }
                            catch (IOException ignored) {}

                            // 如果有已经被挪走的文件，则强制detach这个数据库
                            for (String dbName : autoloadDatabase)
                            {
                                if (!Path.of(serverConfiguration.getData_Dir(), dbName).toFile().exists())
                                {
                                    try {
                                        Connection conn = ((DuckDBConnection) backendSysConnection).duplicate();
                                        Statement stmt = conn.createStatement();
                                        String sql = "DETACH IF EXISTS \"" + file.getName().replace(".db", "") + "\"";
                                        stmt.execute(sql);
                                        conn.close();
                                    }
                                    catch (SQLException ignored) {}
                                    // 移除文件目录
                                    autoloadDatabase.remove(dbName);
                                }
                            }
                        }
                    }
                }

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

    // SQL历史记录线程
    class DBInstanceSQLHistoryThread extends Thread
    {
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
            PreparedStatement historyInsertStmt;
            PreparedStatement historyUpdateStmt;
            try {
                Connection sqlHistoryConn = ((DuckDBConnection) backendSysConnection).duplicate();
                sqlHistoryConn.createStatement().execute("use memory");
                sqlHistoryConn.setAutoCommit(false);
                int nProcessedRows = 0;
                historyInsertStmt = sqlHistoryConn.prepareStatement(historyInsertSQL);
                historyUpdateStmt = sqlHistoryConn.prepareStatement(historyUpdateSQL);
                while (!isInterrupted()) {
                    while (!sqlHistoryList.isEmpty() && !isInterrupted()) {
                        SQLHistoryRecord sqlHistoryRecord = sqlHistoryList.poll();
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
                logger.error("[SQLHistory] Save sql history failed.", sqlException);
            }
        }
    }
    private DBInstanceSQLHistoryThread dbInstanceSQLHistoryThread = null;

    // 注册/取消注册当前的服务端口到指定的监听进程上
    private void talkWithRemoteListener(String method, String remoteListenerHost, int remoteListenerPort) throws ServerException
    {
        // Encoder to convert byte[] to ByteBuf
        class ByteArrayEncoder extends MessageToByteEncoder<byte[]> {
            @Override
            protected void encode(ChannelHandlerContext channelHandlerContext, byte[] msg, ByteBuf out)  {
                out.writeBytes(msg);
            }
        }

        // Decoder to convert ByteBuf to byte[]
        class ByteArrayDecoder extends ByteToMessageDecoder {
            @Override
            protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
                while (in.readableBytes() > 0) {
                    if (in.readableBytes() < 5) {
                        // 等待发送完毕，前5个字节为标识符和消息体长度
                        return;
                    }

                    // 首字节为消息体的长度
                    byte[] data = new byte[5];
                    in.readBytes(data);
                    ByteBuffer byteBuffer = ByteBuffer.wrap(data);
                    byteBuffer.get();  // 忽略传递的首位标志
                    int messageLen = byteBuffer.getInt();

                    // 等待消息体发送结束, 4字节的字节长度也是消息体长度的一部分
                    if (in.readableBytes() < (messageLen - 4)) {
                        in.readerIndex(in.readerIndex() - 5); // 重置读取位置
                        return;
                    }
                    data = new byte[messageLen - 4];
                    in.readBytes(data);

                    // 处理消息，并回显. 避免噪音重复打印
                    String registerFeedBackMessage = new String(data, StandardCharsets.UTF_8);
                    if ((data.length != 0) && !DBInstanceMonitorThread.lastRegisterMessage.equalsIgnoreCase(registerFeedBackMessage))
                    {
                        logger.info("[SERVER] {}", registerFeedBackMessage);
                        DBInstanceMonitorThread.lastRegisterMessage = registerFeedBackMessage;
                    }

                    // 关闭连接，每次只处理一个请求
                    ctx.close();
                }
            }
        }

        // 启动Netty客户端
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        try {
            Bootstrap client = new Bootstrap();
            client.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ChannelInitializer<NioSocketChannel>() {
                                 @Override
                                 protected void initChannel(NioSocketChannel ch) {
                                     // 定义消息处理
                                     ch.pipeline().addLast(new ByteArrayEncoder());
                                     ch.pipeline().addLast(new ByteArrayDecoder());
                                 }
                             }
                    );

            // 连接服务器
            ChannelFuture future =
                    client.connect(remoteListenerHost, remoteListenerPort).sync();

            // 发送消息头，并等待回应标志
            ByteBuf buffer = Unpooled.wrappedBuffer(AdminClientRequest.AdminClientRequestHeader);
            future.channel().writeAndFlush(buffer).sync();

            // 拼接消息正文
            InetSocketAddress localAddress = (InetSocketAddress) future.channel().localAddress();
            String message = "";
            // REGISTER <hostName>:<port,portX>/<dbInstanceName> AS <alias>
            if (method.equals("REGISTER")) {
                message = "REGISTER " +
                        localAddress.getHostName() + ":" + serverConfiguration.getPort() + "," + serverConfiguration.getPortX() +
                        "/" + serverConfiguration.getData() + " AS " +
                        serverConfiguration.getData();
            }
            else if (method.equals("UNREGISTER")) {
                message = "UNREGISTER " + serverConfiguration.getData() ;
            }

            // 发送消息正文
            byte[] msg = message.getBytes(StandardCharsets.UTF_8);
            buffer = Unpooled.buffer().capacity(5+msg.length);
            buffer.writeByte('!');
            buffer.writeInt(4 + msg.length);
            buffer.writeBytes(msg);
            future.channel().writeAndFlush(buffer).sync();

            // Wait until the connection is closed.
            future.channel().closeFuture().sync();
        }
        catch (Exception e) {
            logger.warn("[SERVER] Register current service to remote server failed. Will try again later ...");
            logger.trace("Error connecting to remote server", e);
        }
        finally {
            group.shutdownGracefully();
        }
    }

    // 终止会话
    // 默认回滚所有会话
    public void abortSession(int sessionId) throws SQLException {
        // 标记最后活跃时间
        this.lastActiveTime = System.currentTimeMillis();

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
    public void closeSession(int sessionId) throws SQLException {
        // 标记最后活跃时间
        this.lastActiveTime = System.currentTimeMillis();

        // 销毁会话保持的数据库信息
        DBSession dbSession = dbSessions.get(sessionId);
        if (dbSession != null) {
            // 移除会话信息
            dbSessions.remove(sessionId);
            // 关闭会话
            dbSession.closeSession();
        }
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
            String sql = SQLReplacer.removeSQLComments(sqlItem);
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

    // 构造函数
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

    // 根据参数配置文件启动数据库实例
    public synchronized void start() throws ServerException {
        String instanceName = serverConfiguration.getData();

        // 初始化日志服务
        logger = AppLogger.createLogger(
                serverConfiguration.getData(),
                serverConfiguration.getLog_level().levelStr,
                serverConfiguration.getLog());

        // 只有在停止的状态下才能启动
        if (!this.instanceState.equalsIgnoreCase("IDLE"))
        {
            throw new ServerException("SLACKERDB-00006", getMessage("SLACKERDB-00006"));
        }

        // 服务器开始启动
        this.instanceState = "STARTING";
        this.bootTime = LocalDateTime.now();

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

        // 文件是否为第一次打开
        boolean databaseFirstOpened = false;

        // 建立基础数据库连接
        this.backendConnectString = "jdbc:duckdb:memory:db" + UUID.randomUUID().toString().replace("-","");
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
            stmt.close();
        }
        catch (SQLException sqlException)
        {
            throw new ServerException("Init backend connection error. ", sqlException);
        }
        logger.info("[SERVER][STARTUP    ] Instance started successful.");

        // 如果指定了Data，则需要Attach到指定的数据库上
        if (!serverConfiguration.getData().isEmpty()) {
            try {
                String attachString;
                String dataFilePath;
                if (serverConfiguration.getData_Dir().trim().equalsIgnoreCase(":memory:")) {
                    attachString = "ATTACH ':memory:' AS \"" + instanceName + "\"";
                    dataFilePath = ":memory:";
                    databaseFirstOpened = true;
                } else {
                    if (!new File(serverConfiguration.getData_Dir()).isDirectory()) {
                        throw new ServerException("Data directory [" + serverConfiguration.getData_Dir() + "] does not exist!");
                    }
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
                if (serverConfiguration.getAccess_mode().equals("READ_ONLY")) {
                    attachString = attachString + " (READ_ONLY)";
                }
                Statement stmt = backendSysConnection.createStatement();
                stmt.execute(attachString);
                stmt.close();
                if (serverConfiguration.getData_Dir().trim().equalsIgnoreCase(":memory:")) {
                    logger.info("[SERVER][STARTUP    ] Instance mounted at ':memory:{}'.", instanceName);
                }
                else
                {
                    logger.info("[SERVER][STARTUP    ] Instance mounted at '{}' successful.", dataFilePath);
                }

                if (!serverConfiguration.getAccess_mode().equals("READ_ONLY")) {
                    try {
                        // 虚构一些PG的数据字典，以满足后续各种工具对数据字典的查找
                        SlackerCatalog.createFakeCatalog(this, backendSysConnection);

                        stmt = backendSysConnection.createStatement();
                        // 强制约定在程序推出的时候保存检查点
                        stmt.execute("PRAGMA enable_checkpoint_on_shutdown");
                        stmt.close();
                    } catch (SQLException e) {
                        logger.error("[SERVER][STARTUP    ] Init backend connection error. ", e);
                        throw new ServerException(e);
                    }

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
                }
            }
            catch (SQLException | IOException exception)
            {
                throw new ServerException("Init backend connection error. Attach failed.", exception);
            }
        }

        try {
            // 检查模板文件是否存在，如果有问题，直接退出
            if (databaseFirstOpened && !serverConfiguration.getTemplate().trim().isEmpty()) {
                String templateFileName = serverConfiguration.getTemplate().trim();
                File templateFile = new File(templateFileName);
                if (!templateFile.exists() || !templateFile.canRead())
                {
                    throw new ServerException("Template file [" + templateFile.getAbsolutePath() + "] does not exist or no permission!");
                }
            }

            // 检查模板文件是否存在
            if (databaseFirstOpened && !serverConfiguration.getTemplate().trim().isEmpty()) {
                String templateFileName = serverConfiguration.getTemplate().trim();
                File templateFile = new File(templateFileName);
                if (!templateFile.exists() || !templateFile.canRead())
                {
                    throw new ServerException("Template file [" + templateFile.getAbsolutePath() + "] does not exist or no permission!");
                }
                logger.info("[SERVER][STARTUP    ] Copy template database [{}] in ...", templateFile.getAbsolutePath());
                Statement stmt = backendSysConnection.createStatement();
                stmt.execute("ATTACH '" + templateFile.getAbsolutePath() + "' AS _imp_db (READ_ONLY)");
                ResultSet rs = stmt.executeQuery("SELECT current_catalog()");
                rs.next();
                stmt.execute("COPY FROM DATABASE _imp_db TO " + rs.getString(1));
                stmt.execute("DETACH DATABASE _imp_db");
                stmt.close();
                logger.info("[SERVER][STARTUP    ] Copy template database completed.");
            }

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
                    dbInstanceSQLHistoryThread.start();
                }
            }
        }
        catch(SQLException e){
            throw new ServerException("Init backend connection error. ", e);
        }

        // SQL替换
        // DuckDB并不支持所有的PG语法，所以需要进行转义替换，以保证第三方工具能够正确使用
        SQLReplacer.load(this);

        // 标记服务已经挂载成功
        this.instanceState = "MOUNTED";

        // 启动PG的协议处理程序
        if (serverConfiguration.getPort() != -1) {
            protocolServer = new PostgresServer();
            protocolServer.setLogger(logger);
            protocolServer.setBindHostAndPort(serverConfiguration.getBindHost(), serverConfiguration.getPort());
            protocolServer.setServerTimeout(serverConfiguration.getClient_timeout(), serverConfiguration.getClient_timeout(), serverConfiguration.getClient_timeout());
            protocolServer.setNioEventThreads(serverConfiguration.getMax_Workers());
            protocolServer.setDBInstance(this);
            protocolServer.start();
            while (!protocolServer.isPortReady()) {
                // 等待Netty进程就绪
                try {
                    Sleeper.sleep(1000);
                }
                catch (InterruptedException ignored) {
                    throw new ServerException("Server terminated due to user cancelled.");
                }
            }
            logger.info("[SERVER][STARTUP    ] Instance opened at {}:{} successful.",
                    serverConfiguration.getBindHost(), serverConfiguration.getPort());
        }
        else
        {
            logger.info("[SERVER][STARTUP    ] Instance opened without listener successful.");
        }

        // 在自动挂载列表中增加一行记录，避免被重复挂载
        autoloadDatabase.add(instanceName + ".db");

        // 启动监控线程
        if (dbInstanceMonitorThread == null)
        {
            dbInstanceMonitorThread = new DBInstanceMonitorThread();
            dbInstanceMonitorThread.start();
        }

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

        // 标记服务已经启动完成
        this.instanceState = "RUNNING";
    }

    // 停止数据库实例
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
            if (dbInstanceMonitorThread.isAlive()) {
                dbInstanceMonitorThread.interrupt();
            }
            dbInstanceMonitorThread = null;
        }
        // 关闭SQLHistory的后台进程
        if (dbInstanceSQLHistoryThread != null) {
            if (dbInstanceSQLHistoryThread.isAlive()) {
                dbInstanceSQLHistoryThread.interrupt();
            }
            dbInstanceSQLHistoryThread = null;
        }

        try {
            // 关闭数据库连接池
            if (this.dbDataSourcePool != null) {
                this.dbDataSourcePool.shutdown();
            }

            // 关闭BackendSysConnection
            backendSysConnection.close();
        }
        catch (SQLException e) {
            logger.error("[SERVER][STARTUP    ] Close backend connection error. ", e);
        }

        // 停止数据库对外网络服务
        protocolServer.stop();

        // 取消之前注册的远程监听
        String remoteListener = serverConfiguration.getRemoteListener();
        if (!remoteListener.isEmpty()) {
            String remoteListenerHost = remoteListener.substring(0, remoteListener.lastIndexOf(':'));
            int remoteListenerPort = Integer.parseInt(remoteListener.substring(remoteListener.indexOf(':') + 1));
            try {
                talkWithRemoteListener("UNREGISTER", remoteListenerHost, remoteListenerPort);
            } catch (ServerException serverException) {
                logger.warn("[SERVER] Try unregister current service to [{}] failed. {}",
                        serverConfiguration.getRemoteListener(), serverException.getErrorMessage());
            }
        }

        // 数据库强制进行检查点操作
        forceCheckPoint();

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
        return this.protocolServer.getRegisteredConnectionsCount();
    }

    // 初始化一个新的数据库会话
    public int newSession(DBSession dbSession)
    {
        // 标记最后活跃时间
        this.lastActiveTime = System.currentTimeMillis();

        int currentSessionId = maxSessionId.incrementAndGet();
        dbSessions.put(currentSessionId, dbSession);
        return currentSessionId;
    }

    // 根据SessionID获取对应的Session信息
    public DBSession getSession(int sessionId)
    {
        return dbSessions.get(sessionId);
    }

    // 强制执行检查点
    // 用来在退出时候同步完成所有的WAL文件
    public void forceCheckPoint()
    {
        try {
            if (backendSysConnection != null && !backendSysConnection.isClosed() && !backendSysConnection.isReadOnly()) {
                Statement stmt = backendSysConnection.createStatement();
                stmt.execute("FORCE CHECKPOINT");
                stmt.close();
            }
        }
        catch (SQLException e) {
            logger.error("Force checkpoint failed.", e);
        }
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
}
