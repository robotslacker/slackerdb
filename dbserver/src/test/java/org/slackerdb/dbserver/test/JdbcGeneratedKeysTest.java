package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：duckdb-java 两个**行为类** PR 在 dbdriver 上的等价能力。
 *
 * <p>这两个 PR 不是"元数据/SQL 方言"改动，而是通用 JDBC 行为，所以容易在
 * "DuckDB 版本升级清单"里被漏掉（参考树 2.0 里有，服务端用的 1.5.6 里也都有）：</p>
 *
 * <ul>
 *   <li><b>PR #569 “Support LibreOffice table browser”</b>（1.5.2 起）：
 *     <ol>
 *       <li>{@code getTableTypes()}/{@code getTables()} 把 {@code BASE TABLE} 改报成 JDBC 标准名
 *           {@code TABLE}，并把入参 {@code types} 反向映射；</li>
 *       <li>只读连接允许创建任意 (resultSetType, concurrency) 的语句（LibreOffice 会那样问）；</li>
 *       <li>{@code access_mode} URL 属性（READ_ONLY/READ_WRITE/AUTOMATIC）。</li>
 *     </ol>
 *     我们走 PG 协议：<b>(1) 等价满足</b>（表类型本来就报 TABLE）、<b>(2) 不适用</b>
 *     （结果集能力与"连接是否只读"无关）、<b>(3) 不适用</b>（只读是服务端事务语义，
 *     没有内嵌数据库文件要开）。</li>
 *   <li><b>PR #829 “RETURN_GENERATED_KEYS via DML RETURNING rewrite”</b>（1.5.6 起）：
 *     把 DML 改写成带 {@code RETURNING} 的形式，再用内存 ResultSet 充当 generated keys。
 *     我们的等价机制是 pgjdbc 自带的 {@code RETURNING} 追加（{@code Parser} +
 *     {@code PgStatement.wantsGeneratedKeys*}），由**服务端**回结果集。<b>目标一致、机制不同</b>；
 *     差异是我们不支持"按列下标"取 key（{@code prepareStatement(sql, int[])} 抛 NOT_IMPLEMENTED，
 *     这是上游 pgjdbc 的既定行为）。</li>
 * </ul>
 */
public class JdbcGeneratedKeysTest {
    static int dbPort;
    static DBInstance dbInstance;
    static final String DB = "jdbcgenkeys";
    static final String SCHEMA = "gk";
    static final String PROTOCOL = "slackerdb";

    @BeforeAll
    static void initAll() throws ServerException, SQLException {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setData(DB);
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("OFF");
        dbPort = serverConfiguration.getPort();

        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
            st.execute("CREATE SEQUENCE IF NOT EXISTS " + SCHEMA + ".seq_gk START 1");
            st.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA + ".t_gk ("
                    + "id BIGINT DEFAULT nextval('" + SCHEMA + ".seq_gk'), name VARCHAR)");
        }
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
        assert dbInstance.instanceState.equalsIgnoreCase("IDLE");
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                "jdbc:" + PROTOCOL + "://127.0.0.1:" + dbPort + "/" + DB, "", "");
    }

    // ==================================================================
    // PR #829 的等价能力：RETURN_GENERATED_KEYS + getGeneratedKeys()
    // ==================================================================

    @Test
    void supportsGetGeneratedKeysMatchesReality() throws Exception {
        try (Connection conn = connect()) {
            // 曾被改成 false，与实现（RETURNING 追加）自相矛盾：工具会据此放弃取主键
            assertTrue(conn.getMetaData().supportsGetGeneratedKeys(),
                    "驱动确实支持 RETURN_GENERATED_KEYS，DatabaseMetaData 必须如实报告");
        }
    }

    @Test
    void returnGeneratedKeysGivesTheInsertedKey() throws Exception {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO " + SCHEMA + ".t_gk (name) VALUES (?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "duck");
            assertEquals(1, ps.executeUpdate());

            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertNotNull(keys, "getGeneratedKeys() 不能返回 null");
                assertTrue(keys.next(), "应有一条 generated key（驱动改写成 RETURNING 后由服务端返回）");
                long id = keys.getLong(1);
                assertTrue(id >= 1, "自增 id 应被序列赋值，实际: " + id);
                assertFalse(keys.next(), "单行 INSERT 只应有一行 key");
            }
        }
    }

    @Test
    void returnGeneratedKeysByColumnNameWorks() throws Exception {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO " + SCHEMA + ".t_gk (name) VALUES (?)", new String[] {"id"})) {
            ps.setString(1, "goose");
            assertEquals(1, ps.executeUpdate());
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                assertTrue(keys.getLong(1) >= 1);
            }
        }
    }

    @Test
    void returnGeneratedKeysByColumnIndexIsRejected() throws Exception {
        try (Connection conn = connect()) {
            // 与上游 pgjdbc 一致：按列下标取 key 抛 NOT_IMPLEMENTED（duckdb-java #829 支持，我们不跟）
            SQLException e = assertThrows(SQLException.class, () -> conn.prepareStatement(
                    "INSERT INTO " + SCHEMA + ".t_gk (name) VALUES ('x')", new int[] {1}));
            assertTrue(String.valueOf(e.getMessage()).contains("autogenerated keys")
                            || String.valueOf(e.getMessage()).contains("not supported"),
                    "应为 NOT_IMPLEMENTED 语义，实际: " + e.getMessage());
        }
    }

    // ==================================================================
    // PR #569 的等价能力
    // ==================================================================

    @Test
    void getTableTypesUsesJdbcStandardNames() throws Exception {
        List<String> types = new ArrayList<>();
        try (Connection conn = connect();
             ResultSet rs = conn.getMetaData().getTableTypes()) {
            while (rs.next()) {
                types.add(rs.getString("TABLE_TYPE"));
            }
        }
        // #569 的核心诉求：不能报 "BASE TABLE"，要用 JDBC 标准名 "TABLE"
        assertTrue(types.contains("TABLE"), "getTableTypes 必须包含 TABLE，实际: " + types);
        assertFalse(types.contains("BASE TABLE"), "不能出现 BASE TABLE（LibreOffice 等工具只认 TABLE）");
        assertTrue(types.contains("VIEW"), "实际: " + types);
    }

    @Test
    void scrollableReadOnlyStatementWorks() throws Exception {
        // LibreOffice 的表格浏览器会请求可滚动结果集；我们的驱动走 PG 游标，应能工作
        try (Connection conn = connect();
             Statement st = conn.createStatement(
                     ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
             ResultSet rs = st.executeQuery("SELECT * FROM (VALUES (1), (2), (3)) t(i) ORDER BY i")) {
            assertTrue(rs.last(), "可滚动结果集应能直接定位到最后一行");
            assertEquals(3, rs.getInt(1));
            assertTrue(rs.absolute(1), "absolute(1) 应成功");
            assertEquals(1, rs.getInt(1));
            assertTrue(rs.previous() == false, "第一行再 previous 应返回 false");
        }
        // 能力声明必须与实现一致：之前只报 FORWARD_ONLY，等于告诉工具"别用滚动游标"
        try (Connection conn = connect()) {
            assertTrue(conn.getMetaData().supportsResultSetType(ResultSet.TYPE_SCROLL_INSENSITIVE),
                    "TYPE_SCROLL_INSENSITIVE 实际可用，DatabaseMetaData 必须如实报告");
            assertFalse(conn.getMetaData().supportsResultSetType(ResultSet.TYPE_SCROLL_SENSITIVE),
                    "TYPE_SCROLL_SENSITIVE 我们按 insensitive 实现，应报 false");
        }
    }

    @Test
    void updatableStatementCanBeCreatedButUpdatesAreRejected() throws Exception {
        // #569 让只读连接也接受 CONCUR_UPDATABLE；我们这条路径的既定取舍是：
        // 语句能创建（值合法），但一旦真的去改行就抛 SQLFeatureNotSupportedException
        // （可更新结果集整块裁剪，见 doc/README.md §7）
        try (Connection conn = connect();
             Statement st = conn.createStatement(
                     ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
             ResultSet rs = st.executeQuery("SELECT id FROM " + SCHEMA + ".t_gk LIMIT 1")) {
            assertDoesNotThrow(rs::next);
            assertThrows(SQLFeatureNotSupportedException.class, () -> rs.updateLong("id", 1L),
                    "可更新结果集已裁剪：updateXxx 必须明确抛不支持，而不是静默无效");
            assertFalse(conn.getMetaData().supportsResultSetConcurrency(
                            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE),
                    "既然不支持可更新结果集，DatabaseMetaData 必须报 false");
        }
    }
}
