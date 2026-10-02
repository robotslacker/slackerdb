package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.sql.CopyCsvReader;
import org.slackerdb.dbserver.sql.PostgresSQLUtil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dbserver 中所有 COPY 相关测试的统一入口。
 *
 * <p>合并自以下几个测试类（原文件已删除，避免 COPY 用例散落在多处）：</p>
 * <ul>
 *   <li>{@code Sanity01Test} 里的 COPY 用例：
 *       testCopy / testCopy2 / testEmptyCopy / testCopyReorderedColumnCsv /
 *       testBinaryCopyTruncatedStream / testBinaryCopy1 ~ testBinaryCopy10；</li>
 *   <li>{@code CopyCsvSemanticsTest}：CSV 方言语义、畸形输入、列数不符等；</li>
 *   <li>{@code CopyFailureAtomicityTest}：失败的 COPY 必须整体不生效、会话保持可用；</li>
 *   <li>{@code sql.CopyCsvReaderTest}：CSV 解析器本身的语义与分片增量正确性。</li>
 * </ul>
 *
 * <p>所有用例共用同一个内存实例。每个用例自己 {@code CREATE OR REPLACE} 自己的表，
 * 表名互不重复，因此用例之间彼此独立、执行顺序不影响结果。</p>
 */
public class CopySanityTest {

    static int dbPort;
    static DBInstance dbInstance;
    static String protocol = "postgresql";

    @BeforeAll
    static void initAll() throws ServerException {
        // 强制使用UTC时区，以避免时区问题在PG和后端数据库中不一致的行为
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // 修改默认的db启动端口
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setData("mem");
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("OFF");
        dbPort = serverConfiguration.getPort();

        // 初始化数据库
        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        assert dbInstance.instanceState.equalsIgnoreCase("RUNNING");
        System.out.println("TEST:: COPY Server started successful ...");
    }

    @AfterAll
    static void tearDownAll() {
        System.out.println("TEST:: Active sessions : " + dbInstance.activeSessions);
        dbInstance.stop();
        assert dbInstance.instanceState.equalsIgnoreCase("IDLE");
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem", "", "");
    }

    private static long copyIn(Connection conn, String sql, String csv) throws SQLException {
        try {
            return new CopyManager((BaseConnection) conn)
                    .copyIn(sql, new StringReader(csv));
        } catch (java.io.IOException e) {
            // 读取 StringReader 不会失败；这里只是为了让用例签名保持只有 SQLException
            throw new SQLException(e);
        }
    }

    private static long copyIn(Connection conn, String sql, byte[] binary) throws SQLException {
        try {
            // ByteArrayInputStream 不持有系统资源，close() 无副作用，无需 try-with-resources
            return new CopyManager((BaseConnection) conn)
                    .copyIn(sql, new ByteArrayInputStream(binary));
        } catch (java.io.IOException e) {
            // 从内存读取不会失败；这里只是为了让用例签名保持只有 SQLException
            throw new SQLException(e);
        }
    }

    /** 用同一条连接统计——这样未提交的数据也能被看到，能真实反映"是否留下来了"。 */
    private static long count(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ============================================================
    // 一、基础 COPY：CSV 文本格式 + BINARY 字节流（原 Sanity01Test 的 COPY 用例）
    // ============================================================

    @Test
    void testCopy() throws SQLException, IOException {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        {
            pgConn1.createStatement().execute("create or replace table testCopy(id int, first_name varchar(20), last_name varchar(20))");
            String csvData = "1,John,Doe\n2,Jane,Smith\n"; // 示例数据

            // 使用BaseConnection以便于进行COPY操作
            CopyManager copyManager = new CopyManager((BaseConnection) pgConn1);

            // 执行COPY FROM STDIN操作(包含表名)
            String copySql = "COPY testCopy (id, last_name, first_name) FROM STDIN WITH (FORMAT csv)";
            copyManager.copyIn(copySql, new StringReader(csvData));
            pgConn1.commit();

            // 检查数据
            PreparedStatement pstmt = pgConn1.prepareStatement("select * FROM testCopy order by id");
            ResultSet rs = pstmt.executeQuery();
            int expectedResult = 2;
            int nRows = 0;
            while (rs.next()) {
                nRows = nRows + 1;
                if (rs.getInt("id") == 1) {
                    assert rs.getString("first_name").equals("Doe");
                    assert rs.getString("last_name").equals("John");
                }
                if (rs.getInt("id") == 2) {
                    assert rs.getString("first_name").equals("Smith");
                    assert rs.getString("last_name").equals("Jane");
                }
            }
            pstmt.close();

            assert nRows == expectedResult;
        }
        {
            pgConn1.createStatement().execute("create or replace table testCopy2(id int, first_name varchar(20), last_name varchar(20))");
            String csvData = "1,John,Doe\n2,Jane,Smith\n"; // 示例数据

            // 使用BaseConnection以便于进行COPY操作
            CopyManager copyManager = new CopyManager((BaseConnection) pgConn1);

            // 执行COPY FROM STDIN操作(不包含表名)
            String copySql = "COPY testCopy2 FROM STDIN WITH (FORMAT csv)";
            copyManager.copyIn(copySql, new StringReader(csvData));
            pgConn1.commit();

            PreparedStatement pstmt = pgConn1.prepareStatement("select * FROM testCopy2 order by id");
            ResultSet rs = pstmt.executeQuery();
            int nRows = 0;
            int expectedResult = 2;
            while (rs.next()) {
                nRows = nRows + 1;
                if (rs.getInt("id") == 1)
                {
                    assert rs.getString("first_name").equals("John");
                    assert rs.getString("last_name").equals("Doe");
                }
                if (rs.getInt("id") == 2)
                {
                    assert rs.getString("first_name").equals("Jane");
                    assert rs.getString("last_name").equals("Smith");
                }
            }
            pstmt.close();
            pgConn1.close();

            assert nRows == expectedResult;
        }
    }

    @Test
    void testCopy2() throws SQLException, IOException {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        pgConn1.createStatement().execute("create or replace table testCopy2(id int, first_name varchar(20), last_name varchar(20))");
        String csvData = "1,John,Doe\n2,Jane,Smith\n"; // 示例数据

        // 使用BaseConnection以便于进行COPY操作
        CopyManager copyManager = new CopyManager((BaseConnection) pgConn1);

        // 执行COPY FROM STDIN操作(不包含表名)
        String copySql = "COPY testCopy2 FROM STDIN WITH (FORMAT csv)";
        copyManager.copyIn(copySql, new StringReader(csvData));
        pgConn1.commit();

        PreparedStatement pstmt = pgConn1.prepareStatement("select * FROM testCopy2 order by id");
        ResultSet rs = pstmt.executeQuery();
        int nRows = 0;
        int expectedResult = 2;
        while (rs.next()) {
            nRows = nRows + 1;
            if (rs.getInt("id") == 1)
            {
                assert rs.getString("first_name").equals("John");
                assert rs.getString("last_name").equals("Doe");
            }
            if (rs.getInt("id") == 2)
            {
                assert rs.getString("first_name").equals("Jane");
                assert rs.getString("last_name").equals("Smith");
            }
        }
        pstmt.close();
        pgConn1.close();

        assert nRows == expectedResult;
    }

    // 客户端以0字节的COPY数据流执行COPY ... FROM STDIN时(驱动只发送CopyDone，不发送任何CopyData)，
    // 服务端也必须关闭Appender并回应CommandComplete，否则驱动无法确认COPY结束(copyIn返回-1)。
    @Test
    void testEmptyCopy() throws SQLException, IOException {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        pgConn1.createStatement().execute("create or replace table testEmptyCopy(id int, first_name varchar(20), last_name varchar(20))");

        // 使用BaseConnection以便于进行COPY操作
        CopyManager copyManager = new CopyManager((BaseConnection) pgConn1);

        // 执行COPY FROM STDIN操作(0字节数据流)
        String copySql = "COPY testEmptyCopy FROM STDIN WITH (FORMAT csv)";
        long nCopiedRows = copyManager.copyIn(copySql, new StringReader(""));

        PreparedStatement pstmt = pgConn1.prepareStatement("select count(*) FROM testEmptyCopy");
        ResultSet rs = pstmt.executeQuery();
        rs.next();
        int nRows = rs.getInt(1);
        rs.close();
        pstmt.close();
        pgConn1.close();

        assert nCopiedRows == 0;
        assert nRows == 0;
    }

    // COPY语句中指定的列顺序与表定义的列顺序不一致时(包括完全逆序、部分列、乱序)，
    // 数据必须按 COPY 语句中列的顺序被解释，并写入对应的目标列。
    @Test
    void testCopyReorderedColumnCsv() throws SQLException, IOException {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        CopyManager copyManager = new CopyManager((BaseConnection) pgConn1);

        // 1) 完全逆序: COPY(score, first_name, id) vs 表(id, first_name, score)
        pgConn1.createStatement().execute(
                "create or replace table testCopyOrderCsv(id int, first_name varchar(20), score double)");
        long n1 = copyManager.copyIn(
                "COPY testCopyOrderCsv (score, first_name, id) FROM STDIN WITH (FORMAT csv)",
                new StringReader("9.5,John,1\n"));
        PreparedStatement ps1 = pgConn1.prepareStatement("select id, first_name, score from testCopyOrderCsv");
        ResultSet rs1 = ps1.executeQuery();
        boolean row1 = false;
        while (rs1.next()) {
            assert rs1.getInt("id") == 1;
            assert rs1.getString("first_name").equals("John");
            assert rs1.getDouble("score") == 9.5;
            row1 = true;
        }
        rs1.close();
        ps1.close();

        // 2) 部分列 + 顺序不一致: 只指定 (first_name, id)，score 走默认值
        pgConn1.createStatement().execute(
                "create or replace table testCopyOrderCsv2(id int, first_name varchar(20), score double default 7.5)");
        long n2 = copyManager.copyIn(
                "COPY testCopyOrderCsv2 (first_name, id) FROM STDIN WITH (FORMAT csv)",
                new StringReader("Ann,3\n"));
        PreparedStatement ps2 = pgConn1.prepareStatement("select id, first_name, score from testCopyOrderCsv2");
        ResultSet rs2 = ps2.executeQuery();
        boolean row2 = false;
        while (rs2.next()) {
            assert rs2.getInt("id") == 3;
            assert rs2.getString("first_name").equals("Ann");
            assert rs2.getDouble("score") == 7.5;
            row2 = true;
        }
        rs2.close();
        ps2.close();
        pgConn1.close();

        assert n1 == 1;
        assert n2 == 1;
        assert row1;
        assert row2;
    }

    /**
     * 构造一段 PG BINARY COPY 数据: 19字节固定头 + 一行(1列 INTEGER) [+ 可选的行尾 -1 结束标志]。
     */
    private static byte[] buildBinaryCopyPayload(boolean withTrailer) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        // 11字节签名 + 4字节标志位 + 4字节头部扩展区长度
        out.writeBytes(new byte[]{0x50, 0x47, 0x43, 0x4F, 0x50, 0x59, 0x0A, (byte) 0xFF, 0x0D, 0x0A, 0x00,
                0, 0, 0, 0, 0, 0, 0, 0});
        out.write(0); out.write(1);                             // 列数 = 1
        out.write(0); out.write(0); out.write(0); out.write(4); // 列长度 = 4
        out.write(0); out.write(0); out.write(0); out.write(1); // 值 = 1
        if (withTrailer) {
            out.write(0xFF); out.write(0xFF);                   // (short)-1
        }
        return out.toByteArray();
    }

    // BINARY COPY 数据流不完整时(缺少行尾的 -1 结束标志)，服务端必须回明确错误，
    // 而不是让 BufferUnderflowException 逃逸到Netty(那样客户端会永久挂起且会话不可再用)。
    @Test
    void testBinaryCopyTruncatedStream() throws Exception {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);
        // 兜底: 万一回归(服务端不响应)，客户端在60秒后报错而不是把整个测试挂死
        ExecutorService timeoutExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "copy-truncated-timeout");
            t.setDaemon(true);
            return t;
        });
        pgConn1.setNetworkTimeout(timeoutExecutor, 60000);

        pgConn1.createStatement().execute("create or replace table test_truncated_copy(id integer)");

        CopyManager copyManager = new CopyManager((BaseConnection) pgConn1);
        boolean errorCaught = false;
        try (InputStream binaryStream = new ByteArrayInputStream(buildBinaryCopyPayload(false))) {
            try {
                copyManager.copyIn("COPY test_truncated_copy (id) FROM STDIN WITH (FORMAT BINARY)", binaryStream);
            } catch (SQLException sqlException) {
                errorCaught = true;
                assert sqlException.getMessage().contains("invalid binary COPY data");
                assert sqlException.getMessage().contains("truncated");
            }
        }

        // 出错之后同一条连接必须仍然可用
        PreparedStatement pstmt = pgConn1.prepareStatement("select count(*) from test_truncated_copy");
        ResultSet rs = pstmt.executeQuery();
        rs.next();
        int nRows = rs.getInt(1);
        rs.close();
        pstmt.close();
        pgConn1.close();
        timeoutExecutor.shutdownNow();

        assert errorCaught;
        // 不完整的数据流不应该写入任何数据
        assert nRows == 0;
    }

    // ---------------------------------------------------------------- BINARY 各类型往返
    @Test
    void testBinaryCopy1() throws Exception
    {
        String timeStr1 = "2020-01-05 23:50:50";
        String timeStr2 = "2025-03-05 06:33:28";
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        List<Object[]> data = List.of(
                new Object[]
                        {
                                1L, "Alice", 25.5, new BigDecimal("12345.6789"),
                                Timestamp.from(LocalDateTime.parse(timeStr1, formatter).atZone(ZoneId.of("UTC")).toInstant()),
                                true
                        },
                new Object[]
                        {
                                2L, "Bob", 30.8, new BigDecimal("98765.4321"),
                                Timestamp.from(LocalDateTime.parse(timeStr2, formatter).atZone(ZoneId.of("UTC")).toInstant()),
                                true
                        }
        );
        byte[] binaryCopyData = PostgresSQLUtil.convertPGRowToByte(data);

        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = """
            CREATE OR REPLACE TABLE test_binary_copy1 (
                id BIGINT PRIMARY KEY,
                name VARCHAR(50),
                age DOUBLE PRECISION,
                salary NUMERIC(10,4),
                created_at TIMESTAMP,
                is_active BOOLEAN
            )
            """;
        pgConn1.createStatement().execute(sql);

        CopyManager copyManager = new CopyManager((BaseConnection) pgConn1);
        try (InputStream binaryStream = new ByteArrayInputStream(binaryCopyData)) {
            copyManager.copyIn("COPY test_binary_copy1 FROM STDIN WITH (FORMAT BINARY)", binaryStream);
        }

        try (Statement stmt = pgConn1.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM test_binary_copy1 order by id")) {
            rs.next();
            assert String.format("ID: %d, Name: %s, Age: %.2f, Salary: %s, CreatedAt: %s, Active: %b%n",
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getDouble("age"),
                        rs.getBigDecimal("salary"),
                        rs.getTimestamp("created_at"),
                        rs.getBoolean("is_active")).trim().equals("ID: 1, Name: Alice, Age: 25.50, Salary: 12345.6789, CreatedAt: 2020-01-05 23:50:50.0, Active: true");
            rs.next();
            assert String.format("ID: %d, Name: %s, Age: %.2f, Salary: %s, CreatedAt: %s, Active: %b%n",
                    rs.getInt("id"),
                    rs.getString("name"),
                    rs.getDouble("age"),
                    rs.getBigDecimal("salary"),
                    rs.getTimestamp("created_at"),
                    rs.getBoolean("is_active")).trim().equals("ID: 2, Name: Bob, Age: 30.80, Salary: 98765.4321, CreatedAt: 2025-03-05 06:33:28.0, Active: true");
            boolean hasMoreRows = rs.next();
            assert !hasMoreRows;
        }

        pgConn1.close();
    }

    @Test
    void testBinaryCopy2() throws Exception
    {
        String timeStr1 = "2020-01-05 23:50:50";
        String timeStr2 = "2025-03-05 06:33:28";
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        List<Object[]> data = List.of(
                new Object[]
                        {
                                1L, "Alice", 25.5, new BigDecimal("12345.6789"),
                                Timestamp.from(LocalDateTime.parse(timeStr1, formatter).atZone(ZoneId.of("UTC")).toInstant()),
                                true
                        },
                new Object[]
                        {
                                2L, "Bob", 30.8, new BigDecimal("98765.4321"),
                                Timestamp.from(LocalDateTime.parse(timeStr2, formatter).atZone(ZoneId.of("UTC")).toInstant()),
                                true
                        }
        );
        byte[] binaryCopyData = PostgresSQLUtil.convertPGRowToByte(data);

        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = """
            CREATE OR REPLACE TABLE test_binary_copy2 (
                id BIGINT PRIMARY KEY,
                name VARCHAR(50),
                age DOUBLE PRECISION,
                salary NUMERIC(10,4),
                created_at TIMESTAMP,
                is_active BOOLEAN
            )
            """;
        pgConn1.createStatement().execute(sql);

        CopyManager copyManager = new CopyManager((BaseConnection) pgConn1);
        try (InputStream binaryStream = new ByteArrayInputStream(binaryCopyData)) {
            copyManager.copyIn("COPY test_binary_copy2 (id, name, age) FROM STDIN WITH (FORMAT BINARY)", binaryStream);
        }

        try (Statement stmt = pgConn1.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM test_binary_copy2 order by id")) {
            rs.next();
            assert String.format("ID: %d, Name: %s, Age: %.2f, Salary: %s, CreatedAt: %s, Active: %b%n",
                    rs.getInt("id"),
                    rs.getString("name"),
                    rs.getDouble("age"),
                    rs.getBigDecimal("salary"),
                    rs.getTimestamp("created_at"),
                    rs.getBoolean("is_active")).trim().equals("ID: 1, Name: Alice, Age: 25.50, Salary: null, CreatedAt: null, Active: false");
            rs.next();
            assert String.format("ID: %d, Name: %s, Age: %.2f, Salary: %s, CreatedAt: %s, Active: %b%n",
                    rs.getInt("id"),
                    rs.getString("name"),
                    rs.getDouble("age"),
                    rs.getBigDecimal("salary"),
                    rs.getTimestamp("created_at"),
                    rs.getBoolean("is_active")).trim().equals("ID: 2, Name: Bob, Age: 30.80, Salary: null, CreatedAt: null, Active: false");
            boolean hasMoreRows = rs.next();
            assert !hasMoreRows;
        }

        pgConn1.close();
    }

    @Test
    void testBinaryCopy3() throws Exception
    {
        String timeStr1 = "2020-01-05 23:50:50";
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        List<Object[]> data = new ArrayList<>();
        for (int i=0; i<10000;i++)
        {
            // 传输内容要超过65K
            Object[] row =
                    new Object[]
                    {
                            i, "Alice", 25.5, new BigDecimal("12345.6789"),
                            Timestamp.from(LocalDateTime.parse(timeStr1, formatter).atZone(ZoneId.of("UTC")).toInstant()),
                            true
                    };
            data.add(row);
        }
        byte[] binaryCopyData = PostgresSQLUtil.convertPGRowToByte(data);

        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = """
            CREATE OR REPLACE TABLE test_binary_copy3 (
                id INT PRIMARY KEY,
                name VARCHAR(50),
                age DOUBLE PRECISION,
                salary NUMERIC(10,4),
                created_at TIMESTAMP,
                is_active BOOLEAN
            )
            """;
        pgConn1.createStatement().execute(sql);

        CopyManager copyManager = new CopyManager((BaseConnection) pgConn1);
        try (InputStream binaryStream = new ByteArrayInputStream(binaryCopyData)) {
            copyManager.copyIn("COPY test_binary_copy3 (id, name, age) FROM STDIN WITH (FORMAT BINARY)", binaryStream);
        }

        try (Statement stmt = pgConn1.createStatement(); ResultSet rs = stmt.executeQuery("SELECT Count(*),Sum(id),Sum(age)*1000 FROM test_binary_copy3")) {
            rs.next();
            assert rs.getInt(1 ) == 10000;
            assert rs.getInt(2 ) == 49995000;
            assert rs.getInt(3 ) == 255000000;
        }
        pgConn1.close();
    }

    @Test
    void testBinaryCopy4() throws Exception
    {
        List<Object[]> data = new ArrayList<>();
        for (int i=0; i<10000;i++)
        {
            // 传输内容要超过65K
            Object[] row =
                    new Object[]
                            {
                                    i, "Alice", (short)-1, "中国",
                            };
            data.add(row);
        }
        byte[] binaryCopyData = PostgresSQLUtil.convertPGRowToByte(data);

        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = """
            CREATE OR REPLACE TABLE test_binary_copy4 (
                id INT PRIMARY KEY,
                name VARCHAR(50),
                age   SMALLINT,
                title VARCHAR
            )
            """;
        pgConn1.createStatement().execute(sql);

        CopyManager copyManager = new CopyManager((BaseConnection) pgConn1);
        try (InputStream binaryStream = new ByteArrayInputStream(binaryCopyData)) {
            copyManager.copyIn("COPY test_binary_copy4  FROM STDIN WITH (FORMAT BINARY)", binaryStream);
        }

        try (
                Statement stmt = pgConn1.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT Count(*),Sum(id),Sum(age),Min(title) FROM test_binary_copy4")) {
            rs.next();
            assert rs.getInt(1 ) == 10000;
            assert rs.getInt(2 ) == 49995000;
            assert rs.getInt(3 ) == -10000;
            assert rs.getString(4).equals("中国");
        }
        pgConn1.close();
    }

    @Test
    void testBinaryCopy5() throws Exception
    {
        List<Object[]> data = List.of(
                new Object[]
                        {
                                1L
                        },
                new Object[]
                        {
                                2L
                        }
        );
        byte[] binaryCopyData = PostgresSQLUtil.convertPGRowToByte(data);

        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        boolean errorCaugt = false;
        String sql = """
            CREATE OR REPLACE TABLE test_binary_copy5 (
                id INT
            )
            """;
        pgConn1.createStatement().execute(sql);

        CopyManager copyManager = new CopyManager((BaseConnection) pgConn1);
        try (InputStream binaryStream = new ByteArrayInputStream(binaryCopyData)) {
            try {
                copyManager.copyIn("COPY test_binary_copy5(id)  FROM STDIN WITH (FORMAT BINARY)", binaryStream);
            } catch (SQLException sqlException)
            {
                errorCaugt = true;
                assert sqlException.getMessage().contains("data type mismatch");
            }
        }
        pgConn1.close();

        // 确认找到了错误
        assert errorCaugt;
    }

    @Test
    void testBinaryCopy6() throws Exception
    {
        List<Object[]> data = new ArrayList<>();
        for (int i=0; i<10000;i++)
        {
            // 传输内容要超过65K
            Object[] row =
                    new Object[]
                            {
                                    "1", (long)i, "SEND", "DD", "DD"
                            };
            data.add(row);
        }
        byte[] binaryCopyData = PostgresSQLUtil.convertPGRowToByte(data);

        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = """
            CREATE OR REPLACE TABLE test_binary_copy6 (
                id BIGINT,
                create_time TIMESTAMP default CURRENT_TIMESTAMP,
                CNT VARCHAR,
                EVENT_TYPE VARCHAR,
                SRC_TABLE_UNIQUE_ID VARCHAR,
                TIME VARCHAR
            )
            """;
        pgConn1.createStatement().execute(sql);

        CopyManager copyManager = new CopyManager((BaseConnection) pgConn1);
        try (InputStream binaryStream = new ByteArrayInputStream(binaryCopyData)) {
            // 只指定部分列，没有指定的列(create_time)使用默认值
            copyManager.copyIn("COPY test_binary_copy6(CNT, ID, EVENT_TYPE, SRC_TABLE_UNIQUE_ID, TIME)  FROM STDIN WITH (FORMAT BINARY)", binaryStream);
        }

        try (Statement stmt = pgConn1.createStatement(); ResultSet rs = stmt.executeQuery("SELECT COUNT(*), SUM(ID) FROM test_binary_copy6")) {
            rs.next();
            assert rs.getInt(1) == 10000;
            assert rs.getInt(2) == 49995000;
        }
        pgConn1.close();
    }


    @Test
    void testBinaryCopy7() throws Exception
    {
        List<Object[]> data = new ArrayList<>();
        for (int i=0; i<2;i++)
        {
            // 传输内容要超过65K
            Object[] row =
                    new Object[]
                            {
                                    "1", (long)i, "SEND", "DD", "DD", "DD"
                            };
            data.add(row);
        }
        byte[] binaryCopyData = PostgresSQLUtil.convertPGRowToByte(data);

        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = """
            CREATE OR REPLACE TABLE test_binary_copy7 (
                id BIGINT,
                create_time TIMESTAMP default CURRENT_TIMESTAMP,
                CNT VARCHAR,
                SRC_TABLE_UNIQUE_ID VARCHAR,
                TIME VARCHAR
            )
            """;
        pgConn1.createStatement().execute(sql);

        CopyManager copyManager = new CopyManager((BaseConnection) pgConn1);
        boolean errorCaught = false;
        try (InputStream binaryStream = new ByteArrayInputStream(binaryCopyData)) {
            try {
                // test_binary_copy7中根本没有EVENT_TYPE字段，这里应该报错
                copyManager.copyIn("COPY test_binary_copy7(CNT, ID, EVENT_TYPE, SRC_TABLE_UNIQUE_ID, START_TIME, TIME)  FROM STDIN WITH (FORMAT BINARY)", binaryStream);
            }
            catch (SQLException sqlException)
            {
                errorCaught = true;
                assert sqlException.getMessage().toLowerCase().contains("event_type");
                assert sqlException.getMessage().contains("does not exist");
            }
        }

        // 确认找到了错误
        assert errorCaught;

        // 因为列不存在，所以整个Copy应该被拒绝，表中不应该有任何数据
        try (Statement stmt = pgConn1.createStatement(); ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_binary_copy7")) {
            rs.next();
            assert rs.getInt(1) == 0;
        }

        pgConn1.close();
    }

    @Test
    void testBinaryCopy8() throws Exception
    {
        String timeStr1 = "2020-01-05 23:50:50";
        String timeStr2 = "2025-03-05 06:33:28";
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        List<Object[]> data = List.of(
                new Object[]
                        {
                                1L, "Alice", 25.5, new BigDecimal("12345.6789"),
                                Timestamp.from(LocalDateTime.parse(timeStr1, formatter).atZone(ZoneId.of("UTC")).toInstant()),
                                true
                        },
                new Object[]
                        {
                                2L, "Bob", 30.8, new BigDecimal("98765.4321"),
                                Timestamp.from(LocalDateTime.parse(timeStr2, formatter).atZone(ZoneId.of("UTC")).toInstant()),
                                true
                        }
        );
        byte[] binaryCopyData = PostgresSQLUtil.convertPGRowToByte(data);

        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = """
            CREATE OR REPLACE TABLE test_binary_copy8 (
                id BIGINT PRIMARY KEY,
                name VARCHAR(50),
                age DOUBLE PRECISION,
                salary NUMERIC(10,4),
                created_at TIMESTAMP,
                is_active BOOLEAN,
                notnullcol  VARCHAR 
            )
            """;
        pgConn1.createStatement().execute(sql);

        CopyManager copyManager = new CopyManager((BaseConnection) pgConn1);
        boolean errorCaught = false;
        try (InputStream binaryStream = new ByteArrayInputStream(binaryCopyData)) {
            try {
                // 数据里只有6列，而表test_binary_copy8有7列(notnullcol没有数据)，
                // 必须收到明确的"列数不符"信息，而不是 ArrayIndexOutOfBoundsException 的裸消息
                // "Index 6 out of bounds for length 6"。
                copyManager.copyIn("COPY test_binary_copy8 FROM STDIN WITH (FORMAT BINARY)", binaryStream);
            } catch (SQLException sqlException) {
                errorCaught = true;
                String message = sqlException.getMessage();
                assert message != null && message.contains("column size not match")
                        : "错误信息应说明列数不符，实际: " + message;
                // [实际列数] vs [期望列数]：数据6列，表7列
                assert message.contains("[6] vs [7]")
                        : "错误信息应带实际列数与期望列数，实际: " + message;
                assert message.contains("6 columns") && message.contains("7 columns are expected")
                        : "错误信息应说明数据列数不足，实际: " + message;
                assert !message.contains("out of bounds")
                        : "不应该再把数组越界的裸消息回给客户端，实际: " + message;
            }
        }

        // 确认找到了错误
        assert errorCaught;

        // 列数不符的COPY必须整体不生效，表中不应该有任何数据
        try (Statement stmt = pgConn1.createStatement(); ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_binary_copy8")) {
            rs.next();
            assert rs.getInt(1) == 0;
        }

        pgConn1.close();
    }

    @Test
    void testBinaryCopy9() throws Exception
    {
        List<Object[]> data = List.of(
                new Object[]
                        {
                                1L
                        },
                new Object[]
                        {
                                2L
                        }
        );
        byte[] binaryCopyData = PostgresSQLUtil.convertPGRowToByte(data);

        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";

        // 表先在自动提交模式下建好并落库：这样后面 ROLLBACK 之后表还在，可以验证"没有半截数据"
        try (Connection setupConn = DriverManager.getConnection(connectURL, "", "")) {
            setupConn.createStatement().execute("""
                CREATE OR REPLACE TABLE test_binary_copy9 (
                    id BIGINT PRIMARY KEY,
                    notnullcol  VARCHAR not null 
                )
                """);
        }

        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        CopyManager copyManager = new CopyManager((BaseConnection) pgConn1);
        boolean errorCaught = false;
        try (InputStream binaryStream = new ByteArrayInputStream(binaryCopyData)) {
            try {
                copyManager.copyIn("COPY test_binary_copy9(ID) FROM STDIN WITH (FORMAT BINARY)", binaryStream);
            } catch (SQLException sqlException) {
                errorCaught = true;
                String message = sqlException.getMessage();
                // COPY只给了ID列，notnullcol是NOT NULL且没有DEFAULT：DuckDB 只在 Appender flush 时才发现，
                // 这个错误必须如实带回给客户端 —— 既不能变成"COPY成功"的假象，也不能是看不懂的内部错误。
                assert message != null && message.toLowerCase().contains("not null constraint failed")
                        : "错误信息应来自DuckDB并且有意义，实际: " + message;
                assert message.contains("notnullcol")
                        : "错误信息应指出是哪一列，实际: " + message;
                assert message.contains("test_binary_copy9")
                        : "错误信息应指出是哪张表，实际: " + message;
            }
        }

        // 确认找到了错误
        assert errorCaught;

        // 客户端自己开的事务里发生写入错误后，该事务已经中止（与PostgreSQL一致）：
        // 此时任何语句都会被拒绝，必须先 ROLLBACK。
        boolean aborted = false;
        try (Statement stmt = pgConn1.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_binary_copy9")) {
            rs.next();
        } catch (SQLException sqlException) {
            aborted = true;
            assert sqlException.getMessage() != null
                    && sqlException.getMessage().toLowerCase().contains("abort")
                    : "应提示事务已中止，实际: " + sqlException.getMessage();
        }
        assert aborted;

        // ROLLBACK 之后会话必须恢复可用，并且失败的COPY不能留下任何数据
        pgConn1.createStatement().execute("ROLLBACK");
        try (Statement stmt = pgConn1.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_binary_copy9")) {
            rs.next();
            assert rs.getInt(1) == 0;
        }

        pgConn1.close();
    }

    @Test
    void testBinaryCopy10() throws Exception
    {
        // 没被COPY覆盖的列如果"NOT NULL + 有DEFAULT"，必须仍然允许导入，由DuckDB补上默认值
        // (即 appendDefault() 走的是列的DEFAULT，而不是无条件NULL)。
        List<Object[]> data = List.of(
                new Object[]
                        {
                                1L
                        },
                new Object[]
                        {
                                2L
                        }
        );
        byte[] binaryCopyData = PostgresSQLUtil.convertPGRowToByte(data);

        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = """
            CREATE OR REPLACE TABLE test_binary_copy10 (
                id BIGINT PRIMARY KEY,
                notnullcol_with_default  VARCHAR not null default 'unknown',
                cnt BIGINT default 7
            )
            """;
        pgConn1.createStatement().execute(sql);

        CopyManager copyManager = new CopyManager((BaseConnection) pgConn1);
        try (InputStream binaryStream = new ByteArrayInputStream(binaryCopyData)) {
            copyManager.copyIn("COPY test_binary_copy10(ID) FROM STDIN WITH (FORMAT BINARY)", binaryStream);
        }

        try (Statement stmt = pgConn1.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*), MIN(notnullcol_with_default), MIN(cnt) FROM test_binary_copy10")) {
            rs.next();
            assert rs.getInt(1) == 2;
            // 未指定且带DEFAULT的列必须拿到默认值
            assert rs.getString(2).equals("unknown");
            assert rs.getInt(3) == 7;
        }

        pgConn1.close();
    }

    // ============================================================
    // 二、CSV 方言语义与畸形输入（原 CopyCsvSemanticsTest）
    // ============================================================

    /** 未加引号的空字段是 NULL，加了引号的空字段是空字符串 —— 两者必须能区分。 */
    @Test
    void testNullVersusEmptyString() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            conn.createStatement().execute(
                    "create or replace table csv_null(id int, name varchar, note varchar)");
            // 第1行: name 与 note 都是"未加引号的空字段" -> NULL
            // 第2行: name 是 "" -> 空字符串, note 未加引号为空 -> NULL
            long n = copyIn(conn, "COPY csv_null (id, name, note) FROM STDIN WITH (FORMAT csv)",
                    "1,,\n2,\"\",\n");
            conn.commit();
            assert n == 2;

            try (PreparedStatement ps = conn.prepareStatement(
                    "select id, name, note from csv_null order by id")) {
                ResultSet rs = ps.executeQuery();

                assert rs.next();
                assert rs.getInt("id") == 1;
                assert rs.getString("name") == null : "未加引号的空字段必须是 NULL";
                assert rs.wasNull();
                assert rs.getString("note") == null;

                assert rs.next();
                assert rs.getInt("id") == 2;
                assert "".equals(rs.getString("name")) : "带引号的空字段必须是空字符串";
                assert !rs.wasNull();
                assert rs.getString("note") == null;

                assert !rs.next();
            }
        }
    }

    /** 数值/布尔/时间列的空字段 = NULL（旧实现会抛 NumberFormatException）。 */
    @Test
    void testEmptyNumericFieldIsNull() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            conn.createStatement().execute(
                    "create or replace table csv_nullnum(id int, score double, cnt bigint,"
                            + " flag boolean, ts timestamp)");
            long n = copyIn(conn, "COPY csv_nullnum FROM STDIN WITH (FORMAT csv)",
                    "1,,,,\n2,1.5,7,true,2024-01-02 03:04:05\n");
            conn.commit();
            assert n == 2;

            try (PreparedStatement ps = conn.prepareStatement(
                    "select id, score, cnt, flag, ts from csv_nullnum order by id")) {
                ResultSet rs = ps.executeQuery();

                assert rs.next();
                assert rs.getInt("id") == 1;
                rs.getDouble("score");
                assert rs.wasNull() : "空的 DOUBLE 字段必须是 NULL";
                rs.getLong("cnt");
                assert rs.wasNull() : "空的 BIGINT 字段必须是 NULL";
                rs.getBoolean("flag");
                assert rs.wasNull() : "空的 BOOLEAN 字段必须是 NULL";
                assert rs.getTimestamp("ts") == null : "空的 TIMESTAMP 字段必须是 NULL";

                assert rs.next();
                assert rs.getInt("id") == 2;
                assert rs.getDouble("score") == 1.5;
                assert rs.getLong("cnt") == 7L;
                assert rs.getBoolean("flag");
                assert rs.getTimestamp("ts").toLocalDateTime()
                        .equals(java.time.LocalDateTime.of(2024, 1, 2, 3, 4, 5));

                assert !rs.next();
            }
        }
    }

    /** 引号方言：引号内的分隔符、"" 转义、引号内的换行。 */
    @Test
    void testQuotingDialect() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            conn.createStatement().execute(
                    "create or replace table csv_quote(id int, name varchar, note varchar)");
            // 第1行: 引号内的逗号 + "" 转义出的双引号
            // 第2行: 引号内的换行
            // 第3行: 引号内的裸逗号与引号混排
            String csv = "1,\"Smith, John\",\"He said \"\"hi\"\"\"\n"
                    + "2,\"line1\nline2\",plain\n"
                    + "3,a,\"x,\"\"y\"\"\"\n";
            long n = copyIn(conn, "COPY csv_quote FROM STDIN WITH (FORMAT csv)", csv);
            conn.commit();
            assert n == 3;

            try (PreparedStatement ps = conn.prepareStatement(
                    "select id, name, note from csv_quote order by id")) {
                ResultSet rs = ps.executeQuery();

                assert rs.next();
                assert rs.getString("name").equals("Smith, John");
                assert rs.getString("note").equals("He said \"hi\"");

                assert rs.next();
                assert rs.getString("name").equals("line1\nline2") : "引号内的换行必须保留";
                assert rs.getString("note").equals("plain");

                assert rs.next();
                assert rs.getString("name").equals("a");
                assert rs.getString("note").equals("x,\"y\"");

                assert !rs.next();
            }
        }
    }

    /** 行尾(\n / \r\n)、完全空行跳过、末行无换行、行末空白不被裁剪。 */
    @Test
    void testLineEndingsAndBlankLines() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            conn.createStatement().execute(
                    "create or replace table csv_lines(id int, name varchar)");
            // \n 行尾 + 中间完全空行 + \r\n 行尾 + 末行没有换行 + 末字段带空格
            String csv = "1,a\n\n2,b\r\n3,c \n4,d";
            long n = copyIn(conn, "COPY csv_lines FROM STDIN WITH (FORMAT csv)", csv);
            conn.commit();
            assert n == 4 : "完全空行必须被跳过，末行没有换行也必须算一行";

            try (PreparedStatement ps = conn.prepareStatement(
                    "select id, name from csv_lines order by id")) {
                ResultSet rs = ps.executeQuery();
                assert rs.next() && rs.getInt(1) == 1 && rs.getString(2).equals("a");
                assert rs.next() && rs.getInt(1) == 2 && rs.getString(2).equals("b");
                assert rs.next() && rs.getInt(1) == 3 && rs.getString(2).equals("c ") : "未加引号字段的尾部空格必须保留";
                assert rs.next() && rs.getInt(1) == 4 && rs.getString(2).equals("d");
                assert !rs.next();
            }
        }
    }

    /** COPY 列顺序与表定义不一致（含部分列）时，NULL/引号语义仍然按 COPY 的列序解释。 */
    @Test
    void testReorderedColumnsWithNulls() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            conn.createStatement().execute(
                    "create or replace table csv_order(id int, first_name varchar, score double default 7.5)");
            // COPY 里列的顺序是 (score, first_name, id)：第1行 score 与 first_name 都是 NULL
            long n = copyIn(conn,
                    "COPY csv_order (score, first_name, id) FROM STDIN WITH (FORMAT csv)",
                    ",,1\n9.5,\"\",2\n");
            conn.commit();
            assert n == 2;

            try (PreparedStatement ps = conn.prepareStatement(
                    "select id, first_name, score from csv_order order by id")) {
                ResultSet rs = ps.executeQuery();

                assert rs.next();
                assert rs.getInt("id") == 1;
                assert rs.getString("first_name") == null : "该列在 COPY 的列序里位于第2位，空的应是 NULL";
                rs.getDouble("score");
                assert rs.wasNull();

                assert rs.next();
                assert rs.getInt("id") == 2;
                assert "".equals(rs.getString("first_name"));
                assert rs.getDouble("score") == 9.5;

                assert !rs.next();
            }
        }
    }

    /** 不在 COPY 列清单中的列走表默认值（包括该列类型本身不受支持时也不应报错）。 */
    @Test
    void testUnmappedColumnUsesDefault() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            // d 列的类型(DATE)不在支持列表里，但没有出现在 COPY 列清单中，因此不应触发"类型不支持"
            conn.createStatement().execute(
                    "create or replace table csv_default(id int, d date default DATE '2020-01-01', name varchar)");
            long n = copyIn(conn, "COPY csv_default (id, name) FROM STDIN WITH (FORMAT csv)",
                    "1,John\n");
            conn.commit();
            assert n == 1;

            try (PreparedStatement ps = conn.prepareStatement(
                    "select id, d, name from csv_default")) {
                ResultSet rs = ps.executeQuery();
                assert rs.next();
                assert rs.getInt("id") == 1;
                assert rs.getDate("d").toLocalDate().equals(java.time.LocalDate.of(2020, 1, 1));
                assert rs.getString("name").equals("John");
                assert !rs.next();
            }
        }
    }

    /** 列数不符必须报错，错误信息里带上实际列数与期望列数，且报错后连接仍可用。 */
    @Test
    void testColumnCountMismatch() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            conn.createStatement().execute(
                    "create or replace table csv_mismatch(id int, name varchar, note varchar)");

            boolean failed = false;
            try {
                copyIn(conn, "COPY csv_mismatch FROM STDIN WITH (FORMAT csv)", "1,John\n");
            } catch (SQLException e) {
                failed = true;
                String message = e.getMessage();
                assert message != null && message.contains("column size not match")
                        : "错误信息应说明列数不符，实际: " + message;
                assert message.contains("[2] vs [3]")
                        : "错误信息应带实际列数与期望列数，实际: " + message;
            }
            assert failed : "列数不符必须失败";

            // 报错后同一连接仍可继续使用
            try (Statement st = conn.createStatement()) {
                ResultSet rs = st.executeQuery("select 1 + 1");
                assert rs.next() && rs.getInt(1) == 2;
            }
        }
    }

    /**
     * 写入阶段才暴露的错误必须被如实上报：这里是"未加引号的空字段 = NULL"撞上 NOT NULL 列。
     *
     * <p>DuckDB 的 Appender 只在 flush 时才报这个错，而 {@code DuckDBAppender.close()} 会把 flush
     * 的错误吞掉 —— 修复前服务端会误报 COPY 成功，实际一行都没写进去。现在必须把 DuckDB 的原因
     * 带给客户端，且失败的 COPY 不能留下任何数据、会话不能被弄坏。</p>
     *
     * <p>这里刻意<b>不开</b>客户端事务（autoCommit=true）：COPY 由服务端自己的事务包住，
     * 失败时服务端整体 ROLLBACK，会话保持可用。</p>
     */
    @Test
    void testAppenderWriteErrorIsReported() throws SQLException {
        try (Connection conn = connect()) {
            conn.createStatement().execute(
                    "create or replace table csv_notnull(id integer, nn varchar not null)");

            boolean failed = false;
            try {
                // 空字段 = NULL，违反 NOT NULL 约束，错误只在 Appender flush 时才出现
                copyIn(conn, "COPY csv_notnull FROM STDIN WITH (FORMAT csv)", "1,\n");
            } catch (SQLException e) {
                failed = true;
                String message = e.getMessage();
                assert message != null && message.toLowerCase().contains("not null constraint failed")
                        : "应把 DuckDB 报出的原因带给客户端，实际: " + message;
            }
            assert failed : "违反 NOT NULL 约束必须报错，不能报 COPY 成功";

            // 失败的 COPY 不能留下任何数据，会话也必须仍然可用
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("select count(*) from csv_notnull")) {
                rs.next();
                assert rs.getInt(1) == 0 : "失败的 COPY 不能留下任何数据";
            }
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("select 1 + 1")) {
                rs.next();
                assert rs.getInt(1) == 2 : "COPY 失败后会话必须仍然可用";
            }
        }
    }

    /** 畸形 CSV（引号未闭合）必须显式报错，且报错后连接仍可用（不能挂起/不能把会话打坏）。 */
    @Test
    void testMalformedCsvReportsErrorAndKeepsSessionUsable() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            conn.createStatement().execute(
                    "create or replace table csv_bad(id int, name varchar)");

            boolean failed = false;
            try {
                copyIn(conn, "COPY csv_bad FROM STDIN WITH (FORMAT csv)", "1,\"abc\n");
            } catch (SQLException e) {
                failed = true;
                assert e.getMessage() != null && e.getMessage().contains("CSV")
                        : "应报 CSV 格式错误，实际: " + e.getMessage();
            }
            assert failed : "引号未闭合必须失败";

            // 闭引号之后出现非法字符，同样必须报错
            boolean failed2 = false;
            try {
                copyIn(conn, "COPY csv_bad FROM STDIN WITH (FORMAT csv)", "1,\"ab\"c\n");
            } catch (SQLException e) {
                failed2 = true;
            }
            assert failed2 : "闭引号之后的非法字符必须失败";

            try (Statement st = conn.createStatement()) {
                ResultSet rs = st.executeQuery("select 2 + 2");
                assert rs.next() && rs.getInt(1) == 4;
            }
        }
    }

    /**
     * 块 Appender 建不起来的列类型（BIT / INTERVAL / TIME_NS / BIGNUM / VARIANT）快速失败：
     * 在任何数据写入之前就报错，消息里带列名与类型名。
     *
     * <p>这几类列在 duckdb_jdbc 1.5.6.0 里 {@code createAppender()} 直接抛
     * {@code unsupported C API type: N}，必须给出可读的原因。</p>
     */
    @Test
    void testUnsupportedColumnTypeFailsEarly() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            conn.createStatement().execute(
                    "create or replace table csv_unsupported(id int, b bit, iv interval)");

            boolean failed = false;
            try {
                copyIn(conn, "COPY csv_unsupported FROM STDIN WITH (FORMAT csv)",
                        "1,1010,1 day\n2,1011,2 days\n");
            } catch (SQLException e) {
                failed = true;
                assert e.getMessage() != null && e.getMessage().contains("does not support")
                        : "应报类型不支持，实际: " + e.getMessage();
                assert e.getMessage().contains("BIT")
                        : "错误信息应带列类型名(BIT)，实际: " + e.getMessage();
                assert e.getMessage().contains("b")
                        : "错误信息应带列名(b)，实际: " + e.getMessage();
            }
            assert failed : "BIT 列必须明确报错";

            // 表里含这种列时，即使不写它也无法用 Appender（createAppender 按整表列类型校验），
            // 同样必须给出明确错误
            boolean failed2 = false;
            try {
                copyIn(conn, "COPY csv_unsupported (id) FROM STDIN WITH (FORMAT csv)", "7\n");
            } catch (SQLException e) {
                failed2 = true;
                assert e.getMessage() != null && e.getMessage().contains("does not support")
                        : "应报类型不支持，实际: " + e.getMessage();
            }
            assert failed2 : "表里含不受支持的列类型时必须明确报错";
        }
    }

    /** 大数据量：多次 CopyData 分片(驱动按块发送)时必须与单行等价，且行数准确。 */
    @Test
    void testLargeChunkedCopy() throws SQLException {
        int rows = 5000;
        StringBuilder sb = new StringBuilder(rows * 24);
        for (int i = 0; i < rows; i++) {
            // 每 3 行留一个 NULL 与一个空串，确保跨分片状态下语义不漂移
            if (i % 3 == 0) {
                sb.append(i).append(",,x\n");
            } else if (i % 3 == 1) {
                sb.append(i).append(",\"\",y\n");
            } else {
                sb.append(i).append(",\"a,b\",z\n");
            }
        }

        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            conn.createStatement().execute(
                    "create or replace table csv_bulk(id int, name varchar, tag varchar)");
            long n = copyIn(conn, "COPY csv_bulk FROM STDIN WITH (FORMAT csv)", sb.toString());
            conn.commit();
            assert n == rows : "应导入 " + rows + " 行，实际 " + n;

            try (PreparedStatement ps = conn.prepareStatement(
                    "select count(*) from csv_bulk where id % 3 = 0 and name is null")) {
                ResultSet rs = ps.executeQuery();
                rs.next();
                assert rs.getInt(1) == (rows + 2) / 3 : "未加引号的空字段必须都是 NULL";
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "select count(*) from csv_bulk where id % 3 = 1 and name = ''")) {
                ResultSet rs = ps.executeQuery();
                rs.next();
                assert rs.getInt(1) == (rows + 1) / 3 : "带引号的空字段必须都是空字符串";
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "select count(*) from csv_bulk where id % 3 = 2 and name = 'a,b'")) {
                ResultSet rs = ps.executeQuery();
                rs.next();
                assert rs.getInt(1) == rows / 3;
            }
        }
    }

    // ============================================================
    // 三、失败的 COPY 必须整体不生效（原 CopyFailureAtomicityTest）
    // ============================================================

    // ============================================================
    // CSV
    // ============================================================

    /**
     * CSV 第 1 行合法、第 2 行的 id 不是数字（抛 NumberFormatException）。
     * 该异常走 catch 分支，修复前会一路落到方法尾部 close() Appender 从而提交第 1 行。
     */
    @Test
    void csvTypeErrorAfterValidRowLeavesNoRows() throws Exception {
        try (Connection conn = connect()) {
            conn.createStatement().execute(
                    "create or replace table t_csv_atomic(id integer, name varchar, note varchar)");

            boolean failed = false;
            try {
                copyIn(conn, "COPY t_csv_atomic FROM STDIN WITH (FORMAT csv)",
                        "1,Alice,x\nnot-a-number,Bob,y\n");
            } catch (SQLException e) {
                failed = true;
            }
            assertTrue(failed, "第 2 行 id 不是数字，必须报错");
            assertEquals(0, count(conn, "t_csv_atomic"),
                    "失败的 COPY 不能留下已写入的部分行（第 1 行）");
        }
    }

    /** 列数不符：前两行合法、第 3 行列数不足。同样不能留下前两行。 */
    @Test
    void csvColumnCountMismatchLeavesNoRows() throws Exception {
        try (Connection conn = connect()) {
            conn.createStatement().execute(
                    "create or replace table t_csv_atomic2(id integer, name varchar, note varchar)");

            boolean failed = false;
            try {
                copyIn(conn, "COPY t_csv_atomic2 FROM STDIN WITH (FORMAT csv)",
                        "1,Alice,x\n2,Bob,y\n3,Cathy\n");
            } catch (SQLException e) {
                failed = true;
                assertTrue(e.getMessage() != null && e.getMessage().contains("column size not match"),
                        "应报列数不符，实际: " + e.getMessage());
            }
            assertTrue(failed, "列数不符必须报错");
            assertEquals(0, count(conn, "t_csv_atomic2"),
                    "失败的 COPY 不能留下已写入的部分行（前两行）");
        }
    }

    /**
     * 第 1 行完整合法、第 2 行 CSV 本身畸形（引号未闭合）。
     *
     * <p>这是最能暴露问题的一种：畸形在第 2 行<b>解析阶段</b>就抛出（不是 append 阶段），
     * 所以第 1 行已经干净地 append 完成、Appender 也不处于"半行"状态，
     * 异常走 catch 分支后修复前会一路落到方法尾部执行 {@code close()} 从而把第 1 行提交掉。</p>
     */
    @Test
    void csvMalformedRowAfterValidRowLeavesNoRows() throws Exception {
        try (Connection conn = connect()) {
            conn.createStatement().execute(
                    "create or replace table t_csv_atomic3(id integer, name varchar, note varchar)");

            boolean failed = false;
            try {
                copyIn(conn, "COPY t_csv_atomic3 FROM STDIN WITH (FORMAT csv)",
                        "1,Alice,x\n2,\"unclosed\n");
            } catch (SQLException e) {
                failed = true;
            }
            assertTrue(failed, "引号未闭合必须报错");
            assertEquals(0, count(conn, "t_csv_atomic3"),
                    "失败的 COPY 不能留下已写入的部分行（第 1 行）");
        }
    }

    /**
     * 失败后即使会话结束，部分行也不能被"迟到"提交。
     *
     * <p>覆盖提前 return 的分支（列数不符）：修复前这些分支直接 return，
     * 跳过了方法尾部，Appender 一直开着；等到会话结束 {@code closeSession()}
     * 再去 close() 它时，前两行就被提交进表了。这里用一条<b>新连接</b>来观察最终落库结果。</p>
     */
    @Test
    void partialRowsAreNotCommittedWhenSessionEnds() throws Exception {
        try (Connection conn = connect()) {
            conn.createStatement().execute(
                    "create or replace table t_csv_atomic4(id integer, name varchar, note varchar)");
            try {
                // 前两行合法、第 3 行列数不足 ⇒ 走提前 return 的错误分支
                copyIn(conn, "COPY t_csv_atomic4 FROM STDIN WITH (FORMAT csv)",
                        "1,Alice,x\n2,Bob,y\n3,Cathy\n");
                assertTrue(false, "列数不符应当报错");
            } catch (SQLException expected) {
                // 预期失败
            }
        } // 会话在此关闭

        try (Connection fresh = connect()) {
            assertEquals(0, count(fresh, "t_csv_atomic4"),
                    "失败的 COPY 的部分行不能因为会话结束时的 Appender.close() 而被提交");
        }
    }

    // ============================================================
    // BINARY
    // ============================================================

    /**
     * BINARY 第 1 行是合法 INTEGER(4 字节)，第 2 行给了 Long(8 字节) ⇒ 长度校验失败。
     * 前 1 行已 append，失败路径必须把它丢掉。
     */
    @Test
    void binaryTypeMismatchAfterValidRowLeavesNoRows() throws Exception {
        byte[] payload = PostgresSQLUtil.convertPGRowToByte(List.of(
                new Object[]{1},      // Integer -> 4 字节，与 INT 列匹配
                new Object[]{2L}));   // Long    -> 8 字节，长度不符

        try (Connection conn = connect()) {
            conn.createStatement().execute("create or replace table t_bin_atomic(id integer)");

            boolean failed = false;
            try {
                copyIn(conn, "COPY t_bin_atomic (id) FROM STDIN WITH (FORMAT BINARY)", payload);
            } catch (SQLException e) {
                failed = true;
                assertTrue(e.getMessage() != null && e.getMessage().contains("data type mismatch"),
                        "应报类型/长度不匹配，实际: " + e.getMessage());
            }
            assertTrue(failed, "长度不匹配必须报错");
            assertEquals(0, count(conn, "t_bin_atomic"),
                    "失败的 COPY 不能留下已写入的部分行（第 1 行）");
        }
    }

    /**
     * BINARY 数据列数不足：第 1 行 2 列合法，第 2 行只有 1 列。
     *
     * <p>修复前这里会在 {@code row[nPos]} 处抛出 ArrayIndexOutOfBoundsException，客户端只能看到
     * {@code ERROR: Index 1 out of bounds for length 1}；现在必须报出明确的"列数不符"，
     * 带上行号/实际列数/期望列数，并且同样不能留下第 1 行。</p>
     */
    @Test
    void binaryColumnCountMismatchAfterValidRowLeavesNoRows() throws Exception {
        byte[] payload = PostgresSQLUtil.convertPGRowToByte(List.of(
                new Object[]{1, "Alice"},   // 2 列，与目标表匹配
                new Object[]{2}));          // 只给了 1 列，列数不足

        try (Connection conn = connect()) {
            conn.createStatement().execute(
                    "create or replace table t_bin_atomic2(id integer, name varchar)");

            boolean failed = false;
            try {
                copyIn(conn, "COPY t_bin_atomic2 FROM STDIN WITH (FORMAT BINARY)", payload);
            } catch (SQLException e) {
                failed = true;
                String message = e.getMessage();
                assertTrue(message != null && message.contains("column size not match"),
                        "应报列数不符，实际: " + message);
                assertTrue(message.contains("[1] vs [2]"),
                        "错误信息应带实际列数与期望列数，实际: " + message);
                assertTrue(message.contains("Row 2") && message.contains("1 columns") && message.contains("2 columns are expected"),
                        "错误信息应说明第 2 行只有 1 列、期望 2 列，实际: " + message);
                assertTrue(!message.contains("out of bounds"),
                        "不应该再把数组越界的裸消息回给客户端，实际: " + message);
            }
            assertTrue(failed, "列数不足必须报错");
            assertEquals(0, count(conn, "t_bin_atomic2"),
                    "失败的 COPY 不能留下已写入的部分行（第 1 行）");
        }
    }

    /**
     * Appender 在写入阶段报错（这里让 NOT NULL 列的数据显式给 NULL）：这类错误只有 flush 时才会暴露，
     * 而 {@code DuckDBAppender.close()} 会把 flush 抛出的异常吞掉 —— 修复前服务端会误报 COPY 成功
     * （客户端拿到 CommandComplete），而 DuckDB 那边的事务其实已经被标记为 aborted。
     *
     * <p>现在必须把 Appender 报出的原因原样带回给客户端，且不能留下第 1 行、会话也仍然可用。</p>
     */
    @Test
    void binaryAppenderErrorIsReportedAndLeavesNoRows() throws Exception {
        byte[] payload = PostgresSQLUtil.convertPGRowToByte(List.of(
                new Object[]{1, "Alice"},
                new Object[]{2, null}));   // nn 列显式给 NULL(-1 长度)

        try (Connection conn = connect()) {
            conn.createStatement().execute(
                    "create or replace table t_bin_atomic3(id integer, nn varchar not null)");

            boolean failed = false;
            try {
                copyIn(conn, "COPY t_bin_atomic3 (id, nn) FROM STDIN WITH (FORMAT BINARY)", payload);
            } catch (SQLException e) {
                failed = true;
                String message = e.getMessage();
                // DuckDB 的原因必须原样带出来（这里是它自己的 NOT NULL 措辞）
                assertTrue(message != null && message.contains("NOT NULL constraint failed"),
                        "应把 Appender 报出的原因带给客户端，实际: " + message);
                assertTrue(message.contains("t_bin_atomic3") && message.contains("nn"),
                        "错误信息应包含表名与列名，实际: " + message);
                assertTrue(!message.contains("catalog: 'null'"),
                        "应剥掉 Appender 的固定外壳(catalog/schema 恒为 'null')，实际: " + message);
            }
            assertTrue(failed, "违反 NOT NULL 约束必须报错，不能报 COPY 成功");
            assertEquals(0, count(conn, "t_bin_atomic3"),
                    "失败的 COPY 不能留下已写入的部分行（第 1 行）");

            // 报错之后同一条连接必须仍然可用（事务已经被正确回滚，不能停在 aborted 状态）
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("select 1 + 1")) {
                assertTrue(rs.next() && rs.getInt(1) == 2, "COPY 失败后会话必须仍然可用");
            }
        }
    }

    /**
     * 主键重复 —— 和 NOT NULL 完全无关的另一类错误，但同样只有 Appender flush 时才暴露。
     *
     * <p>用来钉住"错误上报是通用的、不是只为 NOT NULL 开的口子"：任何在 flush 时才出现的
     * 错误都必须原样带给客户端，不能被 {@code DuckDBAppender.close()} 吞掉。</p>
     */
    @Test
    void binaryDuplicateKeyErrorIsReportedAndLeavesNoRows() throws Exception {
        byte[] payload = PostgresSQLUtil.convertPGRowToByte(List.of(
                new Object[]{1, "Alice"},
                new Object[]{1, "Bob"}));   // id 重复

        try (Connection conn = connect()) {
            conn.createStatement().execute(
                    "create or replace table t_bin_atomic4(id integer primary key, name varchar)");

            boolean failed = false;
            try {
                copyIn(conn, "COPY t_bin_atomic4 (id, name) FROM STDIN WITH (FORMAT BINARY)", payload);
            } catch (SQLException e) {
                failed = true;
                String message = e.getMessage();
                assertTrue(message != null && message.toLowerCase().contains("constraint"),
                        "应把 Appender 报出的原因带给客户端，实际: " + message);
                assertTrue(!message.contains("catalog: 'null'"),
                        "应剥掉 Appender 的固定外壳(catalog/schema 恒为 'null')，实际: " + message);
            }
            assertTrue(failed, "主键冲突必须报错，不能报 COPY 成功");
            assertEquals(0, count(conn, "t_bin_atomic4"),
                    "失败的 COPY 不能留下任何行");
        }
    }

    // ============================================================
    // 成功路径不能受影响
    // ============================================================

    @Test
    void csvSuccessStillCommitsEveryRow() throws Exception {
        try (Connection conn = connect()) {
            conn.createStatement().execute(
                    "create or replace table t_csv_ok(id integer, name varchar, note varchar)");

            long n = copyIn(conn, "COPY t_csv_ok FROM STDIN WITH (FORMAT csv)",
                    "1,Alice,x\n2,Bob,y\n3,Cathy,z\n");

            assertEquals(3, n, "copyIn 应返回写入行数");
            assertEquals(3, count(conn, "t_csv_ok"), "成功的 COPY 必须把数据提交进表");
        }
    }

    @Test
    void binarySuccessStillCommitsEveryRow() throws Exception {
        byte[] payload = PostgresSQLUtil.convertPGRowToByte(List.of(
                new Object[]{1},
                new Object[]{2},
                new Object[]{3}));

        try (Connection conn = connect()) {
            conn.createStatement().execute("create or replace table t_bin_ok(id integer)");

            long n = copyIn(conn, "COPY t_bin_ok (id) FROM STDIN WITH (FORMAT BINARY)", payload);

            assertEquals(3, n, "copyIn 应返回写入行数");
            assertEquals(3, count(conn, "t_bin_ok"), "成功的 COPY 必须把数据提交进表");
        }
    }

    /** 失败之后同一连接必须仍可用，且下一次 COPY 能正常提交。 */
    @Test
    void sessionUsableAfterFailureAndNextCopyCommits() throws Exception {
        try (Connection conn = connect()) {
            conn.createStatement().execute(
                    "create or replace table t_recover(id integer, name varchar, note varchar)");

            try {
                copyIn(conn, "COPY t_recover FROM STDIN WITH (FORMAT csv)", "1,Alice,x\nbad,Bob,y\n");
                assertTrue(false, "应当报错");
            } catch (SQLException expected) {
                // 预期的失败
            }
            assertEquals(0, count(conn, "t_recover"), "失败后不应有残留数据");

            // 同一条连接继续做一次成功的 COPY
            long n = copyIn(conn, "COPY t_recover FROM STDIN WITH (FORMAT csv)", "7,Tom,t\n8,Jerry,j\n");
            assertEquals(2, n);
            assertEquals(2, count(conn, "t_recover"), "失败后的下一次 COPY 必须正常提交");
        }
    }

    /**
     * 客户端自己开的事务不能被失败的 COPY 连带回滚：
     * 服务端只回滚"自己为 COPY 开的事务"，客户端事务里先前的工作必须保留。
     */
    @Test
    void failedCopyDoesNotRollBackClientTransaction() throws Exception {
        try (Connection conn = connect()) {
            conn.createStatement().execute("create or replace table t_client_txn(id integer)");
            conn.createStatement().execute("begin");
            conn.createStatement().execute("insert into t_client_txn values (1)");

            boolean failed = false;
            try {
                // 第 1 行就非法，确保没有部分行干扰断言
                copyIn(conn, "COPY t_client_txn FROM STDIN WITH (FORMAT csv)", "not-a-number\n");
            } catch (SQLException e) {
                failed = true;
            }
            assertTrue(failed, "非法输入必须报错");

            assertEquals(1, count(conn, "t_client_txn"),
                    "失败的 COPY 不应把客户端事务里先前插入的行一起回滚");

            conn.createStatement().execute("rollback");
            assertEquals(0, count(conn, "t_client_txn"), "客户端 ROLLBACK 后应清空");
        }
    }

    /** 成功路径在客户端事务中也不能替客户端提交。 */
    @Test
    void successfulCopyInsideClientTransactionIsNotAutoCommitted() throws Exception {
        try (Connection conn = connect()) {
            conn.createStatement().execute("create or replace table t_client_txn2(id integer)");
            conn.createStatement().execute("begin");

            long n = copyIn(conn, "COPY t_client_txn2 FROM STDIN WITH (FORMAT csv)", "1\n2\n");
            assertEquals(2, n);
            assertEquals(2, count(conn, "t_client_txn2"), "事务内应可见");

            conn.createStatement().execute("rollback");
            assertEquals(0, count(conn, "t_client_txn2"),
                    "服务端不应替客户端提交：ROLLBACK 后数据必须消失");
        }
    }

    // ============================================================
    // 四、CopyCsvReader 解析器本身（原 sql/CopyCsvReaderTest）
    // ============================================================

    // ---------------------------------------------------------------- 语义

    private static List<String> parse(String payload) {
        return parseChunked(payload.getBytes(StandardCharsets.UTF_8), Integer.MAX_VALUE);
    }

    /** 以固定切片大小喂入，返回 {@code 值} / {@code <NULL>} / {@code <EOR>} 的序列。 */
    private static List<String> parseChunked(byte[] payload, int chunk) {
        List<String> out = new ArrayList<>();
        CopyCsvReader reader = new CopyCsvReader();
        int off = 0;
        while (off < payload.length) {
            int len = Math.min(chunk, payload.length - off);
            reader.append(payload, off, len);
            drain(reader, out);
            off += len;
        }
        reader.markEof();
        drain(reader, out);
        return out;
    }

    private static void drain(CopyCsvReader reader, List<String> out) {
        while (reader.nextRow(new CopyCsvReader.FieldSink() {
            @Override
            public void field(int off, int len, boolean isNull) {
                // isNull 由解析器按方言判定（CSV：未加引号的空字段；TEXT：等于 NULL 串的字段）
                if (isNull) {
                    out.add("<NULL>");
                } else {
                    out.add(new String(reader.buffer(), off, len, StandardCharsets.UTF_8));
                }
            }

            @Override
            public void endRow() {
                out.add("<EOR>");
            }
        })) {
            // keep going
        }
    }

    @Test
    void parsesPgCopyCsvDialect() {
        assertEquals(Arrays.asList("1", "a,b", "2.5", "<EOR>"), parse("1,\"a,b\",2.5\n"));
        assertEquals(Arrays.asList("1", "he said \"hi\"", "2.5", "<EOR>"), parse("1,\"he said \"\"hi\"\"\",2.5\n"));
        assertEquals(Arrays.asList("1", "line1\nline2", "2.5", "<EOR>"), parse("1,\"line1\nline2\",2.5\n"));
        assertEquals(Arrays.asList("1", "a", "<EOR>", "2", "b", "<EOR>"), parse("1,a\r\n2,b\r\n"));
        assertEquals(Arrays.asList("1", "a", "<EOR>"), parse("1,a"));
        assertEquals(Arrays.asList("1", "a", "<EOR>", "2", "b", "<EOR>"), parse("1,a\n\n2,b\n"));
        assertEquals(Arrays.asList(), parse("\n"));
        assertEquals(Arrays.asList("a", "b", "c", "d", "e", "<EOR>"), parse("a,b,c,d,e\n"));
        // 裸 \r 也当行尾
        assertEquals(Arrays.asList("1", "a", "<EOR>", "2", "b", "<EOR>"), parse("1,a\r2,b\r"));
        // 引号只在该字段的第一个字符才有特殊含义
        assertEquals(Arrays.asList("a\"b", "<EOR>"), parse("a\"b\n"));
    }

    @Test
    void distinguishesNullFromEmptyString() {
        // 未加引号的空字段 -> NULL
        assertEquals(Arrays.asList("1", "<NULL>", "<EOR>"), parse("1,\n"));
        // "" -> 空字符串（不是 NULL）
        assertEquals(Arrays.asList("1", "", "<EOR>"), parse("1,\"\"\n"));
        // 两者并存，必须能区分（这正是 commons-csv 做不到、也是本次行为变更的核心）
        assertEquals(Arrays.asList("1", "", "<EOR>", "2", "<NULL>", "<EOR>"), parse("1,\"\"\n2,\n"));
        // 整行都是空字段
        assertEquals(Arrays.asList("<NULL>", "<NULL>", "<EOR>"), parse(",\n"));
        // 空行的四周
        assertEquals(Arrays.asList("<NULL>", "", "<EOR>"), parse("\n,\"\"\n\n"));
    }

    @Test
    void rejectsMalformedInput() {
        // 引号未闭合
        assertThrows(CopyCsvReader.CsvFormatException.class, () -> parse("1,\"abc\n"));
        // 闭引号之后出现非分隔字符
        assertThrows(CopyCsvReader.CsvFormatException.class, () -> parse("1,\"abc\"x\n"));
        assertThrows(CopyCsvReader.CsvFormatException.class, () -> parse("1,\"abc\" \n"));
    }

    // ---------------------------------------------------------------- 增量正确性

    /**
     * 分片不变性：同一份载荷按 1..N 字节切片、以及随机切点喂入，结果必须一致。
     * 这是"一行被 TCP 分片切断"的等价场景。
     */
    @Test
    void chunkedFeedingProducesIdenticalResult() {
        String[] payloads = {
                "1,a,2.5\n2,\"b,b\",3.5\n3,\"he said \"\"hi\"\"\",4.5\n",
                "1,\"line1\nline2\",x\n2,\"\",\n3,,\n",
                "a\r\nb\r\n\"c\"\r\n",
                "1,\"\"\n2,\n\n3,z",
                "1,\"multi\nline\nwith \"\"quotes\"\" inside\"\n",
                "\n\n1,a\n\n2,b\n\n",
        };
        for (String payload : payloads) {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            List<String> expected = parseChunked(bytes, Integer.MAX_VALUE);
            for (int chunk = 1; chunk <= 12; chunk++) {
                assertEquals(expected, parseChunked(bytes, chunk),
                        "切片大小 " + chunk + " 时结果不一致，载荷=" + escape(payload));
            }
            // 随机切点
            Random random = new Random(42);
            for (int round = 0; round < 50; round++) {
                List<String> got = new ArrayList<>();
                CopyCsvReader reader = new CopyCsvReader();
                int off = 0;
                while (off < bytes.length) {
                    int len = 1 + random.nextInt(Math.min(7, bytes.length - off));
                    reader.append(bytes, off, len);
                    drain(reader, got);
                    off += len;
                }
                reader.markEof();
                drain(reader, got);
                assertEquals(expected, got, "随机切分时结果不一致，载荷=" + escape(payload));
            }
        }
    }

    private static String escape(String s) {
        return "\"" + s.replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }
}
