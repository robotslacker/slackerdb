package org.slackerdb.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.entity.Column;
import org.slackerdb.entity.Field;
import org.slackerdb.entity.ParsedStatement;
import org.slackerdb.entity.PostgresTypeOids;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.message.PostgresMessage;
import org.slackerdb.message.PostgresRequest;
import org.slackerdb.message.response.*;
import org.slackerdb.plsql.PlSqlVisitor;
import org.slackerdb.server.DBInstance;
import org.slackerdb.server.GroovyInstance;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    //      Maximum number of rows to return, if portal contains a query that returns rows (ignored otherwise).
    //      Zero denotes “no limit”.
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

    public long insertSqlHistory(ChannelHandlerContext ctx)
    {
        long sqlHistoryId = -1;
        if (DBInstance.backendSqlHistoryConnection == null)
        {
            // 没有开始日志服务
            return sqlHistoryId;
        }
        String historySQL = "Insert INTO SQL_HISTORY(ID, SessionId, ClientIP, SQL, SqlId, StartTime) " +
                "VALUES(?,?,?,?,?, current_timestamp)";
        try {
            Statement stmt = DBInstance.backendSqlHistoryConnection.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT nextval('sql_history_id')");
            rs.next();
            sqlHistoryId = rs.getLong(1);
            rs.close();
            stmt.close();
            PreparedStatement preparedStatement =
                    DBInstance.backendSqlHistoryConnection.prepareStatement(historySQL);
            preparedStatement.setLong(1, sqlHistoryId);
            preparedStatement.setLong(2, getCurrentSessionId(ctx));
            preparedStatement.setString(3, DBInstance.getSession(getCurrentSessionId(ctx)).clientAddress);
            preparedStatement.setString(4, DBInstance.getSession(getCurrentSessionId(ctx)).executingSQL);
            preparedStatement.setLong(5, DBInstance.getSession(getCurrentSessionId(ctx)).executingSqlId.get());
            preparedStatement.execute();
            preparedStatement.close();
        }
        catch (SQLException se)
        {
            AppLogger.logger.warn("[SERVER] Save to sql history failed.", se);
        }
        return sqlHistoryId;
    }

    public void updateSqlHistory(long sqlHistoryId, int sqlCode, long affectedRows, String errorMsg)
    {
        if (DBInstance.backendSqlHistoryConnection == null)
        {
            // 没有开始日志服务
            return;
        }
        String historySQL = "Update SQL_HISTORY " +
                "SET   EndTime = current_timestamp," +
                "      SqlCode = ?, " +
                "      AffectedRows = ?, " +
                "      ErrorMsg = ? " +
                "WHERE ID = ?";
        try {
            PreparedStatement preparedStatement =
                    DBInstance.backendSqlHistoryConnection.prepareStatement(historySQL);
            preparedStatement.setInt(1, sqlCode);
            preparedStatement.setLong(2, affectedRows);
            preparedStatement.setString(3, errorMsg);
            preparedStatement.setLong(4, sqlHistoryId);
            preparedStatement.execute();
            preparedStatement.close();
        }
        catch (SQLException se)
        {
            AppLogger.logger.warn("[SERVER] Save to sql history failed.", se);
        }
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        // 记录会话的开始时间，以及业务类型
        DBInstance.getSession(getCurrentSessionId(ctx)).executingFunction = this.getClass().getSimpleName();
        DBInstance.getSession(getCurrentSessionId(ctx)).executingTime = LocalDateTime.now();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long  nRowsAffected = 0;
        long  nSqlHistoryId = -1;

        tryBlock:
        try {
            // 开始处理
            ParsedStatement parsedStatement =
                    DBInstance.getSession(getCurrentSessionId(ctx)).getParsedStatement("Portal" + "-" + portalName);
            if (parsedStatement != null && parsedStatement.isPlSql)
            {
                // PLSQL处理
                Connection conn = DBInstance.getSession(getCurrentSessionId(ctx)).dbConnection;
                GroovyInstance groovyInstance = DBInstance.getSession(getCurrentSessionId(ctx)).groovyInstance;
                PlSqlVisitor.runPlSql(conn, groovyInstance, parsedStatement.sql);

                CommandComplete commandComplete = new CommandComplete();
                commandComplete.setCommandResult("UPDATE 0");
                commandComplete.process(ctx, request, out);

                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out);

                // PLSQL的记录保存到SQL历史中
                DBInstance.getSession(getCurrentSessionId(ctx)).executingSQL = parsedStatement.sql;
                DBInstance.getSession(getCurrentSessionId(ctx)).executingSqlId.incrementAndGet();
                nSqlHistoryId = insertSqlHistory(ctx);

                break tryBlock;
            }
            if (parsedStatement == null || parsedStatement.preparedStatement == null)
            {
                // 之前语句解析或者绑定出了错误, 没有继续执行的必要
                break tryBlock;
            }

            // 取出上次解析的SQL，如果为空语句，则直接返回
            String executeSQL = DBInstance.getSession(getCurrentSessionId(ctx)).getParsedStatement("Portal" + "-" + portalName).sql;
            if (executeSQL.isEmpty()) {
                CommandComplete commandComplete = new CommandComplete();
                commandComplete.process(ctx, request, out);

                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out);
                out.close();
                break tryBlock;
            }
            DBInstance.getSession(getCurrentSessionId(ctx)).executingSQL = executeSQL;
            // 之前有缓存记录
            if (parsedStatement.resultSet != null)
            {
                // 保留原有SqlId不变
                // 记录到SQL历史中
                nSqlHistoryId = insertSqlHistory(ctx);

                ResultSet rs = parsedStatement.resultSet;
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
                        parsedStatement.nRowsAffected += rowsReturned;
                        break tryBlock;
                    }
                }
                // 所有的记录查询完毕
                rs.close();
                nRowsAffected = parsedStatement.nRowsAffected + rowsReturned;
                DBInstance.getSession(getCurrentSessionId(ctx)).clearParsedStatement("Portal" + "-" + portalName);
            }
            else
            {
                // 记录一个新的SqlID, 和当前正在执行的句柄（便于取消）
                DBInstance.getSession(getCurrentSessionId(ctx)).executingSqlId.incrementAndGet();
                DBInstance.getSession(getCurrentSessionId(ctx)).executingPreparedStatement = parsedStatement.preparedStatement;

                // 记录到SQL历史中
                nSqlHistoryId = insertSqlHistory(ctx);

                boolean isResultSet = false;
                try {
                    isResultSet = parsedStatement.preparedStatement.execute();
                }
                catch (SQLException e) {
                    if (!e.getMessage().contains("no transaction is active"))
                    {
                        throw e;
                    }
                }
                int rowsReturned = 0;
                if (isResultSet) {
                    DataRow dataRow = new DataRow();

                    boolean describeRequestExist = DBInstance.getSession(getCurrentSessionId(ctx)).hasDescribeRequest;
                    if (describeRequestExist) {
                        // 如果之前有describeRequest， 则返回RowsDescription； 否则直接返回结果
                        DBInstance.getSession(getCurrentSessionId(ctx)).hasDescribeRequest = false;

                        List<Field> fields = new ArrayList<>();

                        // 获取返回的结构信息
                        ResultSetMetaData resultSetMetaData = parsedStatement.preparedStatement.getMetaData();
                        for (int i = 1; i <= resultSetMetaData.getColumnCount(); i++) {
                            String columnTypeName = resultSetMetaData.getColumnTypeName(i);
                            Field field = new Field();
                            field.name = resultSetMetaData.getColumnName(i);
                            field.objectIdOfTable = 0;
                            field.attributeNumberOfColumn = 0;
                            field.dataTypeId = PostgresTypeOids.getTypeOidFromTypeName(columnTypeName);
                            if (field.dataTypeId == -1 )
                            {
                                AppLogger.logger.error("executeSQL: {}" , executeSQL);
                            }
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

                    ResultSet rs = parsedStatement.preparedStatement.getResultSet();
                    parsedStatement.resultSet = rs;
                    // 保留当前的ResultSet到Portal中
                    DBInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement("Portal" + "-" + portalName, parsedStatement);

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
                            parsedStatement.nRowsAffected += rowsReturned;
                            break tryBlock;
                        }
                    }
                    rs.close();
                    nRowsAffected = parsedStatement.nRowsAffected + rowsReturned;
                    DBInstance.getSession(getCurrentSessionId(ctx)).clearParsedStatement("Portal" + "-" + portalName);
                }
                else
                {
                    // 记录更新的行数
                    if (parsedStatement.preparedStatement.isClosed())
                    {
                        nRowsAffected = -1;
                    }
                    else {
                        nRowsAffected = parsedStatement.preparedStatement.getUpdateCount();
                    }
                }
            }

            // 设置语句的事务级别
            if (executeSQL.toUpperCase().startsWith("BEGIN")) {
                DBInstance.getSession(getCurrentSessionId(ctx)).inTransaction = true;
            } else if (executeSQL.toUpperCase().startsWith("END")) {
                DBInstance.getSession(getCurrentSessionId(ctx)).inTransaction = false;
            } else if (executeSQL.toUpperCase().startsWith("COMMIT")) {
                DBInstance.getSession(getCurrentSessionId(ctx)).inTransaction = false;
            } else if (executeSQL.toUpperCase().startsWith("ROLLBACK")) {
                DBInstance.getSession(getCurrentSessionId(ctx)).inTransaction = false;
            } else if (executeSQL.toUpperCase().startsWith("ABORT")) {
                DBInstance.getSession(getCurrentSessionId(ctx)).inTransaction = false;
            }

            CommandComplete commandComplete = new CommandComplete();
            if (executeSQL.toUpperCase().startsWith("BEGIN")) {
                commandComplete.setCommandResult("BEGIN");
            } else if (executeSQL.toUpperCase().startsWith("END")) {
                commandComplete.setCommandResult("COMMIT");
            } else if (executeSQL.toUpperCase().startsWith("SELECT")) {
                commandComplete.setCommandResult("SELECT " + nRowsAffected);
            } else if (executeSQL.toUpperCase().startsWith("INSERT")) {
                commandComplete.setCommandResult("INSERT 0 " + nRowsAffected);
            } else if (executeSQL.toUpperCase().startsWith("COMMIT")) {
                commandComplete.setCommandResult("COMMIT");
            } else if (executeSQL.toUpperCase().startsWith("ROLLBACK")) {
                commandComplete.setCommandResult("ROLLBACK");
            } else if (executeSQL.toUpperCase().startsWith("ABORT")) {
                    commandComplete.setCommandResult("ROLLBACK");
            }
            else
            {
                commandComplete.setCommandResult("UPDATE " + nRowsAffected);
            }
            commandComplete.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out);

            // 更新SQL历史信息
            if (nSqlHistoryId != -1)
            {
                updateSqlHistory(nSqlHistoryId, 0, nRowsAffected, "");
            }
        }
        catch (SQLException e) {
            // 生成一个错误消息
            ErrorResponse errorResponse = new ErrorResponse();
            errorResponse.setErrorFile("ExecuteRequest");
            errorResponse.setErrorResponse(String.valueOf(e.getErrorCode()), e.getMessage());
            errorResponse.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out);

            // 更新SQL历史信息
            if (nSqlHistoryId != -1)
            {
                updateSqlHistory(nSqlHistoryId, e.getErrorCode(), nRowsAffected, e.getMessage());
            }
        }
        finally {
            out.close();
        }

        // 取消会话的开始时间，以及业务类型
        DBInstance.getSession(getCurrentSessionId(ctx)).executingFunction = "";
        DBInstance.getSession(getCurrentSessionId(ctx)).executingSQL = "";
        DBInstance.getSession(getCurrentSessionId(ctx)).executingTime = null;
    }
}
