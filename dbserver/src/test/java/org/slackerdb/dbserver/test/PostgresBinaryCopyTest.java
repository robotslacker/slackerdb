package org.slackerdb.dbserver.test;


import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.slackerdb.common.utils.DBUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public class PostgresBinaryCopyTest {
    private static final String URL = "jdbc:postgresql://192.168.40.129:5432/jtls_db"; // PostgreSQL 连接
    private static final String USER = "postgres";
    private static final String PASSWORD = "postgres";

    public static void main(String[] args) throws Exception {
        try (Connection conn = getConnection()) {
            createTestTable(conn); // 创建测试表
            List<Object[]> data = List.of(
                    new Object[]{1, "Alice", 25.5, new BigDecimal("12345.6789"), Timestamp.from(Instant.now()), true},
                    new Object[]{2, "Bob", 30.8, new BigDecimal("98765.4321"), Timestamp.from(Instant.now()), false}
            );

            byte[] binaryCopyData = generateBinaryCopyData(data);
            copyBinaryToPostgres(conn, binaryCopyData);

            queryAndPrintResults(conn); // 查询测试
        }
    }

    // **1️⃣ 连接 PostgreSQL**
    private static Connection getConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", USER);
        props.setProperty("password", PASSWORD);
        return DriverManager.getConnection(URL, props);
    }

    // **2️⃣ 创建测试表**
    private static void createTestTable(Connection conn) throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS test_binary_copy (
                id SERIAL PRIMARY KEY,
                name VARCHAR(50),
                age DOUBLE PRECISION,
                salary NUMERIC(10,4),
                created_at TIMESTAMP,
                is_active BOOLEAN
            )
            """;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            stmt.execute("delete from test_binary_copy");
        }
    }

    // **3️⃣ 生成 BINARY COPY 格式的数据**
    private static byte[] generateBinaryCopyData(List<Object[]> data) throws Exception {
        return DBUtil.convertPGRowToByte(data);
    }

    // **4️⃣ 使用 CopyManager 进行流式导入**
    private static void copyBinaryToPostgres(Connection conn, byte[] binaryCopyData) throws Exception {
        CopyManager copyManager = new CopyManager((BaseConnection) conn);
        try (InputStream binaryStream = new ByteArrayInputStream(binaryCopyData)) {
            copyManager.copyIn("COPY test_binary_copy FROM STDIN WITH (FORMAT BINARY)", binaryStream);
        }
    }

    // **5️⃣ 查询并打印导入的数据**
    private static void queryAndPrintResults(Connection conn) throws SQLException {
        String sql = "SELECT * FROM test_binary_copy";
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                System.out.printf("ID: %d, Name: %s, Age: %.2f, Salary: %s, CreatedAt: %s, Active: %b%n",
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getDouble("age"),
                        rs.getBigDecimal("salary"),
                        rs.getTimestamp("created_at"),
                        rs.getBoolean("is_active"));
            }
        }
    }
}
