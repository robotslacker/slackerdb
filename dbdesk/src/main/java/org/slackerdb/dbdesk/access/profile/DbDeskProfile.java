package org.slackerdb.dbdesk.access.profile;

/**
 * DbDesk 自己的一条连接历史。
 *
 * <p>数据库端口和管理端口都是可选的，但不能同时为空：</p>
 * <ul>
 *   <li>只有数据库端口：只能用 SQL，没有管理能力；</li>
 *   <li>只有管理端口：必须指向 dbproxy 本身，进入集群实例管理；</li>
 *   <li>两个都填：由 {@code ConnectionProbe} 判断管理口是实例还是 dbproxy，并校验
 *       数据库端口与管理口属于同一个服务。</li>
 * </ul>
 *
 * @param alias          显示名/唯一键
 * @param host           pgwire/管理口主机
 * @param databasePort   pgwire 端口；{@code null} 表示没有数据库端口
 * @param managementPort 管理端口；{@code null} 表示没有管理口
 * @param database       数据库名；填了数据库端口时必填，只填管理端口时可空
 */
public record DbDeskProfile(
        String alias,
        String host,
        Integer databasePort,
        Integer managementPort,
        String database) {

    public DbDeskProfile {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("连接别名不能为空");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("主机不能为空");
        }
        if (databasePort == null && managementPort == null) {
            throw new IllegalArgumentException("数据库端口和管理端口不能同时为空");
        }
        if (databasePort != null && (databasePort <= 0 || databasePort > 65535)) {
            throw new IllegalArgumentException("数据库端口必须在 1-65535 之间");
        }
        if (managementPort != null && (managementPort <= 0 || managementPort > 65535)) {
            throw new IllegalArgumentException("管理端口必须在 1-65535 之间");
        }
        if (databasePort != null && (database == null || database.isBlank())) {
            throw new IllegalArgumentException("填写数据库端口时数据库名不能为空");
        }
        alias = alias.strip();
        host = host.strip();
        database = database == null ? null : database.strip();
        if (database != null && database.isEmpty()) {
            database = null;
        }
    }

    public boolean hasDatabasePort() {
        return databasePort != null;
    }

    public boolean hasDatabase() {
        return databasePort != null && database != null;
    }

    public boolean hasManagement() {
        return managementPort != null;
    }

    /** 没有数据库端口时返回 {@code null}；调用方必须先判断 {@link #hasDatabase()}。 */
    public String jdbcUrl() {
        if (!hasDatabase()) {
            return null;
        }
        return "jdbc:postgresql://" + host + ":" + databasePort + "/" + database;
    }

    /**
     * 管理口的根地址；没有管理口时返回空串。
     *
     * <p>经 dbproxy 转发到实例时的 {@code /<别名>} 由连接探测阶段按“代理数据口 + 代理管理口”
     * 自动推导，不保存在 profile 里，也不让用户填写。</p>
     */
    public String managementUrl() {
        if (managementPort == null) {
            return "";
        }
        return "http://" + host + ":" + managementPort;
    }

    public DbDeskProfile withAlias(String newAlias) {
        return new DbDeskProfile(newAlias, host, databasePort, managementPort, database);
    }
}
