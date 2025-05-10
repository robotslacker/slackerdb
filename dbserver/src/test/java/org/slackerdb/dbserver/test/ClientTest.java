package org.slackerdb.dbserver.test;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClientTest {
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
    void testSlackerDriverConnection() throws SQLException {
        Logger.getLogger("org.postgresql.Driver").setLevel(Level.FINE);
        String connectURL = "jdbc:slackerdb://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);
        pgConn1.createStatement().execute("ATTACH ':memory:' AS newdb1");
        pgConn1.createStatement().execute("ATTACH ':memory:' AS newdb2");
        pgConn1.commit();
        pgConn1.createStatement().execute("USE newdb1; CREATE SCHEMA schema1;");
        pgConn1.commit();
        pgConn1.createStatement().execute("USE newdb1; CREATE SCHEMA schema2;");
        pgConn1.commit();
        pgConn1.createStatement().execute("USE newdb2; CREATE SCHEMA schema3;");
        pgConn1.commit();
        pgConn1.createStatement().execute("USE newdb2; CREATE SCHEMA schema4;");
        pgConn1.commit();
        pgConn1.createStatement().execute("USE memory; CREATE SCHEMA schema5;");
        pgConn1.commit();
        pgConn1.createStatement().execute("USE memory; CREATE SCHEMA schema6;");
        pgConn1.commit();

        pgConn1.createStatement().execute("USE memory.schema5; Create or replace Table tab1(col1 int,col2 bigint, col3 double, col4 varchar)");
        pgConn1.commit();

        // getCatalogs
        ResultSet rs = pgConn1.getMetaData().getCatalogs();
        List<String> catalogsInfo = new ArrayList<>();
        while (rs.next()) {
            for (int i = 0; i < rs.getMetaData().getColumnCount(); i++) {
                catalogsInfo.add(rs.getMetaData().getColumnName(i + 1) + ": " + rs.getString(i + 1));
            }
        }
        rs.close();
        Collections.sort(catalogsInfo);
        assert catalogsInfo.toString().equals("[TABLE_CAT: memory, TABLE_CAT: newdb1, TABLE_CAT: newdb2, TABLE_CAT: system, TABLE_CAT: temp]");

        // getSchemas
        List<String> schemasInfo = new ArrayList<>();
        pgConn1.createStatement().execute("USE newdb1");
        rs = pgConn1.getMetaData().getSchemas();
        while (rs.next()) {
            for (int i = 0; i < rs.getMetaData().getColumnCount(); i++) {
                schemasInfo.add(rs.getMetaData().getColumnName(i + 1) + ": " + rs.getString(i + 1));
            }
        }
        rs.close();
        Collections.sort(schemasInfo);
        assert schemasInfo.toString().equals("[TABLE_CATALOG: memory, TABLE_CATALOG: memory, TABLE_CATALOG: memory, TABLE_CATALOG: memory, TABLE_CATALOG: memory, TABLE_CATALOG: newdb1, TABLE_CATALOG: newdb1, TABLE_CATALOG: newdb1, TABLE_CATALOG: newdb2, TABLE_CATALOG: newdb2, TABLE_CATALOG: newdb2, TABLE_CATALOG: system, TABLE_CATALOG: system, TABLE_CATALOG: system, TABLE_CATALOG: temp, TABLE_SCHEM: duck_catalog, TABLE_SCHEM: information_schema, TABLE_SCHEM: main, TABLE_SCHEM: main, TABLE_SCHEM: main, TABLE_SCHEM: main, TABLE_SCHEM: main, TABLE_SCHEM: pg_catalog, TABLE_SCHEM: schema1, TABLE_SCHEM: schema2, TABLE_SCHEM: schema3, TABLE_SCHEM: schema4, TABLE_SCHEM: schema5, TABLE_SCHEM: schema6, TABLE_SCHEM: sysaux]");

        // getTables
        List<String> tablesInfo = new ArrayList<>();
        pgConn1.createStatement().execute("USE memory.schema5");
        rs = pgConn1.getMetaData().getTables("memory", "%", "%", new String[]{"BASE TABLE"});
        JSONArray tableDefines = new JSONArray();
        while (rs.next()) {
            if (rs.getString("TABLE_SCHEM").equalsIgnoreCase("duck_catalog")) {
                continue;
            }
            JSONObject tableDefine = new JSONObject();
            for (int i = 0; i < rs.getMetaData().getColumnCount(); i++) {
                tableDefine.put(rs.getMetaData().getColumnName(i + 1), rs.getString(i + 1));
            }
            tableDefines.add(tableDefine);
        }
        rs.close();
        assert tableDefines.toJSONString(JSONWriter.Feature.MapSortField).trim().equals("""
                [{"TABLE_CAT":"memory","TABLE_SCHEM":"schema5","TABLE_NAME":"tab1","TABLE_TYPE":"BASE TABLE"},{"TABLE_CAT":"memory","TABLE_SCHEM":"sysaux","TABLE_NAME":"SQL_HISTORY","TABLE_TYPE":"BASE TABLE"}]
                """.trim());

        // getColumns
        JSONArray columnDefines = new JSONArray();
        pgConn1.createStatement().execute("USE memory.schema5");
        rs = pgConn1.getMetaData().getColumns("memory", "schema5", "%", "%");
        while (rs.next())
        {
            if (rs.getString("TABLE_SCHEM").equalsIgnoreCase("duck_catalog")) {
                continue;
            }
            JSONObject columnDefine = new JSONObject();
            for (int i=0; i<rs.getMetaData().getColumnCount();i++) {
                columnDefine.put(rs.getMetaData().getColumnName(i + 1), rs.getString(i + 1));
            }
            columnDefines.add(columnDefine);
        }
        rs.close();
        assert columnDefines.toJSONString(JSONWriter.Feature.MapSortField).equals("[{\"TABLE_CAT\":\"memory\",\"TABLE_SCHEM\":\"schema5\",\"TABLE_NAME\":\"tab1\",\"COLUMN_NAME\":\"col1\",\"DATA_TYPE\":\"4\",\"TYPE_NAME\":\"INTEGER\",\"COLUMN_SIZE\":\"32\",\"DECIMAL_DIGITS\":\"0\",\"NUM_PREC_RADIX\":\"10\",\"NULLABLE\":\"1\",\"ORDINAL_POSITION\":\"1\",\"IS_NULLABLE\":\"YES\",\"IS_AUTOINCREMENT\":\"\",\"IS_GENERATEDCOLUMN\":\"\"},{\"TABLE_CAT\":\"memory\",\"TABLE_SCHEM\":\"schema5\",\"TABLE_NAME\":\"tab1\",\"COLUMN_NAME\":\"col2\",\"DATA_TYPE\":\"-5\",\"TYPE_NAME\":\"BIGINT\",\"COLUMN_SIZE\":\"64\",\"DECIMAL_DIGITS\":\"0\",\"NUM_PREC_RADIX\":\"10\",\"NULLABLE\":\"1\",\"ORDINAL_POSITION\":\"2\",\"IS_NULLABLE\":\"YES\",\"IS_AUTOINCREMENT\":\"\",\"IS_GENERATEDCOLUMN\":\"\"},{\"TABLE_CAT\":\"memory\",\"TABLE_SCHEM\":\"schema5\",\"TABLE_NAME\":\"tab1\",\"COLUMN_NAME\":\"col3\",\"DATA_TYPE\":\"8\",\"TYPE_NAME\":\"DOUBLE\",\"COLUMN_SIZE\":\"53\",\"DECIMAL_DIGITS\":\"0\",\"NUM_PREC_RADIX\":\"10\",\"NULLABLE\":\"1\",\"ORDINAL_POSITION\":\"3\",\"IS_NULLABLE\":\"YES\",\"IS_AUTOINCREMENT\":\"\",\"IS_GENERATEDCOLUMN\":\"\"},{\"TABLE_CAT\":\"memory\",\"TABLE_SCHEM\":\"schema5\",\"TABLE_NAME\":\"tab1\",\"COLUMN_NAME\":\"col4\",\"DATA_TYPE\":\"12\",\"TYPE_NAME\":\"VARCHAR\",\"NUM_PREC_RADIX\":\"10\",\"NULLABLE\":\"1\",\"ORDINAL_POSITION\":\"4\",\"IS_NULLABLE\":\"YES\",\"IS_AUTOINCREMENT\":\"\",\"IS_GENERATEDCOLUMN\":\"\"}]");

        // getIndexInfo
        // getPrimaryKeys
    }
}
