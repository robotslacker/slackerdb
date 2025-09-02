package org.slackerdb.dbserver.test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.slackerdb.common.utils.DBUtil;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.server.DBInstance;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

public class Sanity01Test {
    static int dbPort=4309;
    static DBInstance dbInstance ;
    static String     protocol = "postgresql";
    
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
        System.out.println("TEST:: Server started successful ...");
    }

    @AfterAll
    static void tearDownAll() {
        System.out.println("TEST:: Will shutdown server ...");
        System.out.println("TEST:: Active sessions : " +  dbInstance.activeSessions);
        dbInstance.stop();
        System.out.println("TEST:: Server stopped successful.");
        assert dbInstance.instanceState.equalsIgnoreCase("IDLE");
    }

    @Test
    void connectDB() throws SQLException {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
    }

    @Test
    void simpleQuery() throws SQLException {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);

        ResultSet rs = pgConn.createStatement().executeQuery("SELECT 3+4");
        while (rs.next()) {
            assert rs.getInt(1) == 7;
        }
        pgConn.close();
    }

    @Test
    void simpleDDL() throws SQLException {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);

        pgConn.createStatement().execute("Create TABLE aaa (id int)");
        pgConn.createStatement().execute("insert into aaa values(3)");

        ResultSet rs = pgConn.createStatement().executeQuery("SELECT * from aaa");
        while (rs.next()) {
            assert rs.getInt(1) == 3;
        }
        pgConn.close();
    }

    @Test
    void multiConnectionWithOneInstance() throws SQLException {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);
        Connection pgConn2 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn2.setAutoCommit(false);

        pgConn1.createStatement().execute("Create TABLE multiConnectionWithOneInstance (id int)");
        pgConn1.createStatement().execute("insert into multiConnectionWithOneInstance values(3)");
        pgConn1.commit();

        ResultSet rs = pgConn2.createStatement().executeQuery("SELECT * from multiConnectionWithOneInstance");
        while (rs.next()) {
            assert rs.getInt(1) == 3;
        }
        pgConn1.close();
        pgConn2.close();
    }

    @Test
    void commitAndRollback() throws SQLException {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        pgConn1.createStatement().execute("Create TABLE commitAndRollback (id int)");
        pgConn1.commit();

        pgConn1.createStatement().execute("insert into commitAndRollback values(3)");

        ResultSet rs = pgConn1.createStatement().executeQuery("SELECT * from commitAndRollback");

        while (rs.next()) {
            assert rs.getInt(1) == 3;
        }
        rs.close();

        pgConn1.rollback();

        rs = pgConn1.createStatement().executeQuery("SELECT COUNT(*) from commitAndRollback");
        while (rs.next()) {
            assert rs.getInt(1) == 0;
        }
        rs.close();

        pgConn1.createStatement().execute("insert into commitAndRollback values(5)");

        rs = pgConn1.createStatement().executeQuery("SELECT * from commitAndRollback");
        while (rs.next()) {
            assert rs.getInt(1) == 5;
        }
        rs.close();

        pgConn1.commit();
        pgConn1.close();
    }

    @Test
    void duplicateCommitAndRollback() throws SQLException {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        pgConn1.createStatement().execute("Create TABLE duplicateCommitAndRollback (id int)");
        pgConn1.commit();

        pgConn1.createStatement().execute("insert into duplicateCommitAndRollback values(3)");

        ResultSet rs = pgConn1.createStatement().executeQuery("SELECT * from duplicateCommitAndRollback");

        while (rs.next()) {
            assert rs.getInt(1) == 3;
        }
        rs.close();
        pgConn1.rollback();

        // 反复的多次commit和rollback
        pgConn1.commit();
        pgConn1.rollback();
        pgConn1.commit();
        pgConn1.rollback();

        rs = pgConn1.createStatement().executeQuery("SELECT COUNT(*) from duplicateCommitAndRollback");
        while (rs.next()) {
            assert rs.getInt(1) == 0;
        }
        rs.close();

        pgConn1.createStatement().execute("insert into duplicateCommitAndRollback values(5)");

        rs = pgConn1.createStatement().executeQuery("SELECT * from duplicateCommitAndRollback");
        while (rs.next()) {
            assert rs.getInt(1) == 5;
        }
        rs.close();

        pgConn1.commit();
        // 反复的多次commit和rollback
        pgConn1.commit();
        pgConn1.rollback();
        pgConn1.commit();
        pgConn1.rollback();

        pgConn1.close();
    }

    @Test
    void lotsOfConnection() throws SQLException {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        int  MAX_THREADS = 20;

        // 创建一个包含100个线程的数组
        Thread[] threads = new Thread[MAX_THREADS];

        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);
        pgConn1.createStatement().execute("DROP TABLE IF EXISTS lotsOfConnection");

        pgConn1.createStatement().execute("Create TABLE lotsOfConnection (id int)");
        pgConn1.commit();

        for (int i = 0; i < threads.length; i++) {
            int finalI = i;
            threads[i] = new Thread(() -> {
                try {
                    Connection pgConnX = DriverManager.getConnection(
                            connectURL, "", "");
                    pgConnX.setAutoCommit(false);
                    pgConnX.createStatement().execute("insert into lotsOfConnection values(" + finalI + ")");
                    pgConnX.commit();
                    pgConnX.close();
                } catch (SQLException se) {
                    se.printStackTrace();
                }
            });
        }

        // 启动所有线程
        for (Thread thread : threads) {
            thread.start();
        }

        // 等待所有线程完成
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        int expectedSumValue = 0;
        for (int i = 0; i < threads.length; i++) {
            expectedSumValue = expectedSumValue + i;
        }
        ResultSet rs = pgConn1.createStatement().executeQuery("SELECT COUNT(*),SUM(id) from lotsOfConnection");
        while (rs.next()) {
            assert rs.getInt(1) == MAX_THREADS;
            assert rs.getInt(2) == expectedSumValue;
        }
        rs.close();
        pgConn1.commit();
        pgConn1.close();
    }

    @Test
    void testFailedHybridSQL() throws SQLException {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        pgConn1.rollback();
        pgConn1.createStatement().execute("Create TABLE testFailedHybridSQL (id int)");
        pgConn1.commit();

        pgConn1.createStatement().execute("insert into testFailedHybridSQL values(1)");

        try {
            pgConn1.createStatement().execute("insert into testFailedHybridSQLFake values(2)");
        }
        catch (SQLException se)
        {
            assert se.getClass().getSimpleName().equalsIgnoreCase("PSQLException");
        }
        pgConn1.createStatement().execute("insert into testFailedHybridSQL values(3)");

        ResultSet rs = pgConn1.createStatement().executeQuery("SELECT Count(*),Sum(id) from testFailedHybridSQL");

        int resultCount = 0;
        while (rs.next()) {
            resultCount++;
            assert rs.getInt(1) == 2;
            assert rs.getInt(2) == 4;
        }
        rs.close();
        pgConn1.close();
        assert resultCount == 1;
    }

    @Test
    void variousDataTypeSelect() throws SQLException {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = "CREATE TABLE variousDataTypeSelect ( " +
                "id INTEGER PRIMARY KEY," +
                "name VARCHAR(100)," +
                "birth_date DATE," +
                "is_active BOOLEAN," +
                "salary DECIMAL(10, 2)," +
                "float_value FLOAT," +
                "double_value DOUBLE," +
                "numeric_value NUMERIC," +
                "timestamp_value TIMESTAMP" +
                ")";
        pgConn1.createStatement().execute(sql);

        sql = "INSERT INTO variousDataTypeSelect (id, name, birth_date, is_active, salary, float_value, double_value, numeric_value, timestamp_value) " +
                "VALUES " +
                "(1, 'John Doe', '1990-01-01', TRUE, 50000.00, 3.14, 3.14159, 12345, '2022-01-01 00:00:00')," +
                "(2, 'Jane Smith', '1995-05-15', FALSE, 60000.00, 2.71, 2.71828, 98765, '2023-06-30 12:00:00')";
        pgConn1.createStatement().execute(sql);

        ResultSet rs = pgConn1.createStatement().executeQuery("SELECT * from variousDataTypeSelect Where id = 2");
        while (rs.next()) {
            assert rs.getInt("id") == 2;
            assert rs.getString("name").equals("Jane Smith");
            assert rs.getDate("birth_date").toString().equals("1995-05-15");
            assert !rs.getBoolean("is_active");
            assert rs.getBigDecimal("salary").longValue() == 60000;
            assert Math.abs(rs.getFloat("float_value") - 2.71) < 0.001;
            assert rs.getDouble("double_value") == 2.71828;
            assert rs.getDouble("numeric_value") == 98765;
            assert rs.getTimestamp("timestamp_value").toString().startsWith("2023-06-30 12:00:00");
        }
        rs.close();
        pgConn1.close();
    }

    @Test
    void testMultiPreparedStmt() throws SQLException {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        PreparedStatement pstmt1 = pgConn1.prepareStatement("Select 3+4");
        PreparedStatement pstmt2 = pgConn1.prepareStatement("Select 5+8");
        ResultSet rs = pstmt1.executeQuery();
        int resultCount = 0;
        while (rs.next()) {
            assert rs.getInt(1) == 7;
            resultCount++;
        }
        assert resultCount == 1;
        rs.close();

        rs = pstmt2.executeQuery();
        resultCount = 0;
        while (rs.next()) {
            assert rs.getInt(1) == 13;
            resultCount++;
        }
        assert resultCount == 1;

        pstmt1.close();
        pstmt2.close();
        pgConn1.close();
    }

    @Test
    void testBindInsert() throws SQLException {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = "CREATE TABLE testBindInsert ( " +
                "id INTEGER PRIMARY KEY," +
                "name VARCHAR(100)," +
                "birth_date DATE," +
                "is_active BOOLEAN," +
                "salary DECIMAL(10, 2)," +
                "float_value FLOAT," +
                "double_value DOUBLE," +
                "numeric_value NUMERIC," +
                "timestamp_value TIMESTAMP" +
                ")";
        pgConn1.createStatement().execute(sql);

        sql = "INSERT INTO testBindInsert (id, name, birth_date, is_active, salary, float_value, double_value, numeric_value, timestamp_value) " +
                "VALUES " +
                "(?, ?, ?, ?, ?, ?, ?, ?, ?)";
        PreparedStatement pStmt = pgConn1.prepareStatement(sql);
        pStmt.setLong(1, 99);
        pStmt.setString(2, "John Doe");
        pStmt.setDate(3, Date.valueOf("1990-01-01"));
        pStmt.setBoolean(4, true);
        pStmt.setBigDecimal(5, new BigDecimal("50000"));
        pStmt.setFloat(6, 3.14F);
        pStmt.setDouble(7, 3.14159);
        pStmt.setInt(8, 12345);
        pStmt.setTimestamp(9, Timestamp.valueOf("2022-06-30 12:00:00"));
        pStmt.execute();

        ResultSet rs = pgConn1.createStatement().executeQuery("SELECT * from testBindInsert Where id = 99");
        int resultCount = 0;
        while (rs.next()) {
            resultCount++;
            assert rs.getInt("id") == 99;
            assert rs.getString("name").equals("John Doe");
            assert rs.getDate("birth_date").toString().equals("1990-01-01");
            assert rs.getBoolean("is_active");
            assert rs.getBigDecimal("salary").longValue() == 50000;
            assert Math.abs(rs.getFloat("float_value") - 3.14) < 0.001;
            assert rs.getDouble("double_value") == 3.14159;
            assert rs.getDouble("numeric_value") == 12345;
            assert rs.getTimestamp("timestamp_value", Calendar.getInstance(TimeZone.getTimeZone("UTC")))
                    .toString().startsWith("2022-06-30 12:00:00");
        }
        assert resultCount == 1;
        rs.close();
        pStmt.close();
        pgConn1.close();
    }

    @Test
    void testBindInsertWithExecuteUpdate() throws Exception {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = "CREATE TABLE testBindInsertWithExecuteUpdate ( " +
                "id INTEGER PRIMARY KEY," +
                "name VARCHAR(100)," +
                "birth_date DATE," +
                "is_active BOOLEAN," +
                "salary DECIMAL(10, 2)," +
                "float_value FLOAT," +
                "double_value DOUBLE," +
                "numeric_value NUMERIC," +
                "timestamp_value TIMESTAMP," +
                "time_value TIME," +
                "timestamp_tz_value TIMESTAMPTZ" +
                ")";
        pgConn1.createStatement().execute(sql);

        sql = "INSERT INTO testBindInsertWithExecuteUpdate " +
                "(id, name, birth_date, is_active, salary, float_value, double_value, " +
                "numeric_value, timestamp_value, time_value, timestamp_tz_value) " +
                "VALUES " +
                "(?, ?, ?, ?, ?,  ?, ?, ?, ?, ?, ?)";
        PreparedStatement pStmt = pgConn1.prepareStatement(sql);
        pStmt.setLong(1, 99);
        pStmt.setString(2, "John Doe");
        pStmt.setDate(3, Date.valueOf("1990-01-01"));
        pStmt.setBoolean(4, true);
        pStmt.setBigDecimal(5, new BigDecimal("50000"));
        pStmt.setFloat(6, 3.14F);
        pStmt.setDouble(7, 3.14159);
        pStmt.setInt(8, 12345);
        pStmt.setTimestamp(9, Timestamp.valueOf("2022-06-30 12:00:00"));
        pStmt.setTime(10, Time.valueOf("13:35:06"));
        pStmt.setTimestamp(11, Timestamp.valueOf("2022-07-30 12:00:00"));

        pStmt.executeUpdate();

        ResultSet rs = pgConn1.createStatement().executeQuery("SELECT * from testBindInsertWithExecuteUpdate Where id = 99");
        int resultCount = 0;
        while (rs.next()) {
            resultCount++;
            assert rs.getInt("id") == 99;
            assert rs.getString("name").equals("John Doe");
            assert rs.getDate("birth_date").toString().equals("1990-01-01");
            assert rs.getBoolean("is_active");
            assert rs.getBigDecimal("salary").longValue() == 50000;
            assert Math.abs(rs.getFloat("float_value") - 3.14) < 0.001;
            assert rs.getDouble("double_value") == 3.14159;
            assert rs.getDouble("numeric_value") == 12345;
            assert rs.getTimestamp("timestamp_value").toString().startsWith("2022-06-30 12:00:00");
            assert rs.getTime("time_value").toLocalTime().toString().equalsIgnoreCase("13:35:06");
            assert rs.getTimestamp("timestamp_tz_value").toString().startsWith("2022-07-30 12:00:00");
        }
        assert resultCount == 1;
        rs.close();
        pStmt.close();
        pgConn1.close();
    }

    @Test
    void BatchInsert() throws SQLException {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = "CREATE TABLE testBatchInsert ( " +
                "id INTEGER PRIMARY KEY," +
                "name VARCHAR(100)," +
                "birth_date DATE," +
                "is_active BOOLEAN," +
                "salary DECIMAL(10, 2)," +
                "float_value FLOAT," +
                "double_value DOUBLE," +
                "numeric_value NUMERIC," +
                "timestamp_value TIMESTAMP" +
                ")";
        pgConn1.createStatement().execute(sql);

        sql = "INSERT INTO testBatchInsert (id, name, birth_date, is_active, salary, float_value, double_value, numeric_value, timestamp_value) " +
                "VALUES " +
                "(?, 'John Doe', '1990-01-01', TRUE, 50000.00, 3.14, 3.14159, 12345, '2023-06-30 12:00:00')";
        PreparedStatement pStmt = pgConn1.prepareStatement(sql);
        int expectedResult = 0;
        for (int i=1; i<=100; i++) {
            pStmt.setInt(1, i);
            pStmt.addBatch();
            expectedResult = expectedResult + i;
        }
        pStmt.executeBatch();
        for (int i=1; i<=100; i++) {
            pStmt.setInt(1, i+100);
            pStmt.addBatch();
            expectedResult = expectedResult + i+100;
        }
        pStmt.executeBatch();
        pgConn1.commit();

        int actualResult = 0;
        ResultSet rs = pgConn1.createStatement().executeQuery("SELECT * from testBatchInsert");
        while (rs.next()) {
            actualResult = actualResult + rs.getInt("id");
        }
        rs.close();
        pStmt.close();
        pgConn1.commit();
        pgConn1.close();

        assert expectedResult == actualResult;
    }

    @Test
    void testConnectionAutoCommitOnClose() throws SQLException
    {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        pgConn1.createStatement().execute("Create TABLE testConnectionAutoCommitOnClose (id int)");
        pgConn1.commit();

        pgConn1.createStatement().execute("insert into testConnectionAutoCommitOnClose values(3)");
        pgConn1.close();

        Connection pgConn2 = DriverManager.getConnection(connectURL, "", "");
        pgConn2.setAutoCommit(false);

        ResultSet rs = pgConn2.createStatement().executeQuery("SELECT * from testConnectionAutoCommitOnClose");
        int recCount = 0;
        while (rs.next()) {
            recCount++;
            assert rs.getInt(1) == 3;
        }
        assert recCount == 1;
        rs.close();
        pgConn2.close();

    }

    @Test
    void testMultiString() throws SQLException
    {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = "CREATE TABLE testMultiString (id INTEGER)";
        pgConn1.createStatement().execute(sql);

        sql = "INSERT INTO testMultiString Values(1);INSERT INTO testMultiString Values(2);";
        PreparedStatement pStmt = pgConn1.prepareStatement(sql);
        pStmt.execute();

        pStmt = pgConn1.prepareStatement("select Count(*), Sum(Id) from testMultiString ");
        ResultSet rs = pStmt.executeQuery();
        while (rs.next())
        {
            assert rs.getInt(1) == 2;
            assert rs.getInt(2) == 3;
        }
        pStmt.close();

        pgConn1.close();
    }

    @Test
    void testFetchSize() throws SQLException
    {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = "CREATE TABLE testFetchSize ( " +
                "id INTEGER PRIMARY KEY," +
                "name VARCHAR(100)," +
                "birth_date DATE," +
                "is_active BOOLEAN," +
                "salary DECIMAL(10, 2)," +
                "float_value FLOAT," +
                "double_value DOUBLE," +
                "numeric_value NUMERIC," +
                "timestamp_value TIMESTAMP" +
                ")";
        pgConn1.createStatement().execute(sql);

        sql = "INSERT INTO testFetchSize (id, name, birth_date, is_active, salary, float_value, double_value, numeric_value, timestamp_value) " +
                "VALUES " +
                "(?, 'John Doe', '1990-01-01', TRUE, 50000.00, 3.14, 3.14159, 12345, '2023-06-30 12:00:00')";
        PreparedStatement pStmt = pgConn1.prepareStatement(sql);
        int expectedResult = 0;
        for (int i=1; i<=15; i++) {
            pStmt.setInt(1, i);
            pStmt.addBatch();
            expectedResult = expectedResult + i;
        }
        pStmt.executeBatch();
        pStmt.close();

        pStmt = pgConn1.prepareStatement("select * from testFetchSize order by 1");
        pStmt.setFetchSize(5);
        ResultSet rs = pStmt.executeQuery();
        for (int i=1; i<=5;i++)
        {
            rs.next();
            assert rs.getInt("id") == i;
        }

        // 中间打断一次查询
        PreparedStatement pstmt2 = pgConn1.prepareStatement("select 3+5");
        pstmt2.executeQuery();

        for (int i=6; i<=15;i++)
        {
            rs.next();
            assert rs.getInt("id") == i;
        }

        rs.close();
        pStmt.close();
        pgConn1.close();
    }

    @Test
    void testHikariCP() throws SQLException
    {
        // 忽略hakiri的日志，避免刷屏
        Logger hakiriLogger = (Logger) LoggerFactory.getLogger("com.zaxxer");
        hakiriLogger.setLevel(Level.OFF);

        // 创建HikariConfig实例并配置数据库连接信息
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem");
        config.setUsername("");
        config.setPassword("");

        // 创建HikariDataSource实例
        HikariDataSource hikariDataSource = new HikariDataSource(config);

        // 从连接池获取一个连接
        Connection connection = hikariDataSource.getConnection();

        // 使用获取到的连接进行操作...
        Statement stmt = connection.createStatement();
        ResultSet rs = stmt.executeQuery("Select 3+4");
        boolean hasValidResult = false;
        if (rs.next())
        {
            assert rs.getInt(1) == 7;
            hasValidResult = true;
        }
        assert hasValidResult;
        rs.close();
        stmt.cancel();

        // 关闭连接
        connection.close();

        // 关闭数据源（通常在应用程序关闭时进行）
        hikariDataSource.close();
    }

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

    @Test
    void testSetTimeStamp() throws SQLException
    {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        pgConn1.createStatement().execute("create table testSetTimeStamp(id int, game_time timestamp)");
        PreparedStatement preparedStatement = pgConn1.prepareStatement(
                "insert into testSetTimeStamp by name " +
                        "select * from (select 10 as id, current_timestamp as game_time) where game_time <= ?::TIMESTAMP");
        preparedStatement.setString(1, Timestamp.valueOf(LocalDateTime.now()).toString());
        preparedStatement.execute();

        preparedStatement.close();
        pgConn1.close();
    }

    @Test
    void testMultiStatement() throws SQLException
    {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        pgConn1.createStatement().execute("create table testMultiStatement(id int)");
        pgConn1.createStatement().execute("""
                """);
        pgConn1.createStatement().execute("""
                insert into testMultiStatement values(1);
                insert into testMultiStatement values(2);
                -- insert into testMultiStatement values(10);
                insert into testMultiStatement values(3);
                """);
        ResultSet rs = pgConn1.createStatement().executeQuery("Select Sum(Id) from testMultiStatement");
        if (rs.next())
        {
            assert  rs.getInt(1) == 6;
        }
        else
        {
            assert false;
        }
        rs.close();
        pgConn1.close();
    }

    @Test
    void testMultiStatement2() throws SQLException
    {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        pgConn1.createStatement().execute("""
                    drop table if exists testMultiStatement2;
                    -- VICTIM_XX1
                    -- VICTIM_XX1 HELLO
                    CREATE OR REPLACE TABLE testMultiStatement2
                    (
                        TIME                              DATETIME,
                        CHECKPOINT                        VARCHAR(25),
                        UNIQUE_ID                         DECIMAL(20),
                        KILLER_ID                         DECIMAL(20)
                    );
                    COMMENT ON TABLE testMultiStatement2 IS 'MultiStatement';
                """);
        pgConn1.close();
    }
    @Test
    void testMultiStatement3() throws SQLException
    {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        pgConn1.createStatement().execute("""
                    select 'POLYGON((118.960556 32.067500;129.787222 28.348333;123.650833 20.096111;118.928333 20.096111;112.342500 24.552222;118.960556 32.067500))';
                    select 'POLYGON((118.960556 32.067500;129.787222 28.348333;123.650833 20.096111;118.928333 20.096111;112.342500 24.552222;118.960556 32.067500))';
                """);
        pgConn1.close();
    }

    @Test
    void testParseStatementReuse() throws SQLException
    {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        pgConn1.createStatement().execute("""
                    Drop table if exists testParseStatementReuse;
                    Create Table testParseStatementReuse(id int, name varchar);
                    insert into testParseStatementReuse values(10,'AA');
                    insert into testParseStatementReuse values(20,'BB');
                    insert into testParseStatementReuse values(30,'CC');
                    insert into testParseStatementReuse values(40,'DD');
                    insert into testParseStatementReuse values(50,'EE');
                    insert into testParseStatementReuse values(60,'FF');
                    insert into testParseStatementReuse values(70,'GG');
                    insert into testParseStatementReuse values(80,'HH');
                """);
        PreparedStatement preparedStatement;
        Statement stmt;
        preparedStatement = pgConn1.prepareStatement("Select * From testParseStatementReuse Where id = ? and name = ?");
        ResultSet rs;
        preparedStatement.setInt(1, 10);
        preparedStatement.setString(2, "AA");
        rs = preparedStatement.executeQuery();
        rs.next();
        assert rs.getString(2).equals("AA") ;

        preparedStatement.setInt(1, 20);
        preparedStatement.setString(2, "BB");
        rs = preparedStatement.executeQuery();
        rs.next();
        assert rs.getString(2).equals("BB") ;

        preparedStatement.setInt(1, 30);
        preparedStatement.setString(2, "CC");
        rs = preparedStatement.executeQuery();
        rs.next();
        assert rs.getString(2).equals("CC") ;
//
        preparedStatement.setInt(1, 40);
        preparedStatement.setString(2, "DD");
        rs = preparedStatement.executeQuery();
        rs.next();
        assert rs.getString(2).equals("DD") ;

        preparedStatement.setInt(1, 50);
        preparedStatement.setString(2, "EE");
        rs = preparedStatement.executeQuery();
        rs.next();
        assert rs.getString(2).equals("EE") ;

        stmt = pgConn1.createStatement();
        stmt.execute("create table if not exists testParseStatementReuse22(num int)");
        stmt.close();

        preparedStatement.setInt(1, 60);
        preparedStatement.setString(2, "FF");
        rs = preparedStatement.executeQuery();
        rs.next();
        assert rs.getString(2).equals("FF") ;

        stmt = pgConn1.createStatement();
        stmt.execute("create or replace table testParseStatementReuse22(num int)");
        stmt.close();
        preparedStatement.setInt(1, 70);
        preparedStatement.setString(2, "GG");
        rs = preparedStatement.executeQuery();
        rs.next();
        assert rs.getString(2).equals("GG") ;

        stmt = pgConn1.createStatement();
        stmt.execute("create or replace table testParseStatementReuse22(num int)");
        stmt.close();
        preparedStatement.setInt(1, 80);
        preparedStatement.setString(2, "HH");
        rs = preparedStatement.executeQuery();
        rs.next();
        assert rs.getString(2).equals("HH") ;

        preparedStatement.close();
        pgConn1.close();
    }

    @Test
    void testBindDecimal() throws SQLException
    {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        pgConn1.createStatement().execute("create or replace table testtestBindDecimal(col decimal(10,2))");

        PreparedStatement preparedStatement = pgConn1.prepareStatement("insert into testtestBindDecimal values(?)");
        preparedStatement.setBigDecimal(1, BigDecimal.valueOf(100.2));
        preparedStatement.addBatch();
        preparedStatement.executeBatch();
        preparedStatement.close();
        pgConn1.commit();

        preparedStatement = pgConn1.prepareStatement("select * from testtestBindDecimal");
        ResultSet rs = preparedStatement.executeQuery();
        if (rs.next())
        {
            assert  rs.getBigDecimal(1).toPlainString().equals("100.20");
        }
        else
        {
            assert false;
        }
        rs.close();
        preparedStatement.close();
        pgConn1.close();
    }

    @Test
    void testBinaryCopy1() throws Exception
    {
        String timeStr1 = "2020-01-05 23:50:50";
        String timeStr2 = "2025-03-05 06:33:28";
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        List<Object[]> data = List.of(
                new Object[]
                        {
                                1, "Alice", 25.5, new BigDecimal("12345.6789"),
                                Timestamp.from(LocalDateTime.parse(timeStr1, formatter).atZone(ZoneId.of("UTC")).toInstant()),
                                true
                        },
                new Object[]
                        {
                                2, "Bob", 30.8, new BigDecimal("98765.4321"),
                                Timestamp.from(LocalDateTime.parse(timeStr2, formatter).atZone(ZoneId.of("UTC")).toInstant()),
                                true
                        }
        );
        byte[] binaryCopyData = DBUtil.convertPGRowToByte(data);

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
                                1, "Alice", 25.5, new BigDecimal("12345.6789"),
                                Timestamp.from(LocalDateTime.parse(timeStr1, formatter).atZone(ZoneId.of("UTC")).toInstant()),
                                true
                        },
                new Object[]
                        {
                                2, "Bob", 30.8, new BigDecimal("98765.4321"),
                                Timestamp.from(LocalDateTime.parse(timeStr2, formatter).atZone(ZoneId.of("UTC")).toInstant()),
                                true
                        }
        );
        byte[] binaryCopyData = DBUtil.convertPGRowToByte(data);

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
        byte[] binaryCopyData = DBUtil.convertPGRowToByte(data);

        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = """
            CREATE OR REPLACE TABLE test_binary_copy3 (
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
        byte[] binaryCopyData = DBUtil.convertPGRowToByte(data);

        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = """
            CREATE OR REPLACE TABLE test_binary_copy4 (
                id BIGINT PRIMARY KEY,
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
    void testJing() throws Exception {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem?currentSchema=main";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

    }
}

