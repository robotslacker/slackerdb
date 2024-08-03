package org.slackerdb.test;

import org.slackerdb.utils.Sleeper;
import org.slackerdb.utils.Utils;

import javax.xml.transform.Result;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.*;
import java.util.Calendar;
import java.util.TimeZone;

public class test002 {

    public static void main(String[] args) throws SQLException, ClassNotFoundException {
         Connection conn = DriverManager.getConnection("jdbc:duckdb:c:\\temp\\aa.db");
//        Connection conn = DriverManager.getConnection("jdbc:h2:");

        String sql = "CREATE TABLE aaa (id int, col1 Timestamp)";
//        conn.createStatement().execute(sql);
//        conn.createStatement().execute("SET TimeZone = 'Asia/Shanghai'");

        PreparedStatement preparedStatement = conn.prepareStatement("insert into aaa values(1,?)");

        preparedStatement.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
        preparedStatement.execute();

        ResultSet rs = conn.prepareStatement("select * from aaa").executeQuery();
        while (rs.next()) {
            System.out.println(rs.getTimestamp("col1"));
        }
    }
}

