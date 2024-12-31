package org.slackerdb.dbserver.server;

import java.util.Properties;

public class DBDataSourcePoolConfig {
    public String jdbcURL;
    public String userName;
    public String password;
    public Properties connectProperties;
    public int maximumPoolSize;
    public int minimumIdle;
    public long connectionTimeout;

    public int connectTimeout;
    public long idleTimeout;
    public long maxLifetime;
    public String validationSQL;
    public int validationTimeout;
}
