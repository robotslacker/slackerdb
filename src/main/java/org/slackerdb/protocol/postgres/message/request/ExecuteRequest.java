package org.slackerdb.protocol.postgres.message.request;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.protocol.postgres.entity.Column;
import org.slackerdb.protocol.postgres.entity.Field;
import org.slackerdb.protocol.postgres.entity.PostgresTypeOids;
import org.slackerdb.protocol.postgres.message.*;
import org.slackerdb.protocol.postgres.message.response.CommandComplete;
import org.slackerdb.protocol.postgres.message.response.DataRow;
import org.slackerdb.protocol.postgres.message.response.ErrorResponse;
import org.slackerdb.protocol.postgres.message.response.RowDescription;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
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

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 取出上次解析的SQL，如果为空语句，则直接返回
        String executeSQL = (String)ctx.channel().attr(AttributeKey.valueOf("SQL")).get();
        if (executeSQL.isEmpty()) {
            CommandComplete commandComplete = new CommandComplete();
            commandComplete.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out);

            return;
        }

        try {
            PreparedStatement preparedStatement = (PreparedStatement) ctx.channel().attr(AttributeKey.valueOf("PreparedStatement")).get();
            if (preparedStatement == null) {
                return;
            }
            boolean isResultSet = preparedStatement.execute();
            int affectedRows = 0;
            if (isResultSet) {
                Boolean describeRequestExist = (Boolean) ctx.channel().attr(AttributeKey.valueOf("DescribeRequest")).get();

                if (describeRequestExist != null && describeRequestExist) {
                    // 如果之前有describeRequest， 则返回RowsDescription； 否则直接返回结果
                    ctx.channel().attr(AttributeKey.valueOf("DescribeRequest")).set(Boolean.FALSE);

                    List<Field> fields = new ArrayList<>();

                    // 获取返回的结构信息
                    ResultSetMetaData resultSetMetaData = preparedStatement.getMetaData();
                    for (int i = 1; i <= resultSetMetaData.getColumnCount(); i++) {
                        String columnTypeName = resultSetMetaData.getColumnTypeName(i);
                        Field field = new Field();
                        field.name = resultSetMetaData.getColumnName(i).getBytes(StandardCharsets.UTF_8);
                        field.objectIdOfTable = 0;
                        field.attributeNumberOfColumn = 0;
                        field.dataTypeId = PostgresTypeOids.getTypeOidFromTypeName(columnTypeName);
                        field.dataTypeSize = (short) 2147483647;
                        field.dataTypeModifier = -1;
                        switch (columnTypeName) {
                            case "VARCHAR":
                            case "TIMESTAMP":
                                field.formatCode = 0;
                                break;
                            default:
                                if (columnTypeName.startsWith("DECIMAL"))
                                {
                                    // DECIMAL 应该是二进制格式，但是目前分析二进制格式的结果总是不对
                                    // 所有这里用字符串进行返回
                                    field.formatCode = 0;
                                }
                                else
                                {
                                    field.formatCode = 1;
                                }
                        }
                        fields.add(field);
                    }

                    RowDescription rowDescription = new RowDescription();
                    rowDescription.setFields(fields);
                    rowDescription.process(ctx, request, out);

                    // 发送并刷新RowsDescription消息
                    PostgresMessage.writeAndFlush(ctx, RowDescription.class.getSimpleName(), out);
                }
                ResultSet rs = preparedStatement.getResultSet();
                ResultSetMetaData rsmd = rs.getMetaData();
                while (rs.next()) {
                    List<Column> columns = new ArrayList<>();
                    DataRow dataRow = new DataRow();
                    for (int i = 1; i <= rsmd.getColumnCount(); i++) {
                        Column column = new Column();
                        if (rs.getObject(i) == null)
                        {
                            column.columnLength = -1;
                        }
                        else
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
                                    column.columnValue = Utils.int32ToBytes((int)timeInterval);
                                    break;
                                case "BOOLEAN":
                                    column.columnLength = 1;
                                    if (rs.getBoolean(i))
                                    {
                                        column.columnValue = new byte[]{(byte) 0x01};
                                    }
                                    else
                                    {
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
                                default:
                                    if (columnTypeName.toUpperCase().startsWith("DECIMAL"))
                                    {
                                        // DECIMAL 应该是二进制格式，但是目前分析二进制格式的结果总是不对
                                        // 所有这里用字符串进行返回
                                        String bigDecimal = rs.getBigDecimal(i).toPlainString();

                                        // 获取整数部分和小数部分
                                        column.columnLength = bigDecimal.length();
                                        column.columnValue = bigDecimal.getBytes(StandardCharsets.US_ASCII);
                                        break;
                                    }
                                    else {
                                        AppLogger.logger.error("Not implemented column type: {}", columnTypeName);
                                    }
                            }
                        }
                        columns.add(column);
                    }
                    dataRow.setColumns(columns);
                    dataRow.process(ctx, request, out);
                    affectedRows = affectedRows + 1;
                    // 发送并刷新返回消息
                    PostgresMessage.writeAndFlush(ctx, DataRow.class.getSimpleName(), out);
                }
                rs.close();
            }

            // 设置语句的事务级别
            if (executeSQL.startsWith("BEGIN"))
            {
                ctx.channel().attr(AttributeKey.valueOf("TRANSACTION")).set(true);
            }
            else if (executeSQL.startsWith("COMMIT"))
            {
                ctx.channel().attr(AttributeKey.valueOf("TRANSACTION")).set(false);
            }
            else if (executeSQL.startsWith("ROLLBACK"))
            {
                ctx.channel().attr(AttributeKey.valueOf("TRANSACTION")).set(false);
            }

            CommandComplete commandComplete = new CommandComplete();
            if (executeSQL.toUpperCase().startsWith("BEGIN"))
            {
                commandComplete.setCommandResult("BEGIN");
            } else if (executeSQL.toUpperCase().startsWith("SELECT")) {
                commandComplete.setCommandResult("SELECT " + affectedRows);
            } else if (executeSQL.toUpperCase().startsWith("INSERT")) {
                commandComplete.setCommandResult("INSERT 0 " + affectedRows);
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
    }
}