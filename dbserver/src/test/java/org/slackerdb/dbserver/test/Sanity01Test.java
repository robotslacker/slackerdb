package org.slackerdb.dbserver.test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.server.DBInstance;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.Calendar;
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
    void connectDBWithoutName() throws SQLException {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/";
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

    /**
     * 事务块内失败的语句必须让整个事务块进入 aborted 状态（PG 语义）。
     *
     * <p><b>注意：本用例改造前断言的是相反的行为</b> —— 失败之后继续执行后续语句，
     * 并期望两条 insert 都在（count=2 / sum=4）。那其实是
     * "Parse 阶段失败的语句不会中止事务块" 这个缺陷的产物：当时只有简单查询路径会调用
     * {@code markTransactionFailed()}，所以扩展协议下"表不存在"这类在 prepare 阶段就报错的语句
     * 不会中止事务块，后续语句照常执行。现在两条路径一致：
     * 失败后事务块被中止，后续语句一律以 25P02 拒绝，必须 ROLLBACK/COMMIT 才能继续。</p>
     */
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
            assert false : "对不存在的表插入应当失败";
        }
        catch (SQLException se)
        {
            assert se.getClass().getSimpleName().equalsIgnoreCase("PSQLException");
        }

        // 事务块已被中止：后续语句必须被 25P02 拒绝，而不是照常执行
        try {
            pgConn1.createStatement().execute("insert into testFailedHybridSQL values(3)");
            assert false : "失败事务块内的语句应当被 25P02 拒绝";
        }
        catch (SQLException se) {
            assert "25P02".equals(se.getSQLState())
                    : "失败事务块内的语句应以 25P02 拒绝，实际 " + se.getSQLState();
        }

        // ROLLBACK 结束事务块：块内那条成功的 insert(1) 一并回滚，会话恢复可用
        pgConn1.rollback();
        pgConn1.createStatement().execute("insert into testFailedHybridSQL values(4)");
        pgConn1.commit();

        ResultSet rs = pgConn1.createStatement().executeQuery("SELECT Count(*),Sum(id) from testFailedHybridSQL");

        int resultCount = 0;
        while (rs.next()) {
            resultCount++;
            assert rs.getInt(1) == 1
                    : "只有 ROLLBACK 之后提交的那一行应当存在，实际 " + rs.getInt(1) + " 行";
            assert rs.getInt(2) == 4
                    : "仅应有 id=4 这一行，实际 sum=" + rs.getInt(2);
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

    /**
     * 客户端在事务块中关闭连接（pgjdbc 会发送 Terminate 报文）时，未提交的改动必须被<b>回滚</b>。
     *
     * <p>PG 语义：没有显式 COMMIT 就等于没有提交，与连接是否优雅关闭无关。</p>
     *
     * <p>注意：改造前服务端的 {@code closeSession()} 对非 COPY 场景执行的是 commit，
     * 本用例原本名为 {@code testConnectionAutoCommitOnClose} 并断言"关闭时会提交"（recCount == 1），
     * 那正是 BUG-7 本身。现已按 PG 语义改为断言回滚。</p>
     */
    @Test
    void testConnectionRollbackOnClose() throws SQLException
    {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        pgConn1.createStatement().execute("Create TABLE testConnectionRollbackOnClose (id int)");
        pgConn1.commit();

        // 这条 INSERT 处于事务中且从未提交
        pgConn1.createStatement().execute("insert into testConnectionRollbackOnClose values(3)");
        pgConn1.close();

        Connection pgConn2 = DriverManager.getConnection(connectURL, "", "");
        pgConn2.setAutoCommit(false);

        ResultSet rs = pgConn2.createStatement().executeQuery("SELECT * from testConnectionRollbackOnClose");
        int recCount = 0;
        while (rs.next()) {
            recCount++;
        }
        // 未提交的数据必须被丢弃
        assert recCount == 0 : "事务中未提交的数据在连接关闭后被保留了，实际行数=" + recCount;
        rs.close();

        // 反向确认：显式提交之后关闭，数据必须保留（防止"一刀切全回滚"的过度修复）
        pgConn2.createStatement().execute("insert into testConnectionRollbackOnClose values(4)");
        pgConn2.commit();
        pgConn2.close();

        Connection pgConn3 = DriverManager.getConnection(connectURL, "", "");
        ResultSet rs2 = pgConn3.createStatement().executeQuery("SELECT * from testConnectionRollbackOnClose");
        int committedCount = 0;
        while (rs2.next()) {
            assert rs2.getInt(1) == 4;
            committedCount++;
        }
        assert committedCount == 1 : "已提交的数据在连接关闭后丢失，实际行数=" + committedCount;
        rs2.close();
        pgConn3.close();
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
    void testTimeInterval() throws Exception {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = """
                SELECT
                	INTERVAL 1 YEAR, -- single unit using YEAR keyword
                	INTERVAL (5.562) YEAR, -- parentheses necessary for variable amounts;
                	-- stored as integer number of months
                	INTERVAL '1 month 1 day', -- string type necessary for multiple units; stored as (1 month, 1 day)
                	'16 months'::INTERVAL, -- string cast supported; stored as 16 months
                	'24:00:00'::INTERVAL -- HH::MM::SS string supported
            """;
        Statement stmt = pgConn1.createStatement();
        ResultSet rs = stmt.executeQuery(sql);
        if (rs.next())
        {
            assert rs.getString(1).equalsIgnoreCase("1 year");
            assert rs.getString(2).equalsIgnoreCase("5 years");
            assert rs.getString(3).equalsIgnoreCase("1 month 1 day");
            assert rs.getString(4).equalsIgnoreCase("1 year 4 months");
            assert rs.getString(5).equalsIgnoreCase("24:00:00");
        }
        rs.close();
        stmt.close();
        pgConn1.close();
    }

    @Test
    void testBitString() throws Exception {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = """
                SELECT  10::BITSTRING, 200::BITSTRING
            """;
        Statement stmt = pgConn1.createStatement();
        ResultSet rs = stmt.executeQuery(sql);
        if (rs.next())
        {
            assert rs.getString(1).equalsIgnoreCase("00000000000000000000000000001010");
            assert rs.getString(2).equalsIgnoreCase("00000000000000000000000011001000");
            assert Utils.bytesToHex(rs.getBytes(1)).replace("30", "0").replace("31", "1").replace(" ","").equalsIgnoreCase("00000000000000000000000000001010");
            assert Utils.bytesToHex(rs.getBytes(2)).replace("30", "0").replace("31", "1").replace(" ","").equalsIgnoreCase("00000000000000000000000011001000");
        }
        rs.close();
        stmt.close();
        pgConn1.close();
    }

    @Test
    void testTimeStamp() throws Exception {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = """
                SELECT  TIMESTAMP '2026-10-01 00:00:00.123'
            """;
        Statement stmt = pgConn1.createStatement();
        ResultSet rs = stmt.executeQuery(sql);
        if (rs.next())
        {
            assert rs.getString(1).equalsIgnoreCase("2026-10-01 00:00:00.123");
            assert rs.getTimestamp(1).toString().equalsIgnoreCase("2026-10-01 00:00:00.123");
        }
        rs.close();
        stmt.close();
        pgConn1.close();
    }

    @Test
    void testInterval() throws Exception {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = """
                SELECT  to_microseconds(1)
            """;
        Statement stmt = pgConn1.createStatement();
        ResultSet rs = stmt.executeQuery(sql);
        if (rs.next())
        {
            assert rs.getString(1).equalsIgnoreCase("00:00:00.000001");
        }
        else
        {
            assert false;
        }
        rs.close();
        stmt.close();
        pgConn1.close();
    }

    @Test
    void testCurrentSchema() throws Exception {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem?currentSchema=main";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = """
                SELECT  current_catalog(), current_schema()
            """;
        Statement stmt = pgConn1.createStatement();
        ResultSet rs = stmt.executeQuery(sql);
        if (rs.next())
        {
            assert rs.getString(1).equalsIgnoreCase("mem");
            assert rs.getString(2).equalsIgnoreCase("main");
        }
        else
        {
            assert false;
        }
        rs.close();
        stmt.close();
        pgConn1.close();
    }

    @Test
    void testTime() throws Exception {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem?currentSchema=main";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = """
                create or replace table testTime(id int, col1 time);
                insert into testTime values(1, '02:14:34');
            """;
        Statement stmt = pgConn1.createStatement();
        stmt.execute(sql);
        stmt.close();
        pgConn1.commit();
        sql = "Select id, COL1 from testTime";
        stmt = pgConn1.createStatement();
        ResultSet rs = stmt.executeQuery(sql);
        if (rs.next())
        {
            assert rs.getInt(1) == 1;
            assert rs.getTime(2).toString().equalsIgnoreCase("02:14:34");
        }
        else
        {
            assert false;
        }
        rs.close();
        stmt.close();
        pgConn1.close();
    }

    /**
     * 结果集批量刷出（H1）回归测试。
     *
     * <p>构造一个编码后远大于 {@code PostgresMessage.FLUSH_THRESHOLD_BYTES}(64KB) 的结果集，
     * 使得数据行的发送过程必然跨越多次 flush。用于验证：</p>
     * <ul>
     *   <li>扩展协议路径（PreparedStatement）在一次 Execute 内多次 flush 时，行数与内容都完整正确；</li>
     *   <li>简单查询路径（Statement，逐行 write + 阈值 flush）同样完整；</li>
     *   <li>Portal 分批（setFetchSize）路径在 flush 与 PortalSuspended 混用时不丢行、不串行。</li>
     * </ul>
     */
    @Test
    void testLargeResultSetBatchedFlush() throws SQLException {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(connectURL, "", "");
        pgConn1.setAutoCommit(false);

        // 2000 行 x 约 60 字节 name，加上协议头，编码后总量约 150KB 以上，必然多次跨越 64KB 阈值
        final int rowCount = 2000;
        final String pad = "0123456789".repeat(6);
        Statement stmt = pgConn1.createStatement();
        stmt.execute("CREATE OR REPLACE TABLE testBatchedFlush (id INTEGER, name VARCHAR)");
        stmt.close();

        PreparedStatement insertStmt = pgConn1.prepareStatement(
                "INSERT INTO testBatchedFlush (id, name) VALUES (?, ?)");
        for (int i = 1; i <= rowCount; i++) {
            insertStmt.setInt(1, i);
            insertStmt.setString(2, i + "-" + pad);
            insertStmt.addBatch();
        }
        insertStmt.executeBatch();
        insertStmt.close();
        pgConn1.commit();

        // 1) 扩展协议：一次 Execute 返回全部行，期间会多次 flush
        PreparedStatement pStmt = pgConn1.prepareStatement("SELECT id, name FROM testBatchedFlush ORDER BY id");
        ResultSet rs = pStmt.executeQuery();
        int seen = 0;
        while (rs.next()) {
            seen = seen + 1;
            assert rs.getInt(1) == seen;
            assert rs.getString(2).equals(seen + "-" + pad);
        }
        assert seen == rowCount;
        rs.close();
        pStmt.close();

        // 2) 简单查询路径：逐行 write + 阈值 flush
        stmt = pgConn1.createStatement();
        rs = stmt.executeQuery("SELECT id, name FROM testBatchedFlush ORDER BY id");
        seen = 0;
        while (rs.next()) {
            seen = seen + 1;
            assert rs.getInt(1) == seen;
            assert rs.getString(2).equals(seen + "-" + pad);
        }
        assert seen == rowCount;
        rs.close();
        stmt.close();

        // 3) Portal 分批路径：每次只取 7 行，会在 flush 阈值与 PortalSuspended 之间交替
        pStmt = pgConn1.prepareStatement("SELECT id, name FROM testBatchedFlush ORDER BY id");
        pStmt.setFetchSize(7);
        rs = pStmt.executeQuery();
        seen = 0;
        while (rs.next()) {
            seen = seen + 1;
            assert rs.getInt(1) == seen;
            assert rs.getString(2).equals(seen + "-" + pad);
        }
        assert seen == rowCount;
        rs.close();
        pStmt.close();

        stmt = pgConn1.createStatement();
        stmt.execute("DROP TABLE testBatchedFlush");
        stmt.close();
        pgConn1.commit();
        pgConn1.close();
    }

    /**
     * 空结果集与单行结果集在批量刷出改造后仍应正常收尾（CommandComplete / ReadyForQuery 不丢）。
     */
    @Test
    void testEmptyAndSingleRowResultSet() throws SQLException {
        String  connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(connectURL, "", "");
        pgConn1.setAutoCommit(false);

        // 空结果集
        PreparedStatement pStmt = pgConn1.prepareStatement("SELECT x FROM (SELECT 42 AS x) t WHERE x = 0");
        ResultSet rs = pStmt.executeQuery();
        assert !rs.next();
        rs.close();
        pStmt.close();

        // 单行结果集
        pStmt = pgConn1.prepareStatement("SELECT x FROM (SELECT 42 AS x) t");
        rs = pStmt.executeQuery();
        assert rs.next();
        assert rs.getInt(1) == 42;
        assert !rs.next();
        rs.close();
        pStmt.close();

        // 空结果集之后连接仍然可用（验证 CommandComplete/ReadyForQuery 已正确刷出）
        pStmt = pgConn1.prepareStatement("SELECT x FROM (SELECT 7 AS x) t");
        rs = pStmt.executeQuery();
        assert rs.next();
        assert rs.getInt(1) == 7;
        rs.close();
        pStmt.close();

        pgConn1.close();
    }
}

