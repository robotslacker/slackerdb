package org.slackerdb.test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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

        // 创建一个初始容量为 256 字节的 ByteBuf
        ByteBuf byteBuf = Unpooled.buffer(256);  // 256 字节的初始容量

        try {
            // 模拟写入一些数据
            for (int i = 0; i < 1000; i++) {
                byteBuf.writeInt(i);
            }

            // 输出 ByteBuf 的信息
            System.out.println("ByteBuf capacity: " + byteBuf.capacity());
            System.out.println("ByteBuf readable bytes: " + byteBuf.readableBytes());
        } finally {
            // 释放 ByteBuf
            byteBuf.release();
        }

    }
}

