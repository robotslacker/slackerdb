package org.slackerdb.dbdesk.access.profile;

import java.util.List;

/**
 * DbDesk 自己的一条连接历史。
 *
 * @param alias          显示名/唯一键
 * @param host           pgwire 主机
 * @param databasePort   pgwire 端口
 * @param managementPort 管理端口；null 表示没有管理口（仅 SQL）
 * @param database       数据库名（必填，无默认值）
 */
public record DbDeskProfile(
        String alias,
        String host,
        int databasePort,
        Integer managementPort,
        String database) {

    public DbDeskProfile {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("连接别名不能为空");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("主机不能为空");
        }
        if (databasePort <= 0 || databasePort > 65535) {
            throw new IllegalArgumentException("数据库端口必须在 1-65535 之间");
        }
        if (managementPort != null && (managementPort <= 0 || managementPort > 65535)) {
            throw new IllegalArgumentException("管理端口必须在 1-65535 之间");
        }
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("数据库名不能为空");
        }
        alias = alias.strip();
        host = host.strip();
        database = database.strip();
    }

    public boolean hasManagement() {
        return managementPort != null;
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://" + host + ":" + databasePort + "/" + database;
    }

    /** 管理口地址；没有管理口时返回空串。 */
    public String managementUrl() {
        return managementPort == null ? "" : "http://" + host + ":" + managementPort;
    }

    public DbDeskProfile withAlias(String newAlias) {
        return new DbDeskProfile(newAlias, host, databasePort, managementPort, database);
    }
}
