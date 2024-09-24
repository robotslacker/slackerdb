package org.slackerdb.test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.slackerdb.Main;
import org.slackerdb.configuration.ServerConfiguration;
import org.slackerdb.server.DBInstance;
import org.slackerdb.utils.Sleeper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.*;
import java.util.Calendar;
import java.util.TimeZone;

public class Sanity01Test {
    static Thread dbThread = null;
    static int dbPort=4309;

    @BeforeAll
    static void initAll() {
        // 强制使用UTC时区，以避免时区问题在PG和后端数据库中不一致的行为
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // 启动slackerDB的服务
        Thread dbThread = new Thread(() -> {
            try {
                // 修改默认的db启动端口
                ServerConfiguration.LoadDefaultConfiguration();
                ServerConfiguration.setPort(dbPort);
                ServerConfiguration.setData("mem");

                // 启动数据库
                Main.setLogLevel("INFO");
                Main.serverStart();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        dbThread.start();
        while (true)
        {
            if (DBInstance.state.equalsIgnoreCase("RUNNING"))
            {
                break;
            }
            else
            {
                Sleeper.sleep(1000);
            }
        }
        System.out.println("TEST:: Server started successful ...");
    }


    @AfterAll
    static void tearDownAll() throws Exception{
        Main.serverStop();
        if (dbThread != null) {
            dbThread.interrupt();
        }
    }

    @Test
    void connectDB() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
    }

    @Test
    void simpleQuery() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
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
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
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
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
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
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
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
    void duplicateCcommitAndRollback() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        pgConn1.createStatement().execute("Create TABLE duplicateCcommitAndRollback (id int)");
        pgConn1.commit();

        pgConn1.createStatement().execute("insert into duplicateCcommitAndRollback values(3)");

        ResultSet rs = pgConn1.createStatement().executeQuery("SELECT * from duplicateCcommitAndRollback");

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

        rs = pgConn1.createStatement().executeQuery("SELECT COUNT(*) from duplicateCcommitAndRollback");
        while (rs.next()) {
            assert rs.getInt(1) == 0;
        }
        rs.close();

        pgConn1.createStatement().execute("insert into duplicateCcommitAndRollback values(5)");

        rs = pgConn1.createStatement().executeQuery("SELECT * from duplicateCcommitAndRollback");
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
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
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
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

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
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
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
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
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
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
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
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
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
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
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
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
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
    void testFetchSize() throws SQLException
    {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
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
        config.setJdbcUrl("jdbc:postgresql://127.0.0.1:" + dbPort + "/mem");
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
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        pgConn1.createStatement().execute("create table testCopy(id int, first_name varchar(20), last_name varchar(20))");
        String csvData = "1,John,Doe\n2,Jane,Smith"; // 示例数据

        // 使用BaseConnection以便于进行COPY操作
        CopyManager copyManager = new CopyManager((BaseConnection) pgConn1);

        // 执行COPY FROM STDIN操作
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
            if (rs.getInt("id") == 1)
            {
                assert rs.getString("first_name").equals("Doe");
                assert rs.getString("last_name").equals("John");
            }
            if (rs.getInt("id") == 2)
            {
                assert rs.getString("first_name").equals("Smith");
                assert rs.getString("last_name").equals("Jane");
            }
        }
        pstmt.close();
        pgConn1.close();

        assert nRows == expectedResult;
    }
}

