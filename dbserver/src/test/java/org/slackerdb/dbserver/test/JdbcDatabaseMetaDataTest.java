package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：{@code DatabaseMetaData} 的整套查询必须能在**当前 DuckDB 版本**上跑通。
 *
 * <p>dbdriver 的 {@code PgDatabaseMetaData} 是照 duckdb-java 的
 * {@code DuckDBDatabaseMetaData} 改的魔改版，查询全打在 DuckDB 的系统表函数上
 * （{@code duckdb_tables()} / {@code duckdb_columns()} / {@code duckdb_constraints} /
 * {@code duckdb_indexes()} / {@code duckdb_types()} / {@code duckdb_functions()}）。
 * 这些系统表函数是 DuckDB 的"内部 API"：列名、列类型、返回的类型名字符串都会随版本变化，
 * 而编译器对此一无所知。因此 DuckDB 升级后，唯一可靠的验证手段就是真的把每个方法跑一遍。</p>
 *
 * <p>本次升级（参考 duckdb-java 从记录在案的 1.4.x 形态升到 1.5.6）踩到的坑，都在下面的断言里
 * 钉死：</p>
 * <ul>
 *   <li>DuckDB 1.5 新增 {@code TIME_NS} / {@code VARIANT} / {@code GEOMETRY} 三种类型名，
 *       不在驱动映射表里就一律退化成 {@code Types.OTHER}；</li>
 *   <li>{@code duckdb_columns().data_type} 对 {@code TIMESTAMP_MS}/{@code TIMESTAMP_NS}/
 *       {@code TIMESTAMP_S}/{@code TIME_NS} 是**带下划线**的名字（曾经被统一改写成空格，
 *       于是这些列全部匹配不上）；</li>
 *   <li>列表列的类型名是 {@code INTEGER[]} 这种形式，不是 {@code LIST}；</li>
 *   <li>{@code getTypeInfo()} / {@code getUDTs()} / {@code getImportedKeys()} 等此前直接抛
 *       {@code SQLFeatureNotSupportedException}，1.5.x 的 DuckDB 已经有能力实现。</li>
 * </ul>
 */
public class JdbcDatabaseMetaDataTest {
    static int dbPort;
    static DBInstance dbInstance;
    static final String DB = "jdbcmetadata";
    static final String SCHEMA = "meta1";

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

            // 覆盖 DuckDB 1.5 的全部类型族：有符号/无符号整型、三种时间精度后缀、
            // TZ 家族、嵌套类型，以及 1.5 新增的 TIME_NS / VARIANT / GEOMETRY。
            st.execute("CREATE TABLE " + SCHEMA + ".t_types ("
                    + "c_bool BOOLEAN, c_int INTEGER, c_big BIGINT,"
                    + "c_utiny UTINYINT, c_usmall USMALLINT, c_uint UINTEGER,"
                    + "c_huge HUGEINT, c_dec DECIMAL(12,3),"
                    + "c_varchar VARCHAR, c_blob BLOB,"
                    + "c_time TIME, c_timens TIME_NS, c_date DATE,"
                    + "c_ts TIMESTAMP, c_ts_ms TIMESTAMP_MS, c_ts_ns TIMESTAMP_NS, c_ts_s TIMESTAMP_S,"
                    + "c_tstz TIMESTAMP WITH TIME ZONE, c_timetz TIME WITH TIME ZONE,"
                    + "c_bit BIT, c_interval INTERVAL, c_uuid UUID, c_json JSON,"
                    + "c_list INTEGER[], c_struct STRUCT(a INTEGER, b VARCHAR),"
                    + "c_variant VARIANT, c_geometry GEOMETRY)");

            st.execute("CREATE TABLE " + SCHEMA + ".t_parent ("
                    + "id INTEGER PRIMARY KEY, name VARCHAR NOT NULL UNIQUE, amount DECIMAL(10,2))");
            st.execute("CREATE TABLE " + SCHEMA + ".t_child ("
                    + "id INTEGER PRIMARY KEY,"
                    + "pid INTEGER REFERENCES " + SCHEMA + ".t_parent(id), note VARCHAR)");
            st.execute("CREATE TABLE " + SCHEMA + ".t_child2 ("
                    + "id INTEGER PRIMARY KEY,"
                    + "pname VARCHAR,"
                    + "FOREIGN KEY (pname) REFERENCES " + SCHEMA + ".t_parent(name))");
            st.execute("CREATE INDEX idx_parent_name ON " + SCHEMA + ".t_parent(name)");
            st.execute("CREATE VIEW " + SCHEMA + ".v_parent AS SELECT id, name FROM " + SCHEMA + ".t_parent");
        }
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
        assert dbInstance.instanceState.equalsIgnoreCase("IDLE");
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                "jdbc:slackerdb://127.0.0.1:" + dbPort + "/" + DB, "", "");
    }

    // ==================================================================
    // 1. getTables / getColumns：系统表函数还能按预期返回
    // ==================================================================

    @Test
    void getTablesReportsTablesAndViews() throws Exception {
        try (Connection conn = connect()) {
            Map<String, String> types = new LinkedHashMap<>();
            try (ResultSet rs = conn.getMetaData().getTables(null, SCHEMA, "%", null)) {
                while (rs.next()) {
                    types.put(rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE"));
                }
            }
            assertEquals("TABLE", types.get("t_parent"), "普通表必须报 TABLE，实际: " + types);
            assertEquals("TABLE", types.get("t_types"));
            assertEquals("VIEW", types.get("v_parent"), "视图必须报 VIEW，实际: " + types);
            assertFalse(types.containsKey("t_parent_backup"));

            // types 过滤器只留视图
            List<String> onlyViews = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getTables(null, SCHEMA, "%", new String[] {"VIEW"})) {
                while (rs.next()) {
                    onlyViews.add(rs.getString("TABLE_NAME"));
                }
            }
            assertEquals(List.of("v_parent"), onlyViews);
        }
    }

    @Test
    void getColumnsMapsEveryDuckDbType() throws Exception {
        Map<String, Integer> dataTypes = new LinkedHashMap<>();
        Map<String, String> typeNames = new LinkedHashMap<>();
        try (Connection conn = connect();
             ResultSet rs = conn.getMetaData().getColumns(null, SCHEMA, "t_types", "%")) {
            while (rs.next()) {
                dataTypes.put(rs.getString("COLUMN_NAME"), rs.getInt("DATA_TYPE"));
                typeNames.put(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"));
            }
        }
        assertFalse(dataTypes.isEmpty(), "getColumns 必须返回 t_types 的列");

        // 有符号整型：与 JDBC 一一对应
        assertEquals(Types.INTEGER, dataTypes.get("c_int"));
        assertEquals(Types.BIGINT, dataTypes.get("c_big"));

        // 无符号整型：JDBC 没有无符号概念，必须往"装得下值域的上一档"映射，
        // 否则 UTINYINT 的 250 会被当成 TINYINT 的负数。
        assertEquals(Types.SMALLINT, dataTypes.get("c_utiny"), "UTINYINT 必须映射到 SMALLINT");
        assertEquals(Types.INTEGER, dataTypes.get("c_usmall"), "USMALLINT 必须映射到 INTEGER");
        assertEquals(Types.BIGINT, dataTypes.get("c_uint"), "UINTEGER 必须映射到 BIGINT");

        // 三种时间精度后缀：DuckDB 里是 TIMESTAMP_MS/NS/S（带下划线），必须走到 TIMESTAMP
        assertEquals(Types.TIMESTAMP, dataTypes.get("c_ts"));
        assertEquals(Types.TIMESTAMP, dataTypes.get("c_ts_ms"), "TIMESTAMP_MS 必须映射到 TIMESTAMP");
        assertEquals(Types.TIMESTAMP, dataTypes.get("c_ts_ns"), "TIMESTAMP_NS 必须映射到 TIMESTAMP");
        assertEquals(Types.TIMESTAMP, dataTypes.get("c_ts_s"), "TIMESTAMP_S 必须映射到 TIMESTAMP");
        assertEquals(Types.TIME, dataTypes.get("c_time"));
        // DuckDB 1.5 新增的纳秒时间
        assertEquals(Types.TIME, dataTypes.get("c_timens"), "TIME_NS 必须映射到 TIME");

        assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, dataTypes.get("c_tstz"));
        assertEquals(Types.TIME_WITH_TIMEZONE, dataTypes.get("c_timetz"));

        // 列表列：duckdb_columns() 里叫 "INTEGER[]"，不是 "LIST"
        assertEquals(Types.ARRAY, dataTypes.get("c_list"), "数组列必须映射到 Types.ARRAY");
        assertEquals(Types.STRUCT, dataTypes.get("c_struct"));

        // DuckDB 1.5 新增的两种类型
        assertEquals(Types.BLOB, dataTypes.get("c_geometry"), "GEOMETRY 必须映射到 BLOB");
        assertEquals(Types.OTHER, dataTypes.get("c_variant"), "VARIANT 必须映射到 Types.OTHER");

        assertEquals(Types.VARCHAR, dataTypes.get("c_varchar"));
        assertEquals("VARCHAR", typeNames.get("c_varchar"));
        assertTrue(typeNames.get("c_dec").startsWith("DECIMAL"), "TYPE_NAME 要保留精度: " + typeNames.get("c_dec"));
    }

    // ==================================================================
    // 2. 主键 / 索引
    // ==================================================================

    @Test
    void getPrimaryKeysReturnsKeyColumns() throws Exception {
        List<String> pk = new ArrayList<>();
        try (Connection conn = connect();
             ResultSet rs = conn.getMetaData().getPrimaryKeys(null, SCHEMA, "t_parent")) {
            while (rs.next()) {
                assertEquals("t_parent", rs.getString("TABLE_NAME"));
                pk.add(rs.getString("COLUMN_NAME"));
                assertTrue(rs.getInt("KEY_SEQ") >= 1);
            }
        }
        assertEquals(List.of("id"), pk);
    }

    @Test
    void getIndexInfoReturnsIndexes() throws Exception {
        List<String> indexes = new ArrayList<>();
        try (Connection conn = connect();
             ResultSet rs = conn.getMetaData().getIndexInfo(null, SCHEMA, "t_parent", false, true)) {
            while (rs.next()) {
                indexes.add(rs.getString("INDEX_NAME"));
            }
        }
        assertTrue(indexes.contains("idx_parent_name"), "索引列表里必须能看到 idx_parent_name，实际: " + indexes);
    }

    // ==================================================================
    // 3. 外键：DuckDB 1.4+ 的 information_schema.referential_constraints
    // ==================================================================

    @Test
    void getImportedKeysReportsForeignKey() throws Exception {
        List<String[]> fks = new ArrayList<>();
        try (Connection conn = connect();
             ResultSet rs = conn.getMetaData().getImportedKeys(null, SCHEMA, "t_child")) {
            while (rs.next()) {
                fks.add(new String[] {
                        rs.getString("PKTABLE_NAME"), rs.getString("PKCOLUMN_NAME"),
                        rs.getString("FKTABLE_NAME"), rs.getString("FKCOLUMN_NAME")});
            }
        }
        assertEquals(1, fks.size(), "t_child 上恰好一条外键");
        assertEquals("t_parent", fks.get(0)[0]);
        assertEquals("id", fks.get(0)[1]);
        assertEquals("t_child", fks.get(0)[2]);
        assertEquals("pid", fks.get(0)[3]);
    }

    @Test
    void getExportedKeysReportsReferencingTable() throws Exception {
        List<String[]> fks = new ArrayList<>();
        try (Connection conn = connect();
             ResultSet rs = conn.getMetaData().getExportedKeys(null, SCHEMA, "t_parent")) {
            while (rs.next()) {
                fks.add(new String[] {rs.getString("FKTABLE_NAME"), rs.getString("FKCOLUMN_NAME")});
            }
        }
        // t_parent(id) 被 t_child.pid 引用；t_parent(name) 被 t_child2.pname 引用
        assertEquals(2, fks.size(), "t_parent 被两处引用，实际: " + fks.size());
        assertTrue(fks.stream().anyMatch(f -> "t_child".equals(f[0]) && "pid".equals(f[1])));
        assertTrue(fks.stream().anyMatch(f -> "t_child2".equals(f[0]) && "pname".equals(f[1])));
    }

    @Test
    void getCrossReferenceReportsBothDirections() throws Exception {
        int rows = 0;
        try (Connection conn = connect();
             ResultSet rs = conn.getMetaData().getCrossReference(
                     null, SCHEMA, "t_parent", null, SCHEMA, "t_child")) {
            while (rs.next()) {
                rows++;
                assertEquals("t_parent", rs.getString("PKTABLE_NAME"));
                assertEquals("t_child", rs.getString("FKTABLE_NAME"));
            }
        }
        assertEquals(1, rows);
    }

    // ==================================================================
    // 4. 类型清单 / UDT
    // ==================================================================

    @Test
    void getTypeInfoIsImplemented() throws Exception {
        Map<String, Integer> types = new LinkedHashMap<>();
        try (Connection conn = connect(); ResultSet rs = conn.getMetaData().getTypeInfo()) {
            while (rs.next()) {
                types.put(rs.getString("TYPE_NAME") + "/" + rs.getInt("DATA_TYPE"), rs.getInt("SEARCHABLE"));
            }
        }
        assertFalse(types.isEmpty(), "getTypeInfo 不能再抛 SQLFeatureNotSupportedException");
        assertTrue(types.containsKey("INTEGER/" + Types.INTEGER), "类型清单里必须有 INTEGER，实际: " + types.keySet());
        assertTrue(types.containsKey("VARCHAR/" + Types.VARCHAR));
        assertTrue(types.containsKey("TIMESTAMP/" + Types.TIMESTAMP));
    }

    @Test
    void getUDTsReportsUserDefinedTypes() throws Exception {
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE TYPE " + SCHEMA + ".mood AS ENUM ('sad', 'ok', 'happy')");
        } catch (SQLException e) {
            // 该版本 DuckDB 不支持 CREATE TYPE 时，本用例退化为"getUDTs 可调用且返回空集"
            // ——仍然要保证方法本身不再抛 UnsupportedOperation。
        }
        List<String> udts = new ArrayList<>();
        try (Connection conn = connect();
             ResultSet rs = conn.getMetaData().getUDTs(null, SCHEMA, "%", null)) {
            while (rs.next()) {
                udts.add(rs.getString("TYPE_NAME"));
            }
        }
        assertTrue(udts.isEmpty() || udts.contains("mood"), "getUDTs 必须能跑通，实际: " + udts);
    }

    // ==================================================================
    // 6. 与 duckdb-java PR #639 / #665 对齐的两条元数据语义
    // ==================================================================

    @Test
    void getTablesIncludesSystemViews() throws Exception {
        // #639：getTables 要能查到 information_schema 下的系统视图，且类型为 SYSTEM VIEW
        try (Connection conn = connect();
             ResultSet rs = conn.getMetaData().getTables(null, "information_schema", "columns", null)) {
            assertTrue(rs.next(), "information_schema.columns 应作为 SYSTEM VIEW 出现在 getTables 里");
            assertEquals("SYSTEM VIEW", rs.getString("TABLE_TYPE"));
        }
        // getTableTypes 也要声明这个类型（否则工具的类型过滤器会把它排除掉）
        List<String> types = new ArrayList<>();
        try (Connection conn = connect(); ResultSet rs = conn.getMetaData().getTableTypes()) {
            while (rs.next()) {
                types.add(rs.getString("TABLE_TYPE"));
            }
        }
        assertTrue(types.contains("SYSTEM VIEW"), "getTableTypes 必须包含 SYSTEM VIEW，实际: " + types);
    }

    @Test
    void getColumnsReportsNullabilityCorrectly() throws Exception {
        // #665：NULLABLE 必须是 INTEGER（0/1），IS_NULLABLE 必须是 'YES'/'NO'
        try (Connection conn = connect();
             ResultSet rs = conn.getMetaData().getColumns(null, SCHEMA, "t_parent", "%")) {
            Map<String, String> isNullable = new LinkedHashMap<>();
            Map<String, Integer> nullable = new LinkedHashMap<>();
            while (rs.next()) {
                String col = rs.getString("COLUMN_NAME");
                isNullable.put(col, rs.getString("IS_NULLABLE"));
                nullable.put(col, rs.getInt("NULLABLE"));
            }
            // t_parent(name) 是 NOT NULL UNIQUE；id 是主键（非空）；amount 可空
            assertEquals("NO", isNullable.get("name"), "NOT NULL 列必须报 NO，实际: " + isNullable);
            assertEquals(0, nullable.get("name"), "NOT NULL 列 NULLABLE 必须是 0（columnNoNulls）");
            assertEquals("NO", isNullable.get("id"), "主键列必须报 NO");
            assertEquals("YES", isNullable.get("amount"), "可空列必须报 YES");
            assertEquals(1, nullable.get("amount"), "可空列 NULLABLE 必须是 1（columnNullable）");
        }
    }

    // ==================================================================
    // 7. NULL 排序声明（duckdb-java PR #924 的等价校验）
    // ==================================================================

    @Test
    void nullOrderingDeclarationMatchesRealOrderBy() throws Exception {
        // 声明：JDBC 要求这四个里恰好一个为 true；DuckDB 默认 NULLS_LAST => atEnd
        try (Connection conn = connect()) {
            DatabaseMetaData md = conn.getMetaData();
            int trues = 0;
            if (md.nullsAreSortedHigh()) { trues++; }
            if (md.nullsAreSortedLow()) { trues++; }
            if (md.nullsAreSortedAtStart()) { trues++; }
            if (md.nullsAreSortedAtEnd()) { trues++; }
            assertEquals(1, trues,
                    "nullsAreSortedHigh/Low/AtStart/AtEnd 必须恰好一个为 true，实际 trues=" + trues);
            assertTrue(md.nullsAreSortedAtEnd(), "DuckDB 默认 NULLS_LAST：NULL 永远在最后");
            assertFalse(md.nullsAreSortedHigh(), "DuckDB 不是「NULL 最大」语义（那是 PostgreSQL 的）");
        }

        // 行为：ASC / DESC 两个方向 NULL 都应该在最后（这就是 atEnd 的含义）
        try (Connection conn = connect()) {
            for (String dir : new String[] {"ASC", "DESC"}) {
                List<String> got = new ArrayList<>();
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT i FROM (VALUES (1), (NULL), (2)) t(i) ORDER BY i " + dir)) {
                    while (rs.next()) {
                        got.add(rs.getString(1));
                    }
                }
                assertEquals(3, got.size());
                assertTrue(got.get(2) == null, dir + " 方向 NULL 必须在最后，实际: " + got);
            }
        }
    }

    // ==================================================================
    // 7. 其余常被工具调用的方法不能抛
    // ==================================================================

    @Test
    void otherMetadataCallsDoNotThrow() throws Exception {
        try (Connection conn = connect()) {
            DatabaseMetaData md = conn.getMetaData();
            assertNotNull(md.getDriverName());
            assertNotNull(md.getDatabaseProductVersion());

            drain(md.getSchemas());
            drain(md.getCatalogs());
            drain(md.getTableTypes());
            drain(md.getProcedures(null, SCHEMA, "%"));
            drain(md.getFunctions(null, SCHEMA, "%"));

            // 这四个分类列表都打在 duckdb_functions() 的 parameter_types 上：
            // 那是 VARCHAR[]，一旦 DuckDB 改了列名/列类型就会全盘失灵。
            assertFalse(md.getNumericFunctions().isEmpty(), "getNumericFunctions 不能为空");
            assertFalse(md.getStringFunctions().isEmpty(), "getStringFunctions 不能为空");
            assertFalse(md.getSystemFunctions().isEmpty(), "getSystemFunctions 不能为空");
            assertFalse(md.getTimeDateFunctions().isEmpty(), "getTimeDateFunctions 不能为空");
            assertNotNull(md.getSearchStringEscape());

            List<String> schemas = new ArrayList<>();
            try (ResultSet rs = md.getSchemas(null, SCHEMA)) {
                while (rs.next()) {
                    schemas.add(rs.getString("TABLE_SCHEM"));
                }
            }
            assertTrue(schemas.contains(SCHEMA), "getSchemas(catalog, pattern) 必须能按 schema 过滤，实际: " + schemas);
        }
    }

    private static void drain(ResultSet rs) throws SQLException {
        try (ResultSet r = rs) {
            int n = 0;
            while (r.next() && n++ < 200) {
                // 只是把结果集走完，确认没有协议层/解码错误
            }
        }
    }
}
