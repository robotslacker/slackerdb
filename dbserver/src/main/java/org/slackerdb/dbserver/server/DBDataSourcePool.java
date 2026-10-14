package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Logger;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.utils.Sleeper;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class DBDataSourcePool {
    // 连接池扩展信息
    // Duck的连接对象不支持setClientInfo，所以不得不扩展实现这个
    private final ConcurrentHashMap<Connection, ConnectionMetaData> connectionMetaDataMap = new ConcurrentHashMap<>();
    // 连接池配置
    private final DBDataSourcePoolConfig dbDataSourcePoolConfig;
    // 空闲连接的连接池（使用 LinkedHashSet 实现 O(1) 的 remove 操作）
    private final LinkedHashSet<Connection> idleConnectionPool = new LinkedHashSet<>();
    // 使用中的连接池（使用 LinkedHashSet 实现 O(1) 的 remove 操作）
    private final LinkedHashSet<Connection> usedConnectionPool = new LinkedHashSet<>();
    private final Logger logger;
    // 连接ID
    private final AtomicInteger connectionId = new AtomicInteger(0);
    // 连接监控进程，用来回收多余连接，重建连接等
    DBDataSourcePoolMonitor dbDataSourcePoolMonitor;
    // 连接池最高水位线，用来标记历史最高连接数
    private volatile int   highWaterMark = 0;

    // 连接池的名称
    private final String poolName;
    private final ReentrantLock poolLock = new ReentrantLock();
    private final Condition connectionAvailable = poolLock.newCondition();
    private final long connectionAcquireTimeoutMs;

    // 连接池是否已关闭的标志。关闭后 getConnection 将直接抛出异常，
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // 待关闭连接队列。retireConnection 在持锁时仅做登记（元数据移除 + 逻辑退役），
    //     真正的物理 connection.close() 延迟到锁外由 closePendingConnections 统一执行，
    //     从而避免在持有 poolLock 的情况下执行阻塞性 IO。
    private final List<Connection> pendingCloseConnections = new ArrayList<>();

    static class DBDataSourcePoolMonitor extends Thread
    {
        private final DBDataSourcePool dbDataSourcePool;
        private final Logger logger;
        public DBDataSourcePoolMonitor(DBDataSourcePool dbDataSourcePool)
        {
            this.dbDataSourcePool = dbDataSourcePool;
            this.logger = this.dbDataSourcePool.logger;
            setDaemon(true);
        }

        @Override
        public void run()
        {
            setName("DataSourcePool-" + this.dbDataSourcePool.poolName);
            while (!isInterrupted()) {
                try {
                    // 1. 裁减超出 maximumIdle 的空闲连接
                    if (this.dbDataSourcePool.dbDataSourcePoolConfig.getMaximumIdle() > 0) {
                        int targetIdle = this.dbDataSourcePool.dbDataSourcePoolConfig.getMaximumIdle();
                        this.dbDataSourcePool.poolLock.lock();
                        try {
                            int extra = this.dbDataSourcePool.idleConnectionPool.size() - targetIdle;
                            if (extra > 0) {
                                // 使用迭代器移除多余的连接，保持 O(1) 性能
                                var iter = this.dbDataSourcePool.idleConnectionPool.iterator();
                                for (int i = 0; i < extra && iter.hasNext(); i++) {
                                    Connection connection = iter.next();
                                    iter.remove();
                                    this.dbDataSourcePool.retireConnection(connection, "exceeds maximumIdle");
                                }
                            }
                        } finally {
                            this.dbDataSourcePool.poolLock.unlock();
                        }
                    }

                    // 2. 补给到 minimumIdle（连接创建在锁外执行，避免持锁 IO）
                    if (this.dbDataSourcePool.dbDataSourcePoolConfig.getMinimumIdle() != 0) {
                        this.dbDataSourcePool.ensureMinimumIdle();
                    }

                    // 3. 驱逐超过 maximumLifeCycleTime 的空闲连接
                    if (this.dbDataSourcePool.dbDataSourcePoolConfig.getMaximumLifeCycleTime() > 0) {
                        this.dbDataSourcePool.retireExpiredIdleConnections();
                    }

                    // 4. 锁外统一关闭本轮登记待关闭的连接，避免持锁 IO
                    this.dbDataSourcePool.closePendingConnections();
                } catch (SQLException sqlException) {
                    logger.trace("[SERVER] Internal error in Connection Pool [{}].",
                            this.dbDataSourcePool.poolName, sqlException);
                }
                try {
                    Sleeper.sleep(30 * 1000);
                }
                catch (InterruptedException ignored)
                {
                    break;
                }
            }
        }
    }

    /**
     * 最小空闲连接补给：当空闲连接少于 minimumIdle 且总连接未达上限时，
     * 在锁外创建物理连接，再回到锁内注册并放入空闲池。
     *
     * <p>方法存在两个提前返回点（已补足 / 池已关闭、以及本次创建的连接被放弃）。
     * 两处都在未持有 poolLock 的情况下自行调用 {@link #closePendingConnections()} 收尾，
     * 因此登记进 {@code pendingCloseConnections} 的连接不会依赖调用方后续的批次关闭，
     * 也不会把物理 close 拖到调用方的下一轮定时任务。</p>
     */
    private void ensureMinimumIdle() throws SQLException {
        int minimumIdle = this.dbDataSourcePoolConfig.getMinimumIdle();
        int maximumPoolSize = this.dbDataSourcePoolConfig.getMaximumPoolSize();

        while (true) {
            // 锁内判断是否需要补给
            boolean needCreate;
            poolLock.lock();
            try {
                needCreate = this.idleConnectionPool.size() < minimumIdle
                        && this.connectionMetaDataMap.size() < maximumPoolSize;
            } finally {
                poolLock.unlock();
            }
            if (!needCreate || this.closed.get()) {
                // 提前返回：补给结束，但队列里可能仍有待关闭连接（例如 maximumIdle 裁剪登记的连接），
                // 这里就地清空，不依赖调用方的批次收尾。
                closePendingConnections();
                return;
            }

            // 锁外创建物理连接，避免持锁 IO
            Connection connection = createPhysicalConnection();

            // 本次创建的连接是否成功注册进池。未注册说明被放弃，需要在锁外立即物理关闭。
            boolean registered = false;
            poolLock.lock();
            try {
                if (this.closed.get()
                        || this.idleConnectionPool.size() >= minimumIdle
                        || this.connectionMetaDataMap.size() >= maximumPoolSize) {
                    // 池已关闭或并发下已满足条件，放弃刚创建的连接（延迟到锁外关闭）
                    this.pendingCloseConnections.add(connection);
                } else {
                    registerNewConnection(connection);
                    this.idleConnectionPool.add(connection);
                    this.connectionAvailable.signal();
                    registered = true;
                }
            } finally {
                poolLock.unlock();
            }

            if (!registered) {
                // 提前返回：锁外立即关闭被放弃的连接，不把物理 close 推迟到调用方的下一轮批次
                closePendingConnections();
                return;
            }
        }
    }

    /**
     * 驱逐空闲池中超过 maximumLifeCycleTime 的连接。
     * 调用方不需要持有 poolLock，方法内部自行加锁；被退役的连接延迟到锁外关闭。
     */
    private void retireExpiredIdleConnections() {
        long maxLifeMs = this.dbDataSourcePoolConfig.getMaximumLifeCycleTime();
        if (maxLifeMs <= 0) {
            return;
        }
        poolLock.lock();
        try {
            var iter = this.idleConnectionPool.iterator();
            while (iter.hasNext()) {
                Connection connection = iter.next();
                ConnectionMetaData metaData = connectionMetaDataMap.get(connection);
                if (metaData != null) {
                    long lifeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - metaData.getCreatedNanoTime());
                    if (lifeMs > maxLifeMs) {
                        iter.remove();
                        retireConnection(connection, "exceeds maximumLifeCycleTime");
                    }
                }
            }
        } finally {
            poolLock.unlock();
        }
    }

    /**
     * 锁外重新计算阻塞性 IO 后，统一物理关闭 pendingCloseConnections 中的连接。
     * 必须在未持有 poolLock 的情况下调用。
     */
    private void closePendingConnections() {
        List<Connection> toClose;
        poolLock.lock();
        try {
            if (this.pendingCloseConnections.isEmpty()) {
                toClose = null;
            } else {
                toClose = new ArrayList<>(this.pendingCloseConnections);
                this.pendingCloseConnections.clear();
            }
        } finally {
            poolLock.unlock();
        }

        if (toClose != null) {
            for (Connection connection : toClose) {
                try {
                    if (connection != null && !connection.isClosed()) {
                        connection.close();
                    }
                } catch (SQLException ignored) {
                }
            }
        }
    }

    private boolean validateConnection(Connection conn)
    {
        try
        {
            return (conn != null) && (!conn.isClosed());
        }
        catch (SQLException ignored)
        {
            return false;
        }
    }

    private boolean isReusable(Connection connection) {
        if (!validateConnection(connection)) {
            return false;
        }
        if (this.dbDataSourcePoolConfig.getMaximumLifeCycleTime() > 0) {
            ConnectionMetaData metaData = connectionMetaDataMap.get(connection);
            if (metaData != null) {
                long lifeNs = System.nanoTime() - metaData.getCreatedNanoTime();
                long lifeMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(lifeNs);
                return lifeMs <= this.dbDataSourcePoolConfig.getMaximumLifeCycleTime();
            }
        }
        return true;
    }

    /**
     * 归还前的连接状态复位：把连接恢复成"刚创建"的样子，避免上一个借用者的状态
     * 泄漏给下一个借用者。
     *
     * <p><b>为什么必须做</b>：池里的连接是复用的，而 {@link PooledConnection#close()}
     * 的语义是"归还"而不是"销毁"（调用方按 JDBC 习惯写的
     * {@code try (Connection c = pool.getConnection())} 也会走到这里）。如果连接上
     * 开过事务却没提交/回滚，直接放回空闲池就等于把这条<b>未提交事务</b>交给下一个借用者：
     * 它会看到上一个会话未提交的数据，甚至在自己 COMMIT 时把别人的改动一起提交掉。</p>
     *
     * <p><b>为什么不能只看 {@code getAutoCommit()}</b>：实测 DuckDB 的显式
     * {@code BEGIN} 在 autoCommit=true 下<b>不会</b>反映到 {@code getAutoCommit()}，
     * 但 {@code rollback()} 能把它的数据一并丢掉。因此这里<b>无条件</b>尝试回滚，
     * 只把"确实没有事务"当成正常情况忽略。反过来先 {@code setAutoCommit} 是错的：
     * JDBC 规定切换 autoCommit 会<b>提交</b>当前事务。</p>
     *
     * <p><b>代价</b>：无事务时会拿到一次 {@code cannot rollback - no transaction is active}
     * （DuckDB 的正常路径，按 debug 记录、不算失败），与 {@code DBSession.endSession}
     * 的处理方式保持一致。</p>
     *
     * <p><b>在哪里调用</b>：由 {@link #releaseConnection} 在 <b>poolLock 之外</b>调用——
     * 回滚是真实工作量，不能占着全池唯一的锁做。调用期间连接仍留在 usedConnectionPool 中：
     * 既不会被 {@code getConnection()} 借出去，也不会被 {@code shutdown()} 漏掉。</p>
     *
     * @return true 表示连接已复位、可以复用；false 表示复位失败，必须退役
     *         （状态不明的连接不能放回池里）
     */
    private boolean resetConnectionForReuse(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException sqlException) {
            if (!isNoActiveTransaction(sqlException)) {
                logger.warn("[SERVER][CONN POOL  ]: Pool [{}] Rollback before reuse failed ({}). "
                        + "Connection will be retired.", this.poolName, sqlException.getMessage());
                return false;
            }
            logger.debug("[SERVER][CONN POOL  ]: Pool [{}] No active transaction to roll back.",
                    this.poolName);
        }

        try {
            boolean defaultAutoCommit = this.dbDataSourcePoolConfig.getAutoCommit();
            if (connection.getAutoCommit() != defaultAutoCommit) {
                connection.setAutoCommit(defaultAutoCommit);
            }
        } catch (SQLException sqlException) {
            logger.warn("[SERVER][CONN POOL  ]: Pool [{}] Restoring autoCommit before reuse failed ({}). "
                    + "Connection will be retired.", this.poolName, sqlException.getMessage());
            return false;
        }
        return true;
    }

    /** DuckDB 在"没有活动事务"时的固定报错：{@code cannot rollback - no transaction is active}。 */
    private static boolean isNoActiveTransaction(SQLException sqlException) {
        String message = sqlException.getMessage();
        return message != null && message.contains("no transaction is active");
    }

    /**
     * 回收连接。注意：调用方必须持有 poolLock。
     * 这里只负责清理 metadata 并登记待关闭，物理 close 由 closePendingConnections 在锁外执行。
     */
    private void retireConnection(Connection connection, String reason) {
        if (connection == null) {
            return;
        }
        ConnectionMetaData metaData = connectionMetaDataMap.remove(connection);
        int connectionNumber = metaData != null ? metaData.getConnectionId() : -1;
        logger.debug("[SERVER][CONN POOL  ]: Pool [{}] Retire connection {}. Reason: {}",
                this.poolName, connectionNumber, reason);
        // 调用方已经负责从 idleConnectionPool 或 usedConnectionPool 中移除。
        // 此处只登记待关闭，物理 close 延迟到锁外，避免持锁 IO。
        this.pendingCloseConnections.add(connection);
        connectionAvailable.signalAll();
    }

    /**
     * 锁外创建物理连接（可能涉及网络/attach 等阻塞性 IO）。
     */
    private Connection createPhysicalConnection() throws SQLException {
        Properties connectProperties = new Properties();
        if (this.dbDataSourcePoolConfig.getConnectProperties() != null)
        {
            connectProperties.putAll(this.dbDataSourcePoolConfig.getConnectProperties());
        }

        Connection connection;
        Driver driver = this.dbDataSourcePoolConfig.getDriver();
        if (driver != null) {
            connection = driver.connect(this.dbDataSourcePoolConfig.getJdbcURL(), connectProperties);
        } else {
            connection = DriverManager.getConnection(this.dbDataSourcePoolConfig.getJdbcURL(), connectProperties);
        }
        connection.setAutoCommit(this.dbDataSourcePoolConfig.getAutoCommit());
        return connection;
    }

    /**
     * 锁内注册连接元数据。注意：调用方必须持有 poolLock。
     */
    private void registerNewConnection(Connection connection) {
        int connectionId = this.connectionId.incrementAndGet();
        ConnectionMetaData connectionMetaData = new ConnectionMetaData();
        connectionMetaData.setConnectionId(connectionId);
        connectionMetaData.setCreatedNanoTime(System.nanoTime());
        this.connectionMetaDataMap.put(connection, connectionMetaData);
        updateHighWaterMark();
        logger.debug("[SERVER][CONN POOL  ]: Pool [{}] Create new connection {}.",
                this.poolName, connectionId);
    }

    public DBDataSourcePool(
            String poolName,
            DBDataSourcePoolConfig config,
            Logger logger) throws SQLException {

        this.poolName = poolName;
        this.logger = logger;
        this.logger.debug("[SERVER][CONN POOL  ]: DBDataSourcePool [{}] started ..", this.poolName);

        if (config.getMinimumIdle() < 0)
        {
            throw new ServerException("Invalid config. minimumIdle must be greater than or equal to 0.");
        }
        if (config.getMaximumPoolSize() <= 0)
        {
            throw new ServerException("Invalid config. maximumPoolSize must be greater than 0.");
        }
        if (config.getMaximumIdle() < 0)
        {
            throw new ServerException("Invalid config. maximumIdle must be greater than or equal to 0.");
        }
        if (config.getMinimumIdle() > config.getMaximumPoolSize())
        {
            throw new ServerException("Invalid config. minimumIdle must be less than or equal to maximumPoolSize.");
        }
        // maximumIdle == 0 表示不限制空闲连接数，因此仅在显式配置大于 0 时校验其上下界关系
        if (config.getMaximumIdle() > 0 && config.getMinimumIdle() > config.getMaximumIdle())
        {
            throw new ServerException("Invalid config. minimumIdle must be less than or equal to maximumIdle.");
        }

        this.dbDataSourcePoolConfig = config;
        this.connectionAcquireTimeoutMs = Math.max(0, config.getConnectionAcquireTimeoutMs());

        // 初始化 minimumIdle 个连接（构造阶段无并发，锁外创建即可）
        for (int i = 0; i < this.dbDataSourcePoolConfig.getMinimumIdle(); i++)
        {
            Connection connection = createPhysicalConnection();
            registerNewConnection(connection);
            this.idleConnectionPool.add(connection);
        }

        dbDataSourcePoolMonitor = new DBDataSourcePoolMonitor(this);
        dbDataSourcePoolMonitor.start();
    }

    public int getHighWaterMark()
    {
        return this.highWaterMark;
    }

    public int getIdleConnectionPoolSize()
    {
        poolLock.lock();
        try {
            return this.idleConnectionPool.size();
        } finally {
            poolLock.unlock();
        }
    }

    public int getUsedConnectionPoolSize()
    {
        poolLock.lock();
        try {
            return this.usedConnectionPool.size();
        } finally {
            poolLock.unlock();
        }
    }

    /** 池名（用于日志与诊断输出）。 */
    String poolName() {
        return this.poolName;
    }

    /**
     * 把物理连接包装成"关闭即归还"的池句柄。
     *
     * <p>所有对外发放连接的出口都必须经过这里：调用方按 JDBC 习惯
     * {@code try (Connection c = ...)} 时关闭的是句柄，槽位会正确回到池里。
     * 池内部的 {@code usedConnectionPool} / {@code connectionMetaDataMap}
     * 始终以<b>物理连接</b>为键，因此包装只发生在池的边界上。</p>
     */
    private Connection wrapForCaller(Connection physical) {
        return physical == null ? null : new PooledConnection(physical, this, this.logger);
    }

    public Connection getConnection() throws SQLException {
        long timeoutMs = this.connectionAcquireTimeoutMs;
        long deadline = timeoutMs > 0 ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs) : Long.MAX_VALUE;

        while (true) {
            boolean needCreate = false;

            // ---- 锁内：复用空闲连接 / 等待 / 判断是否需要在锁外创建 ----
            poolLock.lock();
            try {
                if (this.closed.get()) {
                    throw new SQLException("Pool [" + poolName + "] is closed.");
                }

                // 从空闲池取一个连接（FIFO 顺序）
                Connection connection = pollFirstIdleConnection();
                if (connection != null) {
                    if (isReusable(connection)) {
                        usedConnectionPool.add(connection);
                        ConnectionMetaData metaData = connectionMetaDataMap.get(connection);
                        int connectionNumber = metaData != null ? metaData.getConnectionId() : -1;
                        logger.debug("[SERVER][CONN POOL  ]: Pool [{}] Offer reused connection {}.",
                                this.poolName,
                                connectionNumber);
                        return wrapForCaller(connection);
                    }
                    // 连接不可用，登记退役（物理关闭延迟到锁外）
                    retireConnection(connection, "failed validation");
                } else if (this.connectionMetaDataMap.size() < this.dbDataSourcePoolConfig.getMaximumPoolSize()) {
                    // 容量未满，标记为需要创建（连接创建将移到锁外执行）
                    needCreate = true;
                } else {
                    // 池已满，等待连接释放
                    try {
                        if (timeoutMs <= 0) {
                            connectionAvailable.await();
                        } else {
                            long remaining = deadline - System.nanoTime();
                            if (remaining <= 0) {
                                throw new SQLException("Timeout while waiting for connection in Pool [" + poolName + "].");
                            }
                            var ignored = connectionAvailable.awaitNanos(remaining);
                        }
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("Interrupted while waiting for connection in Pool [" + poolName + "].",
                                interruptedException);
                    }
                    // 被唤醒后回到循环头部重新尝试
                }
            } finally {
                poolLock.unlock();
            }

            // ---- 锁外：关闭本轮退役登记中的连接 ----
            closePendingConnections();

            if (!needCreate) {
                // 退役了不可用连接、或池满等待被唤醒，重新循环重试
                continue;
            }

            // ---- 锁外创建物理连接 ----
            Connection newConnection = createPhysicalConnection();

            poolLock.lock();
            try {
                if (this.closed.get()
                        || this.connectionMetaDataMap.size() >= this.dbDataSourcePoolConfig.getMaximumPoolSize()) {
                    // 池已关闭，或创建期间容量已被其他线程占满，放弃刚创建的连接
                    this.pendingCloseConnections.add(newConnection);
                    newConnection = null;
                } else {
                    registerNewConnection(newConnection);
                    usedConnectionPool.add(newConnection);
                }
            } finally {
                poolLock.unlock();
            }

            // 锁外关闭可能被放弃的连接
            closePendingConnections();

            if (newConnection != null) {
                return wrapForCaller(newConnection);
            }
            // 容量竞争失败，回到循环重新尝试
        }
    }

    /**
     * 从空闲连接池中取出第一个连接（FIFO 顺序），O(1) 操作。
     */
    private Connection pollFirstIdleConnection() {
        var iter = idleConnectionPool.iterator();
        if (iter.hasNext()) {
            Connection conn = iter.next();
            iter.remove();
            return conn;
        }
        return null;
    }

    /**
     * 归还连接。
     *
     * <p>同时接受两种形态，两条路径都幂等：
     * <ul>
     *   <li>{@link PooledConnection} 句柄 —— 交给句柄自己去归还，与 {@code conn.close()} 共用
     *       同一个"只归还一次"的原子标志，因此 {@code releaseConnection(conn)} 和
     *       {@code conn.close()} 可以同时出现在一条代码路径上而不会重复归还；</li>
     *   <li>物理连接 —— 池内部及历史调用方直接持有的形态。</li>
     * </ul>
     */
    public void releaseConnection(Connection connection) {
        if (connection == null) {
            return;
        }

        // 池句柄：由句柄负责归还（幂等），无需在这里解包
        if (connection instanceof PooledConnection pooledConnection) {
            pooledConnection.release();
            return;
        }

        // ---- 阶段一（锁内）：校验这条连接确实属于本池、且仍在"使用中"。
        //
        // 刻意**不**在这里把它移出 usedConnectionPool —— 复位阶段在锁外执行，
        // 而 shutdown() 只遍历 used/idle 两个集合，连接若在窗口里"两边都不在"就会被漏掉、
        // 永远不关（这正是当初把复位放在锁内的理由）。留在 usedConnectionPool 里同时满足两件事：
        // shutdown() 看得见它，getConnection() 也不会把它借出去（那里只从 idle 取）。
        poolLock.lock();
        try {
            if (this.closed.get()) {
                // 池已关闭，清理该连接并登记延迟关闭
                connectionMetaDataMap.remove(connection);
                this.usedConnectionPool.remove(connection);
                this.idleConnectionPool.remove(connection);
                this.pendingCloseConnections.add(connection);
                return;
            }

            ConnectionMetaData metaData = connectionMetaDataMap.get(connection);
            int connectionNumber = metaData != null ? metaData.getConnectionId() : -1;
            this.logger.debug("[SERVER][CONN POOL  ]: Pool [{}] Release connection {}.",
                    this.poolName, connectionNumber);

            if (!this.usedConnectionPool.contains(connection)) {
                // 连接不在使用池中（重复释放或非本池连接），登记关闭以避免泄漏
                this.logger.warn("[SERVER][CONN POOL  ]: Pool [{}] Release unknown connection {}. Closing to avoid leak.",
                        this.poolName, connectionNumber);
                connectionMetaDataMap.remove(connection);
                this.pendingCloseConnections.add(connection);
                return;
            }

            if (!isReusable(connection)) {
                this.usedConnectionPool.remove(connection);
                // retireConnection 要求调用方先把它移出 used/idle
                retireConnection(connection, "failed validation on release");
                return;
            }
        } finally {
            poolLock.unlock();
        }

        // 锁外关闭上面登记的连接，避免持锁 IO
        closePendingConnections();

        // ---- 阶段二（锁外）：复位会话状态（回滚未提交事务 + 恢复 autoCommit）。
        //
        // 这里必须移出 poolLock：回滚可能要丢弃一个大事务的全部工作，DuckDB 会做真实的工作量，
        // 而 poolLock 是全池唯一的锁——一次慢回滚会让所有 getConnection() 一起排队。
        // 此刻连接仍在 usedConnectionPool 中，池不会把它借给别人。
        boolean restored = resetConnectionForReuse(connection);

        // ---- 阶段三（锁内）：按复位结果把连接挪进空闲池、或退役。
        poolLock.lock();
        try {
            if (!restored) {
                // 状态不明的连接不能放回池里
                this.usedConnectionPool.remove(connection);
                retireConnection(connection, "failed to reset state on release");
                return;
            }
            if (this.closed.get()) {
                // 复位期间池被关闭：shutdown() 已经把 usedConnectionPool 里的连接收走并关闭，
                // 这里绝不能再放回空闲池（否则空闲池里会躺着一条已关闭的连接）。
                this.usedConnectionPool.remove(connection);
                this.idleConnectionPool.remove(connection);
                connectionMetaDataMap.remove(connection);
                return;
            }
            if (!this.usedConnectionPool.remove(connection)) {
                // 复位期间被别处接管（例如 shutdown 已清空集合并负责关闭它）：不再放回空闲池
                return;
            }

            // O(1) 添加到空闲池
            this.idleConnectionPool.add(connection);
            connectionAvailable.signal();
        } finally {
            poolLock.unlock();
        }

        // 锁外关闭可能登记的连接，避免持锁 IO
        closePendingConnections();
    }

    /**
     * 退役一条正由池句柄归还的连接：不放回空闲池，直接物理关闭。
     *
     * <p>用于"归还时状态不可信"的情况——句柄上仍有未关闭或正在执行的语句
     * （见 {@link PooledConnection#close()}）。这类连接若放回池里，会被下一个借用者
     * <b>立刻</b>拿到，而语句可能仍在别的线程上执行：DuckDB 的单条连接不是线程安全的，
     * 两个会话会在同一条连接上互相踩。退役（销毁）比复用安全，代价只是这一次连接创建。</p>
     *
     * <p>与 {@link #releaseConnection(Connection)} 互斥，由 {@link PooledConnection} 的
     * "只归还一次"原子标志保证一条连接只会走其中一条路径。本方法自己取锁，
     * 调用方<b>不得</b>持锁调用。</p>
     */
    void retireConnectionFromHandle(Connection connection, String reason) {
        if (connection == null) {
            return;
        }
        poolLock.lock();
        try {
            this.usedConnectionPool.remove(connection);
            this.idleConnectionPool.remove(connection);
            // retireConnection 要求调用方先把它移出 used/idle
            retireConnection(connection, reason);
        } finally {
            poolLock.unlock();
        }
        // 锁外做物理关闭
        closePendingConnections();
    }

    public void shutdown()
    {
        this.closed.set(true);
        this.logger.debug("[SERVER][CONN POOL  ]: Pool [{}] DBDataSourcePool will shutdown ... ",
                this.poolName);
        dbDataSourcePoolMonitor.interrupt();
        // 等待监控线程完全终止
        long waitStart = System.nanoTime();
        long timeoutNs = TimeUnit.SECONDS.toNanos(10);
        while (dbDataSourcePoolMonitor.isAlive()) {
            try {
                dbDataSourcePoolMonitor.join(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                // 如果当前线程被中断，强制退出等待
                break;
            }
            if (System.nanoTime() - waitStart >= timeoutNs) {
                logger.warn("[SERVER][CONN POOL  ]: Pool [{}] Monitor thread did not terminate within timeout, proceeding anyway.",
                        this.poolName);
                break;
            }
        }

        List<Connection> toClose = new ArrayList<>();
        poolLock.lock();
        try {
            // 收集所有使用中的连接（修复 NPE：metadata 可能为 null）
            for (Connection connection : this.usedConnectionPool) {
                ConnectionMetaData metaData = connectionMetaDataMap.get(connection);
                int connectionNumber = metaData != null ? metaData.getConnectionId() : -1;
                if (connection != null) {
                    toClose.add(connection);
                    this.logger.debug("[SERVER][CONN POOL  ]: Pool [{}] Will close used connection {} .",
                            this.poolName,
                            connectionNumber);
                }
            }
            // 收集所有空闲连接
            for (Connection connection : this.idleConnectionPool) {
                ConnectionMetaData metaData = connectionMetaDataMap.get(connection);
                int connectionNumber = metaData != null ? metaData.getConnectionId() : -1;
                if (connection != null) {
                    toClose.add(connection);
                    this.logger.debug("[SERVER][CONN POOL  ]: Pool [{}] Will close idle connection {} .",
                            this.poolName,
                            connectionNumber);
                }
            }

            this.usedConnectionPool.clear();
            this.idleConnectionPool.clear();

            // 清空所有扩展信息
            connectionMetaDataMap.clear();

            // 唤醒所有等待获取连接的线程，使其检查 closed 标志后抛出异常
            connectionAvailable.signalAll();
        } finally {
            poolLock.unlock();
        }

        // 锁外关闭所有收集到的连接，避免持锁 IO
        for (Connection connection : toClose) {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException ignored) {
            }
        }

        // 关闭可能尚未处理的待关闭连接
        closePendingConnections();
    }

    /**
     * 更新连接池历史最高水位线。
     * 基于物理连接总数（connectionMetaDataMap）统计，既包含使用中的连接，
     * 也包含预热的空闲连接，从而正确反映历史峰值。
     */
    private void updateHighWaterMark() {
        int total = this.connectionMetaDataMap.size();
        if (this.highWaterMark < total) {
            this.highWaterMark = total;
        }
    }
}