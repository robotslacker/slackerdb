package org.slackerdb.protocol.postgres.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.protocol.postgres.message.*;
import org.slackerdb.protocol.postgres.message.response.ErrorResponse;
import org.slackerdb.protocol.postgres.message.response.ParseComplete;
import org.slackerdb.protocol.postgres.sql.SQLReplacer;
import org.slackerdb.server.DBInstance;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;

public class ParseRequest extends PostgresRequest {
    private String      preparedStmtName = "";
    private String      sql = "";
    private short       numOfParameters = 0;
    private int[]       parameterDataTypeIds;

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

        int currentPos = result[0].length + 1 + result[1].length + 1;
        if (result.length > 2) {
            byte[] part = Arrays.copyOfRange(
                    data,
                    currentPos, currentPos + 2);
            numOfParameters = Utils.bytesToInt16(part);
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

        // 由于PG驱动程序内置的一些语句在目标数据库上无法执行，所以这里要进行转换
        String executeSQL = SQLReplacer.replaceSQL(parseRequest.sql);

        // 记录上次执行的SQL
        DBInstance.getSession(getCurrentSessionId(ctx)).executeSQL = executeSQL;

        // 对于空语句，直接返回结果
        if (executeSQL.isEmpty()) {
            ParseComplete parseComplete = new ParseComplete();
            parseComplete.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ParseComplete.class.getSimpleName(), out);
            out.close();
            return;
        }

        try {
            Connection conn = DBInstance.getSession(getCurrentSessionId(ctx)).dbConnection;
            PreparedStatement preparedStatement = conn.prepareStatement(executeSQL);

            ParseComplete parseComplete = new ParseComplete();
            parseComplete.process(ctx, request, out);

            // 记录PreparedStatement,以及对应的参数类型
            DBInstance.getSession(getCurrentSessionId(ctx)).savePreparedStatement("PreparedStatement" + "-" + preparedStmtName, preparedStatement);
            DBInstance.getSession(getCurrentSessionId(ctx))
                    .savePreparedStatementParameterDataTypeIds("PreparedStatement*DataTypeIds" + "-" + preparedStmtName, parameterDataTypeIds);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ParseComplete.class.getSimpleName(), out);
        }
        catch (SQLException e) {
            // 清空PreparedStatement
            try {
                DBInstance.getSession(getCurrentSessionId(ctx)).clearPreparedStatement("PreparedStatement" + "-" + preparedStmtName);
            } catch (Exception e2) {
                AppLogger.logger.error("Error clearing prepared statement", e2);
            }
            DBInstance.getSession(getCurrentSessionId(ctx)).clearPreparedStatementParameterDataTypeIds("PreparedStatement*DataTypeIds" + "-" + preparedStmtName);

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
