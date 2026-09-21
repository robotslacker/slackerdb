package org.slackerdb.dbserver.server;

import io.netty.channel.Channel;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.slackerdb.dbserver.entity.ParsedStatement;

import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class DBSession {
    // 数据库实例
    private final DBInstance dbInstance;
    // 数据库连接
    public Connection dbConnection = null;
    // 客户端连接建立时间
    public LocalDateTime connectedTime;
    // 数据库连接创建时间
    public LocalDateTime dbConnectedTime;
    // 客户端连接时候的选项
    public Map<String, String> startupOptions;
    // 当前是否处于事务当中
    public boolean inTransaction = false;
    // 当前会话状态  connected, dbConnected
    // 会被管理端/状态接口跨线程读取，故为 volatile
    public volatile String status = "N/A";
    // 客户端的IP地址
    public String clientAddress = "";
    // 保存的语句解析信息
    //
    // 必须是并发容器：CancelRequest 与 KILL SESSION 会从**别的线程**遍历它
    // （取消请求走的是新建连接，与目标会话不在同一个线程上）。
    public final Map<String, ParsedStatement> parsedStatements = new ConcurrentHashMap<>();
    // 标记客户端是否请求了描述信息（如果请求需要返回RowDescription, 反之不返回)
    public boolean hasDescribeRequest = false;

    // 记录当前COPY的文件格式
    public String copyTableFormat = "";
    // 记录当前COPY的Appender
    public DuckDBAppender copyTableAppender = null;
    // 记录这个目标表在数据库的实际列名
    public List<Integer> copyTableDbColumnMapPos;
    // 本次 COPY 是否由服务端自己开了一个显式事务。
    //
    // DuckDBAppender 在事务之外 close() 会立即提交，只要后续某一行出错就会留下"半截数据"；
    // 放进服务端自己的显式事务后，失败时可以整体 ROLLBACK（实测 JDBC 的
    // setAutoCommit(false) + rollback() 对 appender 无效，必须用显式事务）。
    // 客户端已经处于事务块中时不另开事务，此时该标志为 false，数据留在客户端事务里由客户端决定。
    public boolean copyOwnTransaction = false;
    // 记录当前COPY数据中每行的字段数量(没有指定列的时候，就是目标表的列数量)
    public int copyColumnCount = 0;
    // 上次由于不完整而没有复制的Copy剩余命令
    public ByteArrayOutputStream copyLastRemained = new ByteArrayOutputStream();

    // Binary模式进行Copy的时候需要知道目标表结构, 包括列字段名称，列字段类型
    public List<String> copyTableDbColumnType = null;
    public List<String> copyTableDbColumnName = null;

    // 当前执行任务的语句
    // 以下三个字段会被管理端跨线程读取（会话列表 / /status），故为 volatile
    public volatile String executingSQL = "";
    // 当前所处的业务请求
    public volatile String executingFunction = "";
    // 当前调用的开始时间
    public volatile LocalDateTime executingTime = null;
    // 当前正在执行的句柄。
    // 跨线程访问：CancelRequest / KILL SESSION 会调用它的 cancel()
    public volatile PreparedStatement executingPreparedStatement = null;
    // SqlId, 考虑到SQL的分批执行情况，这里用SqlId来表示对应的信息
    public final AtomicLong executingSqlId = new AtomicLong();

    // 本会话绑定的客户端连接。
    // 用于让**其它线程**安全地终止本会话：Netty 的 Channel.close() 是线程安全的，
    // 关闭后会在本会话自己的线程上触发 channelInactive -> abortSession()，
    // 由那条线程完成清理，避免跨线程直接改本会话的状态。
    public volatile Channel channel = null;

    public DBSession(DBInstance pDbInstance)
    {
        dbInstance = pDbInstance;
    }

    public ParsedStatement getParsedStatement(String portalName) {
        return parsedStatements.get(portalName);
    }

    /**
     * 丢弃本次 COPY 尚未提交的写入：关闭 Appender 并回滚服务端自有的 COPY 事务。
     *
     * <p>为什么需要它：{@link DuckDBAppender} 在事务之外 {@code close()} 会<b>立即提交</b>，
     * 因此只要第 N 行出错，前 N-1 行就成了"半截数据"（PG 语义下失败的 COPY 应当整体不生效）。
     * 服务端在 QueryRequest 里用显式事务包裹了本次 COPY（见 {@link #copyOwnTransaction}），
     * 这里负责把它整体回滚。</p>
     *
     * <p>可重复调用（失败分支、提前返回、以及"上一个 COPY 没正常收尾"的防御性清理都可能走到），
     * 第二次调用是空操作。</p>
     */
    public void discardUncommittedCopy() {
        if (copyTableAppender != null) {
            try {
                copyTableAppender.close();
            }
            catch (SQLException se) {
                dbInstance.logger.warn("[SERVER] Close appender while discarding COPY failed.", se);
            }
            copyTableAppender = null;
        }
        copyLastRemained.reset();

        // 客户端自己开的事务不归我们管：只有 copyOwnTransaction 才回滚，
        // 否则会把客户端事务里先前的工作一起抹掉。
        if (copyOwnTransaction) {
            copyOwnTransaction = false;
            try (Statement rollbackStatement = ((DuckDBConnection) dbConnection).createStatement()) {
                rollbackStatement.execute("ROLLBACK");
            }
            catch (SQLException se) {
                dbInstance.logger.warn("[SERVER] Rollback for failed COPY failed.", se);
            }
        }
    }

    /**
     * 取消本会话当前正在执行的语句。
     *
     * <p>允许被其它线程调用（CancelRequest 与 KILL SESSION 都是跨会话操作）。
     * {@link #parsedStatements} 是并发容器，这里的遍历是弱一致的快照语义，不会抛
     * {@code ConcurrentModificationException}；若语句恰好正在被本会话关闭，
     * 则由 try/catch 兜住。</p>
     */
    public void cancelRunningStatements() {
        for (ParsedStatement parsedStatement : parsedStatements.values()) {
            PreparedStatement preparedStatement = parsedStatement.preparedStatement;
            if (preparedStatement != null) {
                try {
                    if (!preparedStatement.isClosed()) {
                        preparedStatement.cancel();
                    }
                }
                catch (SQLException ignored) {
                }
            }
        }
    }

    /**
     * 从任意线程请求关闭本会话的连接。
     * 真正的清理（摘除注册、回滚、释放连接）由 {@code channelInactive} 在本会话自己的线程上完成。
     */
    public void closeChannel() {
        Channel currentChannel = this.channel;
        if (currentChannel != null) {
            currentChannel.close();
        }
    }

    public void closeSession() throws SQLException
    {
        // 关闭所有连接，并释放所有资源
        // 默认close的时候要执行Commit操作
        for (ParsedStatement parsedStatement : parsedStatements.values())
        {
            if (parsedStatement != null) {
                if (parsedStatement.preparedStatement != null && !parsedStatement.preparedStatement.isClosed()) {
                    parsedStatement.preparedStatement.close();
                }
                if (parsedStatement.resultSet != null && !parsedStatement.resultSet.isClosed()) {
                    parsedStatement.resultSet.close();
                }
            }
        }
        parsedStatements.clear();

        if (copyTableAppender != null)
        {
            copyTableAppender.close();
            copyTableAppender = null;
        }
        if (dbConnection != null && !dbConnection.isClosed())
        {
            if (!dbConnection.isReadOnly())
            {
                // 若会话结束时仍存在服务端为 COPY 开的事务，说明这次 COPY 从未正常收尾
                //（例如客户端没发 CopyDone 就断开、或直接发了别的语句），此时必须回滚而不是提交，
                // 否则已经 append 的"半截数据"会被 closeSession 落库。
                boolean rollbackOwnedCopy = copyOwnTransaction;
                copyOwnTransaction = false;
                try {
                    if (rollbackOwnedCopy) {
                        dbConnection.rollback();
                    }
                    else {
                        dbConnection.commit();
                    }
                }
                catch (SQLException e) {
                    if (!e.getMessage().contains("no transaction is active"))
                    {
                        throw e;
                    }
                }
            }
            dbInstance.dbDataSourcePool.releaseConnection(dbConnection);
        }
    }

    public void abortSession() throws SQLException
    {
        // 关闭所有连接，并释放所有资源
        // 默认abort的时候要执行Rollback操作
        for (ParsedStatement parsedStatement : parsedStatements.values())
        {
            if (parsedStatement != null) {
                if (parsedStatement.preparedStatement != null && !parsedStatement.preparedStatement.isClosed()) {
                    parsedStatement.preparedStatement.close();
                }
                if (parsedStatement.resultSet != null && !parsedStatement.resultSet.isClosed()) {
                    parsedStatement.resultSet.close();
                }
            }
        }
        if (copyTableAppender != null)
        {
            copyTableAppender.close();
            copyTableAppender = null;
        }
        if (dbConnection != null && !dbConnection.isClosed())
        {
            if (!dbConnection.isReadOnly())
            {
                try {
                    dbConnection.rollback();
                }
                catch (SQLException e) {
                    if (!e.getMessage().contains("no transaction is active"))
                    {
                        throw e;
                    }
                }
            }
            copyOwnTransaction = false;
            dbInstance.dbDataSourcePool.releaseConnection(dbConnection);
        }
    }

    public void saveParsedStatement(String portalName, ParsedStatement parsedPrepareStatement)
    {
        // 注意：parsedStatements 是 ConcurrentHashMap，**不接受 null 值**。
        // "空语句/空 portal" 请存一个 sql="" 且 preparedStatement=null 的对象，而不是 null。
        parsedStatements.put(portalName, parsedPrepareStatement);
    }

    public void clearParsedStatement(String portalName) throws SQLException
    {
        if (parsedStatements.containsKey(portalName))
        {
            PreparedStatement preparedStatement = parsedStatements.get(portalName).preparedStatement;
            if (preparedStatement != null && !preparedStatement.isClosed()) {
                preparedStatement.close();
            }
            parsedStatements.remove(portalName);
        }
    }

}
