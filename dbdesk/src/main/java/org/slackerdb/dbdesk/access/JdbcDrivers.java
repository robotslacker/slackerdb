package org.slackerdb.dbdesk.access;

/**
 * 保证 JDBC 驱动在**可执行 jar** 里也能被 {@code DriverManager} 看见。
 *
 * <p><b>为什么需要它</b>：Spring Boot 的 fat jar 用自定义类加载器加载 {@code BOOT-INF/lib/*}，
 * 而 {@code DriverManager} 的驱动发现与"调用方类加载器可见性"检查（{@code isDriverAllowed}）
 * 在这种结构下并不可靠 —— 实测打包运行时报
 * {@code No suitable driver found for jdbc:postgresql://…}（同一个 URL 在 IDE/测试 classpath 下完全正常）。</p>
 *
 * <p>显式 {@code Class.forName} 让驱动在**本类的类加载器**里初始化并自行注册，
 * 之后 {@code DriverManager.getConnection} 就能通过可见性检查。驱动是 runtime 作用域，
 * 所以这里用反射按名字加载（不产生编译期依赖）。</p>
 */
public final class JdbcDrivers {

    private static volatile boolean loaded;

    private JdbcDrivers() {
    }

    public static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (JdbcDrivers.class) {
            if (loaded) {
                return;
            }
            for (String driver : new String[]{"org.postgresql.Driver", "org.slackerdb.jdbc.Driver"}) {
                try {
                    Class.forName(driver);
                } catch (ClassNotFoundException ignored) {
                    // 该驱动不在 classpath 上就跳过：用哪个由连接串决定
                }
            }
            loaded = true;
        }
    }
}
