package org.slackerdb.test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.duckdb.DuckDBResultSet;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.slackerdb.server.DBInstance;
import org.slackerdb.sql.SQLReplacer;
import org.slackerdb.utils.Sleeper;
import org.slackerdb.utils.Utils;

import javax.xml.transform.Result;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class test002 {
    public static void main(String[] args) throws SQLException, ClassNotFoundException, IOException {
        DuckDBConnection duckDBConnection =
                (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:", "", "");

        class testClass extends Thread
        {
            @Override
            public void run()
            {
                try {
                    Connection conn = duckDBConnection.duplicate();
                    Statement stmt = conn.createStatement();
//                    stmt.execute("DETACH DATABASE IF EXISTS db");
                    stmt.execute("ATTACH IF NOT EXISTS 'dbname=postgres user=postgres password=123456 port=5432 host=192.168.11.120' AS db (TYPE POSTGRES, READ_ONLY, SCHEMA test1)");
                    stmt.close();
                    conn.close();
                    System.out.println("test OK");
                }
                catch (SQLException se)
                {
                    System.out.println(se.getMessage());
                }
            }
        };

        for (int i=0;i<10;i++)
        {
            testClass t = new testClass();
            t.start();
        }
        System.out.println("OK");
    }
}

