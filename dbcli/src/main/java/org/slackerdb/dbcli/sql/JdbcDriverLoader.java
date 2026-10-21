package org.slackerdb.dbcli.sql;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * {@code _LOAD JDBCDRIVER <jar>} 的实现核心：把外部 jar 里的 JDBC 驱动装进当前进程。
 *
 * <h2>加载结果</h2>
 * <ul>
 *   <li>同一个 jar（按绝对路径规范化后）重复加载不会重新打开：返回 {@code alreadyLoaded=true}；</li>
 *   <li>jar 里没有 {@code META-INF/services/java.sql.Driver} 登记任何驱动 → {@link LoadException}；</li>
 *   <li>成功加载后，该驱动对本进程后续的 {@code _CONNECT}（含作业线程）一直可用。</li>
 * </ul>
 */
public final class JdbcDriverLoader {

    /** 已加载的 jar → 驱动类名（有序，便于状态行按加载顺序列出）。 */
    private static final Map<Path, List<String>> LOADED = new LinkedHashMap<>();

    /** 加载动作的唯一锁：{@code _LOAD} 可能来自作业线程，注册表必须串行改。 */
    private static final Object LOCK = new Object();

    private JdbcDriverLoader() {
    }

    /**
     * 一次加载的结果。
     *
     * @param jar         规范化后的绝对路径
     * @param drivers     这个 jar 里登记的驱动类名
     * @param alreadyLoaded true 表示这个 jar 之前已经加载过（本次没有重复注册）
     */
    public record Loaded(Path jar, List<String> drivers, boolean alreadyLoaded) {
    }

    /** 加载失败（消息可直接给使用者看）。 */
    public static final class LoadException extends Exception {

        private static final long serialVersionUID = 1L;

        public LoadException(String message) {
            super(message);
        }
    }

    /**
     * 加载一个外部 JDBC 驱动 jar。
     *
     * @param jar 驱动 jar（调用方已确认它是个存在的文件）
     * @throws LoadException 读不了 / 里面没有驱动 / 驱动登记损坏
     */
    public static Loaded load(Path jar) throws LoadException {
        Path absolute = jar.toAbsolutePath().normalize();
        synchronized (LOCK) {
            List<String> known = LOADED.get(absolute);
            if (known != null) {
                return new Loaded(absolute, known, true);
            }
            List<Driver> drivers = instantiate(absolute);
            List<String> names = new ArrayList<>(drivers.size());
            for (Driver driver : drivers) {
                try {
                    // 垫片必须在 dbcli 的 classloader 里，DriverManager 才会真正调用它（见类注释）
                    DriverManager.registerDriver(new DriverShim(driver));
                } catch (SQLException registerFailed) {
                    throw new LoadException("Cannot register the JDBC driver [" + driver.getClass().getName()
                            + "] from [" + absolute + "]: " + registerFailed.getMessage());
                }
                names.add(driver.getClass().getName());
            }
            LOADED.put(absolute, List.copyOf(names));
            return new Loaded(absolute, List.copyOf(names), false);
        }
    }

    /**
     * 打开 jar，按它自己的 {@code META-INF/services/java.sql.Driver} 实例化驱动。
     *
     */
    private static List<Driver> instantiate(Path jar) throws LoadException {
        URLClassLoader classLoader;
        try {
            classLoader = new URLClassLoader(new URL[] {jar.toUri().toURL()},
                    JdbcDriverLoader.class.getClassLoader());
        } catch (IOException orInvalidUrl) {
            throw new LoadException("Cannot open [" + jar + "]: " + orInvalidUrl.getMessage());
        }
        List<String> driverClassNames;
        try {
            driverClassNames = readDriverClassNames(jar);
        } catch (IOException unreadable) {
            closeQuietly(classLoader);
            throw new LoadException("Cannot read [" + jar + "]: " + unreadable.getMessage());
        }
        if (driverClassNames.isEmpty()) {
            closeQuietly(classLoader);
            throw new LoadException("No JDBC driver found in [" + jar
                    + "]: the jar does not register META-INF/services/java.sql.Driver.");
        }
        List<Driver> drivers = new ArrayList<>(driverClassNames.size());
        for (String className : driverClassNames) {
            try {
                Class<?> type = Class.forName(className, true, classLoader);
                if (!Driver.class.isAssignableFrom(type)) {
                    throw new LoadException("Class [" + className + "] in [" + jar
                            + "] is not a java.sql.Driver.");
                }
                drivers.add((Driver) type.getDeclaredConstructor().newInstance());
            } catch (LoadException invalidDriver) {
                closeQuietly(classLoader);
                throw invalidDriver;
            } catch (ReflectiveOperationException | RuntimeException cannotInstantiate) {
                closeQuietly(classLoader);
                throw new LoadException("Cannot load a JDBC driver from [" + jar + "]: "
                        + cannotInstantiate.getMessage());
            }
        }
        return drivers;
    }

    /** 读 jar 里 {@code META-INF/services/java.sql.Driver} 的类名（去掉 {@code #} 注释与空行）。 */
    private static List<String> readDriverClassNames(Path jar) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry("META-INF/services/java.sql.Driver");
            if (entry == null) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(jarFile.getInputStream(entry), StandardCharsets.UTF_8))) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    int comment = line.indexOf('#');
                    String name = (comment < 0 ? line : line.substring(0, comment)).strip();
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
            return names;
        }
    }

    private static void closeQuietly(URLClassLoader classLoader) {
        try {
            classLoader.close();
        } catch (IOException ignored) {
            // 关不掉没有补救手段：这个 classloader 随后被丢弃
        }
    }

    /**
     * 动态驱动的垫片：属于 dbcli 的 classloader，{@link DriverManager} 因此认得它。
     *
     * <p>它只做委托：URL 是否归自己、怎么连、版本号与属性都由真正的驱动回答。</p>
     */
    private static final class DriverShim implements Driver {

        private final Driver driver;

        DriverShim(Driver driver) {
            this.driver = driver;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(driver.getClass().getClassLoader());
                return driver.connect(url, info);
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return driver.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info)
                throws SQLException {
            return driver.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return driver.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return driver.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return driver.jdbcCompliant();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return driver.getParentLogger();
        }
    }
}
