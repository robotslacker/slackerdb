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

        Timestamp start = new Timestamp(System.currentTimeMillis());
        Sleeper.sleep(3*1000);
        Timestamp end = new Timestamp(System.currentTimeMillis());
        System.out.println(end.getTime() - start.getTime());
    }
}

