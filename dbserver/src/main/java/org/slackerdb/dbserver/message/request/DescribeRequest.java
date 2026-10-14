package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.entity.ParsedStatement;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.response.ErrorResponse;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.server.DBSession;
import org.slackerdb.dbserver.sql.DescribeHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Describe ('D') —— 客户端请求语句或门户的元数据。
 *
 * <pre>
 *   Byte1('D')
 *   Int32   长度
 *   Byte1   'S' 描述语句；'P' 描述门户
 *   String  语句名或门户名（空串表示未命名的那一个）
 * </pre>
 *
 * <p><b>协议要求立即应答</b>，与 Execute 无关：</p>
 * <ul>
 *   <li>{@code 'S'} → {@code ParameterDescription('t')} + RowDescription('T') / NoData('n')</li>
 *   <li>{@code 'P'} → RowDescription('T') / NoData('n')</li>
 * </ul>
 */
public class DescribeRequest extends PostgresRequest {

    public DescribeRequest(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    public char describeType;
    public String portalName;

    @Override
    public void decode(byte[] data) {
        //  Describe (F)
        //    Byte1('D')
        //      Identifies the message as a Describe command.
        //    Int32
        //      Length of message contents in bytes, including self.
        //    Byte1
        //      'S' to describe a prepared statement; or 'P' to describe a portal.
        //    String
        //       The name of the prepared statement or portal
        //       (an empty string selects the unnamed prepared statement or portal).

        if (data.length == 0) {
            describeType = 'S';
            portalName = "";
        }
        else {
            describeType = (char) (data[0] & 0xFF);

            // String 以 0 结尾：真实驱动会写出结尾 0，部分手写客户端不写，
            // 两种报文都必须能解析出同一个名称。
            int end = 1;
            while (end < data.length && data[end] != 0) {
                end++;
            }
            portalName = new String(data, 1, end - 1, StandardCharsets.UTF_8);
        }

        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        DBSession session = this.dbInstance.getSession(getCurrentSessionId(ctx));
        if (describeType == 'S') {
            // 语句名与 ParseRequest 的存储键保持一致（未命名语句存为 NONAME）
            ParsedStatement parsedStatement = session
                    .getParsedStatement("PreparedStatement" + "-" + (portalName.isEmpty() ? "NONAME" : portalName));
            if (parsedStatement == null) {
                // PG 语义：Describe 一个不存在的语句 → 26000 invalid_sql_statement_name。
                // 改造前这里会回"0 个参数 + NoData"，客户端无法区分"语句没有结果集"与"语句不存在"。
                writeMissingObjectError(ctx, request, session, "26000",
                        "prepared statement \"" + portalName + "\" does not exist");
                return;
            }
            int[] declaredOids = parsedStatement.parameterDataTypeIds;
            DescribeHandler.describeStatement(this.dbInstance, ctx, session, parsedStatement, declaredOids);
        }
        else {
            // 门户名与 BindRequest 的存储键保持一致
            ParsedStatement parsedStatement = session.getParsedStatement("Portal" + "-" + portalName);
            if (parsedStatement == null) {
                // PG 语义：Describe 一个不存在的门户 → 34000 invalid_cursor_name
                writeMissingObjectError(ctx, request, session, "34000",
                        "portal \"" + portalName + "\" does not exist");
                return;
            }
            DescribeHandler.describePortal(this.dbInstance, ctx, session, parsedStatement);
        }
    }

    /**
     * 描述一个不存在的语句/门户：回协议规定的错误码，并进入"丢弃到 Sync"状态
     * （扩展协议里出错后，后续到 Sync 之间的报文都不该被执行）。
     */
    private void writeMissingObjectError(ChannelHandlerContext ctx, Object request, DBSession session,
                                         String sqlState, String message) throws IOException {
        session.markExtendedProtocolError();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
        errorResponse.setErrorFile("DescribeRequest");
        errorResponse.setErrorResponse(sqlState, message);
        errorResponse.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);
        out.close();
    }
}
