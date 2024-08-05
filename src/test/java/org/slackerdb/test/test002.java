package org.slackerdb.test;

import org.duckdb.DuckDBConnection;
import org.duckdb.DuckDBResultSet;
import org.slackerdb.utils.Sleeper;
import org.slackerdb.utils.Utils;

import javax.xml.transform.Result;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.TimeZone;

public class test002 {

    public static void main(String[] args) throws SQLException, ClassNotFoundException {
        Connection conn = DriverManager.getConnection("jdbc:duckdb:");

        String sql = "CREATE TABLE aaa (col1 timestamptz)";
        conn.createStatement().execute(sql);
        PreparedStatement preparedStatement = conn.prepareStatement("insert into aaa values(?)");

        preparedStatement.setString(1, "2020-01-01 12:00:00 CST");
        preparedStatement.execute();


        ResultSet rs = conn.prepareStatement("select * from aaa").executeQuery();
        while (rs.next()) {
            DuckDBResultSet xxrs = (DuckDBResultSet) rs;
            Instant instant = rs.getTimestamp("col1").toInstant();
            ZonedDateTime zonedDateTime = instant.atZone(ZoneId.systemDefault());
            System.out.println(zonedDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS[XXX][ VV]")));
        }
    }
}

