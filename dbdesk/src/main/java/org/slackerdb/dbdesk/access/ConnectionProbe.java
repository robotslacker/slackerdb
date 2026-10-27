package org.slackerdb.dbdesk.access;

import org.slackerdb.dbdesk.access.http.Capabilities;
import org.slackerdb.dbdesk.access.http.Health;
import org.slackerdb.dbdesk.access.http.ManagementClient;
import org.slackerdb.dbdesk.access.http.ManagementException;
import org.slackerdb.dbdesk.access.profile.DbDeskProfile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

/**
 * 连接前探测：先判定管理口的“身份”，再校验数据库端口与管理口是否属于同一个服务。
 *
 * <p>规则与 UI 的语义一一对应：</p>
 * <ul>
 *   <li>只有数据库端口：SQL only；</li>
 *   <li>只有管理端口：必须是 dbproxy 根，进入集群实例管理；</li>
 *   <li>数据库 + 实例管理口：直连实例，管理 URL 就是管理口根；</li>
 *   <li>数据库 + 代理管理口：数据库端口必须等于代理自报的 pg 端口；管理 URL 自动追加
 *       {@code /<database>}。如果代理转发的实例没有开放管理端口，降级为 SQL only；</li>
 *   <li>实例数据口 + 代理管理口、代理数据口 + 实例管理口：直接拒绝。</li>
 * </ul>
 */
public final class ConnectionProbe {

    private static final Duration MANAGEMENT_TIMEOUT = Duration.ofSeconds(3);

    private ConnectionProbe() {
    }

    /** 真正探测 JDBC 的接缝；单元测试可以替换，避免为管理口判定启动数据库。 */
    @FunctionalInterface
    interface DatabaseChecker {
        String check(DbDeskProfile profile) throws ProbeException;
    }

    public static Result probe(DbDeskProfile profile) throws ProbeException {
        return probe(profile, ConnectionProbe::probeDatabase);
    }

    static Result probe(DbDeskProfile profile, DatabaseChecker databaseChecker) throws ProbeException {
        if (!profile.hasManagement()) {
            String jdbcUrl = databaseChecker.check(profile);
            return new Result(profile, jdbcUrl, "", "", ManagementScope.NONE, null, null, null);
        }

        Capabilities management = readCapabilities(profile.managementUrl());
        if (management.isProxy()) {
            return probeProxy(profile, databaseChecker, management);
        }
        return probeInstance(profile, databaseChecker, management);
    }

    private static Result probeProxy(DbDeskProfile profile, DatabaseChecker databaseChecker,
                                     Capabilities proxyCapabilities) throws ProbeException {
        String root = profile.managementUrl();

        // 只连代理管理口：唯一的“实例管理”入口。
        if (!profile.hasDatabase()) {
            return new Result(profile, null, "", root, ManagementScope.CLUSTER,
                    null, proxyCapabilities, null);
        }

        requireMatchingDatabasePort(profile, proxyCapabilities, true);
        String jdbcUrl = databaseChecker.check(profile);

        String database = profile.database();
        if (database == null || database.isBlank()) {
            throw new ProbeException("经代理管理实例时缺少数据库名");
        }
        String instanceUrl = root + "/" + encodePathSegment(database);
        String warning = "管理口可达，但实例 [" + database
                + "] 未开放管理端口或管理口不可用，已按 SQL only 连接";
        try {
            Capabilities instanceCapabilities;
            Health health;
            try (ManagementClient client = new ManagementClient(instanceUrl, MANAGEMENT_TIMEOUT)) {
                instanceCapabilities = client.capabilities();
                if (instanceCapabilities.isProxy()) {
                    return new Result(profile, jdbcUrl, "", "", ManagementScope.NONE,
                            null, proxyCapabilities, warning + "（代理路径返回了代理能力）");
                }
                health = client.health();
            }
            if (!health.up()) {
                return new Result(profile, jdbcUrl, "", "", ManagementScope.NONE,
                        null, proxyCapabilities, warning + "（状态 " + health.status() + "）");
            }
            return new Result(profile, jdbcUrl, instanceUrl, "", ManagementScope.INSTANCE,
                    instanceCapabilities, null, null);
        } catch (ManagementException e) {
            return new Result(profile, jdbcUrl, "", "", ManagementScope.NONE,
                    null, proxyCapabilities, warning + "：" + message(e));
        }
    }

    private static Result probeInstance(DbDeskProfile profile, DatabaseChecker databaseChecker,
                                        Capabilities instanceCapabilities) throws ProbeException {
        if (!profile.hasDatabase()) {
            throw new ProbeException("只填写管理端口时，管理口必须指向 dbproxy 本身；当前管理口是 dbserver 实例。"
                    + "请补充数据库端口，或改连 dbproxy 管理端口。");
        }

        requireMatchingDatabasePort(profile, instanceCapabilities, false);

        Health health;
        try (ManagementClient client = new ManagementClient(profile.managementUrl(), MANAGEMENT_TIMEOUT)) {
            health = client.health();
        } catch (ManagementException e) {
            throw new ProbeException("管理口不可达：" + message(e), e);
        }
        if (!health.up()) {
            throw new ProbeException("管理口健康检查失败：" + health.status());
        }

        String jdbcUrl = databaseChecker.check(profile);
        return new Result(profile, jdbcUrl, profile.managementUrl(), "",
                ManagementScope.INSTANCE, instanceCapabilities, null, null);
    }

    /**
     * 管理口自报的 {@code ports.pg} 必须等于用户填写的数据库端口。
     *
     * <p>这是“实例数据口 + 代理管理口”和“代理数据口 + 实例管理口”两种错误组合的判定依据；
     * 依赖直连端口一致，NAT/端口映射场景不在当前支持范围内。</p>
     */
    private static void requireMatchingDatabasePort(DbDeskProfile profile, Capabilities capabilities,
                                                    boolean proxy) throws ProbeException {
        int expected = capabilities.pgPort();
        int actual = profile.databasePort();
        if (expected <= 0) {
            throw new ProbeException("管理口（" + (proxy ? "dbproxy" : "dbserver")
                    + "）没有声明可用的数据库端口，无法确认数据库端口与管理口是否匹配。");
        }
        if (expected == actual) {
            return;
        }
        if (proxy) {
            throw new ProbeException("数据库端口与管理端口不匹配：管理口属于 dbproxy（其数据端口为 "
                    + expected + "），但填写的是 " + actual
                    + "。实例数据端口 + 代理管理端口不允许；直连实例请填写实例的管理端口。");
        }
        throw new ProbeException("数据库端口与管理端口不匹配：管理口属于 dbserver 实例（其数据端口为 "
                + expected + "），但填写的是 " + actual
                + "。代理数据端口 + 实例管理端口不允许；经代理请填写代理的管理端口。");
    }

    private static Capabilities readCapabilities(String managementUrl) throws ProbeException {
        try (ManagementClient client = new ManagementClient(managementUrl, MANAGEMENT_TIMEOUT)) {
            return client.capabilities();
        } catch (ManagementException e) {
            throw new ProbeException("管理口不可达：" + message(e), e);
        }
    }

    private static String probeDatabase(DbDeskProfile profile) throws ProbeException {
        if (!profile.hasDatabase()) {
            throw new ProbeException("数据库端口不可达：缺少数据库名");
        }
        JdbcDrivers.ensureLoaded();

        String jdbcUrl = profile.jdbcUrl()
                + "?connectTimeout=3&socketTimeout=5&ApplicationName=dbdesk";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "", "");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            if (!resultSet.next()) {
                throw new ProbeException("数据库端口可以连接，但 SELECT 1 没有返回结果");
            }
        } catch (SQLException e) {
            throw new ProbeException("数据库端口不可达：" + message(e), e);
        }
        return jdbcUrl;
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    /** 探测成功的结果。 */
    public record Result(
            DbDeskProfile profile,
            String jdbcUrl,
            String managementUrl,
            String clusterUrl,
            ManagementScope scope,
            Capabilities capabilities,
            Capabilities proxyCapabilities,
            String warning) {

        public boolean hasDatabase() {
            return jdbcUrl != null && !jdbcUrl.isBlank();
        }

        public boolean hasInstanceManagement() {
            return scope == ManagementScope.INSTANCE
                    && managementUrl != null && !managementUrl.isBlank()
                    && capabilities != null;
        }

        public boolean hasClusterManagement() {
            return scope == ManagementScope.CLUSTER
                    && clusterUrl != null && !clusterUrl.isBlank()
                    && proxyCapabilities != null;
        }
    }

    /** 连接失败。 */
    public static class ProbeException extends Exception {
        public ProbeException(String message) {
            super(message);
        }

        public ProbeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
