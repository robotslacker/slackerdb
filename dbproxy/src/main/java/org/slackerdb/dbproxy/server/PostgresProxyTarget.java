package org.slackerdb.dbproxy.server;

public class PostgresProxyTarget {
    // 远程数据库的主机地址
    private final String  host;
    // 远程数据库的端口
    private final int     port;
    // 数据库连接的权重
    private final int     weight;

    public PostgresProxyTarget(String pHost, int pPort, int pWeight)
    {
        this.host = pHost;
        this.port = pPort;
        this.weight = pWeight;
    }

    public String getHost()
    {
        return host;
    }

    public int getPort()
    {
        return port;
    }

    public int getWeight() {
        return weight;
    }
}
