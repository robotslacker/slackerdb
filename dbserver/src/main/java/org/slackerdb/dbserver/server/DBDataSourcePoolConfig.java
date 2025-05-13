package org.slackerdb.dbserver.server;

import java.util.Properties;

public class DBDataSourcePoolConfig {
    private String jdbcURL;
    private Properties connectProperties;
    private int maximumPoolSize;
    private int minimumIdle;
    private int maximumIdle;
    private int maximumLifeCycleTime;

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
}
