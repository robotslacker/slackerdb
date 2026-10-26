package org.slackerdb.dbdesk.access;

import org.slackerdb.dbdesk.access.http.Capabilities;
import org.slackerdb.dbdesk.access.http.Health;
import org.slackerdb.dbdesk.access.http.ManagementClient;
import org.slackerdb.dbdesk.access.http.ManagementException;
import org.slackerdb.dbdesk.access.profile.DbDeskProfile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

/**
 * 连接前探测：先用 pgwire 验证数据库端口，再按需验证管理口。
 *
 * <p>严格按连接对话框的语义执行：管理端口一旦填写，{@code /health} 或
 * {@code /capabilities} 任一失败都视为连接失败，不降级。</p>
 */
public final class ConnectionProbe {

    private static final Duration MANAGEMENT_TIMEOUT = Duration.ofSeconds(3);

    private ConnectionProbe() {
    }

    public static Result probe(DbDeskProfile profile) throws ProbeException {
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

        if (!profile.hasManagement()) {
            return new Result(profile, jdbcUrl, "", null);
        }

        String managementUrl = profile.managementUrl();
        try (ManagementClient client = new ManagementClient(managementUrl, MANAGEMENT_TIMEOUT)) {
            Health health = client.health();
            if (!health.up()) {
                throw new ProbeException("管理口健康检查失败：" + health.status());
            }
            Capabilities capabilities = client.capabilities();
            return new Result(profile, jdbcUrl, managementUrl, capabilities);
        } catch (ManagementException e) {
            throw new ProbeException("管理口不可达：" + message(e), e);
        }
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    /** 探测成功的结果。 */
    public record Result(
            DbDeskProfile profile,
            String jdbcUrl,
            String managementUrl,
            Capabilities capabilities) {

        public boolean hasManagement() {
            return managementUrl != null && !managementUrl.isBlank() && capabilities != null;
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
