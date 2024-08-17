package org.slackerdb.protocol.postgres.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.protocol.postgres.entity.Column;
import org.slackerdb.protocol.postgres.entity.Field;
import org.slackerdb.protocol.postgres.entity.PostgresTypeOids;
import org.slackerdb.protocol.postgres.message.*;
import org.slackerdb.protocol.postgres.message.response.*;
import org.slackerdb.server.DBInstance;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;


public class ExecuteRequest extends PostgresRequest {
    //  Execute (F)
    //    Byte1('E')
    //      Identifies the message as an Execute command.
    //    Int32
    //      Length of message contents in bytes, including self.
    //    String
    //      The name of the portal to execute (an empty string selects the unnamed portal).
    //    Int32
    //      Maximum number of rows to return, if portal contains a query that returns rows (ignored otherwise). Zero denotes “no limit”.

    private String portalName;
    private int    maximumRowsReturned;

    @Override
    public void decode(byte[] data) {
        byte[][] result = Utils.splitByteArray(data, (byte)0);
        portalName = new String(result[0], StandardCharsets.UTF_8);
        byte[] part = Arrays.copyOfRange(
                data,
                portalName.length() + 1, portalName.length() + 5);
        maximumRowsReturned = Utils.bytesToInt32(part);

        super.decode(data);
    }

    private List<Column> processRow(ResultSet rs, ResultSetMetaData rsmd) throws SQLException
    {
        List<Column> columns = new ArrayList<>();
        for (int i = 1; i <= rsmd.getColumnCount(); i++) {
            Column column = new Column();
            if (rs.getObject(i) == null) {
                column.columnLength = -1;
            } else
            {
                String columnTypeName = rsmd.getColumnTypeName(i);
                switch (columnTypeName.toUpperCase()) {
                    case "INTEGER":
                        column.columnLength = 4;
                        column.columnValue = Utils.int32ToBytes(rs.getInt(i));
                        break;
                    case "BIGINT":
                    case "HUGEINT":
                        column.columnLength = 8;
                        column.columnValue = Utils.int64ToBytes(rs.getLong(i));
                        break;
                    case "VARCHAR":
                        byte[] columnBytes = rs.getString(i).getBytes(StandardCharsets.UTF_8);
                        column.columnLength = columnBytes.length;
                        column.columnValue = columnBytes;
                        break;
                    case "DATE":
                        column.columnLength = 4;
                        long timeInterval =
                                ChronoUnit.DAYS.between(LocalDate.of(2000, 1, 1), rs.getDate(i).toLocalDate());
                        column.columnValue = Utils.int32ToBytes((int) timeInterval);
                        break;
                    case "BOOLEAN":
                        column.columnLength = 1;
                        if (rs.getBoolean(i)) {
                            column.columnValue = new byte[]{(byte) 0x01};
                        } else {
                            column.columnValue = new byte[]{(byte) 0x00};
                        }
                        break;
                    case "FLOAT":
                        column.columnLength = 4;
                        column.columnValue = Utils.int32ToBytes(Float.floatToIntBits(rs.getFloat(i)));
                        break;
                    case "DOUBLE":
                        column.columnLength = 8;
                        column.columnValue = Utils.int64ToBytes(Double.doubleToLongBits(rs.getDouble(i)));
                        break;
                    case "TIMESTAMP":
                        column.columnLength = 19;
                        column.columnValue =
                                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                                        .format(rs.getTimestamp(i)).getBytes(StandardCharsets.UTF_8);
                        break;
                    case "TIME":
                        column.columnLength = 8;
                        column.columnValue = rs.getTime(i).toLocalTime().toString().getBytes(StandardCharsets.US_ASCII);
                        break;
                    case "TIMESTAMP WITH TIME ZONE":
                        ZonedDateTime zonedDateTime = rs.getTimestamp(i).toInstant().atZone(ZoneId.systemDefault());
                        String formattedTime = zonedDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSx"));
                        column.columnLength = formattedTime.length();
                        column.columnValue = formattedTime.getBytes(StandardCharsets.US_ASCII);
                        break;
                    default:
                        if (columnTypeName.toUpperCase().startsWith("DECIMAL")) {
                            // DECIMAL 应该是二进制格式，但是目前分析二进制格式的结果总是不对
                            // 所有这里用字符串进行返回
                            String bigDecimal = rs.getBigDecimal(i).toPlainString();

                            // 获取整数部分和小数部分
                            column.columnLength = bigDecimal.length();
                            column.columnValue = bigDecimal.getBytes(StandardCharsets.US_ASCII);
                            break;
                        } else {
                            // 不认识的字段类型, 告警后按照字符串来处理
                            AppLogger.logger.warn("Not implemented column type: {}", columnTypeName);
                            column.columnValue = rs.getString(i).getBytes(StandardCharsets.UTF_8);
                            column.columnLength = column.columnValue.length;
                        }
                }
            }
            columns.add(column);
        }
        return columns;
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 取出上次解析的SQL，如果为空语句，则直接返回
        String executeSQL = DBInstance.getSession(getCurrentSessionId(ctx)).executeSQL;
        if (executeSQL.isEmpty()) {
            CommandComplete commandComplete = new CommandComplete();
            commandComplete.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out);
            out.close();
            return;
        }
        try {
            ResultSet rs = DBInstance.getSession(getCurrentSessionId(ctx)).getResultSet("Portal" + "-" + portalName);
            if (rs != null)
            {
                DataRow dataRow = new DataRow();
                ResultSetMetaData rsmd = rs.getMetaData();

                int rowsReturned = 0;
                while (rs.next()) {
                    // 绑定列的信息
                    dataRow.setColumns(processRow(rs, rsmd));
                    dataRow.process(ctx, request, out);
                    dataRow.setColumns(null);

                    // 发送并刷新返回消息
                    PostgresMessage.writeAndFlush(ctx, DataRow.class.getSimpleName(), out);

                    rowsReturned = rowsReturned + 1;
                    if (maximumRowsReturned != 0 && rowsReturned >= maximumRowsReturned) {
                        // 如果要求分批返回，则不再继续，分批返回
                        PortalSuspended portalSuspended = new PortalSuspended();
                        portalSuspended.process(ctx, request, out);
                        PostgresMessage.writeAndFlush(ctx, PortalSuspended.class.getSimpleName(), out);
                        out.close();
                        return;
                    }
                }
                // 所有的记录查询完毕
                rs.close();
                DBInstance.getSession(getCurrentSessionId(ctx)).clearResultSet("Portal" + "-" + portalName);
            }
            else {
                PreparedStatement preparedStatement = DBInstance.getSession(getCurrentSessionId(ctx)).getPreparedStatement("Portal" + "-" + portalName);
                if (preparedStatement == null)
                {
                    // 之前语句解析或者绑定出了错误, 没有继续执行的必要
                    return;
                }

                boolean isResultSet = preparedStatement.execute();
                int rowsReturned = 0;
                if (isResultSet) {
                    DataRow dataRow = new DataRow();

                    boolean describeRequestExist = DBInstance.getSession(getCurrentSessionId(ctx)).hasDescribeRequest;
                    if (describeRequestExist) {
                        // 如果之前有describeRequest， 则返回RowsDescription； 否则直接返回结果
                        DBInstance.getSession(getCurrentSessionId(ctx)).hasDescribeRequest = false;

                        List<Field> fields = new ArrayList<>();

                        // 获取返回的结构信息
                        ResultSetMetaData resultSetMetaData = preparedStatement.getMetaData();
                        for (int i = 1; i <= resultSetMetaData.getColumnCount(); i++) {
                            String columnTypeName = resultSetMetaData.getColumnTypeName(i);
                            Field field = new Field();
                            field.name = resultSetMetaData.getColumnName(i);
                            field.objectIdOfTable = 0;
                            field.attributeNumberOfColumn = 0;
                            field.dataTypeId = PostgresTypeOids.getTypeOidFromTypeName(columnTypeName);
                            field.dataTypeSize = (short) 2147483647;
                            field.dataTypeModifier = -1;
                            switch (columnTypeName) {
                                case "INTEGER":
                                case "BIGINT":
                                case "HUGEINT":
                                case "DATE":
                                case "BOOLEAN":
                                case "FLOAT":
                                case "DOUBLE":
                                    // 这些数据类型都是二进制类型返回
                                    field.formatCode = 1;
                                    break;
                                case "VARCHAR":
                                case "TIME":
                                case "TIMESTAMP":
                                case "TIMESTAMP WITH TIME ZONE":
                                    // 这些数据类型都是文本类型返回
                                    field.formatCode = 0;
                                    break;
                                default:
                                    // 不认识的类型一律文本返回
                                    field.formatCode = 0;
                                    break;
                            }
                            fields.add(field);
                        }

                        RowDescription rowDescription = new RowDescription();
                        rowDescription.setFields(fields);
                        rowDescription.process(ctx, request, out);
                        rowDescription.setFields(null);

                        // 发送并刷新RowsDescription消息
                        PostgresMessage.writeAndFlush(ctx, RowDescription.class.getSimpleName(), out);
                    }

                    rs = preparedStatement.getResultSet();
                    // 保留当前的ResultSet到Portal中
                    DBInstance.getSession(getCurrentSessionId(ctx)).saveResultSet("Portal" + "-" + portalName, rs);

                    ResultSetMetaData rsmd = rs.getMetaData();
                    while (rs.next()) {
                        // 绑定列的信息
                        dataRow.setColumns(processRow(rs, rsmd));
                        dataRow.process(ctx, request, out);
                        dataRow.setColumns(null);

                        // 发送并刷新返回消息
                        PostgresMessage.writeAndFlush(ctx, DataRow.class.getSimpleName(), out);

                        rowsReturned = rowsReturned + 1;
                        if (maximumRowsReturned != 0 && rowsReturned >= maximumRowsReturned) {
                            // 如果要求分批返回，则不再继续，分批返回
                            PortalSuspended portalSuspended = new PortalSuspended();
                            portalSuspended.process(ctx, request, out);
                            PostgresMessage.writeAndFlush(ctx, PortalSuspended.class.getSimpleName(), out);

                            // 返回等待下一次ExecuteRequest
                            out.close();
                            return;
                        }
                    }
                    rs.close();
                    DBInstance.getSession(getCurrentSessionId(ctx)).clearResultSet("Portal" + "-" + portalName);
                }
            }

            // 设置语句的事务级别
            if (executeSQL.startsWith("BEGIN")) {
                DBInstance.getSession(getCurrentSessionId(ctx)).inTransaction = true;
            } else if (executeSQL.startsWith("COMMIT")) {
                DBInstance.getSession(getCurrentSessionId(ctx)).inTransaction = false;
            } else if (executeSQL.startsWith("ROLLBACK")) {
                DBInstance.getSession(getCurrentSessionId(ctx)).inTransaction = false;
            }

            CommandComplete commandComplete = new CommandComplete();
            if (executeSQL.toUpperCase().startsWith("BEGIN")) {
                commandComplete.setCommandResult("BEGIN");
            } else if (executeSQL.toUpperCase().startsWith("SELECT")) {
                commandComplete.setCommandResult("SELECT 0");
            } else if (executeSQL.toUpperCase().startsWith("INSERT")) {
                commandComplete.setCommandResult("INSERT 0 0");
            }
            commandComplete.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out);
        }
        catch (SQLException e) {
            // 生成一个错误消息
            ErrorResponse errorResponse = new ErrorResponse();
            errorResponse.setErrorResponse(String.valueOf(e.getErrorCode()), e.getMessage());
            errorResponse.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out);
        }
        finally {
            out.close();
        }
    }
}
