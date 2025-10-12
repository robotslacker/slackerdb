package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.response.*;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.common.utils.Utils;

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
    public StartupRequest(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    public Map<String, String> getStartupOptions()
    {
        return startupOptions;
    }

    @Override
    public void decode(byte[] data) {
        // 跳过前面4个字节的协议版本号. 目前没有对这个信息进行处理
        byte[][] result = Utils.splitByteArray(Arrays.copyOfRange(data, 4, data.length), (byte)0);
        for (int i = 0; i < result.length-1; i=i+2) {
            startupOptions.put(new String(result[i]), new String(result[i+1]));
        }
        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        // 记录会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = this.getClass().getSimpleName();
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = LocalDateTime.now();

        try {
            // 检查登录选项中是否包含了数据库名称，如果不包含，直接报错
            if (!startupOptions.containsKey("database"))
            {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                // 生成一个错误消息
                ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
                errorResponse.setErrorResponse("SLACKERDB-00001",Utils.getMessage("SLACKERDB-00001"));
                errorResponse.process(ctx, request, out);

                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

                // 关闭连接
                out.close();
                ctx.close();
                return;
            }

            String userSearchPath;
            if (this.dbInstance.serverConfiguration.getData_Dir().equalsIgnoreCase(":memory:")) {
                userSearchPath = "memory";
            }
            else
            {
                if (startupOptions.get("database") != null && !startupOptions.get("database").isEmpty())
                {
                    userSearchPath = startupOptions.get("database");
                }
                else
                {
                    userSearchPath = this.dbInstance.instanceName;
                }
            }
            if (startupOptions.get("search_path") != null && !startupOptions.get("search_path").isEmpty())
            {
                userSearchPath = userSearchPath + "." + startupOptions.get("search_path");
            }

            // 把查询路径指向新的schema
            Connection conn = this.dbInstance.dbDataSourcePool.getConnection();
            Statement stmt = conn.createStatement();
            stmt.execute("set variable current_database = '" + startupOptions.get("database") + "'");

            if (this.dbInstance.serverConfiguration.getData_Dir().equalsIgnoreCase(":MEMORY:")) {
                stmt.execute("set search_path = 'memory.duck_catalog," + userSearchPath + "'");
            }
            else
            {
                stmt.execute("set search_path = '\"" + this.dbInstance.serverConfiguration.getData() + "\".duck_catalog," + userSearchPath + "'");
            }
            stmt.close();

            // 记录会话信息
            int sessionId = getCurrentSessionId(ctx);
            this.dbInstance.getSession(sessionId).dbConnection = conn;
            this.dbInstance.getSession(sessionId).dbConnectedTime = LocalDateTime.now();
            this.dbInstance.getSession(sessionId).startupOptions = startupOptions;
            this.dbInstance.getSession(sessionId).status = "DB-CONNECTED";
        }
        catch (SQLException se)
        {
            this.dbInstance.logger.error("[SERVER] Init backend connection error. ", se);
            ctx.close();
            return;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 总是回复认证成功
        AuthenticationOk authenticationOk = new AuthenticationOk(this.dbInstance);
        authenticationOk.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, AuthenticationOk.class.getSimpleName(), out, this.dbInstance.logger);

        // 返回一些参数信息
        ParameterStatus parameterStatus = new ParameterStatus(this.dbInstance);
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
        PostgresMessage.writeAndFlush(ctx, ParameterStatus.class.getSimpleName(), out, this.dbInstance.logger);

        // 返回 BackendKeyData
        BackendKeyData backendKeyData = new BackendKeyData(this.dbInstance);
        backendKeyData.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, BackendKeyData.class.getSimpleName(), out, this.dbInstance.logger);

        // 做好准备，可以查询
        ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
        readyForQuery.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);
        out.close();

        // 取消会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = "";
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = null;
    }
}
