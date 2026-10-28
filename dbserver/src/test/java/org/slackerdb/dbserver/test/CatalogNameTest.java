package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.sql.SQLReplacer;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.TimeZone;

/**
 * 回归：{@code duck_catalog} 所在的 catalog 名（{@link DBInstance#catalogName}）必须与
 * SQL 改写前缀、会话 search_path 三者同源，且<b>恒为内存主 catalog</b>。
 *
 * <p><b>为什么必须单独立这个用例</b>：既有的 {@code Sanity03Test} 走的是 {@code data_dir}
 * 默认值 {@code :memory:}，而 {@code SQLReplacerGoldenTest} 只调 {@code SQLReplacer.load}
 * 不启动实例——两者都覆盖不到磁盘模式（{@code data_dir=<目录>} + {@code data=<库名>}，
 * 即真实部署形态）。正是这一档曾经被改坏，而且坏得最彻底：</p>
 * <ul>
 *   <li>{@code data_encrypt=true} 启动时实例先挂起等密钥（{@code attachDatabase} 提前 return，
 *       从未 ATTACH），若此时把前缀推成实例库名，{@code set search_path} 会指向尚不存在的
 *       catalog 并直接报错，把<b>唯一能给密钥的那条连接</b>掐断；</li>
 *   <li>磁盘模式 + 挂载成功时若把前缀推成实例库名，DuckDB 会拒绝
 *       {@code SET search_path = '<库名>.duck_catalog,...'}
 *       （{@code No catalog + schema named "xxx.duck_catalog" found.}），
 *       于是<b>每个</b>客户端连接都在握手期失败。</li>
 * </ul>
 *
 * <p>本用例验证三个方向：</p>
 * <ol>
 *   <li>{@link #unattachedInstanceKeepsCatalogNameInMemory()} —— 挂起阶段必须留在 memory；</li>
 *   <li>{@link #diskModeInstanceConnectsAndResolvesFakeCatalog()} —— 磁盘模式必须真的能连上，
 *       且改写后的元数据查询必须真的能查到（只断言改写字符串是发现不了问题的）；
 *   </li>
 *   <li>{@link #emptyDataNameFallsBackToMemoryCatalog()} —— {@code data} 为空时不得出现空前缀。</li>
 * </ol>
 */
public class CatalogNameTest {

    /** 挂起阶段实例名（不要用 mem：那会撞上 Sanity03Test 设置的 SLACKERDB_MEM_KEY）。 */
    private static final String SUSPEND_INSTANCE = "encsuspendcase";

    @AfterEach
    void tearDown() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        // 清掉可能残留的密钥属性，避免污染后续用例
        System.clearProperty("SLACKERDB_" + SUSPEND_INSTANCE.toUpperCase() + "_KEY");
        System.clearProperty("SLACKERDB_PLAININSTANCE_KEY");
    }

    /**
     * 加密模式第一段（无密钥、实例库未挂载）：catalogName 必须是 memory，且连接必须能建立。
     *
     * <p>这是本次回归的核心断言。如果 catalogName 被推成实例库名，
     * 下面这次 {@code DriverManager.getConnection} 会在握手阶段被服务端关闭
     * （{@code StartupRequest} 里 {@code set search_path} 抛错 → catch → ctx.close），
     * 客户端只会看到 "connection attempt failed"。</p>
     */
    @Test
    void unattachedInstanceKeepsCatalogNameInMemory(@TempDir Path dataDir) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // 确保没有任何来源能提供密钥，实例必然走"挂起等密钥"这条分支
        System.clearProperty("SLACKERDB_" + SUSPEND_INSTANCE.toUpperCase() + "_KEY");

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData(SUSPEND_INSTANCE);
        cfg.setData_dir(dataDir.toString());
        cfg.setDataEncrypt(true);

        DBInstance instance = new DBInstance(cfg);
        instance.start();
        try {
            assert instance.instanceState.equalsIgnoreCase("RUNNING")
                    : "实例未启动成功: " + instance.instanceState;
            assert instance.instanceSuspendForSecretKey
                    : "无密钥时实例应处于挂起状态（否则本用例没有覆盖到目标分支）";

            // 核心断言①：前缀必须留在内存主 catalog
            assert "memory".equals(instance.catalogName)
                    : "实例库未挂载时 catalogName 必须是 memory，实际为 [" + instance.catalogName
                            + "]；指向尚不存在的实例 catalog 会让 SET search_path 报错并掐断连接";

            // 核心断言②：SQL 改写前缀必须同源（这正是当初产生非法 ".duck_catalog" 的地方）
            String rewritten = SQLReplacer.replaceSQL(instance, "SELECT * FROM pg_catalog.pg_type");
            assert rewritten.equals("SELECT * FROM memory.duck_catalog.pg_type")
                    : "改写前缀与 catalogName 不同源: " + rewritten;

            // 核心断言③：客户端必须真的能连上（端到端，最贴近用户可见的故障）
            String url = "jdbc:postgresql://127.0.0.1:" + cfg.getPort() + "/" + SUSPEND_INSTANCE;
            try (Connection conn = connect(url, SUSPEND_INSTANCE);
                 Statement stmt = conn.createStatement()) {

                try (ResultSet rs = stmt.executeQuery("SHOW search_path")) {
                    assert rs.next() : "SHOW search_path 没有返回行";
                    String searchPath = rs.getString(1);
                    assert searchPath != null && searchPath.toLowerCase().startsWith("memory.duck_catalog")
                            : "挂起阶段 search_path 应指向 memory.duck_catalog，实际为 [" + searchPath + "]";
                }

                // duck_catalog 在这个阶段必须真的可查（它在内存主 catalog 里）
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM duck_catalog.pg_type")) {
                    assert rs.next() : "duck_catalog.pg_type 查询没有返回行";
                    assert rs.getLong(1) > 0 : "duck_catalog.pg_type 为空，fake catalog 没有建好";
                }
            }
        } finally {
            instance.stop();
        }
    }

    /**
     * 磁盘模式（{@code data_dir=<目录>} + {@code data=<实例名>}，即真实部署形态）：
     * {@code duck_catalog} 仍然只在内存主 catalog，连接必须能建立、改写目标必须能查到。
     *
     * <p><b>为什么这里必须真的建一次连接</b>：把 catalogName 定成实例库名时，改写出来的
     * 字符串"看起来完全正确"（{@code <实例名>.duck_catalog.pg_type}），只断言字符串是发现不了
     * 问题的——错的是那个位置根本不存在。真正的故障表现是：</p>
     * <ul>
     *   <li>会话握手 {@code SET search_path = '<实例名>.duck_catalog,...'} 直接报
     *       {@code Catalog Error: SET search_path: No catalog + schema named "xxx.duck_catalog" found.}
     *       → 每个客户端连接都在握手期被拒；</li>
     *   <li>即便连上，{@code pg_catalog.pg_type} 等改写目标也查不到
     *       （{@code Table with name "duck_catalog.pg_type" does not exist}）。</li>
     * </ul>
     * 因此本用例同时断言"连得上"和"查得到"（{@code pg_catalog.pg_type} 会被改写成
     * {@code memory.duck_catalog.pg_type}，这是每个客户端连上后最早发出的查询之一）。
     */
    @Test
    void diskModeInstanceConnectsAndResolvesFakeCatalog(@TempDir Path dataDir) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        final String instanceName = "plaininstance";
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData(instanceName);
        cfg.setData_dir(dataDir.toString());

        DBInstance instance = new DBInstance(cfg);
        instance.start();
        try {
            assert instance.instanceState.equalsIgnoreCase("RUNNING")
                    : "实例未启动成功: " + instance.instanceState;
            assert !instance.instanceSuspendForSecretKey
                    : "未开启加密时不应处于挂起状态";

            // 核心断言①：fake catalog 恒在内存主 catalog —— 与 data_dir / data 无关
            assert "memory".equals(instance.catalogName)
                    : "fake catalog 只在内存主 catalog 里（SlackerCatalog 先 use memory 再建 schema），"
                            + "catalogName 必须是 memory，实际为 [" + instance.catalogName + "]";

            // 核心断言②：改写前缀必须同源
            String rewritten = SQLReplacer.replaceSQL(instance, "SELECT * FROM pg_catalog.pg_type");
            assert rewritten.equals("SELECT * FROM memory.duck_catalog.pg_type")
                    : "改写前缀与 catalogName 不同源: " + rewritten;

            // 核心断言③：端到端 —— 磁盘模式下客户端必须真的能连上
            // （catalogName 一旦被推成实例库名，这里就会在握手期失败）
            String url = "jdbc:postgresql://127.0.0.1:" + cfg.getPort() + "/" + instanceName;
            try (Connection conn = connect(url, instanceName);
                 Statement stmt = conn.createStatement()) {

                // 核心断言④：最常被客户端触发的改写目标必须真的能查到
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM pg_catalog.pg_type")) {
                    assert rs.next() : "改写后的 pg_catalog.pg_type 查询没有返回行";
                    assert rs.getLong(1) > 0
                            : "改写后的 pg_catalog.pg_type 查不到数据（改写前缀指向了不存在的 catalog）";
                }

                // 核心断言⑤：实例库本身仍然正常挂载为实例名（fake catalog 与实例库是两回事）
                try (ResultSet rs = stmt.executeQuery("SELECT current_catalog()")) {
                    assert rs.next() : "current_catalog() 没有返回行";
                    assert instanceName.equalsIgnoreCase(rs.getString(1))
                            : "实例库应挂载为 [" + instanceName + "]，实际 current_catalog()=["
                                    + rs.getString(1) + "]";
                }
            } catch (SQLException sqlException) {
                assert false : "磁盘模式下客户端连接/查询失败（catalogName 很可能又被推成实例库名了）: "
                        + sqlException.getMessage();
            }
        } finally {
            instance.stop();
        }
    }

    /**
     * 默认配置（{@code data=} 为空 + 磁盘 data_dir）：catalogName 必须是 memory。
     *
     * <p>这是发行版 {@code dbserver.conf} 的默认形态，也是"空前缀"那个 bug 的触发场景：
     * 旧代码在这里会得到 {@code ""}，把 {@code pg_catalog.pg_type} 改写成非法的
     * {@code ".duck_catalog.pg_type"}。</p>
     */
    @Test
    void emptyDataNameFallsBackToMemoryCatalog(@TempDir Path dataDir) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("");                       // 发行版默认值
        cfg.setData_dir(dataDir.toString());

        DBInstance instance = new DBInstance(cfg);
        instance.start();
        try {
            assert instance.instanceState.equalsIgnoreCase("RUNNING")
                    : "实例未启动成功: " + instance.instanceState;
            assert "memory".equals(instance.catalogName)
                    : "data 为空时 catalogName 必须是 memory，实际为 [" + instance.catalogName + "]";

            String rewritten = SQLReplacer.replaceSQL(instance, "SELECT * FROM pg_catalog.pg_type");
            assert rewritten.equals("SELECT * FROM memory.duck_catalog.pg_type")
                    : "出现空前缀改写（非法 SQL）: " + rewritten;

            // 端到端：默认配置下必须能连上并读到 fake catalog
            String url = "jdbc:postgresql://127.0.0.1:" + cfg.getPort() + "/";
            try (Connection conn = connect(url, "");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM duck_catalog.pg_type")) {
                assert rs.next() && rs.getLong(1) > 0 : "默认配置下 duck_catalog.pg_type 不可用";
            } catch (SQLException sqlException) {
                assert false : "默认配置（data 为空）下连接或元数据查询失败: " + sqlException.getMessage();
            }
        } finally {
            instance.stop();
        }
    }

    /**
     * 管理接口 + 没有 {@code --data}（发行版最常见的手工启动方式，例如
     * {@code java -jar slackerdb-dbserver-*.jar --portx 43090 start}）。
     *
     * <p>这是用户报的启动崩溃：{@code DefinitionService} / {@code PortalAccessService} 早期直接拿
     * {@code ServerConfiguration.getData()} 拼 SQL，空值拼出 {@code create schema if not exists .sysaux}，
     * DuckDB 报 {@code Parser Error: syntax error at or near "."}，<b>整个实例起不来</b>
     * （异常从 {@code DBInstanceX} 构造一路冒到 {@code Main}）。</p>
     *
     * <p>断言三件事：实例能起来、实例名回落到 {@code memory}、两张内部表真的建在
     * {@code memory.sysaux} 下。</p>
     */
    @Test
    void managementServicesDefaultToMemoryWhenDataIsEmpty(@TempDir Path dataDir) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setPortX(0);                    // 打开管理接口，才会创建定义/门户访问两个服务
        cfg.setData("");                    // 模拟没传 --data
        cfg.setData_dir(dataDir.toString()); // 磁盘 data_dir + 空 data：不会 ATTACH 任何实例库
        cfg.setSqlHistory("OFF");
        cfg.setLog_level("WARN");

        // 修复前：这里直接抛 IllegalStateException("Failed to prepare sysaux.definitions ...")
        DBInstance instance = new DBInstance(cfg);
        instance.start();
        try {
            assert instance.instanceState.equalsIgnoreCase("RUNNING")
                    : "实例未启动成功: " + instance.instanceState;
            assert "memory".equals(instance.instanceName)
                    : "没有 --data 时实例库名必须是 memory，实际为 [" + instance.instanceName + "]";

            String url = "jdbc:postgresql://127.0.0.1:" + cfg.getPort() + "/";
            try (Connection conn = connect(url, "");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT count(*) FROM information_schema.tables "
                                 + "WHERE table_catalog = 'memory' AND table_schema = 'sysaux' "
                                 + "AND table_name IN ('definitions', 'portal_access')")) {
                assert rs.next() : "information_schema.tables 没有返回行";
                assert rs.getLong(1) == 2
                        : "memory.sysaux 下应同时存在 definitions 与 portal_access，实际命中 "
                                + rs.getLong(1) + " 张（内部服务建表失败）";
            }
        } finally {
            instance.stop();
        }
    }

    /**
     * 经 {@link DriverManager} 建连。
     *
     * <p>刻意走 {@code DriverManager} 而不是数据源：dbserver 的 test classpath 上同时有
     * {@code slackerdb-dbdriver}（经 {@code META-INF/services/java.sql.Driver} 自动注册）和标准
     * {@code org.postgresql.Driver}，而两个驱动的 {@code acceptsURL} 都只看 URL 形状、对
     * {@code jdbc:postgresql:} 都可能返回 true。因此能否连上，完全取决于先被轮到的 slackerdb
     * 驱动是否遵守 JDBC 规范——对不属于自己的 URL 返回 {@code null}，而不是抛异常。</p>
     *
     * <p>本用例因此同时充当该规范行为的回归测试：一旦 slackerdb 驱动的 {@code connect()} 又对
     * {@code jdbc:postgresql:} 抛 "Unable to parse URL"，这里的建连会立刻失败。</p>
     */
    private static Connection connect(String url, String database) throws SQLException {
        ensurePostgresDriverRegistered();
        return DriverManager.getConnection(url, "", "");
    }

    /** 显式注册标准 PostgreSQL 驱动，避免依赖 classpath 扫描顺序。 */
    private static void ensurePostgresDriverRegistered() {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException notFound) {
            assert false : "标准 PostgreSQL 驱动不在 test classpath 上: " + notFound.getMessage();
        }
    }
}
