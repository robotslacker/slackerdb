package org.slackerdb.protocol;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;

public class ParseRequest extends PostgresRequest {
    public String name = "";
    private String sql = "";
    public short  numOfParameters = 0;
    public int    parameterObjectId;

    public String getSql()
    {
        return sql;
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

        // 将Startup中的参数信息分解后送入连接的Context中
        byte[][] result = Utils.splitByteArray(data, (byte)0);
        name = new String(result[0], StandardCharsets.UTF_8);
        sql = new String(result[1], StandardCharsets.UTF_8);

        if (result.length > 2) {
            byte[] part = Arrays.copyOfRange(
                    data,
                    result[0].length + 1 + result[1].length +1,
                    result[0].length + 1 + result[1].length +1 + 2);
            numOfParameters = Utils.bytesToInt16(part);
            if (data.length >= result[0].length + 1 + result[1].length +1 + 2 + 4) {
                part = Arrays.copyOfRange(
                        data,
                        result[0].length + 1 + result[1].length + 1 + 2,
                        result[0].length + 1 + result[1].length + 1 + 2 + 4);
                parameterObjectId = Utils.bytesToInt32(part);
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
        ctx.channel().attr(AttributeKey.valueOf("SQL")).set(executeSQL.trim());

        // 对于空语句，直接返回结果
        if (executeSQL.isEmpty()) {
            ParseComplete parseComplete = new ParseComplete();
            parseComplete.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ParseComplete.class.getSimpleName(), out);
            return;
        }

        try {
            Connection conn = (Connection) ctx.channel().attr(AttributeKey.valueOf("Connection")).get();
            PreparedStatement preparedStatement = conn.prepareStatement(executeSQL);

            ParseComplete parseComplete = new ParseComplete();
            parseComplete.process(ctx, request, out);

            // 记录PreparedStatement
            ctx.channel().attr(AttributeKey.valueOf("PreparedStatement")).set(preparedStatement);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ParseComplete.class.getSimpleName(), out);
        }
        catch (SQLException e) {
            // 清空PreparedStatement
            ctx.channel().attr(AttributeKey.valueOf("PreparedStatement")).set(null);

            // 生成一个错误消息
            ErrorResponse errorResponse = new ErrorResponse();
            errorResponse.setErrorResponse(String.valueOf(e.getErrorCode()), e.getMessage());
            errorResponse.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out);
        }
    }
}
