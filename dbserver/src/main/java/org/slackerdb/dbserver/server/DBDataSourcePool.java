package org.slackerdb.dbserver.server;

import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.utils.Sleeper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;

public class DBDataSourcePool {
    static class ExtendConnection
    {
        Connection   connection;
        long         createTime;
        long         updateTime;
    }

    static class DBDataSourcePoolMonitor extends Thread
    {
        private DBDataSourcePoolConfig dbDataSourcePoolConfig;
        private ConcurrentLinkedQueue<ExtendConnection> idleConnectionPool;
        private Map<Connection, ExtendConnection> usedConnectionPool;
        public DBDataSourcePoolMonitor(
                DBDataSourcePoolConfig dbDataSourcePoolConfig,
                ConcurrentLinkedQueue<ExtendConnection> idleConnectionPool,
                Map<Connection, ExtendConnection> usedConnectionPool)
        {
            this.dbDataSourcePoolConfig = dbDataSourcePoolConfig;
            this.idleConnectionPool = idleConnectionPool;
            this.usedConnectionPool = usedConnectionPool;
        }
        @Override
        public void run()
        {
            while (true)
            {
                for (ExtendConnection extendConnection : idleConnectionPool)
                {
                    if (System.currentTimeMillis() - extendConnection.updateTime > this.dbDataSourcePoolConfig.idleTimeout)
                    {
                        // 超过连接空闲值
                        try {
                            extendConnection.connection.close();
                        } catch (SQLException ignored) {}
                        idleConnectionPool.remove(extendConnection);
                    }
                    if (System.currentTimeMillis() - extendConnection.createTime > this.dbDataSourcePoolConfig.maxLifetime)
                    {
                        try {
                            extendConnection.connection.close();
                        } catch (SQLException ignored) {}
                        idleConnectionPool.remove(extendConnection);
                    }
                }
                Sleeper.sleep(15*1000);
            }
        }
    }

    DBDataSourcePoolConfig dbDataSourcePoolConfig;
    ConcurrentLinkedQueue<ExtendConnection> idleConnectionPool = new ConcurrentLinkedQueue<>();
    Map<Connection, ExtendConnection> usedConnectionPool = new HashMap<>();

    private void cleanupConnectionPool()
    {

    }

    private boolean validateConnection(Connection conn)
    {
        try
        {
            if ((conn == null ) || (conn.isClosed()))
            {
                return false;
            }
            if (this.dbDataSourcePoolConfig.validationSQL != null)
            {
                PreparedStatement preparedStatement = conn.prepareStatement(this.dbDataSourcePoolConfig.validationSQL);
                preparedStatement.setQueryTimeout(this.dbDataSourcePoolConfig.validationTimeout);
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

    public DBDataSourcePool(DBDataSourcePoolConfig config) throws SQLException {
        if (config.minimumIdle < 0)
        {
            throw new ServerException("Invalid config. minimumIdle must be greater than or equal to 0.");
        }
        if (config.maximumPoolSize <= 0)
        {
            throw new ServerException("Invalid config. maximumPoolSize must be greater than 0.");
        }

        this.dbDataSourcePoolConfig = config;

        // 初始化 minimumIdle 个连接
        for (int i = 0; i < this.dbDataSourcePoolConfig.minimumIdle; i++)
        {
            ExtendConnection extendConnection = new ExtendConnection();
            extendConnection.createTime = System.currentTimeMillis();
            extendConnection.updateTime = System.currentTimeMillis();
            extendConnection.connection = createNewConnection();
            this.idleConnectionPool.offer(extendConnection);
        }

        // 开启监控进程
        DBDataSourcePoolMonitor dbDataSourcePoolMonitor = new DBDataSourcePoolMonitor(this.dbDataSourcePoolConfig,
                this.idleConnectionPool, this.usedConnectionPool);
        dbDataSourcePoolMonitor.start();
    }

    public Connection getConnection() throws SQLException {
        synchronized (this) {
            // 获得一个新的连接
            if (this.idleConnectionPool.isEmpty())
            {
                if (this.usedConnectionPool.size() >= this.dbDataSourcePoolConfig.maximumPoolSize)
                {
                    throw new SQLException("Maximum connection reached.");
                }
                else
                {
                    // 创建一个新连接，并直接返回
                    ExtendConnection extendConnection = new ExtendConnection();
                    extendConnection.createTime = System.currentTimeMillis();
                    extendConnection.updateTime = System.currentTimeMillis();
                    extendConnection.connection = createNewConnection();
                    usedConnectionPool.put(extendConnection.connection, extendConnection);
                    return extendConnection.connection;
                }
            }
            else
            {
                // 从连接池中获取一个连接
                ExtendConnection extendConnection = this.idleConnectionPool.poll();
                extendConnection.updateTime = System.currentTimeMillis();
                usedConnectionPool.put(extendConnection.connection, extendConnection);
                return extendConnection.connection;
            }
        }
    }

    public void releaseConnection(Connection connection) {
        synchronized (this) {
            ExtendConnection extendConnection = usedConnectionPool.get(connection);
            this.usedConnectionPool.remove(connection);
            if ((System.currentTimeMillis() - extendConnection.createTime) < this.dbDataSourcePoolConfig.maxLifetime)
            {
                // 如果已经创建时间过长，则销毁该连接
                // 否则保留，用作下次连接
                this.idleConnectionPool.add(extendConnection);
            }
        }
    }

    private void closeConnection(Connection connection) throws SQLException {
        connection.close();
    }

    private Connection createNewConnection() throws SQLException {
        Properties connectProperties = new Properties();
        if (this.dbDataSourcePoolConfig.connectProperties != null)
        {
            connectProperties.putAll(this.dbDataSourcePoolConfig.connectProperties);
        }
        if (this.dbDataSourcePoolConfig.userName != null)
        {
            connectProperties.put("username", this.dbDataSourcePoolConfig.userName);
        }
        if (this.dbDataSourcePoolConfig.password != null)
        {
            connectProperties.put("password", this.dbDataSourcePoolConfig.password);
        }

        // 获取数据库连接
        return DriverManager.getConnection(this.dbDataSourcePoolConfig.jdbcURL, connectProperties);
    }

    public void close() {
        // 清理资源，关闭连接池中的连接
    }

    public static void main(String[] args) throws SQLException {
        DBDataSourcePoolConfig dbDataSourcePoolConfig = new DBDataSourcePoolConfig();
        dbDataSourcePoolConfig.jdbcURL = "jdbc:duckdb::memory:";
        dbDataSourcePoolConfig.minimumIdle = 3;
        dbDataSourcePoolConfig.maximumPoolSize = 10;
        dbDataSourcePoolConfig.connectProperties = null;
        dbDataSourcePoolConfig.validationSQL = "select 1";
        DBDataSourcePool dbDataSourcePool = new DBDataSourcePool(dbDataSourcePoolConfig);

        Connection conx = dbDataSourcePool.getConnection();

        System.out.println("OK");
    }
}
