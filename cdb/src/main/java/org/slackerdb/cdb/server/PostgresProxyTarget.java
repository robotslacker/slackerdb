package org.slackerdb.cdb.server;

import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.server.DBInstance;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PostgresProxyTarget {
    // 远程数据库的主机地址
    private final String  host;
    // 远程数据库的端口
    private final int     port;
    // 远程数据库的数据库名称
    private final String  database;

    private final DBInstance dbInstance;

    public PostgresProxyTarget(String targetURL, DBInstance dbInstance) throws ServerException
    {
        // URL:   ip:port/database
        Pattern pattern = Pattern.compile("([^:/]+)(?::(\\d+))?/(.+)");
        Matcher matcher = pattern.matcher(targetURL);

        if (matcher.matches()) {
            this.host = matcher.group(1);
            try {
                this.port = Integer.parseInt(matcher.group(2));
            }
            catch (NumberFormatException ignored)
            {
                throw new ServerException("Invalid target URL format. port format error.");
            }
            this.database = matcher.group(3);

            // 实例信息，可能为空，如果不是被AutoCreate创建的
            this.dbInstance = dbInstance;
        } else {
            throw new ServerException("Invalid target URL format.");
        }
    }

    public String getHost()
    {
        return host;
    }

    public String getDatabase()
    {
        return database;
    }

    public int getPort()
    {
        return port;
    }

    public DBInstance getDbInstance()
    {
        return this.dbInstance;
    }

}
