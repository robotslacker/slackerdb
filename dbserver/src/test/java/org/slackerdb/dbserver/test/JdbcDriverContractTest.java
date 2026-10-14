package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.Test;

import java.sql.Driver;
import java.sql.DriverManager;
import java.util.Enumeration;

/**
 * 回归：slackerdb JDBC 驱动必须遵守 JDBC 规范对 {@link Driver#connect} 的要求——
 * URL 不属于本驱动时返回 {@code null}，而不是抛异常。
 *
 * <p><b>为什么重要</b>：{@link DriverManager#getConnection} 是按注册顺序逐个调用驱动的
 * {@code connect()}，靠 {@code null} 返回值判断"这个驱动不管，换下一个"。一旦某个驱动对
 * 别人的 URL 直接抛异常，排在它后面的驱动就永远没有机会。</p>
 *
 * <p>实测后果：应用同时引入 slackerdb 驱动与标准 PostgreSQL 驱动时（这在 dbserver 的测试
 * classpath、以及任何同时用两种驱动的客户端里都是常态），{@code jdbc:postgresql://...}
 * 会被 slackerdb 驱动拦下并报 {@code PSQLException: Unable to parse URL}，
 * 标准驱动完全接不到连接请求。</p>
 *
 * <p>注意 {@link Driver#acceptsURL} 不能作为判定依据：它只检查 URL 形状，
 * 两个驱动的实现都可能对彼此的 URL 返回 true，所以真正的守门人是 {@code connect()} 的返回值。</p>
 */
public class JdbcDriverContractTest {

    private static final String FOREIGN_URL = "jdbc:postgresql://127.0.0.1:1/whatever";

    /** 非本驱动的 URL：必须返回 null（规范要求），绝不能抛异常。 */
    @Test
    void connectReturnsNullForForeignUrl() throws Exception {
        Driver slackerdbDriver = findSlackerdbDriver();
        assert slackerdbDriver != null
                : "test classpath 上找不到 org.slackerdb.jdbc.Driver";

        // 这里不关心连接成功与否，只关心"是否让路"。
        // 让路 = 返回 null；不让路 = 抛异常（本次修复的正是后者）。
        Object result;
        try {
            result = slackerdbDriver.connect(FOREIGN_URL, null);
        } catch (Exception e) {
            assert false : "对非本驱动的 URL [" + FOREIGN_URL + "]，connect() 必须返回 null 让路，"
                    + "实际抛出了 " + e.getClass().getSimpleName() + ": " + e.getMessage();
            return;
        }
        assert result == null
                : "对非本驱动的 URL [" + FOREIGN_URL + "]，connect() 必须返回 null，实际返回 " + result;
    }

    /**
     * 端到端：两个驱动同时在 classpath 时，{@code jdbc:postgresql:} 仍应被标准驱动接管。
     *
     * <p>只断言"没有被 slackerdb 驱动拦下"——即异常不是 "Unable to parse URL"。
     * 目标端口 1 必然连不上，但那时已经是标准驱动在尝试建连了。</p>
     */
    @Test
    void driverManagerStillRoutesForeignUrlToPostgresDriver() {
        String message;
        try {
            DriverManager.getConnection(FOREIGN_URL, "", "");
            // 极端情况下连上了也无所谓，说明路由没问题
            return;
        } catch (Exception e) {
            message = String.valueOf(e.getMessage());
        }
        assert !message.contains("Unable to parse URL")
                : "jdbc:postgresql: URL 被 slackerdb 驱动拦下了（规范违规）：" + message;
    }

    /**
     * URL 前缀判定只能有一套规则：{@code connect()} 与 {@code acceptsURL()} 必须一致。
     *
     * <p>历史实现里两者各写各的——{@code connect()} 用大小写不敏感的前缀比较，
     * {@code parseURL()}（{@code acceptsURL()} 的实现）用大小写敏感的 {@code startsWith}。
     * 于是同一个 URL 会出现"{@code connect()} 认领、{@code acceptsURL()} 说不认识"的分歧：
     * {@code jdbc:SLACKERDB://...} 会被认领，然后因为解析不了而抛 "Unable to parse URL"。
     * 这类分歧只在特定写法下暴露，排查成本极高，因此用一个用例把两者钉在一起。</p>
     */
    @Test
    void urlPrefixDetectionIsConsistentRegardlessOfCase() throws Exception {
        Driver slackerdbDriver = findSlackerdbDriver();
        assert slackerdbDriver != null
                : "test classpath 上找不到 org.slackerdb.jdbc.Driver";

        String mixedCaseUrl = "JDBC:SlackerDB://127.0.0.1:1/whatever";

        assert slackerdbDriver.acceptsURL(mixedCaseUrl)
                : "acceptsURL 必须与 connect() 共用同一套前缀判定，实际拒绝了 " + mixedCaseUrl;

        // connect() 不能因为大小写就"不认识"自己的 URL（返回 null 或报无法解析）。
        // 端口 1 连不上是正常的，只要错误不是 "Unable to parse URL" 就说明前缀判定是一致的。
        try {
            Object result = slackerdbDriver.connect(mixedCaseUrl, new java.util.Properties());
            assert result != null : "本驱动应当认领 " + mixedCaseUrl + "（返回值不应为 null）";
        } catch (Exception e) {
            assert !String.valueOf(e.getMessage()).contains("Unable to parse URL")
                    : "大小写不同的 slackerdb URL 被判成『无法解析』（前缀判定又分叉了）: " + e.getMessage();
        }
    }

    private static Driver findSlackerdbDriver() {
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if ("org.slackerdb.jdbc.Driver".equals(driver.getClass().getName())) {
                return driver;
            }
        }
        return null;
    }
}
