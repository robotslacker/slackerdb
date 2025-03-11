package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Logger;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.utils.Sleeper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class DBDataSourcePool {
    private final ConcurrentHashMap<Connection, ConnectionMetaData> connectionMetaDataMap = new ConcurrentHashMap<>();
    private final DBDataSourcePoolConfig dbDataSourcePoolConfig;
    private final ConcurrentLinkedQueue<Connection> idleConnectionPool = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Connection> usedConnectionPool = new ConcurrentLinkedQueue<>();
    private final Logger logger;
    private final AtomicInteger connectionId = new AtomicInteger(0);
    DBDataSourcePoolMonitor dbDataSourcePoolMonitor;
    private int   highWaterMark = 0;

    static class DBDataSourcePoolMonitor extends Thread
    {
        private final DBDataSourcePool dbDataSourcePool;
        private final Logger logger;
        public DBDataSourcePoolMonitor(DBDataSourcePool dbDataSourcePool)
        {
            this.dbDataSourcePool = dbDataSourcePool;
            this.logger = this.dbDataSourcePool.logger;
        }

        @Override
        public void run()
        {
            setName("DBDataSourcePoolMonitor");
            while (!isInterrupted()) {
                try {
                    if (this.dbDataSourcePool.dbDataSourcePoolConfig.getMaximumIdle() != 0) {
                        if (this.dbDataSourcePool.idleConnectionPool.size() > this.dbDataSourcePool.dbDataSourcePoolConfig.getMaximumIdle()) {
                            // 销毁多余连接
                            int currentIdleConnectionPoolSize = this.dbDataSourcePool.idleConnectionPool.size();
                            for (int i = this.dbDataSourcePool.dbDataSourcePoolConfig.getMaximumIdle(); i<currentIdleConnectionPoolSize; i++) {
                                Connection connection = this.dbDataSourcePool.idleConnectionPool.poll();
                                if (connection != null) {
                                    logger.trace("[SERVER][CONN POOL  ]: Free connection {}. ", this.dbDataSourcePool.connectionMetaDataMap.get(connection).getConnectionId());
                                    this.dbDataSourcePool.connectionMetaDataMap.remove(connection);
                                    if (!connection.isClosed()) {
                                        connection.close();
                                    }
                                }
                            }
                        }
                    }
                    if (this.dbDataSourcePool.dbDataSourcePoolConfig.getMinimumIdle() != 0) {
                        if (this.dbDataSourcePool.idleConnectionPool.size() < this.dbDataSourcePool.dbDataSourcePoolConfig.getMinimumIdle()) {
                            // 补充空闲连接
                            int currentIdleConnectionPoolSize = this.dbDataSourcePool.idleConnectionPool.size();
                            for (int i = currentIdleConnectionPoolSize; i < this.dbDataSourcePool.dbDataSourcePoolConfig.getMinimumIdle(); i++) {
                                this.dbDataSourcePool.idleConnectionPool.offer(this.dbDataSourcePool.createNewConnection());
                            }
                        }
                    }
                } catch (SQLException ignored) {
                }

                try {
                    Sleeper.sleep(10 * 1000);
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
            if ((conn == null ) || (conn.isClosed()))
            {
                return false;
            }
            if (this.dbDataSourcePoolConfig.getValidationSQL() != null && !this.dbDataSourcePoolConfig.getValidationSQL().isEmpty())
            {
                PreparedStatement preparedStatement = conn.prepareStatement(this.dbDataSourcePoolConfig.getValidationSQL());
                preparedStatement.execute();
                preparedStatement.close();
            }
            return true;
        }
        catch (SQLException ignored)
        {
            return false;
        }
    }

    public DBDataSourcePool(
            DBDataSourcePoolConfig config,
            Logger logger) throws SQLException {

        this.logger = logger;
        this.logger.trace("[SERVER][CONN POOL  ]: DBDataSourcePool started ..");

        if (config.getMinimumIdle() < 0)
        {
            throw new ServerException("Invalid config. minimumIdle must be greater than or equal to 0.");
        }
        if (config.getMaximumPoolSize() <= 0)
        {
            throw new ServerException("Invalid config. maximumPoolSize must be greater than 0.");
        }

        this.dbDataSourcePoolConfig = config;

        // 初始化 minimumIdle 个连接
        for (int i = 0; i < this.dbDataSourcePoolConfig.getMinimumIdle(); i++)
        {
            this.idleConnectionPool.offer(createNewConnection());
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
        while (true) {
            synchronized (this) {
                // 从连接池中获取一个连接
                Connection connection = this.idleConnectionPool.poll();
                if (connection == null) {
                    if (this.usedConnectionPool.size() >= this.dbDataSourcePoolConfig.getMaximumPoolSize()) {
                        throw new SQLException("Maximum connection reached.");
                    } else {
                        // 创建一个新连接，并直接返回
                        connection = createNewConnection();
                        usedConnectionPool.offer(connection);
                        return connection;
                    }
                } else {
                    if (connection.isClosed())
                    {
                        // 对连接进行生命周期验证
                        logger.trace("[SERVER][CONN POOL  ]: Connection {} has closed, remove it.",
                                connectionMetaDataMap.get(connection).getConnectionId());
                        connectionMetaDataMap.remove(connection);
                        continue;
                    }
                    if (this.dbDataSourcePoolConfig.getMaximumLifeCycleTime() > 0) {
                        if (System.currentTimeMillis() - connectionMetaDataMap.get(connection).getCreatedTime() >
                                this.dbDataSourcePoolConfig.getMaximumLifeCycleTime()) {
                            // 对连接进行生命周期验证
                            logger.trace("[SERVER][CONN POOL  ]: Connection {} has retired, remove it.",
                                    connectionMetaDataMap.get(connection).getConnectionId());
                            connectionMetaDataMap.remove(connection);
                            // 已经超过最大生命周期，没有必要继续保留
                            try {
                                connection.close();
                            } catch (SQLException ignored) {
                            }
                            continue;
                        }
                    }
                    if (!this.validateConnection(connection)) {
                        // 对连接进行验证
                        logger.trace("[SERVER][CONN POOL  ]: Connection {} can't pass validation, remove it.",
                                connectionMetaDataMap.get(connection).getConnectionId());
                        connectionMetaDataMap.remove(connection);
                        try {
                            connection.close();
                        } catch (SQLException ignored) {
                        }
                        continue;
                    }
                    usedConnectionPool.offer(connection);
                    logger.trace("[SERVER][CONN POOL  ]: Offer reused connection {}.", connectionMetaDataMap.get(connection).getConnectionId());
                    return connection;
                }
            }
        }
    }

    public void releaseConnection(Connection connection) {
        this.logger.trace("[SERVER][CONN POOL  ]: Release connection {}.", connectionMetaDataMap.get(connection).getConnectionId());
        this.usedConnectionPool.remove(connection);

        // 将连接放入连接池，准备复用
        try
        {
            if (!connection.isClosed()) {
                if (this.dbDataSourcePoolConfig.getMaximumLifeCycleTime() != 0)
                {
                    if (System.currentTimeMillis() - connectionMetaDataMap.get(connection).getCreatedTime()
                            > this.dbDataSourcePoolConfig.getMaximumLifeCycleTime())
                    {
                        connectionMetaDataMap.remove(connection);
                        // 已经超过最大生命周期，没有必要继续保留
                        connection.close();
                        return;
                    }
                }
                this.idleConnectionPool.offer(connection);
            }
        }
        catch (SQLException ignored) {}
    }

    public void shutdown()
    {
        this.logger.trace("[SERVER][CONN POOL  ]: DBDataSourcePool will shutdown ... ");
        dbDataSourcePoolMonitor.interrupt();

        // 关闭连接
        for (Connection connection : this.usedConnectionPool) {
            try {
                if (connection != null && !connection.isClosed()) {
                    this.logger.trace("[SERVER][CONN POOL  ]: Will close used connection {} .",
                            connectionMetaDataMap.get(connection).getConnectionId());
                    connection.close();
                }
            } catch (SQLException ignored) {
            }
        }
        for (Connection connection : this.idleConnectionPool) {
            try {
                if (connection != null && !connection.isClosed()) {
                    this.logger.trace("[SERVER][CONN POOL  ]: Will close idle connection {} .",
                            connectionMetaDataMap.get(connection).getConnectionId());
                    connection.close();
                }
            } catch (SQLException ignored) {
            }
        }

        // 清空所有扩展信息
        connectionMetaDataMap.clear();
    }

    private Connection createNewConnection() throws SQLException {
        Properties connectProperties = new Properties();
        if (this.dbDataSourcePoolConfig.getConnectProperties() != null)
        {
            connectProperties.putAll(this.dbDataSourcePoolConfig.getConnectProperties());
        }
        if (this.dbDataSourcePoolConfig.getUserName() != null)
        {
            connectProperties.put("username", this.dbDataSourcePoolConfig.getUserName());
        }
        if (this.dbDataSourcePoolConfig.getPassword() != null)
        {
            connectProperties.put("password", this.dbDataSourcePoolConfig.getPassword());
        }

        // 获取数据库连接
        Connection connection = DriverManager.getConnection(this.dbDataSourcePoolConfig.getJdbcURL(), connectProperties);

        int connectionId = this.connectionId.incrementAndGet();
        ConnectionMetaData connectionMetaData = new ConnectionMetaData();
        connectionMetaData.setConnectionId(connectionId);
        connectionMetaData.setCreatedTime(System.currentTimeMillis());
        this.connectionMetaDataMap.put(connection, connectionMetaData);
        logger.trace("[SERVER][CONN POOL  ]: Create new connection {}.",connectionId);

        // 标记连接池的最高水位线
        if (this.highWaterMark < this.usedConnectionPool.size())
        {
            this.highWaterMark = this.usedConnectionPool.size();
        }
        return connection;
    }
}
