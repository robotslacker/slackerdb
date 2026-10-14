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

        // 后端连接：取得后必须先"记账"，任何提前返回都要负责归还，
        // 否则连接池的槽位只增不减（上限 256 用尽后整台服务器再也建不起连接）。
        // 交给会话（session.dbConnection）之后本方法不再持有它，故把局部变量置空。
        Connection conn = null;
        // 握手期的语句同样要先"记账"：失败路径（例如库名拼出的坏 SQL）也要负责关闭。
        // 否则连接池会判定"归还时仍有未关闭语句"、把整条连接退役——
        // 一个反复用坏库名重连的客户端就能一直逼着池重建物理连接。
        Statement stmt = null;

        try {
            // 检查登录选项中是否包含了数据库名称，如果不包含，直接报错
            if (!startupOptions.containsKey("database"))
            {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                // 生成一个错误消息
                ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
                errorResponse.setErrorResponse("3D000",Utils.getMessage("SLACKERDB-00001"));
                errorResponse.process(ctx, request, out);

                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

                // 关闭连接
                out.close();
                ctx.close();
                return;
            }

            // 处理连接参数中的current_schema
            String userSearchPath = "";
            if (startupOptions.get("search_path") != null && !startupOptions.get("search_path").isEmpty())
            {
                userSearchPath = startupOptions.get("search_path");
            }

            // 把查询路径指向默认数据库
            conn = this.dbInstance.dbDataSourcePool.getConnection();
            stmt = conn.createStatement();
            stmt.execute("set variable current_database = '" + startupOptions.get("database") + "'");

            // 会不会执行 USE：库名为空（客户端连 URL 上都没写库）或实例正在等密钥时都不执行。
            boolean willUseDatabase = !startupOptions.get("database").isEmpty()
                    && !this.dbInstance.instanceSuspendForSecretKey;

            // search_path 必须分两种情况下发 —— DuckDB 的 USE 会把它**整个重置**成
            // "<catalog>.main"（不是追加），所以 USE 之前设的路径一定会被冲掉：
            //
            //  • 不执行 USE：一次设好，含 fake catalog 与客户端要求的路径；
            //  • 会执行 USE：此刻当前 catalog 还是 memory，而客户端要求的 schema 很可能只存在于
            //    实例库里 —— 这时先设 search_path 会因为 DuckDB 的校验（不存在的 catalog+schema
            //    是直接报错、不是忽略）把连接直接掐断，反而连 USE 都走不到。
            //    因此路径一律留到 USE 之后再补。
            if (!willUseDatabase) {
                applySearchPath(stmt, userSearchPath);
            }

            if (willUseDatabase) {
                // 如果数据库等待密钥，则无法执行USE操作
                try {
                    stmt.execute("use \"" + startupOptions.get("database") + "\"");
                } catch (SQLException sqlException) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    // 生成一个错误消息
                    ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
                    errorResponse.setErrorResponse(
                            "3D000",
                            "Database [" + startupOptions.get("database") + "] does not exist! \n" +
                                    sqlException.getMessage());
                    errorResponse.process(ctx, request, out);

                    // 发送并刷新返回消息
                    PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

                    // 关闭连接
                    out.close();
                    ctx.close();

                    // 释放数据库信息
                    stmt.close();
                    this.dbInstance.dbDataSourcePool.releaseConnection(conn);
                    return;
                }

                // 客户端**明确要求**了 search_path 时必须在这里补一次，否则它的请求会被静默丢弃：
                // 连接照样成功、不报错、不打日志，非限定名却按 <库>.main 解析——轻则表找不到，
                // 重则解析到另一个同名表、读到错数据。
                // （pgjdbc 的 currentSchema、asyncpg 的 server_settings 都走这个启动参数。）
                //
                // 只在客户端提了要求时才补：没提要求的会话保持 USE 给出的 <库>.main 这个既有默认，
                // 不改变它们的会话语义。
                //
                // 补设失败只记日志、不掐断连接：此前这种连接是"能连上但路径被忽略"，
                // 不能因为修这个缺陷反而把它们变成连不上（客户端要的 schema 未必存在）。
                if (!userSearchPath.isEmpty()) {
                    try {
                        applyRequestedSearchPath(stmt, userSearchPath);
                    } catch (SQLException searchPathException) {
                        this.dbInstance.logger.warn(
                                "[SERVER] Apply client search_path [{}] after USE failed, session keeps the "
                                        + "default search path: {}",
                                userSearchPath, searchPathException.getMessage());
                    }
                }
                stmt.close();
            }
            else {
                // 不执行 USE 时同样要关掉这条语句：会话会一直持有连接（有的长达整个连接生命周期），
                // 留着未关闭的语句就等于把它挂在连接上不放。而且连接池把"归还时仍有未关闭语句"
                // 视为状态不可信、会把连接退役——握手期漏一条语句会让每个无库名会话白扔一条连接。
                stmt.close();
            }

            // 记录会话信息
            int sessionId = getCurrentSessionId(ctx);
            this.dbInstance.getSession(sessionId).dbConnection = conn;
            this.dbInstance.getSession(sessionId).dbConnectedTime = LocalDateTime.now();
            this.dbInstance.getSession(sessionId).startupOptions = startupOptions;
            this.dbInstance.getSession(sessionId).status = "DB-CONNECTED";
            // 连接的所有权已交给会话（由会话在 endSession / abortSession 里归还）。
            // 置空局部变量，避免下面的 catch 把一条已经交给会话的连接又归还一次
            // ——那会让正在使用它的会话突然失去连接。
            conn = null;
        }
        catch (SQLException se)
        {
            this.dbInstance.logger.error("[SERVER] Init backend connection error. ", se);
            // 初始化中途失败：这条连接没有交给任何会话，必须归还，
            // 否则每失败一次就泄漏一个池槽位（历史缺陷：磁盘模式下 search_path 报错，
            // 每个客户端连接都失败，256 次之后整台服务器拒收所有连接）。
            // 语句先关：连接池要求"归还时语句必须已经收拾干净"，否则会退役整条连接。
            if (stmt != null) {
                try {
                    stmt.close();
                }
                catch (SQLException ignored) {
                    // 握手已经失败，关不掉语句不改变结论
                }
            }
            if (conn != null) {
                this.dbInstance.dbDataSourcePool.releaseConnection(conn);
            }
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
        parameterStatus.setKeyValue("server_version", DBInstance.PG_COMPAT_VERSION);
        parameterStatus.process(ctx, request, out);

        parameterStatus.setKeyValue("server_type", "JANUS");
        parameterStatus.process(ctx, request, out);

        parameterStatus.setKeyValue("client_encoding", "UTF8");
        parameterStatus.process(ctx, request, out);

        // libpq 系的客户端（psql / psycopg2 / 任何走 PQescapeStringConn 的库）靠这个参数决定
        // 字符串怎么转义。不下发时 libpq 按 "standard_conforming_strings = off" 处理，把值里的
        // 反斜杠转义成 "\\"，而 DuckDB 按字面量解释反斜杠 —— 于是 'a\b' 写进库里变成 'a\\b'
        // （实测 length 3 -> 4，客户端无任何报错）。这里必须下发 on，且与 SQLReplacer 里
        // "show standard_conforming_strings" 的改写结果保持一致。
        parameterStatus.setKeyValue("standard_conforming_strings", "on");
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

    /**
     * 下发会话的 search_path：{@code <fake catalog>.duck_catalog,<客户端要求的路径>}。
     *
     * <p>唯一来源是 {@link DBInstance#catalogName}（见该字段说明）：{@code duck_catalog}
     * 恒在内存主 catalog，所以前缀是 {@code memory}，绝不能按"实例库叫什么"推导。</p>
     *
     * <p>这条在 {@code USE} 之前下发，服务的是两种不执行 {@code USE} 的会话
     * （库名为空、实例在等密钥）。若后面还会执行 {@code USE}，它会被覆盖，
     * 由 {@link #applyRequestedSearchPath} 接手。</p>
     *
     * <p>注意 DuckDB 会校验 search_path 里的 catalog+schema 是否存在，不存在的会<b>报错</b>
     * 而不是忽略，所以启动期这条失败即连接失败。</p>
     */
    private void applySearchPath(Statement stmt, String userSearchPath) throws SQLException {
        stmt.execute("set search_path = '" + this.dbInstance.catalogName + ".duck_catalog,"
                + userSearchPath + "'");
    }

    /**
     * {@code USE} 之后补设客户端要求的 search_path。
     *
     * <p><b>必须原样下发客户端的值，不能加 {@code memory.duck_catalog} 前缀</b>：
     * DuckDB 会跟着 search_path 的第一项切换当前 catalog。把 memory 放在最前面会让整个会话
     * 回到内存库——实测 {@code current_catalog()} 变成 {@code memory}、
     * {@code current_schema()} 变成 {@code duck_catalog}，于是非限定名建表会落进内存库，
     * 而不是用户自己的实例库。PG 语义里 {@code currentSchema}/{@code search_path}
     * 只影响 schema 搜索路径、不改变当前库，这里必须与之一致
     * （Sanity01Test#testCurrentSchema 正是这样把这个错误实现抓出来的）。</p>
     */
    private void applyRequestedSearchPath(Statement stmt, String userSearchPath) throws SQLException {
        stmt.execute("set search_path = '" + userSearchPath + "'");
    }
}
