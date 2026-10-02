package org.slackerdb.dbserver.server;

import io.netty.channel.Channel;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.slackerdb.dbserver.entity.ParsedStatement;
import org.slackerdb.dbserver.entity.SQLHistoryRecord;
import org.slackerdb.dbserver.sql.CopyDialect;

import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /**
     * 会话的事务状态，对应 PG 协议 ReadyForQuery 的三种状态字节。
     *
     * <ul>
     *   <li>{@link #IDLE} → 'I' 不在事务块中</li>
     *   <li>{@link #IN_TRANSACTION} → 'T' 在事务块中</li>
     *   <li>{@link #FAILED} → 'E' 事务块内出错，后续语句一律拒绝，直到 COMMIT/ROLLBACK 结束事务块</li>
     * </ul>
     *
     * <p>会被管理端/状态接口跨线程读取，故为 volatile。</p>
     */
    public enum TransactionState {
        IDLE('I'),
        IN_TRANSACTION('T'),
        FAILED('E');

        private final byte statusByte;

        TransactionState(char statusByte) {
            this.statusByte = (byte) statusByte;
        }

        /** ReadyForQuery 中要回给客户端的状态字节。 */
        public byte getStatusByte() {
            return statusByte;
        }
    }

    private volatile TransactionState transactionState = TransactionState.IDLE;

    public TransactionState getTransactionState() {
        return transactionState;
    }

    /** 是否处于事务块中（含失败事务块）—— 即 ReadyForQuery 是否应当回 'T' 或 'E'。 */
    public boolean inTransaction() {
        return transactionState != TransactionState.IDLE;
    }

    /** 进入事务块。 */
    public void beginTransaction() {
        transactionState = TransactionState.IN_TRANSACTION;
    }

    /** 结束事务块（COMMIT / ROLLBACK / ABORT / END）。 */
    public void endTransaction() {
        transactionState = TransactionState.IDLE;
    }

    /**
     * 标记"事务块内的语句执行失败"。
     *
     * <p>PG 语义：事务块内一旦有语句报错，整个事务块进入 aborted 状态，
     * 后续语句（COMMIT/ROLLBACK 除外）一律以 25P02 拒绝，直到事务块被结束。
     * 只有确实处于事务块中时才需要标记，自动提交模式下的单条语句失败不影响会话状态。</p>
     */
    public void markTransactionFailed() {
        if (transactionState == TransactionState.IN_TRANSACTION) {
            transactionState = TransactionState.FAILED;
        }
    }

    /** 当前会话状态  connected, dbConnected */
    // 会被管理端/状态接口跨线程读取，故为 volatile
    public volatile String status = "N/A";
    // 客户端的IP地址
    public String clientAddress = "";
    // 保存的语句解析信息
    //
    // 必须是并发容器：CancelRequest 与 KILL SESSION 会从**别的线程**遍历它
    // （取消请求走的是新建连接，与目标会话不在同一个线程上）。
    public final Map<String, ParsedStatement> parsedStatements = new ConcurrentHashMap<>();

    // 记录当前COPY的文件格式（TEXT / CSV / BINARY）
    public String copyTableFormat = "";
    // 记录当前COPY的格式与选项（分隔符/引号/转义/NULL 串/表头），由 COPY 语句解析得到
    public CopyDialect copyDialect = null;
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
    /**
     * 本次 COPY IN 是否已被判定失败（例如缓冲超过上限）。
     *
     * <p>PG 的错误语义是"报错后丢弃到同步点"：失败一旦发出，后续的 CopyData 必须被忽略，
     * 否则那些字节会被当成下一次 COPY 的数据，或者让 CopyDone 误报成功（{@code COPY 0}）。</p>
     */
    public boolean copyAborted = false;

    /**
     * 进行中的 COPY 在 SQL 历史表里的记录 ID（{@code <= 0} 表示没有登记的 COPY）。
     *
     * <p>COPY 是一条横跨多个报文的语句（CopyInResponse → 若干 CopyData → CopyDone/CopyFail），
     * 所以它的审计记录在 {@code CopyProtocolHandler.beginCopyIn} 里开，
     * 在 {@link #closeCopySqlHistory(long, String)} 里收尾 —— 由 CopyDone / CopyFail / 会话清理调用。
     * 三条协议路径（简单查询、扩展协议、COPY）共用同一份历史实现，保证审计口径一致。</p>
     */
    public volatile long copySqlHistoryId = -1;

    /**
     * 收尾进行中的 COPY 审计记录（把结果写回 SQL 历史表）。
     *
     * <p>幂等：正常收尾与防御性清理都会调用，只有第一次生效。</p>
     *
     * @param affectedRows 成功时的写入行数（失败传 0）
     * @param errorMsg     失败原因（成功传 {@code null}）；建议带上 SQLSTATE 前缀
     */
    public void closeCopySqlHistory(long affectedRows, String errorMsg) {
        long historyId = copySqlHistoryId;
        if (historyId <= 0) {
            return;
        }
        copySqlHistoryId = -1;
        if (dbInstance.serverConfiguration.getAccess_mode().equals("READ_ONLY")
                || !dbInstance.serverConfiguration.getSqlHistory().equalsIgnoreCase("ON")) {
            return;
        }
        dbInstance.sqlHistoryList.offer(new SQLHistoryRecord(
                "UPDATE",
                historyId,
                0,
                0,
                null,
                null,
                0,
                null,
                LocalDateTime.now(),
                0,
                affectedRows,
                errorMsg));
    }

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

    // 本会话**此刻正在执行**的语句句柄。
    //
    // 为什么需要它（而不是复用 parsedStatements）：
    // parsedStatements 是扩展协议的语句/portal 缓存，里面既可能有"从未执行过"的语句，
    // 也可能残留"早已执行完"的句柄；而 **简单查询路径（QueryRequest，走 Q 消息）压根不会
    // 往 parsedStatements 里放任何东西**，导致取消请求遍历不到它、简单查询永远取消不掉。
    // 这里只登记"正在跑"的句柄，取消时只碰它们。
    //
    // 并发约定（与 parsedStatements 一致，不能随意改）：
    //   * 容器必须是并发的：CancelRequest / KILL SESSION 会从别的线程遍历；
    //   * 取消方只调用 PreparedStatement.cancel()，绝不直接改本会话的其他字段；
    //   * 登记/注销只在会话自己的线程上发生。
    public final Map<Long, PreparedStatement> runningStatements = new ConcurrentHashMap<>();
    private final AtomicLong runningStatementSeq = new AtomicLong();

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
        // 被放弃的 COPY 也要在审计里收尾，否则那条记录会永远停在"进行中"
        closeCopySqlHistory(0, "COPY did not complete: uncommitted data was discarded");

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
     * 清除与本次 COPY 相关的全部会话状态（COPY 正常收尾、失败、被中止时都走这里）。
     *
     * <p>必须在 {@link #discardUncommittedCopy()} <b>之后</b>调用：丢弃动作依赖
     * {@code copyTableAppender} 与 {@code copyOwnTransaction}。</p>
     */
    public void resetCopyState() {
        copyTableFormat = "";
        copyDialect = null;
        copyColumnCount = 0;
        copyTableDbColumnMapPos = null;
        copyTableDbColumnType = null;
        copyTableDbColumnName = null;
        copyOwnTransaction = false;
        copyAborted = false;
        if (copyLastRemained != null) {
            copyLastRemained.reset();
        }
    }

    /**
     * 登记一个"正在执行"的语句句柄，返回稍后用于注销的句柄 id。
     *
     * <p>只应由执行该语句的会话线程调用。必须在 {@code try} 中登记、在 {@code finally}
     * 中注销，否则取消请求会一直拿着已经执行完的句柄（对已关闭的语句调用 cancel 是无害的，
     * 但会掩盖"到底谁在跑"的事实）。</p>
     */
    public long registerRunningStatement(PreparedStatement preparedStatement) {
        long handleId = runningStatementSeq.incrementAndGet();
        runningStatements.put(handleId, preparedStatement);
        return handleId;
    }

    /**
     * 注销一个正在执行的语句句柄。
     *
     * <p>幂等：允许重复调用，也允许句柄已被关闭（{@link ConcurrentHashMap#remove} 本身是安全的）。</p>
     */
    public void unregisterRunningStatement(long handleId) {
        if (handleId > 0) {
            runningStatements.remove(handleId);
        }
    }

    /**
     * 本会话是否已收到取消请求。
     *
     * <p>为什么除了 {@link #runningStatements} 还需要它：PL/SQL 块内部会执行成百上千条小语句，
     * 而 {@code PreparedStatement.cancel()} 只能命中"此刻正在执行的那一条"。
     * 引擎在每条语句前与每次循环回边轮询本标志，长循环才能被真正打断。</p>
     *
     * <p>由取消方（跨线程）置位，由执行方在开始新请求时清零。</p>
     */
    public final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    /** 执行方在开始处理新请求前清零取消标志。 */
    public void clearCancelRequested() {
        cancelRequested.set(false);
    }

    /**
     * 取消本会话当前正在执行的语句。
     *
     * <p>允许被其它线程调用（CancelRequest 与 KILL SESSION 都是跨会话操作）。
     * 两个容器都是并发容器，遍历是弱一致的快照语义，不会抛
     * {@code ConcurrentModificationException}；若语句恰好正在被本会话关闭，
     * 则由 try/catch 兜住。</p>
     *
     * <p>先处理 {@link #runningStatements}（精确覆盖简单查询与扩展查询两条路径），
     * 再兼容性地处理 {@link #parsedStatements}（历史行为，避免既有调用方回归）。</p>
     */
    public void cancelRunningStatements() {
        // 置会话级标志：PL/SQL 之类的"语句序列"执行器靠它退出（单条语句的 cancel() 命中不了循环）
        cancelRequested.set(true);
        for (PreparedStatement preparedStatement : runningStatements.values()) {
            cancelStatementQuietly(preparedStatement);
        }
        for (ParsedStatement parsedStatement : parsedStatements.values()) {
            if (parsedStatement != null) {
                cancelStatementQuietly(parsedStatement.preparedStatement);
            }
        }
    }

    /**
     * 尽最大努力取消一个语句：允许 {@code null}、允许已关闭、吞掉底层异常。
     * 取消是"尽力而为"的操作，任何失败都不应影响发起取消的那条连接。
     */
    private static void cancelStatementQuietly(PreparedStatement preparedStatement) {
        if (preparedStatement == null) {
            return;
        }
        try {
            if (!preparedStatement.isClosed()) {
                preparedStatement.cancel();
            }
        }
        catch (SQLException ignored) {
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

    /**
     * 优雅关闭会话（客户端发送 Terminate 报文后调用）。
     *
     * <p><b>事务处理与 PG 语义保持一致：一律回滚未提交的事务。</b>
     * PostgreSQL 的规则是"没有显式 COMMIT 就等于没提交"，与客户端是否礼貌断开无关；
     * 因此在事务块中直接断开连接时，未提交的改动必须被丢弃。</p>
     *
     * <p>历史上这里对"非 COPY 场景"执行的是 {@code commit()}，会把客户端明确没有提交的
     * 数据静默落库（BUG-7）。改动后本方法与 {@link #abortSession()} 在事务处理上完全一致，
     * 两者的区别只剩下语义命名与调用时机。</p>
     *
     * <p>唯一需要保留的例外是服务端为 COPY 自开的事务：若客户端没发 CopyDone 就断开，
     * 说明这次 COPY 没有正常收尾，同样必须回滚（不能提交半截数据）。</p>
     */
    public void closeSession() throws SQLException
    {
        // 关闭所有连接，并释放所有资源
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
        // 注销所有"正在执行"的句柄，避免会话回收后残留引用
        runningStatements.clear();
        executingPreparedStatement = null;

        // COPY 若没等到 CopyDone 就断开，审计记录也要收尾
        closeCopySqlHistory(0, "COPY did not complete: session closed before CopyDone");

        if (copyTableAppender != null)
        {
            copyTableAppender.close();
            copyTableAppender = null;
        }
        if (dbConnection != null && !dbConnection.isClosed())
        {
            if (!dbConnection.isReadOnly())
            {
                // 一律回滚：客户端没提交的就是没提交。
                // copyOwnTransaction 为 true 时说明服务端为 COPY 开的事务还没正常收尾，
                // 也必须回滚，否则已经 append 的"半截数据"会落库。
                copyOwnTransaction = false;
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
            dbInstance.dbDataSourcePool.releaseConnection(dbConnection);
        }
        transactionState = TransactionState.IDLE;
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
        // 注销所有"正在执行"的句柄，避免会话回收后残留引用。
        // 注意：这里刻意**不去 close()** 这些句柄——会话线程正在使用它们，
        // 跨线程关闭语句违反 JDBC 的线程安全约定，清理交给那条线程自己的 finally。
        runningStatements.clear();
        executingPreparedStatement = null;
        // COPY 若没等到 CopyDone 就断开，审计记录也要收尾
        closeCopySqlHistory(0, "COPY did not complete: session aborted before CopyDone");
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
        transactionState = TransactionState.IDLE;
    }

    public void saveParsedStatement(String portalName, ParsedStatement parsedPrepareStatement)
    {
        // 注意：parsedStatements 是 ConcurrentHashMap，**不接受 null 值**。
        // "空语句/空 portal" 请存一个 sql="" 且 preparedStatement=null 的对象，而不是 null。
        parsedStatements.put(portalName, parsedPrepareStatement);
    }

    /**
     * 关闭并摘除一个语句（{@code PreparedStatement-<名字>}）缓存项。
     *
     * <p>除了 {@link PreparedStatement}，还必须关闭 {@link ParsedStatement#resultSet}：
     * 门户被挂起（收到过 PortalSuspended）时结果集是<b>开着</b>的，
     * 只关语句会让它在会话结束前一直占着资源（驱动关闭语句时就会走到这里）。</p>
     */
    public void clearParsedStatement(String portalName) throws SQLException
    {
        ParsedStatement parsedStatement = parsedStatements.remove(portalName);
        if (parsedStatement == null) {
            return;
        }
        closeResultSet(parsedStatement.resultSet);
        PreparedStatement preparedStatement = parsedStatement.preparedStatement;
        if (preparedStatement != null && !preparedStatement.isClosed()) {
            preparedStatement.close();
        }
    }

    /**
     * 关闭并摘除一个门户（{@code Portal-<名字>}）缓存项。
     *
     * <p>与 {@link #clearParsedStatement(String)} 的区别：<b>不关闭</b>底层的
     * {@link PreparedStatement}。门户与语句共享同一个句柄（见 {@code BindRequest}，
     * 门户对象直接复用语句对象里的引用），按 PG 语义关闭门户也不应牵连语句 ——
     * 否则对同名语句的下一次 Bind/Execute 会直接打在已关闭的句柄上。</p>
     */
    public void closePortal(String portalName) throws SQLException
    {
        ParsedStatement parsedStatement = parsedStatements.remove(portalName);
        if (parsedStatement == null) {
            return;
        }
        closeResultSet(parsedStatement.resultSet);
    }

    private static void closeResultSet(ResultSet resultSet) throws SQLException
    {
        if (resultSet != null && !resultSet.isClosed()) {
            resultSet.close();
        }
    }

}
