package org.slackerdb.dbproxy.server;

import java.time.LocalDateTime;

public class PostgresProxyTarget {
    // 创建时间
    public LocalDateTime createdDateTime;

    // 最后更新时间
    public LocalDateTime lastActivatedTIme;

    // 远程数据库的主机地址
    public String  host;

    // 远程数据库的端口
    public int     port;

    // 远程数据库的数据库名称
    public String  database;
}
