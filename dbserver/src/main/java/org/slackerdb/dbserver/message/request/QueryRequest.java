package org.slackerdb.dbserver.message.request;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.netty.channel.ChannelHandlerContext;
import org.duckdb.DuckDBConnection;
import org.slackerdb.dbserver.entity.Column;
import org.slackerdb.dbserver.entity.Field;
import org.slackerdb.dbserver.entity.PostgresTypeOids;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.response.*;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.sql.SQLReplacer;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.sql.antlr.CopyVisitor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

public class QueryRequest  extends PostgresRequest {
    private String      sql = "";

    public QueryRequest(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    //  Query (F)
    //    Byte1('Q')
    //      Identifies the message as a simple query.
    //    Int32
    //      Length of message contents in bytes, including self.
    //    String
    //      The query string itself.
    @Override
    public void decode(byte[] data) {
        sql = new String(data, StandardCharsets.UTF_8);

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
                byte[] columnBytes = rs.getString(i).getBytes(StandardCharsets.UTF_8);
                column.columnLength = columnBytes.length;
                column.columnValue = columnBytes;
            }
            columns.add(column);
        }
        return columns;
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        // 记录会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = this.getClass().getSimpleName();
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL = sql;
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = LocalDateTime.now();

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        tryBlock:
        try {
            JSONObject parseObject = CopyVisitor.parseCopyStatement(sql);
            if (parseObject.getInteger("errorCode") == 0)
            {
                // 这次一个Copy语句
                if (!parseObject.getString("copyDirection").equals("FROM") ||
                        !parseObject.getString("copyFilePath").equals("STDIN") ||
                        !parseObject.getString("copyType").equals("table")
                )
                {
                    ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
                    errorResponse.setErrorResponse(
                            "SLACKER-0099",
                            "Feature not supported. Only support COPY .. FROM STDIN");
                    errorResponse.setErrorSeverity("ERROR");

                    // 发送并刷新返回消息
                    PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

                    // 发送ReadyForQuery
                    ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
                    readyForQuery.process(ctx, request, out);

                    // 发送并刷新返回消息
                    PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);

                    return;
                }
                // 获取导入的用户表和表名
                String copyTableName = parseObject.getString("table");
                String targetTableName;
                String targetSchemaName;
                if (copyTableName.split("\\.").length > 1) {
                    targetSchemaName = copyTableName.split("\\.")[0];
                    targetTableName = copyTableName.split("\\.")[1];
                } else {
                    targetSchemaName = "";
                    targetTableName = copyTableName;
                }

                // 获取需要导入的列名
                Map<String, Integer> targetColumnMap = new HashMap<>();
                JSONArray columnsJson = parseObject.getJSONArray("columns");
                if (columnsJson != null) {
                    for (int i = 0; i < columnsJson.size(); i++) {
                        targetColumnMap.put(columnsJson.get(i).toString().trim().toUpperCase(), i);
                    }
                }

                // 获取COPY的文件类型
                JSONObject copyOptions = parseObject.getJSONObject("options");
                String copyTableFormat = null;
                if (copyOptions != null && copyOptions.containsKey("FORMAT"))
                {
                    copyTableFormat = copyOptions.getString("FORMAT").toUpperCase();
                }
                if (copyTableFormat == null ||
                        (!copyTableFormat.equals("CSV") && !copyTableFormat.equals("BINARY") )
                )
                {
                    ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
                    errorResponse.setErrorResponse(
                            "SLACKER-0099",
                            "Feature not supported. Only support FORMAT CSV|BINARY");
                    errorResponse.setErrorSeverity("ERROR");

                    // 发送并刷新返回消息
                    PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

                    // 发送ReadyForQuery
                    ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
                    readyForQuery.process(ctx, request, out);

                    // 发送并刷新返回消息
                    PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);

                    return;
                }

                DuckDBConnection conn = (DuckDBConnection) this.dbInstance.getSession(getCurrentSessionId(ctx)).dbConnection;
                this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableFormat = copyTableFormat;
                this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableAppender = conn.createAppender(targetSchemaName, targetTableName);
                this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained.reset();

                // 获取表名的实际表名，DUCK并不支持部分字段的Appender操作。所以要追加列表中不存在的相关信息
                List<Integer> copyTableDbColumnMapPos = new ArrayList<>();
                List<String> copyTableDbColumnType = new ArrayList<>();
                String executeSql;
                if (targetSchemaName.isEmpty()) {
                    executeSql = "SELECT * FROM " + targetTableName + " LIMIT 0";
                } else {
                    executeSql = "SELECT * FROM " + targetSchemaName + "." + targetTableName + " LIMIT 0";
                }
                PreparedStatement ps = conn.prepareStatement(executeSql);
                ResultSet rs = ps.executeQuery();
                rs.next();
                if (!targetColumnMap.isEmpty()) {
                    // 指定了字段类型，则其余字段填空
                    for (int i = 0; i < rs.getMetaData().getColumnCount(); i++) {
                        copyTableDbColumnMapPos.add(targetColumnMap.getOrDefault(
                                rs.getMetaData().getColumnName(i + 1).toUpperCase(), -1));
                    }
                }
                else
                {
                    // 没有指定任何列名，则认为所有列都需要写入
                    for (int i = 0; i < rs.getMetaData().getColumnCount(); i++) {
                        copyTableDbColumnMapPos.add(i);
                    }
                }
                // 记住列的字段类型，作为Binary插入的需要
                for (int i = 0; i < rs.getMetaData().getColumnCount(); i++) {
                    copyTableDbColumnType.add(rs.getMetaData().getColumnTypeName(i+1));
                }

                rs.close();
                ps.close();

                // 将解析信息记录到Session会话中
                this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableDbColumnMapPos = copyTableDbColumnMapPos;
                this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableDbColumnType = copyTableDbColumnType;

                // 发送CopyInResponse
                CopyInResponse copyInResponse = new CopyInResponse(this.dbInstance);
                copyInResponse.copyColumnCount = (short) targetColumnMap.size();
                copyInResponse.process(ctx, request, out);

                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, CopyInResponse.class.getSimpleName(), out, this.dbInstance.logger);

                out.close();
                break tryBlock;

            }

            // 在执行之前需要做替换
            sql = SQLReplacer.replaceSQL(this.dbInstance, sql);

            // 取出上次解析的SQL，如果为空语句，则直接返回
            if (sql.isEmpty()) {
                CommandComplete commandComplete = new CommandComplete(this.dbInstance);
                commandComplete.process(ctx, request, out);

                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, this.dbInstance.logger);

                // 发送ReadyForQuery
                ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
                readyForQuery.process(ctx, request, out);

                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);

                out.close();

                break tryBlock;
            }

            // 理解为简单查询
            long nAffectedRows = 0;
            PreparedStatement preparedStatement =
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).dbConnection.prepareStatement(sql);
            boolean isResultSet = false;
            try {
                isResultSet = preparedStatement.execute();
            }
            catch (SQLException e) {
                if (!e.getMessage().contains("no transaction is active"))
                {
                    throw e;
                }
            }
            if (isResultSet) {
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
                    // 一律文本返回
                    field.formatCode = 0;
                    fields.add(field);
                }

                RowDescription rowDescription = new RowDescription(this.dbInstance);
                rowDescription.setFields(fields);
                rowDescription.process(ctx, request, out);
                rowDescription.setFields(null);

                // 发送并刷新RowsDescription消息
                PostgresMessage.writeAndFlush(ctx, RowDescription.class.getSimpleName(), out, this.dbInstance.logger);

                DataRow dataRow = new DataRow(this.dbInstance);
                ResultSet rs = preparedStatement.getResultSet();
                ResultSetMetaData rsmd = rs.getMetaData();
                while (rs.next()) {
                    // 绑定列的信息
                    dataRow.setColumns(processRow(rs, rsmd));
                    dataRow.process(ctx, request, out);
                    dataRow.setColumns(null);

                    nAffectedRows ++;
                    // 发送并刷新返回消息
                    PostgresMessage.writeAndFlush(ctx, DataRow.class.getSimpleName(), out, this.dbInstance.logger);
                }
                rs.close();
            }
            else
            {
                if (preparedStatement.isClosed()) {
                    nAffectedRows = -1;
                }
                else
                {
                    nAffectedRows = preparedStatement.getUpdateCount();
                }
            }

            // 设置语句的事务级别
            if (sql.toUpperCase().startsWith("BEGIN")) {
                this.dbInstance.getSession(getCurrentSessionId(ctx)).inTransaction = true;
            } else if (sql.toUpperCase().startsWith("END")) {
                this.dbInstance.getSession(getCurrentSessionId(ctx)).inTransaction = false;
            } else if (sql.toUpperCase().startsWith("COMMIT")) {
                this.dbInstance.getSession(getCurrentSessionId(ctx)).inTransaction = false;
            } else if (sql.toUpperCase().startsWith("ROLLBACK")) {
                this.dbInstance.getSession(getCurrentSessionId(ctx)).inTransaction = false;
            } else if (sql.toUpperCase().startsWith("ABORT")) {
                this.dbInstance.getSession(getCurrentSessionId(ctx)).inTransaction = false;
            }

            CommandComplete commandComplete = new CommandComplete(this.dbInstance);
            if (sql.toUpperCase().startsWith("BEGIN")) {
                commandComplete.setCommandResult("BEGIN");
            } else if (sql.toUpperCase().startsWith("END")) {
                commandComplete.setCommandResult("COMMIT");
            } else if (sql.toUpperCase().startsWith("SELECT")) {
                commandComplete.setCommandResult("SELECT " + nAffectedRows);
            } else if (sql.toUpperCase().startsWith("INSERT")) {
                commandComplete.setCommandResult("INSERT 0 " + nAffectedRows);
            } else if (sql.toUpperCase().startsWith("COMMIT")) {
                commandComplete.setCommandResult("COMMIT");
            } else if (sql.toUpperCase().startsWith("ROLLBACK")) {
                commandComplete.setCommandResult("ROLLBACK");
            } else if (sql.toUpperCase().startsWith("ABORT")) {
                commandComplete.setCommandResult("ROLLBACK");
            }
            else
            {
                commandComplete.setCommandResult("UPDATE " + nAffectedRows);
            }
            commandComplete.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, this.dbInstance.logger);

            // 发送ReadyForQuery
            ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
            readyForQuery.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);
        } catch (SQLException se) {
            StackTraceElement[] stackTrace = se.getStackTrace();

            // 生成一个错误消息
            ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
            errorResponse.setErrorResponse(String.valueOf(se.getErrorCode()), se.getMessage());
            errorResponse.setErrorSeverity("ERROR");
            errorResponse.setErrorFile(stackTrace[0].getFileName());
            errorResponse.setErrorLine(String.valueOf(stackTrace[0].getLineNumber()));
            errorResponse.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

            // 发送ReadyForQuery
            ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
            readyForQuery.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);
        } finally {
            out.close();
        }

        // 取消会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = "";
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL = "";
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = null;
    }
}
