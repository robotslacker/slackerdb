package org.slackerdb.dbserver.test;

import org.duckdb.DuckDBChunkedResult;
import org.duckdb.DuckDBPreparedStatement;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.sql.CopyOutChunkReader;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CopyOutChunkReader} 的单元验收。
 *
 * <p>两个关键契约：</p>
 * <ol>
 *   <li><b>类型白名单</b>：chunk 读取器只覆盖部分类型；不支持的字段必须被识别出来，
 *       让调用方回退到 ResultSet 路径。</li>
 *   <li><b>文本形态零漂移</b>：同一个值从 chunk 读出的字符串必须与
 *       {@code ResultSet.getString()} 完全相同，否则 COPY 的往返会被破坏。</li>
 * </ol>
 */
public class CopyOutChunkReaderTest {

    private static Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:duckdb:");
    }

    private static boolean supports(Connection conn, String expression) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT " + expression + " AS c")) {
            return CopyOutChunkReader.supports(ps.getMetaData());
        }
    }

    // ================================================================
    // 一、类型白名单
    // ================================================================

    @Test
    void supportedTypesAreAccepted() throws Exception {
        try (Connection conn = connect()) {
            assertTrue(supports(conn, "1::INTEGER"), "INTEGER");
            assertTrue(supports(conn, "250::UTINYINT"), "UTINYINT");
            assertTrue(supports(conn, "65000::USMALLINT"), "USMALLINT");
            assertTrue(supports(conn, "4000000000::UINTEGER"), "UINTEGER");
            assertTrue(supports(conn, "18446744073709551615::UBIGINT"), "UBIGINT");
            assertTrue(supports(conn, "1::HUGEINT"), "HUGEINT");
            assertTrue(supports(conn, "1::UHUGEINT"), "UHUGEINT");
            assertTrue(supports(conn, "1.5::DECIMAL(18,3)"), "DECIMAL");
            assertTrue(supports(conn, "'x'::VARCHAR"), "VARCHAR");
            assertTrue(supports(conn, "DATE '2020-01-01'"), "DATE");
            assertTrue(supports(conn, "TIMESTAMP '2020-01-01 00:00:00'"), "TIMESTAMP");
            assertTrue(supports(conn, "TIMESTAMP_NS '2020-01-01 00:00:00'"), "TIMESTAMP_NS");
            assertTrue(supports(conn, "TIMESTAMPTZ '2020-01-01 00:00:00+08'"), "TIMESTAMPTZ");
            assertTrue(supports(conn, "'{}'::JSON"), "JSON 以 VARCHAR 向量暴露，读取结果与 ResultSet 一致");
        }
    }

    @Test
    void unsupportedTypesAreRejected() throws Exception {
        try (Connection conn = connect()) {
            assertFalse(supports(conn, "CAST(NULL AS BLOB)"), "BLOB");
            assertFalse(supports(conn, "TIME '12:34:56'"), "TIME");
            assertFalse(supports(conn, "TIMETZ '12:34:56+08'"), "TIMETZ");
            assertFalse(supports(conn, "'1 day'::INTERVAL"), "INTERVAL");
            assertFalse(supports(conn, "'0101'::BIT"), "BIT");
            assertFalse(supports(conn, "gen_random_uuid()"), "UUID");
            assertFalse(supports(conn, "[1, 2]"), "LIST");
            assertFalse(supports(conn, "{'a': 1}"), "STRUCT");
            assertFalse(supports(conn, "MAP {'a': 1}"), "MAP");

            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TYPE mood AS ENUM ('sad', 'ok')");
            }
            assertFalse(supports(conn, "NULL::mood"), "ENUM");
        }
    }

    @Test
    void decimalTypeNameWithPrecisionIsSupported() {
        assertTrue(CopyOutChunkReader.supportsTypeName("DECIMAL(18,3)"));
        assertTrue(CopyOutChunkReader.supportsTypeName("decimal(38,0)"));
        assertFalse(CopyOutChunkReader.supportsTypeName("BLOB"));
        assertFalse(CopyOutChunkReader.supportsTypeName(null));
    }

    // ================================================================
    // 二、文本形态与 ResultSet.getString 逐值一致
    // ================================================================

    @Test
    void chunkTextMatchesResultSetText() throws Exception {
        String sql = "SELECT "
                + "true AS c_boolean, "
                + "(-5)::TINYINT AS c_tinyint, "
                + "(-300)::SMALLINT AS c_smallint, "
                + "(-70000)::INTEGER AS c_integer, "
                + "(-9000000000)::BIGINT AS c_bigint, "
                + "(250)::UTINYINT AS c_utinyint, "
                + "(65000)::USMALLINT AS c_usmallint, "
                + "(4000000000)::UINTEGER AS c_uinteger, "
                + "(18446744073709551615)::UBIGINT AS c_ubigint, "
                + "(170141183460469231731687303715884105727)::HUGEINT AS c_hugeint, "
                + "(340282366920938463463374607431768211455)::UHUGEINT AS c_uhugeint, "
                + "(1.25)::FLOAT AS c_float, "
                + "(1.23456789012345)::DOUBLE AS c_double, "
                + "(123.450)::DECIMAL(18,3) AS c_decimal, "
                + "(12345678901234567890123456789012345678)::DECIMAL(38,0) AS c_decimal38, "
                + "'hello'::VARCHAR AS c_varchar, "
                + "DATE '2020-01-02' AS c_date, "
                + "TIMESTAMP '2020-01-02 03:04:05.123456' AS c_ts, "
                + "TIMESTAMP_S '2020-01-02 03:04:05' AS c_ts_s, "
                + "TIMESTAMP_MS '2020-01-02 03:04:05.123' AS c_ts_ms, "
                + "TIMESTAMP_NS '2020-01-02 03:04:05.123456789' AS c_ts_ns, "
                + "TIMESTAMPTZ '2020-01-02 03:04:05.123456+08' AS c_tstz, "
                + "'{\"b\": 1, \"a\": 2}'::JSON AS c_json, "
                + "'NaN'::FLOAT AS c_nan, "
                + "'Infinity'::DOUBLE AS c_positive_infinity, "
                + "'-Infinity'::DOUBLE AS c_negative_infinity, "
                + "NULL::INTEGER AS c_null";

        try (Connection conn = connect()) {
            List<String> expected = new ArrayList<>();
            int columnCount;
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                ResultSetMetaData metaData = rs.getMetaData();
                columnCount = metaData.getColumnCount();
                for (int i = 1; i <= columnCount; i++) {
                    expected.add(rs.getString(i));
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(sql);
                 DuckDBChunkedResult chunked =
                         ps.unwrap(DuckDBPreparedStatement.class).query();
                 CopyOutChunkReader reader = new CopyOutChunkReader(chunked, columnCount)) {

                assertTrue(reader.nextChunk(), "单行查询应至少有一个数据块");
                assertEquals(columnCount, reader.columnCount());
                assertEquals(1, reader.rowCount());

                for (int i = 0; i < columnCount; i++) {
                    assertEquals(expected.get(i), reader.getString(i, 0),
                            "第 " + (i + 1) + " 列的 chunk 文本与 ResultSet 不一致");
                }
                assertFalse(reader.nextChunk(), "只有一行，不应有第二个数据块");
            }
        }
    }
}
