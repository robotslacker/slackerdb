package org.slackerdb.test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.duckdb.DuckDBConnection;
import org.duckdb.DuckDBResultSet;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.slackerdb.server.DBInstance;
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
        System.out.println(String.format("%-60s", "  Data:") + "abcd");
        if (true)
        {
            System.exit(0);
        }
        List<CSVRecord> records = new ArrayList<>();

        StringReader reader;
        String       sourceStr;
        String copyLastRemained = "fdasfasd,";
        byte[] copyData = "xx,yy\nxxx,yyy,zzz\nabc\\\"aa".getBytes();

        // 和上次没有解析完全的字符串要拼接起来
        if (!copyLastRemained.isEmpty())
        {
            sourceStr = copyLastRemained + new String(copyData);
        }
        else
        {
            sourceStr = new String(copyData);
        }
        Iterable<CSVRecord> parsedRecords = CSVFormat.DEFAULT.parse(new StringReader(sourceStr));
        for (CSVRecord record : parsedRecords) {
            System.out.println(record);
            System.out.println("ready");
            System.out.println(record.getCharacterPosition());
            System.out.println(sourceStr.substring((int)record.getCharacterPosition()));
            records.add(record);
            copyLastRemained = "";
        }
//            System.out.println(record.get(1));
//            System.out.println(record.get(2));
//            System.out.println(record.get(3));
//        }

    }
}

