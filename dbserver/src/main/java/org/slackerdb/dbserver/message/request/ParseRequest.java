package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.entity.ParsedStatement;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.response.ErrorResponse;
import org.slackerdb.dbserver.message.response.ParseComplete;
import org.slackerdb.dbserver.sql.SQLReplacer;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.common.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ParseRequest extends PostgresRequest {
    private String      preparedStmtName = "";
    private String      sql = "";
    private int[]       parameterDataTypeIds;
    private static final Pattern plsqlPattern =
            Pattern.compile("(.*)(DO)?(\\s+)?\\$\\$(.*)\\$\\$.*",Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    public ParseRequest(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    @Override
    public void decode(byte[] data) {
        //  Parse (F)
        //    Byte1('P')
        //      Identifies the message as a Parse command.
        //    Int32
        //      Length of message contents in bytes, including self.
        //    String
        //      The name of the destination prepared statement (an empty string selects the unnamed prepared statement).
        //    String
        //      The query string to be parsed.
        //    Int16
        //      The number of parameter data types specified (can be zero).
        //      Note that this is not an indication of the number of parameters that might appear in the query string,
        //      only the number that the frontend wants to specify types for.
        //      Then, for each parameter, there is the following:
        //    Int32
        //      Specifies the object ID of the parameter data type.
        //      Placing a zero here is equivalent to leaving the type unspecified.

        // 将ParseRequest中的信息进行分解
        byte[][] result = Utils.splitByteArray(data, (byte)0);
        preparedStmtName = new String(result[0], StandardCharsets.UTF_8);
        if (preparedStmtName.isEmpty())
        {
            preparedStmtName = "NONAME";
        }
        sql = new String(result[1], StandardCharsets.UTF_8);

        // 前面两个部分是SQL的名字和SQL的具体内容
        int currentPos = result[0].length + 1 + result[1].length + 1;

        if (result.length > 2) {
            byte[] part = Arrays.copyOfRange(
                    data,
                    currentPos, currentPos + 2);
            short numOfParameters = Utils.bytesToInt16(part);
            currentPos += 2;

            parameterDataTypeIds = new int[numOfParameters];
            for (int i = 0; i < numOfParameters; i++) {
                part = Arrays.copyOfRange(data, currentPos,currentPos + 4);
                currentPos += 4;
                parameterDataTypeIds[i] = Utils.bytesToInt32(part);
            }
        }

        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ParseRequest parseRequest = (ParseRequest) request;

        // 处理PLSQL语句
        Matcher matcher = plsqlPattern.matcher(parseRequest.sql);
        if (matcher.matches())
        {
            // 这是一个PLSQL语句，不再解析，直接返回，等待Execute执行
            ParseComplete parseComplete = new ParseComplete(this.dbInstance);
            parseComplete.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ParseComplete.class.getSimpleName(), out, this.dbInstance.logger);
            out.close();

            // 记录SQL语句
            ParsedStatement parsedPrepareStatement = new ParsedStatement();
            // 前面三个部分可能是注释、DO，也可能是空白换行
            parsedPrepareStatement.sql = matcher.group(4).trim();
            parsedPrepareStatement.isPlSql = true;
            this.dbInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement(
                    "PreparedStatement" + "-" + preparedStmtName, parsedPrepareStatement);
            return;
        }

        // 由于PG驱动程序内置的一些语句在目标数据库上无法执行，所以这里要进行转换
        String executeSQL = SQLReplacer.replaceSQL(this.dbInstance, parseRequest.sql);

        // 对于空语句，直接返回结果
        if (executeSQL.isEmpty()) {
            ParseComplete parseComplete = new ParseComplete(this.dbInstance);
            parseComplete.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ParseComplete.class.getSimpleName(), out, this.dbInstance.logger);
            out.close();

            // 即使是空语句，也要更新缓存中记录的语句信息
            // 一些第三方工具用发送空语句解析来检测数据库状态
            ParsedStatement parsedPrepareStatement = new ParsedStatement();
            parsedPrepareStatement.sql = "";
            parsedPrepareStatement.isPlSql = false;
            this.dbInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement(
                    "PreparedStatement" + "-" + preparedStmtName, parsedPrepareStatement);
            return;
        }

        // 记录会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = this.getClass().getSimpleName();
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL = executeSQL;
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = LocalDateTime.now();

        try {
            Connection conn = this.dbInstance.getSession(getCurrentSessionId(ctx)).dbConnection;
            PreparedStatement preparedStatement = conn.prepareStatement(executeSQL);

            ParseComplete parseComplete = new ParseComplete(this.dbInstance);
            parseComplete.process(ctx, request, out);

            // 记录PreparedStatement,以及对应的参数类型
            ParsedStatement parsedPrepareStatement = new ParsedStatement();
            parsedPrepareStatement.sql = executeSQL.trim();
            parsedPrepareStatement.preparedStatement = preparedStatement;
            parsedPrepareStatement.parameterDataTypeIds = parameterDataTypeIds;
            this.dbInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement(
                    "PreparedStatement" + "-" + preparedStmtName, parsedPrepareStatement);
            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ParseComplete.class.getSimpleName(), out, this.dbInstance.logger);
        }
        catch (SQLException e) {
            // 清空PreparedStatement
            try {
                this.dbInstance.getSession(getCurrentSessionId(ctx)).clearParsedStatement(
                        "PreparedStatement" + "-" + preparedStmtName);
            } catch (Exception e2) {
                this.dbInstance.logger.error("Error clearing prepared statement", e2);
            }

            // 插入SQL执行历史
            Connection backendSqlHistoryConnection = null;
            try {
                backendSqlHistoryConnection = this.dbInstance.getSqlHistoryConn();
            } catch (SQLException se)
            {
                this.dbInstance.logger.warn("[SERVER] Failed to get sql history conn.", se);
            }

            if (backendSqlHistoryConnection != null)
            {
                String historySQL = "Insert INTO sysaux.SQL_HISTORY(ID, ServerId, SessionId, ClientIP, SQL, SqlId, StartTime, EndTime," +
                        "SQLCode, AffectedRows, ErrorMsg) " +
                        "VALUES(?, " + ProcessHandle.current().pid() + ",?,?,?,?, current_timestamp, current_timestamp, ?, 0, ?)";
                try {
                    PreparedStatement preparedStatement =
                            backendSqlHistoryConnection.prepareStatement(historySQL);
                    preparedStatement.setLong(1, this.dbInstance.backendSqlHistoryId.incrementAndGet());
                    preparedStatement.setLong(2, getCurrentSessionId(ctx));
                    preparedStatement.setString(3, this.dbInstance.getSession(getCurrentSessionId(ctx)).clientAddress);
                    preparedStatement.setString(4, this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL);
                    preparedStatement.setLong(5, this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSqlId.get());
                    if (e.getErrorCode() == 0) {
                        preparedStatement.setInt(6, -99);
                    }
                    else
                    {
                        preparedStatement.setInt(6, e.getErrorCode());
                    }
                    preparedStatement.setString(7, e.getSQLState() + ":" + e.getMessage());
                    preparedStatement.execute();
                    preparedStatement.close();
                    // 希望连接池能够复用数据库连接
                    this.dbInstance.releaseSqlHistoryConn(backendSqlHistoryConnection);
                }
                catch (SQLException se)
                {
                    this.dbInstance.logger.debug("[SERVER] Save to sql history failed.", se);
                }
            }

            // 生成一个错误消息
            ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
            errorResponse.setErrorFile("ParseRequest");
            errorResponse.setErrorResponse(String.valueOf(e.getErrorCode()), e.getMessage());
            errorResponse.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);
        }
        finally {
            out.close();
        }

        // 取消会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = "";
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL = "";
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = null;
    }
}
