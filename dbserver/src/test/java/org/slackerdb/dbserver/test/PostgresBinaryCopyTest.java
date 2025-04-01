package org.slackerdb.dbserver.test;


import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;

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
        }
    }

    // **3️⃣ 生成 BINARY COPY 格式的数据**
    private static byte[] generateBinaryCopyData(List<Object[]> data) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        // **写入 PostgreSQL BINARY COPY 头**
        output.write(new byte[]{0x50, 0x47, 0x43, 0x4F, 0x50, 0x59, 0x0A, (byte) 0xFF, 0x0D, 0x0A, 0x00});
        output.write(intToBytes(0)); // Flags
        output.write(intToBytes(0)); // Header extension area

        // **写入数据**
        for (Object[] row : data) {
            output.write(shortToBytes((short) row.length)); // 列数

            for (Object value : row) {
                if (value == null) {
                    output.write(intToBytes(-1)); // NULL
                } else if (value instanceof Integer) {
                    output.write(intToBytes(4)); // 长度
                    output.write(intToBytes((Integer) value));
                } else if (value instanceof String) {
                    byte[] strBytes = ((String) value).getBytes(StandardCharsets.UTF_8);
                    output.write(intToBytes(strBytes.length));
                    output.write(strBytes);
                } else if (value instanceof Double) {
                    output.write(intToBytes(8));
                    output.write(doubleToBytes((Double) value));
                } else if (value instanceof BigDecimal) {
                    byte[] decimalBytes = encodeBigDecimal((BigDecimal) value);
                    output.write(intToBytes(decimalBytes.length));
                    output.write(decimalBytes);
                } else if (value instanceof Timestamp) {
                    output.write(intToBytes(8));
                    output.write(longToBytes(((Timestamp) value).toInstant().toEpochMilli() * 1000));
                } else if (value instanceof Boolean) {
                    output.write(intToBytes(1));
                    output.write((Boolean) value ? new byte[]{0x01} : new byte[]{0x00});
                } else {
                    throw new IllegalArgumentException("Unsupported type: " + value.getClass());
                }
            }
        }

        // **写入 COPY 结束标志**
        output.write(shortToBytes((short) -1));

        return output.toByteArray();
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

    // **工具方法**
    private static byte[] intToBytes(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array();
    }

    private static byte[] shortToBytes(short value) {
        return ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value).array();
    }

    private static byte[] longToBytes(long value) {
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array();
    }

    private static byte[] doubleToBytes(double value) {
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(value).array();
    }

    public static byte[] encodeBigDecimal(BigDecimal value) {
        // 解析符号
        short sign = (value.signum() < 0) ? (short)1 : (short)0x0000;
        // 计算过程中不考虑正负数
        value = value.abs();

        // 获取小数位数（scale）
        short scale = (short)value.scale();

        // 计算小数部分占用的 digit 数量
        int scaleDigits = (scale + 3) / 4;

        // 提取未缩放值和标度（dScale）
        BigDecimal scaledValue = value.setScale(scale, RoundingMode.HALF_UP);

        // 分解未缩放值为基数10000的数字列表
        BigDecimal  base = BigDecimal.valueOf(10000);
        List<Short> digitsList = new ArrayList<>();
        // 处理整数部分
        while (scaledValue.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal[] divRem = scaledValue.divideAndRemainder(base);
            digitsList.add(0, divRem[1].shortValue());  // 存储低位
            scaledValue = divRem[0];  // 更新剩余部分
        }
        // 处理小数部分
        BigDecimal fractionalPart = value.subtract(value.setScale(0, RoundingMode.FLOOR)).setScale(scale, RoundingMode.HALF_UP);
        for (int i = 0; i < scaleDigits; i++) {
            fractionalPart = fractionalPart.multiply(base);
            short digit = fractionalPart.setScale(0, RoundingMode.FLOOR).shortValue();
            digitsList.add(digit);
            fractionalPart = fractionalPart.subtract(BigDecimal.valueOf(digit));
        }

        short nDigits = (short)digitsList.size();
        short weight = (short)(nDigits - scaleDigits - 1);

        // 整理输出结果
        ByteBuffer buffer = ByteBuffer.allocate(2 + 2 + 2 + 2 + (nDigits * 2)).order(ByteOrder.BIG_ENDIAN);
        buffer.putShort(nDigits);
        buffer.putShort(weight);
        buffer.putShort(sign);
        buffer.putShort(scale);
        for (short digit : digitsList) {
            buffer.putShort(digit);
        }

        return buffer.array();
    }
}
