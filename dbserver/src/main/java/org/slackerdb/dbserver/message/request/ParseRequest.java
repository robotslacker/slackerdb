package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.entity.ParsedStatement;
import org.slackerdb.dbserver.sql.CopyProtocolHandler;
import org.slackerdb.dbserver.sql.CursorSql;
import org.slackerdb.dbserver.sql.DeallocateSql;
import org.slackerdb.dbserver.sql.SessionSettingSql;
import org.slackerdb.dbserver.sql.TransactionSql;
import org.slackerdb.dbserver.entity.SQLHistoryRecord;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.response.ErrorResponse;
import org.slackerdb.dbserver.message.response.ParseComplete;
import org.slackerdb.dbserver.sql.SQLReplacer;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.server.DBSession;
import org.slackerdb.dbserver.sql.SqlStateMapper;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.plsql.detect.PlSqlDetector;
import org.slackerdb.plsql.detect.PlSqlScript;

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

    /**
     * 语句缓存容量把关。
     *
     * <p>只有<b>新增</b>带名字的语句才受限于 {@link DBSession#MAX_PREPARED_STATEMENTS}：
     * 覆盖同名语句（pgjdbc/dbdriver 的一次性查询都复用未命名语句）不受影响。</p>
     *
     * <p>超限时按扩展协议语义处理：回 {@code 54000 program_limit_exceeded}，
     * 并让会话进入"丢弃到 Sync"状态（后续 Bind/Execute 不该被执行）。
     * 选择报错而不是静默淘汰，是因为淘汰会打断正在使用的语句或挂起的游标——
     * 那是比"客户端需要回收"严重得多的隐蔽故障。</p>
     *
     * @return {@code true} = 已回错误、调用方必须立即结束本次 Parse
     */
    private boolean rejectIfStatementCacheFull(ChannelHandlerContext ctx, Object request,
                                               ByteArrayOutputStream out) throws IOException {
        DBSession session = this.dbInstance.getSession(getCurrentSessionId(ctx));
        if (session == null) {
            return false;
        }
        String statementKey = "PreparedStatement" + "-" + preparedStmtName;
        if (session.getParsedStatement(statementKey) != null
                || session.preparedStatementCount() < DBSession.MAX_PREPARED_STATEMENTS) {
            return false;
        }
        session.markExtendedProtocolError();

        ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
        errorResponse.setErrorFile("ParseRequest");
        errorResponse.setErrorResponse("54000", "too many prepared statements in this session (limit "
                + DBSession.MAX_PREPARED_STATEMENTS + "): deallocate unused statements (DEALLOCATE ALL) "
                + "or reconnect");
        errorResponse.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);
        return true;
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ParseRequest parseRequest = (ParseRequest) request;

        // 语句缓存限流：带名字的语句不会被复用，长连接上会无界增长
        // （postgres_fdw 每条 DML 都用一个新名字 Prepare 一次）。
        // 超限直接回 54000 并结束本次 Parse，而不是静默淘汰正在使用的语句。
        if (rejectIfStatementCacheFull(ctx, request, out)) {
            out.close();
            return;
        }

        if (this.dbInstance.instanceSuspendForSecretKey)
        {
            // 这是一个非常特殊的语句, 用来设置数据库密钥
            String patternString = "(?i)alter\\s+database\\s+(\\w+)\\s+set\\s+encrypt\\s+key\\s+(\\w+);?";
            Pattern pattern = Pattern.compile(patternString);
            Matcher matcher = pattern.matcher(sql);
            if (matcher.find())
            {
                String alterDatabaseName = matcher.group(1).trim();
                if (!this.dbInstance.instanceName.equalsIgnoreCase(alterDatabaseName))
                {
                    // 生成一个错误消息
                    ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
                    errorResponse.setErrorFile("ParseRequest");
                    // SQLSTATE 也用标准码：这个分支的语义就是"这个库不存在"
                    errorResponse.setErrorResponse("3D000", "Encrypted database [" + alterDatabaseName + "] dose not exist!");
                    errorResponse.process(ctx, request, out);

                    // Parse 阶段报错：扩展协议后续报文一律丢弃到 Sync
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).markExtendedProtocolError();

                    // 即使是空语句，也要更新缓存中记录的语句信息
                    // 一些第三方工具用发送空语句解析来检测数据库状态
                    ParsedStatement parsedPrepareStatement = new ParsedStatement();
                    parsedPrepareStatement.sql = "";
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement(
                            "PreparedStatement" + "-" + preparedStmtName, parsedPrepareStatement);
                }
                else
                {
                    ParseComplete parseComplete = new ParseComplete(this.dbInstance);
                    parseComplete.process(ctx, request, out);

                    // 记录PreparedStatement,以及对应的参数类型
                    ParsedStatement parsedPrepareStatement = new ParsedStatement();
                    parsedPrepareStatement.sql = sql.trim();
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement(
                            "PreparedStatement" + "-" + preparedStmtName, parsedPrepareStatement);
                }
                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, ParseComplete.class.getSimpleName(), out, this.dbInstance.logger);
                out.close();

                return;
            }
        }

        // 处理 COPY ... FROM STDIN 语句。
        // 与 PLSQL 同样的处理方式：这类语句不能交给 JDBC 的 prepareStatement ——
        // DuckDB 不认识 PG 的 "FROM STDIN"，会把 STDIN 当文件路径，直接报
        // "IO Error: No files found that match the pattern \"/dev/stdin\""。
        // 因此这里只记录语句文本并回 ParseComplete，真正的 COPY 子协议在 Execute 阶段
        // 由 CopyProtocolHandler 建立（见 ExecuteRequest）。
        if (CopyProtocolHandler.isCopyFromStdin(sql)) {
            ParseComplete parseComplete = new ParseComplete(this.dbInstance);
            parseComplete.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, ParseComplete.class.getSimpleName(), out, this.dbInstance.logger);
            out.close();

            ParsedStatement parsedCopyStatement = new ParsedStatement();
            parsedCopyStatement.sql = sql.trim();
            parsedCopyStatement.originalSql = parseRequest.sql;
            this.dbInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement(
                    "PreparedStatement" + "-" + preparedStmtName, parsedCopyStatement);
            return;
        }

        // COPY ... TO STDOUT 同理：DuckDB 不认识 "TO STDOUT"，会把它当文件路径并报
        // "IO Error: Cannot open file \"/dev/stdout\""。这里同样只记录语句文本，
        // 真正的导出在 Execute 阶段由 CopyProtocolHandler.beginCopyOut 完成。
        if (CopyProtocolHandler.isCopyToStdout(sql)) {
            ParseComplete parseComplete = new ParseComplete(this.dbInstance);
            parseComplete.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, ParseComplete.class.getSimpleName(), out, this.dbInstance.logger);
            out.close();

            ParsedStatement parsedCopyOutStatement = new ParsedStatement();
            parsedCopyOutStatement.sql = sql.trim();
            parsedCopyOutStatement.originalSql = parseRequest.sql;
            this.dbInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement(
                    "PreparedStatement" + "-" + preparedStmtName, parsedCopyOutStatement);
            return;
        }

        // DEALLOCATE ALL 与 COPY 同理：不能交给 JDBC 的 prepareStatement ——
        // DuckDB 不支持这条语句（报 42601 "DEALLOCATE requires a name"），
        // 而 postgres_fdw 在出错清理路径会发它。这里只记录"空语句"并回 ParseComplete，
        // 真正的处理（清空协议级缓存 + 回 DEALLOCATE ALL 标签）在 Execute 阶段。
        if (DeallocateSql.isDeallocateAll(sql) || DeallocateSql.isDeallocateAll(parseRequest.sql)) {
            ParseComplete parseComplete = new ParseComplete(this.dbInstance);
            parseComplete.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, ParseComplete.class.getSimpleName(), out, this.dbInstance.logger);
            out.close();

            ParsedStatement parsedDeallocateStatement = new ParsedStatement();
            parsedDeallocateStatement.sql = "";
            parsedDeallocateStatement.originalSql = parseRequest.sql;
            this.dbInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement(
                    "PreparedStatement" + "-" + preparedStmtName, parsedDeallocateStatement);
            return;
        }

        // T2：PG 特有的会话设置/复位（SET <PG 独有参数> / RESET / RESET ALL / DISCARD ALL）
        // 同样不能交给 JDBC 的 prepareStatement —— DuckDB 没有这些参数，或整条语法都不认。
        // 与 DEALLOCATE ALL 一样：记录"空语句"并回 ParseComplete，真正的接管在 Execute 阶段
        // （那里能拿到会话并渲染 CommandComplete/NOTICE/错误码）。
        if (SessionSettingSql.setAssignment(parseRequest.sql) != null
                || SessionSettingSql.isResetAll(parseRequest.sql)
                || SessionSettingSql.resetTarget(parseRequest.sql) != null
                || SessionSettingSql.isDiscardAll(parseRequest.sql)
                || SessionSettingSql.showTarget(parseRequest.sql) != null) {
            ParseComplete parseComplete = new ParseComplete(this.dbInstance);
            parseComplete.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, ParseComplete.class.getSimpleName(), out, this.dbInstance.logger);
            out.close();

            ParsedStatement parsedSettingStatement = new ParsedStatement();
            parsedSettingStatement.sql = "";
            parsedSettingStatement.originalSql = parseRequest.sql;
            this.dbInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement(
                    "PreparedStatement" + "-" + preparedStmtName, parsedSettingStatement);
            return;
        }

        // B1：PG 游标族（DECLARE ... CURSOR FOR / FETCH / MOVE / CLOSE）同样不能交给 JDBC 的
        // prepareStatement —— DuckDB 没有这些语句。与 COPY / DEALLOCATE 一样：
        // 记录"空语句"并回 ParseComplete，真正的执行在 Execute 阶段（那里有会话与游标注册表）。
        if (CursorSql.isCursorStatement(parseRequest.sql)) {
            ParseComplete parseComplete = new ParseComplete(this.dbInstance);
            parseComplete.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, ParseComplete.class.getSimpleName(), out, this.dbInstance.logger);
            out.close();

            ParsedStatement parsedCursorStatement = new ParsedStatement();
            parsedCursorStatement.sql = "";
            parsedCursorStatement.originalSql = parseRequest.sql;
            this.dbInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement(
                    "PreparedStatement" + "-" + preparedStmtName, parsedCursorStatement);
            return;
        }

        // P2：事务控制语句。START TRANSACTION 被 SQLReplacer 改写成空串（历史遗留，用来绕开
        // DuckDB 不认这条语法），因此这里必须用**客户端原文**判定；真正的语义在 Execute 阶段
        // 由 TransactionHandler 接管（要拿到会话才能驱动事务状态）。
        if (TransactionSql.parse(parseRequest.sql) != null) {
            ParseComplete parseComplete = new ParseComplete(this.dbInstance);
            parseComplete.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, ParseComplete.class.getSimpleName(), out, this.dbInstance.logger);
            out.close();

            ParsedStatement parsedTransactionStatement = new ParsedStatement();
            parsedTransactionStatement.sql = "";
            parsedTransactionStatement.originalSql = parseRequest.sql;
            this.dbInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement(
                    "PreparedStatement" + "-" + preparedStmtName, parsedTransactionStatement);
            return;
        }

        // 统一检测：PL/SQL 匿名块、多语句脚本，或普通 SQL（取代历史正则）
        PlSqlScript plsqlScript = PlSqlDetector.analyze(parseRequest.sql);

        if (plsqlScript.hasError()) {
            // 词法层面的错误（未闭合字符串/注释/$$ 等）在这里就能定性，不必等数据库报错
            this.dbInstance.getSession(getCurrentSessionId(ctx)).markTransactionFailed();
            ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
            errorResponse.setErrorFile("ParseRequest");
            errorResponse.setErrorResponse("42601", plsqlScript.error());
            errorResponse.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);
            out.close();
            return;
        }

        if (plsqlScript.isEmpty()) {
            // 空脚本 / 只有注释：与"改写后为空"同样处理
            ParseComplete parseComplete = new ParseComplete(this.dbInstance);
            parseComplete.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, ParseComplete.class.getSimpleName(), out, this.dbInstance.logger);
            out.close();

            ParsedStatement emptyStatement = new ParsedStatement();
            emptyStatement.sql = "";
            emptyStatement.originalSql = parseRequest.sql;
            this.dbInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement(
                    "PreparedStatement" + "-" + preparedStmtName, emptyStatement);
            return;
        }

        if (plsqlScript.isSingleBlock() || plsqlScript.isScript()) {
            // 匿名块 / 多语句脚本：不交给数据库 prepare，等 Execute 阶段执行
            ParseComplete parseComplete = new ParseComplete(this.dbInstance);
            parseComplete.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ParseComplete.class.getSimpleName(), out, this.dbInstance.logger);
            out.close();

            // 记录SQL语句
            ParsedStatement parsedPrepareStatement = new ParsedStatement();
            parsedPrepareStatement.sql = plsqlScript.isSingleBlock()
                    ? plsqlScript.first().body()
                    : parseRequest.sql.trim();
            parsedPrepareStatement.originalSql = parseRequest.sql;
            parsedPrepareStatement.isPlSql = true;
            if (plsqlScript.isScript()) {
                // 多语句脚本：携带完整语句列表，Execute 阶段顺序执行（旧实现会丢掉包装外的语句）
                parsedPrepareStatement.plSqlScript = plsqlScript.statements();
            }
            this.dbInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement(
                    "PreparedStatement" + "-" + preparedStmtName, parsedPrepareStatement);
            return;
        }

        // 客户端常以 SET application_name='x' 上报来源，而该语句在下面会被替换成空语句
        // （DuckDB 不支持）。必须在替换之前截留，记到会话上，供 /status.sessions 辨识来源。
        String requestedApplicationName = SQLReplacer.extractApplicationName(parseRequest.sql);
        if (requestedApplicationName != null) {
            this.dbInstance.getSession(getCurrentSessionId(ctx)).applicationName = requestedApplicationName;
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
            // 改写后为空（SET/SHOW 等）：审计只能靠原文，否则历史表里只剩一条空语句
            parsedPrepareStatement.originalSql = parseRequest.sql;
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
            parsedPrepareStatement.originalSql = parseRequest.sql;
            parsedPrepareStatement.preparedStatement = preparedStatement;
            parsedPrepareStatement.parameterDataTypeIds = parameterDataTypeIds;
            this.dbInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement(
                    "PreparedStatement" + "-" + preparedStmtName, parsedPrepareStatement);
            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ParseComplete.class.getSimpleName(), out, this.dbInstance.logger);
        }
        catch (SQLException e) {
            // 事务块内的语句失败必须让事务块进入 aborted 状态（PG 语义），
            // 与 QueryRequest / ExecuteRequest 的处理保持一致。
            // 改造前只有后两条路径会标记，于是"在 Parse 阶段就失败"的语句（例如表不存在 ——
            // DuckDB 在 prepare 时就报错）不会中止事务块：ReadyForQuery 仍报 'T'，
            // 后续语句也不会被 25P02 拒绝，而是照常执行。
            // 同时进入"丢弃到 Sync"状态：扩展协议里 Parse 失败后，后续 Bind/Execute 必须被丢弃。
            this.dbInstance.getSession(getCurrentSessionId(ctx)).markExtendedProtocolError();

            // 清空PreparedStatement
            try {
                this.dbInstance.getSession(getCurrentSessionId(ctx)).clearParsedStatement(
                        "PreparedStatement" + "-" + preparedStmtName);
            } catch (Exception e2) {
                this.dbInstance.logger.error("Error clearing prepared statement", e2);
            }

            // 插入SQL执行历史
            if (!this.dbInstance.serverConfiguration.getAccess_mode().equals("READ_ONLY") &&
                    this.dbInstance.serverConfiguration.getSqlHistory().equalsIgnoreCase("ON")) {
                SQLHistoryRecord sqlHistoryRecord =
                        new SQLHistoryRecord(
                                "INSERT",
                                this.dbInstance.backendSqlHistoryId.incrementAndGet(),
                                ProcessHandle.current().pid(),
                                getCurrentSessionId(ctx),
                                this.dbInstance.getSession(getCurrentSessionId(ctx)).clientAddress,
                                this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL,
                                this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSqlId.get(),
                                LocalDateTime.now(),
                                LocalDateTime.now(),
                                e.getErrorCode(),
                                0,
                                e.getSQLState() + ":" + e.getMessage()
                        );
                this.dbInstance.sqlHistoryList.offer(sqlHistoryRecord);
            }

            // 生成一个错误消息
            ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
            errorResponse.setErrorFile("ParseRequest");
            errorResponse.setErrorResponse(SqlStateMapper.fromException(e), e.getMessage());
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
