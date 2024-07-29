package org.slackerdb.protocol;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class ExecuteRequest extends PostgresRequest {
    @Override
    public void decode(byte[] data) {
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
                                default:
                                    AppLogger.logger.error("Not implemented column type: {}", columnTypeName);
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
            if (executeSQL.startsWith("BEGIN"))
            {
                commandComplete.setCommandResult("BEGIN");
            } else if (executeSQL.startsWith("SELECT")) {
                commandComplete.setCommandResult("SELECT " + affectedRows);
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
