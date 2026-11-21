package org.slackerdb.dbsyncer.source;

import org.slackerdb.dbsyncer.config.SyncConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

/** 源库 JDBC 连接（元数据/快照/回源补齐共用）。 */
public final class SourceConnections {

    private SourceConnections() {
    }

    public static Connection open(SyncConfig.ConnectionConfig config) throws SQLException {
        return open(config, Map.of());
    }

    /**
     * @param overrides 仅对本次连接生效的属性（例如 PG 逻辑复制的 {@code replication=database}），
     *                  优先级高于连接配置里的 {@code properties}
     */
    public static Connection open(SyncConfig.ConnectionConfig config, Map<String, String> overrides)
            throws SQLException {
        loadDriver(config);
        Properties properties = driverProperties(config);
        overrides.forEach(properties::setProperty);
        return DriverManager.getConnection(config.url, properties);
    }

    /** 连接配置 → 驱动属性；不含连接特有的覆盖项。 */
    private static Properties driverProperties(SyncConfig.ConnectionConfig config) {
        Properties properties = new Properties();
        properties.setProperty("user", config.username);
        properties.setProperty("password", config.password == null ? "" : config.password);
        // 默认的超时只约束"建连"，不设 socketTimeout：快照/复制连接会有长时间静默的合法场景。
        // 需要更短超时的探测（例如状态接口里的 lag 查询）通过 overrides 单独指定。
        properties.setProperty("connectTimeout",
                config.type == SyncConfig.SourceType.POSTGRES ? "5" : "5000");
        if (config.type == SyncConfig.SourceType.POSTGRES) {
            properties.setProperty("ApplicationName", "slackerdb-dbsyncer");
        }
        config.properties.forEach(properties::setProperty);
        return properties;
    }

    /**
     * Spring Boot fat jar 下 DriverManager 的自动发现依赖线程上下文/驱动类加载，
     * 这里显式加载，避免 "No suitable driver found"。
     */
    private static void loadDriver(SyncConfig.ConnectionConfig config) throws SQLException {
        String className = config.type == SyncConfig.SourceType.POSTGRES
                ? "org.postgresql.Driver"
                : "com.mysql.cj.jdbc.Driver";
        try {
            Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new SQLException("JDBC driver [" + className + "] is not on the classpath", e);
        }
    }
}
