package org.slackerdb.dbdesk.access;

import org.slackerdb.dbdesk.access.http.Capabilities;
import org.slackerdb.dbdesk.access.http.Health;
import org.slackerdb.dbdesk.access.http.ManagementClient;
import org.slackerdb.dbdesk.access.http.ManagementException;
import org.slackerdb.dbdesk.access.profile.DbDeskProfile;
import org.slackerdb.dbdesk.i18n.Localized;
import org.slackerdb.dbdesk.i18n.LocalizedMessage;

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
 *
 * <p>失败与告警都只携带 i18n key（{@link Localized}），由界面层按当前语言渲染；
 * 日志里看到的是 key，便于检索。</p>
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
            throw new ProbeException("error.probe.proxyDatabaseRequired");
        }
        String instanceUrl = root + "/" + encodePathSegment(database);
        try {
            Capabilities instanceCapabilities;
            Health health;
            try (ManagementClient client = new ManagementClient(instanceUrl, MANAGEMENT_TIMEOUT)) {
                instanceCapabilities = client.capabilities();
                if (instanceCapabilities.isProxy()) {
                    return new Result(profile, jdbcUrl, "", "", ManagementScope.NONE, null,
                            proxyCapabilities, LocalizedMessage.of(
                            "warning.instanceManagementProxyCapabilities", database));
                }
                health = client.health();
            }
            if (!health.up()) {
                return new Result(profile, jdbcUrl, "", "", ManagementScope.NONE, null,
                        proxyCapabilities, LocalizedMessage.of(
                        "warning.instanceManagementUnhealthy", database, health.status()));
            }
            return new Result(profile, jdbcUrl, instanceUrl, "", ManagementScope.INSTANCE,
                    instanceCapabilities, null, null);
        } catch (ManagementException e) {
            return new Result(profile, jdbcUrl, "", "", ManagementScope.NONE, null,
                    proxyCapabilities, LocalizedMessage.of(
                    "warning.instanceManagementUnreachable", database, e));
        }
    }

    private static Result probeInstance(DbDeskProfile profile, DatabaseChecker databaseChecker,
                                        Capabilities instanceCapabilities) throws ProbeException {
        if (!profile.hasDatabase()) {
            throw new ProbeException("error.probe.managementRequired");
        }

        requireMatchingDatabasePort(profile, instanceCapabilities, false);

        Health health;
        try (ManagementClient client = new ManagementClient(profile.managementUrl(), MANAGEMENT_TIMEOUT)) {
            health = client.health();
        } catch (ManagementException e) {
            throw new ProbeException(e, "error.probe.managementUnreachable", e);
        }
        if (!health.up()) {
            throw new ProbeException("error.probe.managementUnhealthy", health.status());
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
            throw new ProbeException("error.probe.pgPortMissing", proxy ? "dbproxy" : "dbserver");
        }
        if (expected == actual) {
            return;
        }
        if (proxy) {
            throw new ProbeException("error.probe.portMismatch.proxy", expected, actual);
        }
        throw new ProbeException("error.probe.portMismatch.instance", expected, actual);
    }

    private static Capabilities readCapabilities(String managementUrl) throws ProbeException {
        try (ManagementClient client = new ManagementClient(managementUrl, MANAGEMENT_TIMEOUT)) {
            return client.capabilities();
        } catch (ManagementException e) {
            throw new ProbeException(e, "error.probe.managementUnreachable", e);
        }
    }

    private static String probeDatabase(DbDeskProfile profile) throws ProbeException {
        if (!profile.hasDatabase()) {
            throw new ProbeException("error.probe.databaseRequired");
        }
        JdbcDrivers.ensureLoaded();

        String jdbcUrl = profile.jdbcUrl()
                + "?connectTimeout=3&socketTimeout=5&ApplicationName=dbdesk";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "", "");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            if (!resultSet.next()) {
                throw new ProbeException("error.probe.databaseEmpty");
            }
        } catch (SQLException e) {
            throw new ProbeException(e, "error.probe.databaseUnreachable", message(e));
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
            LocalizedMessage warning) {

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

    /** 连接失败；key 由界面层按当前语言渲染。 */
    public static class ProbeException extends Exception implements Localized {

        private final String messageKey;
        private final Object[] messageArgs;

        public ProbeException(String messageKey, Object... messageArgs) {
            super(messageKey);
            this.messageKey = messageKey;
            this.messageArgs = messageArgs == null ? new Object[0] : messageArgs.clone();
        }

        public ProbeException(Throwable cause, String messageKey, Object... messageArgs) {
            super(messageKey, cause);
            this.messageKey = messageKey;
            this.messageArgs = messageArgs == null ? new Object[0] : messageArgs.clone();
        }

        @Override
        public String messageKey() {
            return messageKey;
        }

        @Override
        public Object[] messageArgs() {
            return messageArgs.clone();
        }
    }
}
