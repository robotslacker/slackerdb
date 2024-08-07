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

        // 设置时区为东八区，或者你需要的其他时区
        ZonedDateTime dateTime = ZonedDateTime.now(ZoneId.of("UTC"));
        // 使用指定的格式，包括时区信息
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSx");
        String formattedDate = dateTime.format(formatter);
        System.out.println(formattedDate);

        if (true)
        {
            System.exit(0);
        }

        Connection conn = DriverManager.getConnection("jdbc:duckdb:");

        System.out.println(Instant.now().getEpochSecond());

        System.out.println(Timestamp.valueOf("2024-08-05 19:00:00").toInstant().getEpochSecond());

        if (true)
        {
            System.exit(0);
        }
        conn.createStatement().execute("set TimeZone = 'UTC'");

        String sql = "CREATE TABLE aaa (col1 timestamp)";
        conn.createStatement().execute(sql);
        PreparedStatement preparedStatement = conn.prepareStatement("insert into aaa values(?)");

        ResultSet resultSet = conn.prepareStatement("SELECT value FROM duckdb_settings() WHERE name = 'TimeZone'").executeQuery();
        if (resultSet.next()) {
            System.out.println(resultSet.getString(1));
        }
//        preparedStatement.setString(1, "2020-01-01 12:00:00+08:00");
        Instant myInstant = Instant.now();
        preparedStatement.setTimestamp(1, Timestamp.from(myInstant));
//        preparedStatement.setString(1, "2020-01-01 12:00:00+08");
        preparedStatement.execute();
        System.out.println(myInstant.getEpochSecond());

        ResultSet rs = conn.prepareStatement("select * from aaa").executeQuery();
        while (rs.next()) {
            System.out.println(rs.getTimestamp("col1"));
            System.out.println(rs.getTimestamp("col1").toInstant().getEpochSecond());

            System.out.println(rs.getTimestamp("col1").toLocalDateTime().atZone(ZoneId.systemDefault()));
            DuckDBResultSet xxrs = (DuckDBResultSet) rs;
            Instant instant = rs.getTimestamp("col1").toInstant();
            ZonedDateTime zonedDateTime = instant.atZone(ZoneId.systemDefault());
            System.out.println(zonedDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS[XXX][ VV]")));
        }
    }
}

