package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.utils.Sleeper;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.server.DBInstance;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

public class Sanity02Test {
    static int dbPort = 4309;
    static DBInstance dbInstance;

    @BeforeAll
    static void initAll() throws ServerException {
        // 强制使用UTC时区，以避免时区问题在PG和后端数据库中不一致的行为
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // 修改默认的db启动端口
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setData("mem");
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("ON");
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
        System.out.println("TEST:: Active sessions : " + dbInstance.activeSessions);
        dbInstance.stop();
        System.out.println("TEST:: Server stopped successful.");
        assert dbInstance.instanceState.equalsIgnoreCase("IDLE");
    }

    @Test
    void testSQLHistory() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        pgConn1.createStatement().execute("create or replace table testSQLHistory(id int)");
        for (int i=0; i<200;i++)
        {
            pgConn1.createStatement().execute("Insert into testSQLHistory values(" + i + ")");
        }
        pgConn1.commit();
        pgConn1.close();

        pgConn1 = DriverManager.getConnection(connectURL, "", "");
        ResultSet rs = pgConn1.createStatement().executeQuery("Select Count(*) From sysaux.SQL_HISTORY");
        if (rs.next())
        {
            assert rs.getInt(1) > 200;
        }
        else
        {
            assert false;
        }
        rs.close();
        pgConn1.close();
    }

    @Test
    void testReadOnlyConnection() throws SQLException
    {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem?readOnly=true";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        try {
            pgConn1.createStatement().execute("create or replace table testReadOnlyConnection(id int)");
            assert false;
        }
        catch (SQLException sqlException)
        {
            assert sqlException.getMessage().contains("read-only");
        }

        pgConn1.close();
    }

    @Test
    void testConnectionMetadata() throws SQLException
    {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        pgConn1.createStatement().execute("ATTACH ':memory:' AS newdb1");
        pgConn1.createStatement().execute("ATTACH ':memory:' AS newdb2");
        pgConn1.commit();
        pgConn1.createStatement().execute("USE newdb1; CREATE SCHEMA schema1;");pgConn1.commit();
        pgConn1.createStatement().execute("USE newdb1; CREATE SCHEMA schema2;");pgConn1.commit();
        pgConn1.createStatement().execute("USE newdb2; CREATE SCHEMA schema3;");pgConn1.commit();
        pgConn1.createStatement().execute("USE newdb2; CREATE SCHEMA schema4;");pgConn1.commit();
        pgConn1.createStatement().execute("USE memory; CREATE SCHEMA schema5;");pgConn1.commit();
        pgConn1.createStatement().execute("USE memory; CREATE SCHEMA schema6;");pgConn1.commit();

        // getCatalogs
        pgConn1.createStatement().execute("USE memory.schema5; Create or replace Table tab1(col1 int,col2 bigint, col3 double, col4 varchar)");
        pgConn1.commit();

        ResultSet rs = pgConn1.getMetaData().getCatalogs();
        List<String> catalogsInfo = new ArrayList<>();
        while (rs.next())
        {
            for (int i=0; i<rs.getMetaData().getColumnCount();i++) {
                catalogsInfo.add(rs.getMetaData().getColumnName(i+1) + ": " + rs.getString(i+1));
            }
        }
        rs.close();
        Collections.sort(catalogsInfo);
        assert catalogsInfo.toString().equals("[TABLE_CAT: mem, TABLE_CAT: newdb1, TABLE_CAT: newdb2]");

        // getSchemas
        List<String> schemasInfo = new ArrayList<>();
        pgConn1.createStatement().execute("USE newdb1");
        rs = pgConn1.getMetaData().getSchemas();
        while (rs.next())
        {
            for (int i=0; i<rs.getMetaData().getColumnCount();i++) {
                schemasInfo.add(rs.getMetaData().getColumnName(i+1) + ": " + rs.getString(i+1));
            }
        }
        rs.close();
        Collections.sort(schemasInfo);
        assert schemasInfo.toString().equals("[TABLE_CATALOG: null, TABLE_CATALOG: null, TABLE_SCHEM: schema1, TABLE_SCHEM: schema2]");

        // getTables
        List<String> tablesInfo = new ArrayList<>();
        pgConn1.createStatement().execute("USE memory.schema5");
        rs = pgConn1.getMetaData().getTables("memory", "schema5", "%", new String[]{"TABLE"});
        while (rs.next())
        {
            for (int i=0; i<rs.getMetaData().getColumnCount();i++) {
                tablesInfo.add(rs.getMetaData().getColumnName(i+1) + ": " + rs.getString(i+1));
            }
        }
        rs.close();
        Collections.sort(tablesInfo);
        assert tablesInfo.toString().equals("[REF_GENERATION: , REMARKS: null, SELF_REFERENCING_COL_NAME: , TABLE_CAT: null, TABLE_NAME: tab1, TABLE_SCHEM: schema5, TABLE_TYPE: TABLE, TYPE_CAT: , TYPE_NAME: , TYPE_SCHEM: ]");

        // getColumns
        List<String> columnsInfo = new ArrayList<>();
        pgConn1.createStatement().execute("USE memory.schema5");
        rs = pgConn1.getMetaData().getColumns("memory", "schema5", "%", "%");
        while (rs.next())
        {
            String columnInfo = "";
            for (int i=0; i<rs.getMetaData().getColumnCount();i++) {
                columnInfo = columnInfo +  rs.getMetaData().getColumnName(i+1) + ": " + rs.getString(i+1) + ",";
            }
            columnsInfo.add(columnInfo);
        }
        rs.close();
        Collections.sort(columnsInfo);
        assert columnsInfo.get(0).equals("TABLE_CAT: null,TABLE_SCHEM: schema5,TABLE_NAME: tab1,COLUMN_NAME: col1,DATA_TYPE: 1111,TYPE_NAME: null,COLUMN_SIZE: 2147483647,BUFFER_LENGTH: null,DECIMAL_DIGITS: 0,NUM_PREC_RADIX: 10,NULLABLE: 1,REMARKS: null,COLUMN_DEF: null,SQL_DATA_TYPE: null,SQL_DATETIME_SUB: null,CHAR_OCTET_LENGTH: 2147483647,ORDINAL_POSITION: 1,IS_NULLABLE: YES,SCOPE_CATALOG: null,SCOPE_SCHEMA: null,SCOPE_TABLE: null,SOURCE_DATA_TYPE: null,IS_AUTOINCREMENT: NO,IS_GENERATEDCOLUMN: NO,");
        assert columnsInfo.get(1).equals("TABLE_CAT: null,TABLE_SCHEM: schema5,TABLE_NAME: tab1,COLUMN_NAME: col2,DATA_TYPE: 1111,TYPE_NAME: null,COLUMN_SIZE: 2147483647,BUFFER_LENGTH: null,DECIMAL_DIGITS: 0,NUM_PREC_RADIX: 10,NULLABLE: 1,REMARKS: null,COLUMN_DEF: null,SQL_DATA_TYPE: null,SQL_DATETIME_SUB: null,CHAR_OCTET_LENGTH: 2147483647,ORDINAL_POSITION: 2,IS_NULLABLE: YES,SCOPE_CATALOG: null,SCOPE_SCHEMA: null,SCOPE_TABLE: null,SOURCE_DATA_TYPE: null,IS_AUTOINCREMENT: NO,IS_GENERATEDCOLUMN: NO,");
        assert columnsInfo.get(2).equals("TABLE_CAT: null,TABLE_SCHEM: schema5,TABLE_NAME: tab1,COLUMN_NAME: col3,DATA_TYPE: 4,TYPE_NAME: int4,COLUMN_SIZE: 10,BUFFER_LENGTH: null,DECIMAL_DIGITS: 0,NUM_PREC_RADIX: 10,NULLABLE: 1,REMARKS: null,COLUMN_DEF: null,SQL_DATA_TYPE: null,SQL_DATETIME_SUB: null,CHAR_OCTET_LENGTH: 10,ORDINAL_POSITION: 3,IS_NULLABLE: YES,SCOPE_CATALOG: null,SCOPE_SCHEMA: null,SCOPE_TABLE: null,SOURCE_DATA_TYPE: null,IS_AUTOINCREMENT: NO,IS_GENERATEDCOLUMN: NO,");
        assert columnsInfo.get(3).equals("TABLE_CAT: null,TABLE_SCHEM: schema5,TABLE_NAME: tab1,COLUMN_NAME: col4,DATA_TYPE: 12,TYPE_NAME: text,COLUMN_SIZE: 2147483647,BUFFER_LENGTH: null,DECIMAL_DIGITS: 0,NUM_PREC_RADIX: 10,NULLABLE: 1,REMARKS: null,COLUMN_DEF: null,SQL_DATA_TYPE: null,SQL_DATETIME_SUB: null,CHAR_OCTET_LENGTH: 2147483647,ORDINAL_POSITION: 4,IS_NULLABLE: YES,SCOPE_CATALOG: null,SCOPE_SCHEMA: null,SCOPE_TABLE: null,SOURCE_DATA_TYPE: null,IS_AUTOINCREMENT: NO,IS_GENERATEDCOLUMN: NO,");

        // getIndexInfo
        // getPrimaryKeys
    }
}

