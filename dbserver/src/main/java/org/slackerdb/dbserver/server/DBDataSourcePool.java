package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Logger;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.utils.Sleeper;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
            setName("DataSourcePool");
            while (!isInterrupted()) {
                try {
                    if (this.dbDataSourcePool.dbDataSourcePoolConfig.getMaximumIdle() != 0) {
                        int targetIdle = this.dbDataSourcePool.dbDataSourcePoolConfig.getMaximumIdle();
                        int extra;
                        try {
                            this.dbDataSourcePool.poolLock.lock();
                            extra = this.dbDataSourcePool.idleConnectionPool.size() - targetIdle;
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
                    if (this.dbDataSourcePool.dbDataSourcePoolConfig.getMinimumIdle() != 0) {
                        try {
                            this.dbDataSourcePool.poolLock.lock();
                            while (this.dbDataSourcePool.idleConnectionPool.size() < this.dbDataSourcePool.dbDataSourcePoolConfig.getMinimumIdle()
                                    && this.dbDataSourcePool.connectionMetaDataMap.size() < this.dbDataSourcePool.dbDataSourcePoolConfig.getMaximumPoolSize()) {
                                Connection connection = this.dbDataSourcePool.createNewConnection();
                                this.dbDataSourcePool.idleConnectionPool.add(connection);
                                this.dbDataSourcePool.connectionAvailable.signal();
                            }
                        } finally {
                            this.dbDataSourcePool.poolLock.unlock();
                        }
                    }
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
     * 回收连接。注意：调用方必须持有 poolLock。
     */
    private void retireConnection(Connection connection, String reason) {
        if (connection == null) {
            return;
        }
        ConnectionMetaData metaData = connectionMetaDataMap.remove(connection);
        int connectionNumber = metaData != null ? metaData.getConnectionId() : -1;
        logger.debug("[SERVER][CONN POOL  ]: Pool [{}] Retire connection {}. Reason: {}",
                this.poolName, connectionNumber, reason);
        // 注意：调用方已经负责从 idleConnectionPool 或 usedConnectionPool 中移除
        // 这里只负责关闭连接和清理 metadata
        try {
            if (!connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
        }
        connectionAvailable.signalAll();
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

        this.dbDataSourcePoolConfig = config;
        this.connectionAcquireTimeoutMs = Math.max(0, config.getConnectionAcquireTimeoutMs());

        // 初始化 minimumIdle 个连接
        for (int i = 0; i < this.dbDataSourcePoolConfig.getMinimumIdle(); i++)
        {
            this.idleConnectionPool.add(createNewConnection());
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
        return this.idleConnectionPool.size();
    }

    public int getUsedConnectionPoolSize()
    {
        return this.usedConnectionPool.size();
    }

    public Connection getConnection() throws SQLException {
        long timeoutMs = this.connectionAcquireTimeoutMs;
        long deadline = timeoutMs > 0 ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs) : Long.MAX_VALUE;

        poolLock.lock();
        try {
            while (true) {
                // 从空闲池取一个连接（FIFO 顺序）
                Connection connection = pollFirstIdleConnection();
                if (connection != null) {
                    if (isReusable(connection)) {
                        usedConnectionPool.add(connection);
                        updateHighWaterMark();
                        ConnectionMetaData metaData = connectionMetaDataMap.get(connection);
                        int connectionNumber = metaData != null ? metaData.getConnectionId() : -1;
                        logger.debug("[SERVER][CONN POOL  ]: Pool [{}] Offer reused connection {}.",
                                this.poolName,
                                connectionNumber);
                        return connection;
                    }
                    // 连接不可用，回收并继续尝试
                    retireConnection(connection, "failed validation");
                    continue;
                }

                if (this.connectionMetaDataMap.size() < this.dbDataSourcePoolConfig.getMaximumPoolSize()) {
                    Connection newConnection = createNewConnection();
                    usedConnectionPool.add(newConnection);
                    updateHighWaterMark();
                    return newConnection;
                }

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
            }
        } finally {
            poolLock.unlock();
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

    public void releaseConnection(Connection connection) {
        if (connection == null) {
            return;
        }

        poolLock.lock();
        try {
            ConnectionMetaData metaData = connectionMetaDataMap.get(connection);
            int connectionNumber = metaData != null ? metaData.getConnectionId() : -1;
            this.logger.debug("[SERVER][CONN POOL  ]: Pool [{}] Release connection {}.",
                    this.poolName, connectionNumber);

            // O(1) 从 usedConnectionPool 移除
            if (!this.usedConnectionPool.remove(connection)) {
                return;
            }

            if (!isReusable(connection)) {
                retireConnection(connection, "failed validation on release");
                return;
            }

            // O(1) 添加到空闲池
            this.idleConnectionPool.add(connection);
            connectionAvailable.signal();
        } finally {
            poolLock.unlock();
        }
    }

    public void shutdown()
    {
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

        poolLock.lock();
        try {
            // 关闭所有使用中的连接
            for (Connection connection : this.usedConnectionPool) {
                try {
                    if (connection != null && !connection.isClosed()) {
                        this.logger.debug("[SERVER][CONN POOL  ]: Pool [{}] Will close used connection {} .",
                                this.poolName,
                                connectionMetaDataMap.get(connection).getConnectionId());
                        connection.close();
                    }
                } catch (SQLException ignored) {
                }
            }
            // 关闭所有空闲连接
            for (Connection connection : this.idleConnectionPool) {
                try {
                    if (connection != null && !connection.isClosed()) {
                        this.logger.debug("[SERVER][CONN POOL  ]: Pool [{}] Will close idle connection {} .",
                                this.poolName,
                                connectionMetaDataMap.get(connection).getConnectionId());
                        connection.close();
                    }
                } catch (SQLException ignored) {
                }
            }

            this.usedConnectionPool.clear();
            this.idleConnectionPool.clear();

            // 清空所有扩展信息
            connectionMetaDataMap.clear();
        } finally {
            poolLock.unlock();
        }
    }

    private Connection createNewConnection() throws SQLException {
        Properties connectProperties = new Properties();
        if (this.dbDataSourcePoolConfig.getConnectProperties() != null)
        {
            connectProperties.putAll(this.dbDataSourcePoolConfig.getConnectProperties());
        }

        // 获取数据库连接
        Connection connection;
        Driver driver = this.dbDataSourcePoolConfig.getDriver();
        if (driver != null) {
            connection = driver.connect(this.dbDataSourcePoolConfig.getJdbcURL(), connectProperties);
        } else {
            connection = DriverManager.getConnection(this.dbDataSourcePoolConfig.getJdbcURL(), connectProperties);
        }
        connection.setAutoCommit(this.dbDataSourcePoolConfig.getAutoCommit());

        int connectionId = this.connectionId.incrementAndGet();
        ConnectionMetaData connectionMetaData = new ConnectionMetaData();
        connectionMetaData.setConnectionId(connectionId);
        connectionMetaData.setCreatedNanoTime(System.nanoTime());
        this.connectionMetaDataMap.put(connection, connectionMetaData);
        logger.debug("[SERVER][CONN POOL  ]: Pool [{}] Create new connection {}.",
                this.poolName, connectionId);
        return connection;
    }

    private void updateHighWaterMark() {
        if (this.highWaterMark < this.usedConnectionPool.size()) {
            this.highWaterMark = this.usedConnectionPool.size();
        }
    }
}
