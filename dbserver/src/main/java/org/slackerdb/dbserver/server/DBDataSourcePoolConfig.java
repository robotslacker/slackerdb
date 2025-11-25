package org.slackerdb.dbserver.server;

import java.util.Properties;

/**
 * DBDataSourcePoolConfig 对外暴露连接池调优参数，大部分为整数或布尔开关，方便通过配置文件注入。
 */
public class DBDataSourcePoolConfig {
    private String jdbcURL;
    private Properties connectProperties;
    private int maximumPoolSize;
    private int minimumIdle;
    private int maximumIdle;
    private int maximumLifeCycleTime;
    private boolean autoCommit = true;
    // 新增的获取连接等待时间，默认 30s，可按需关闭（<=0）
    private long connectionAcquireTimeoutMs = 30_000L;

    public String getJdbcURL()
    {
        return this.jdbcURL;
    }

    public void setJdbcURL(String jdbcURL)
    {
        this.jdbcURL = jdbcURL;
    }

    public int getMaximumIdle() {
        return maximumIdle;
    }

    public int getMaximumLifeCycleTime() {
        return maximumLifeCycleTime;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public Properties getConnectProperties() {
        return this.connectProperties;
    }

    public int getMinimumIdle() {
        return minimumIdle;
    }

    public void setConnectProperties(Properties connectProperties) {
        this.connectProperties = connectProperties;
    }

    public void setMaximumIdle(int maximumIdle) {
        this.maximumIdle = maximumIdle;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }

    public void setMaximumLifeCycleTime(int maximumLifeCycleTime) {
        this.maximumLifeCycleTime = maximumLifeCycleTime;
    }

    public void setMinimumIdle(int minimumIdle) {
        this.minimumIdle = minimumIdle;
    }

    public void setAutoCommit(boolean val)
    {
        this.autoCommit = val;
    }

    public boolean getAutoCommit()
    {
        return this.autoCommit;
    }

    public long getConnectionAcquireTimeoutMs() {
        return connectionAcquireTimeoutMs;
    }

    public void setConnectionAcquireTimeoutMs(long connectionAcquireTimeoutMs) {
        this.connectionAcquireTimeoutMs = connectionAcquireTimeoutMs;
    }
}

