package org.slackerdb.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.duckdb.DuckDBConnection;
import org.slackerdb.configuration.ServerConfiguration;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.message.PostgresMessage;
import org.slackerdb.message.PostgresRequest;
import org.slackerdb.message.response.*;
import org.slackerdb.server.DBInstance;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

public class StartupRequest  extends PostgresRequest {
    //  StartupMessage (F)
    //    Int32
    //      Length of message contents in bytes, including self.
    //    Int32(196608)
    //      The protocol version number. The most significant 16 bits are the major version number (3 for the protocol described here).
    //      The least significant 16 bits are the minor version number (0 for the protocol described here).
    //      The protocol version number is followed by one or more pairs of parameter name and value strings. A zero byte is required as a terminator after the last name/value pair. Parameters can appear in any order. user is required, others are optional. Each parameter is specified as:
    //    String
    //      The parameter name. Currently, recognized names are:
    //      user
    //        The database username to connect as. Required; there is no default.
    //      database
    //        The database to connect to. Defaults to the username.
    //      options
    //        Command-line arguments for the backend. (This is deprecated in favor of setting individual run-time parameters.) Spaces within this string are considered to separate arguments, unless escaped with a backslash (\); write \\ to represent a literal backslash.
    //      replication
    //        Used to connect in streaming replication mode, where a small set of replication commands can be issued instead of SQL statements. Value can be true, false, or database, and the default is false. See Section 55.4 for details.
    //
    //    In addition to the above, other parameters may be listed. Parameter names beginning with _pq_. are reserved for use as protocol extensions, while others are treated as run-time parameters to be set at backend start time. Such settings will be applied during backend start (after parsing the command-line arguments if any) and will act as session defaults.
    //    String
    //      The parameter value.

    private final Map<String, String> startupOptions = new HashMap<>();

    @Override
    public void decode(byte[] data) {
        byte[][] result = Utils.splitByteArray(Arrays.copyOfRange(data, 4, data.length), (byte)0);
        for (int i = 0; i < result.length-1; i=i+2) {
            startupOptions.put(new String(result[i]), new String(result[i+1]));
        }
        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        try {
            //  检查登录选项中的数据库名称和文件名称是否匹配，如果不匹配，直接拒绝
            if (!ServerConfiguration.getData().equalsIgnoreCase(startupOptions.get("database")))
            {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                // 生成一个错误消息
                ErrorResponse errorResponse = new ErrorResponse();
                errorResponse.setErrorResponse("SLACKER-0099",
                        "Database [" + startupOptions.get("database") + "] does not exist! Connect refused. ");
                errorResponse.process(ctx, request, out);

                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out);

                // 关闭连接
                out.close();
                ctx.close();
                return;
            }
            if (!startupOptions.containsKey("user") || startupOptions.get("user").trim().isEmpty())
            {
                // 没有指定用户就默认为public
                startupOptions.put("user", "public");
            }
            String connectedUser =  startupOptions.get("user").trim();

            // 检查登录的用户
            if (!connectedUser.equalsIgnoreCase("public")) {
                boolean existUser = false;
                String querySchemaList =
                        "select schema_name from information_schema.schemata " +
                                "where schema_name = '" + connectedUser + "' LIMIT 1";

                Statement querySchemaStmt = DBInstance.backendSysConnection.createStatement();
                ResultSet rs = querySchemaStmt.executeQuery(querySchemaList);
                if (rs.next()) {
                    existUser = true;
                }
                rs.close();
                querySchemaStmt.close();
                if (!existUser) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();

                    ErrorResponse errorResponse = new ErrorResponse();
                    errorResponse.setErrorResponse("SLACKER-0099",
                            "User [" + startupOptions.get("user") + "] does not exist! Connect refused. ");
                    errorResponse.process(ctx, request, out);

                    // 发送并刷新返回消息
                    PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out);

                    // 关闭连接
                    out.close();
                    ctx.close();
                    return;
                }
            }

            // 把查询路径指向新的schema
            Connection conn =
                    ((DuckDBConnection) DBInstance.backendSysConnection).duplicate();
            Statement stmt = conn.createStatement();
            stmt.execute("set search_path = '" + connectedUser + ",duck_catalog'");
            stmt.close();

            // 记录会话信息
            int sessionId = getCurrentSessionId(ctx);
            DBInstance.getSession(sessionId).dbConnection = conn;
            DBInstance.getSession(sessionId).dbConnectedTime = LocalDateTime.now();
            DBInstance.getSession(sessionId).startupOptions = startupOptions;
            DBInstance.getSession(sessionId).status = "DB-CONNECTED";
        }
        catch (SQLException se)
        {
            AppLogger.logger.error("[SERVER] Init backend connection error. ", se);
            ctx.close();
            return;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 总是回复认证成功
        AuthenticationOk authenticationOk = new AuthenticationOk();
        authenticationOk.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, AuthenticationOk.class.getSimpleName(), out);

        // 返回一些参数信息
        ParameterStatus parameterStatus = new ParameterStatus();
        parameterStatus.setKeyValue("server_version", "15");
        parameterStatus.process(ctx, request, out);

        parameterStatus.setKeyValue("server_type", "JANUS");
        parameterStatus.process(ctx, request, out);

        parameterStatus.setKeyValue("client_encoding", "UTF8");
        parameterStatus.process(ctx, request, out);

        parameterStatus.setKeyValue("DateStyle", "ISO, YMD");
        parameterStatus.process(ctx, request, out);

        parameterStatus.setKeyValue("TimeZone", TimeZone.getDefault().getID());
        parameterStatus.process(ctx, request, out);

        parameterStatus.setKeyValue("is_superuser", "on");
        parameterStatus.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ParameterStatus.class.getSimpleName(), out);

        // 返回 BackendKeyData
        BackendKeyData backendKeyData = new BackendKeyData();
        backendKeyData.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, BackendKeyData.class.getSimpleName(), out);

        // 做好准备，可以查询
        ReadyForQuery readyForQuery = new ReadyForQuery();
        readyForQuery.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out);
        out.close();
    }
}
