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
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.TimeZone;

/**
 * H2 回归测试：结果集行编码（{@code RowEncoder}）。
 *
 * <p>覆盖改造后最容易出问题的两点：</p>
 * <ol>
 *   <li>空值判定由"每个单元格 {@code getObject(i) == null}"改成"按类型的 getter + {@code wasNull()}"，
 *       必须保证每种类型的 NULL 仍然以 SQL NULL 返回，而不是 0 / false / 空串；</li>
 *   <li>{@code TIME} 类型的长度缺陷修复：{@code LocalTime.toString()} 在秒为 0 时只输出 "HH:mm"，
 *       原实现却固定声明 8 字节，会导致 DataRow 长度与实际内容不一致（客户端解析错位）。
 *       现在补齐为 "HH:mm:ss" 并按实际字节数声明长度。</li>
 * </ol>
 *
 * <p>同时验证 extended 协议（PreparedStatement）与 simple query（Statement）两条路径，
 * 因为两者使用不同的 formatCode 策略（二进制 / 全文本）。</p>
 */
public class RowEncoderTest {
    static int dbPort;
    static DBInstance dbInstance;
    static final String protocol = "postgresql";

    @BeforeAll
    static void initAll() throws ServerException {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setData("enc");
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("OFF");
        dbPort = serverConfiguration.getPort();

        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
        assert dbInstance.instanceState.equalsIgnoreCase("IDLE");
    }

    private static Connection connect() throws Exception {
        Connection conn = DriverManager.getConnection(
                "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/enc", "", "");
        conn.setAutoCommit(false);
        return conn;
    }

    /**
     * 覆盖 RowEncoder 的每一个类型分支：非空值正确、NULL 值仍为 NULL。
     */
    @Test
    void allSupportedTypesRoundTripWithNulls() throws Exception {
        try (Connection conn = connect()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE OR REPLACE TABLE encAll (" +
                        "id INTEGER, " +
                        "c_smallint SMALLINT, " +
                        "c_integer INTEGER, " +
                        "c_bigint BIGINT, " +
                        "c_varchar VARCHAR, " +
                        "c_interval INTERVAL, " +
                        "c_date DATE, " +
                        "c_boolean BOOLEAN, " +
                        "c_float FLOAT, " +
                        "c_double DOUBLE, " +
                        "c_timestamp TIMESTAMP, " +
                        "c_time TIME, " +
                        "c_timestamptz TIMESTAMPTZ, " +
                        "c_bit BIT, " +
                        "c_uinteger UINTEGER, " +
                        "c_hugeint HUGEINT, " +
                        "c_ubigint UBIGINT, " +
                        "c_decimal DECIMAL(10,2), " +
                        "c_numeric NUMERIC(12,3))");

                // id=1 全字段非空；id=2 除了 id 以外全部为 NULL；id=3 只填 time（秒非 0 的对照样本）
                stmt.execute("""
                        INSERT INTO encAll VALUES (
                            1, 1, 2, 3, 'abc', INTERVAL 1 DAY, DATE '2020-01-02', TRUE,
                            1.5, 2.5, TIMESTAMP '2020-01-02 03:04:05.678',
                            TIME '02:14:00', TIMESTAMPTZ '2020-01-02 03:04:05.678+00',
                            '1010'::BIT, 10::UINTEGER, 20::HUGEINT, 30::UBIGINT,
                            123.45, 67.890)
                        """);
                stmt.execute("INSERT INTO encAll (id) VALUES (2)");
                stmt.execute("INSERT INTO encAll (id, c_time) VALUES (3, TIME '02:14:34')");
            }
            conn.commit();

            verify(conn, true);
            verify(conn, false);

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE encAll");
            }
            conn.commit();
        }
    }

    private void verify(Connection conn, boolean extendedProtocol) throws Exception {
        String sql = "SELECT * FROM encAll ORDER BY id";
        // 两条路径：PreparedStatement 走 extended 协议（定长类型为二进制），Statement 走 simple query（全文本）
        try (Statement stmt = extendedProtocol ? conn.prepareStatement(sql) : conn.createStatement();
             ResultSet rs = extendedProtocol ? ((PreparedStatement) stmt).executeQuery() : stmt.executeQuery(sql)) {

            ResultSetMetaData md = rs.getMetaData();
            int columnCount = md.getColumnCount();

            // ---- id = 1：全部非空 ----
            assert rs.next();
            assert rs.getInt("id") == 1;
            assert rs.getShort("c_smallint") == 1;
            assert rs.getInt("c_integer") == 2;
            assert rs.getLong("c_bigint") == 3;
            assert "abc".equals(rs.getString("c_varchar"));
            assert rs.getString("c_interval") != null;
            assert "2020-01-02".equals(rs.getDate("c_date").toString());
            assert rs.getBoolean("c_boolean");
            assert Math.abs(rs.getFloat("c_float") - 1.5f) < 0.0001;
            assert rs.getDouble("c_double") == 2.5;
            assert rs.getTimestamp("c_timestamp").toString().startsWith("2020-01-02 03:04:05.678");
            // H2 修复点：秒为 0 的 TIME 也必须是合法的 "HH:mm:ss"
            assert "02:14:00".equals(rs.getTime("c_time").toString())
                    : "unexpected c_time = " + rs.getTime("c_time");
            assert rs.getTimestamp("c_timestamptz") != null;
            assert rs.getString("c_bit") != null && rs.getString("c_bit").matches("[01]+")
                    : "unexpected c_bit = " + rs.getString("c_bit");
            assert rs.getLong("c_uinteger") == 10;
            assert rs.getLong("c_hugeint") == 20;
            assert rs.getLong("c_ubigint") == 30;
            assert rs.getBigDecimal("c_decimal").compareTo(new java.math.BigDecimal("123.45")) == 0;
            assert rs.getBigDecimal("c_numeric").compareTo(new java.math.BigDecimal("67.890")) == 0;

            // ---- id = 2：除 id 外全部为 NULL ----
            assert rs.next();
            assert rs.getInt("id") == 2;
            for (int i = 2; i <= columnCount; i++) {
                assert rs.getObject(i) == null
                        : "column " + md.getColumnName(i) + " (" + md.getColumnTypeName(i)
                        + ") should be NULL but was " + rs.getObject(i);
                assert rs.wasNull() : "wasNull() should be true for column " + md.getColumnName(i);
                assert md.getColumnName(i) != null;
            }

            // ---- id = 3：秒非 0 的 TIME 对照 ----
            assert rs.next();
            assert rs.getInt("id") == 3;
            assert "02:14:34".equals(rs.getTime("c_time").toString())
                    : "unexpected c_time = " + rs.getTime("c_time");

            assert !rs.next();
        }
    }

    /**
     * 未识别类型仍按文本返回，且不得因为"每单元格一次 WARN"而拖慢——此处只验证前者，
     * 后者由 {@code RowEncoder} 在构造阶段一次性告警保证。
     */
    @Test
    void unknownTypeFallsBackToText() throws Exception {
        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT [1, 2, 3] AS arr, 'x' AS s");
                 ResultSet rs = ps.executeQuery()) {
                assert rs.next();
                assert rs.getString("s").equals("x");
                assert rs.getString("arr") != null : "unregistered type should still be returned as text";
            }
        }
    }
}
