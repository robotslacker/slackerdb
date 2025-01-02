package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Logger;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.logger.AppLogger;
import org.slackerdb.common.utils.Sleeper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class DBDataSourcePool {
    private final DBDataSourcePoolConfig dbDataSourcePoolConfig;
    private final ConcurrentLinkedQueue<Connection> idleConnectionPool = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Connection> usedConnectionPool = new ConcurrentLinkedQueue<>();
    private final Logger logger;
    private final AtomicInteger connectionId = new AtomicInteger(0);
    DBDataSourcePoolMonitor dbDataSourcePoolMonitor;

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
        @SuppressWarnings("BusyWait")
        public void run()
        {
            setName("DBDataSourcePoolMonitor");
            while (!isInterrupted()) {
//                ConcurrentLinkedQueue<Connection> idleConnectionPool = this.dbDataSourcePool.idleConnectionPool;
                try {
                    // 已经超过最大生命周期的，没有必要保留
                    if (this.dbDataSourcePool.dbDataSourcePoolConfig.getMaximumLifeCycleTime() != 0) {
                        synchronized (this.dbDataSourcePool) {
                            for (Connection connection : this.dbDataSourcePool.idleConnectionPool) {
                                if (System.currentTimeMillis() - Long.parseLong(connection.getClientInfo("CreatedTime")) >
                                        this.dbDataSourcePool.dbDataSourcePoolConfig.getMaximumLifeCycleTime()) {
                                    // 已经超过最大生命周期，没有必要继续保留
                                    this.dbDataSourcePool.idleConnectionPool.remove(connection);
                                    logger.trace("Retire connection {}. ", connection.getClientInfo("ConnectionId"));
                                    try {
                                        connection.close();
                                    }
                                    catch (SQLException ignored) {}
                                }
                            }
                        }
                    }
                    if (this.dbDataSourcePool.dbDataSourcePoolConfig.getMaximumIdle() != 0) {
                        if (this.dbDataSourcePool.idleConnectionPool.size() > this.dbDataSourcePool.dbDataSourcePoolConfig.getMaximumIdle()) {
                            // 销毁多余连接
                            int currentIdleConnectionPoolSize = this.dbDataSourcePool.idleConnectionPool.size();
                            for (int i = this.dbDataSourcePool.dbDataSourcePoolConfig.getMaximumIdle(); i<currentIdleConnectionPoolSize; i++) {
                                Connection connection = this.dbDataSourcePool.idleConnectionPool.poll();
                                if (connection != null) {
                                    logger.trace("Free connection {}. ", connection.getClientInfo("ConnectionId"));
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
                    Thread.sleep(10 * 1000);
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
            if (this.dbDataSourcePoolConfig.getValidationSQL() != null)
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
        this.logger.trace("DBDataSourcePool started ..");

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
                        continue;
                    }
                    if (!this.validateConnection(connection)) {
                        logger.trace("Connection {} can't pass validation, remove it.",
                                connection.getClientInfo("ConnectionId"));
                        try {
                            connection.close();
                        } catch (SQLException ignored) {
                        }
                        continue;
                    }
                    usedConnectionPool.offer(connection);
                    logger.trace("Offer reused connection {}.", connection.getClientInfo("ConnectionId"));
                    return connection;
                }
            }
        }
    }

    public void releaseConnection(Connection connection) {
        try {
            this.logger.trace("Release connection {}.", connection.getClientInfo("ConnectionId"));
        }
        catch (SQLException ignored) {}
        this.usedConnectionPool.remove(connection);

        // 将连接放入连接池，准备复用
        try
        {
            if (!connection.isClosed()) {
                if (this.dbDataSourcePoolConfig.getMaximumLifeCycleTime() != 0)
                {
                    if (System.currentTimeMillis() - Long.parseLong(connection.getClientInfo("CreatedTime"))
                            > this.dbDataSourcePoolConfig.getMaximumLifeCycleTime())
                    {
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
        this.logger.trace("DBDataSourcePool will shutdown ... ");
        dbDataSourcePoolMonitor.interrupt();

        for (Connection connection : this.usedConnectionPool) {
            try {
                if (connection != null && !connection.isClosed()) {
                    this.logger.trace("Will close connection {} .", connection.getClientInfo("ConnectionId"));
                    connection.close();
                }
            } catch (SQLException ignored) {
            }
        }
        for (Connection connection : this.idleConnectionPool) {
            try {
                if (connection != null && !connection.isClosed()) {
                    this.logger.trace("Will close connection {} .", connection.getClientInfo("ConnectionId"));
                    connection.close();
                }
            } catch (SQLException ignored) {
            }
        }
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
        Connection originalConnection = DriverManager.getConnection(this.dbDataSourcePoolConfig.getJdbcURL(), connectProperties);
        Connection wrappedConnection = ConnectionWrapper.createProxy(originalConnection);

        // 记录一些额外的信息
        int connectionId = this.connectionId.incrementAndGet();
        wrappedConnection.setClientInfo("CreatedTime", String.valueOf(System.currentTimeMillis()));
        wrappedConnection.setClientInfo("ConnectionId", String.valueOf(connectionId));
        logger.trace("Create new connection {}.",connectionId);

        return wrappedConnection;
    }

    public static void main(String[] args) throws SQLException {
        Logger logger1 = AppLogger.createLogger("xxx", "TRACE", "CONSOLE");
        DBDataSourcePoolConfig dbDataSourcePoolConfig = new DBDataSourcePoolConfig();
        dbDataSourcePoolConfig.setJdbcURL("jdbc:duckdb::memory:");
        dbDataSourcePoolConfig.setMinimumIdle(3);
        dbDataSourcePoolConfig.setMaximumIdle(5);
        dbDataSourcePoolConfig.setMaximumPoolSize(100);
        dbDataSourcePoolConfig.setMaximumLifeCycleTime(5000);
        dbDataSourcePoolConfig.setConnectProperties(null);
        dbDataSourcePoolConfig.setValidationSQL("select 1");
        DBDataSourcePool dbDataSourcePool = new DBDataSourcePool(
                dbDataSourcePoolConfig, logger1);

        List<Connection> connectionList = new ArrayList<>();
        for (int i=0; i<10;i++) {
            connectionList.add(dbDataSourcePool.getConnection());
        }
        for (int i=0; i<5;i++) {
            dbDataSourcePool.releaseConnection(connectionList.get(i));
        }

        Sleeper.sleep(15*1000);

        for (int i=0; i<10;i++) {
            connectionList.add(dbDataSourcePool.getConnection());
        }
        dbDataSourcePool.shutdown();

        System.out.println("OK");
    }
}
