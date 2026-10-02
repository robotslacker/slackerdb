package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.entity.*;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.response.*;
import org.slackerdb.dbserver.sql.CommandTag;
import org.slackerdb.dbserver.sql.CopyProtocolHandler;
import org.slackerdb.dbserver.sql.PlSqlHostImpl;
import org.slackerdb.dbserver.sql.RowEncoder;
import org.slackerdb.dbserver.sql.SQLReplacer;
import org.slackerdb.plsql.PlSqlEngine;
import org.slackerdb.plsql.PlSqlException;
import org.slackerdb.plsql.detect.PlSqlStatement;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.sql.SqlStateMapper;
import org.slackerdb.dbserver.server.DBSession;
import org.slackerdb.common.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


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

    public ExecuteRequest(DBInstance pDbInstance) {
        super(pDbInstance);
    }

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

    /**
     * 为"改写后 SQL 为空"或"被拒绝执行"的语句登记审计。
     *
     * <p>这两类分支拿不到改写后的 SQL 文本（空语句的 {@code parsedStatement.sql} 是空串），
     * 因此回退到 Parse 阶段保存的<b>客户端原文</b> —— 审计必须能看出客户端到底发了什么。</p>
     *
     * @return 历史记录 ID（调用方用 {@link #updateSqlHistory} 收尾）
     */
    private long openAuditForRewrittenStatement(ChannelHandlerContext ctx, ParsedStatement parsedStatement) {
        String originalSql = (parsedStatement.originalSql != null && !parsedStatement.originalSql.isEmpty())
                ? parsedStatement.originalSql
                : parsedStatement.sql;
        DBSession session = this.dbInstance.getSession(getCurrentSessionId(ctx));
        session.executingSQL = originalSql == null ? "" : originalSql;
        session.executingSqlId.incrementAndGet();
        return insertSqlHistory(ctx);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        // 记录会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = this.getClass().getSimpleName();
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = LocalDateTime.now();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long  nRowsAffected = 0;
        long  nSqlHistoryId = -1;
        // 本次执行登记到会话"正在执行"注册表的句柄 id；0 表示本次没有登记
        long  runningHandleId = 0;
        // 本次执行是否产出了结果集（供 CommandTag 判断"无法归类时按查询还是按命令处理"）
        boolean statementReturnedRows = false;

        tryBlock:
        try {
            // 开始处理
            ParsedStatement parsedStatement =
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).getParsedStatement("Portal" + "-" + portalName);
            if (parsedStatement != null && dbInstance.instanceSuspendForSecretKey)
            {
                // 处理数据库加密的特殊语句
                // 这是一个非常特殊的语句, 用来设置数据库密钥
                String patternString = "(?i)alter\\s+database\\s+(\\w+)\\s+set\\s+encrypt\\s+key\\s+(\\w+);?";
                Pattern pattern = Pattern.compile(patternString);
                Matcher matcher = pattern.matcher(parsedStatement.sql);
                if (matcher.find())
                {
                    // 数据库加密挂载
                    String databaseEncryptKey = matcher.group(2).trim();
                    this.dbInstance.attachDatabase(databaseEncryptKey);
                    this.dbInstance.instanceSuspendForSecretKey = false;

                    // 返回任务完成的消息
                    // 这是 "ALTER DATABASE ... SET ENCRYPT KEY"：按 PG 语义回 "ALTER DATABASE"
                    // （DDL 类命令标签不带行数），而不是 "UPDATE 0"
                    CommandComplete commandComplete = new CommandComplete(this.dbInstance);
                    commandComplete.setCommandResult("ALTER DATABASE");
                    commandComplete.process(ctx, request, out);

                    // 发送并刷新返回消息
                    PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, this.dbInstance.logger);

                    // PLSQL的记录保存到SQL历史中
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL = parsedStatement.sql;
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSqlId.incrementAndGet();

                    break tryBlock;
                }
            }
            if (parsedStatement != null && parsedStatement.isPlSql)
            {
                // PLSQL处理
                DBSession plsqlSession = this.dbInstance.getSession(getCurrentSessionId(ctx));

                // 运行PLSQL代码
                this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL = parsedStatement.sql;
                nSqlHistoryId = insertSqlHistory(ctx);

                // 走 SPI 门面：引擎与数据库之间只有 PlSqlHostImpl 这一条通道，
                // 内部语句因此被登记到 runningStatements（可取消）、可观测、资源不泄漏。
                plsqlSession.clearCancelRequested();
                PlSqlHostImpl plsqlHost = new PlSqlHostImpl(this.dbInstance, plsqlSession);

                String commandTag;
                if (parsedStatement.plSqlScript != null) {
                    // 多语句脚本：按顺序执行（SQL 与匿名块混合），返回最后一条语句的命令标签
                    commandTag = executeScriptStatements(plsqlSession, plsqlHost, parsedStatement.plSqlScript);
                } else {
                    PlSqlEngine.execute(plsqlHost, parsedStatement.sql);
                    commandTag = "DO";
                }

                if (nSqlHistoryId != -1) {
                    updateSqlHistory(nSqlHistoryId, 0, -1, "");
                }

                // 返回任务完成的消息
                // 单块按 PG 语义回 "DO"；脚本回最后一条语句的标签
                CommandComplete commandComplete = new CommandComplete(this.dbInstance);
                commandComplete.setCommandResult(commandTag);
                commandComplete.process(ctx, request, out);

                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, this.dbInstance.logger);

                // PLSQL的记录保存到SQL历史中
                this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL = parsedStatement.sql;
                this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSqlId.incrementAndGet();

                break tryBlock;
            }
            // COPY ... FROM STDIN：扩展协议路径也必须进入 COPY 子协议。
            //
            // 这段检查必须放在"preparedStatement 为空就回空 CommandComplete"的兜底之前：
            // COPY 语句在 Parse 阶段刻意**不**创建 PreparedStatement（DuckDB 不认识 "FROM STDIN"），
            // 所以 preparedStatement 必定为 null，若先走兜底就永远到不了这里。
            //
            // 修复前 COPY 识别只做在简单查询（QueryRequest）里，于是
            // Statement.execute("COPY ... FROM STDIN")（默认 preferQueryMode=extended）
            // 会把语句直接丢给 DuckDB 并报 /dev/stdin 打不开；而 CopyManager 走简单查询却正常。
            // 两条路径现在共用 CopyProtocolHandler，行为一致。
            if (parsedStatement != null
                    && parsedStatement.resultSet == null
                    && parsedStatement.sql != null
                    && !parsedStatement.sql.isEmpty()) {
                CopyProtocolHandler.Result copyResult =
                        CopyProtocolHandler.beginCopyIn(this, ctx, parsedStatement.sql);
                if (copyResult == CopyProtocolHandler.Result.COPY_STARTED) {
                    // 已发出 CopyInResponse：本回合到此结束。
                    // 后续由客户端发 CopyData/CopyDone（或 CopyFail）收尾，
                    // 那时才由 CopyDoneRequest 发 CommandComplete/ErrorResponse 与 ReadyForQuery。
                    out.close();
                    break tryBlock;
                }
                if (copyResult == CopyProtocolHandler.Result.REJECTED) {
                    // CopyProtocolHandler 已经发过错误与 ReadyForQuery
                    out.close();
                    break tryBlock;
                }
            }

            if (parsedStatement == null || parsedStatement.preparedStatement == null || parsedStatement.preparedStatement.isClosed())
            {
                // 即使没有数据，也要返回一个空的CommandComplete
                CommandComplete commandComplete = new CommandComplete(this.dbInstance);
                commandComplete.process(ctx, request, out);

                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, this.dbInstance.logger);
                out.close();

                // 之前语句解析或者绑定出了错误, 没有继续执行的必要
                break tryBlock;
            }

            // 取出上次解析的SQL，如果为空语句，则直接返回
            String executeSQL = this.dbInstance.getSession(getCurrentSessionId(ctx)).getParsedStatement("Portal" + "-" + portalName).sql;
            if (executeSQL.isEmpty()) {
                // 空语句（客户端发的空串，或被 SQLReplacer 改写掉的 SET/SHOW）也要进审计。
                // 改写后的 SQL 是空串，所以要用 Parse 时留下的客户端原文，否则审计里什么都看不到。
                long historyId = openAuditForRewrittenStatement(ctx, parsedStatement);
                updateSqlHistory(historyId, 0, 0, null);

                CommandComplete commandComplete = new CommandComplete(this.dbInstance);
                commandComplete.process(ctx, request, out);

                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, this.dbInstance.logger);
                out.close();
                break tryBlock;
            }

            // 事务块处于失败状态时，除 COMMIT/ROLLBACK/ABORT 之外的语句一律拒绝（SQLSTATE 25P02）。
            // 与 QueryRequest 路径保持一致：ReadyForQuery 上报 'E' 之后必须真的拒绝后续语句。
            DBSession executeSession = this.dbInstance.getSession(getCurrentSessionId(ctx));
            if (executeSession.getTransactionState() == DBSession.TransactionState.FAILED
                    && !QueryRequest.isTransactionEndStatement(executeSQL)) {
                // 被拒绝的语句同样要进审计（客户端确实发过，只是没执行）
                long historyId = openAuditForRewrittenStatement(ctx, parsedStatement);
                updateSqlHistory(historyId, 0, 0,
                        "25P02: current transaction is aborted, commands ignored until end of transaction block");

                ErrorResponse failedTxError = new ErrorResponse(this.dbInstance);
                failedTxError.setErrorResponse("25P02",
                        "current transaction is aborted, commands ignored until end of transaction block");
                failedTxError.setErrorSeverity("ERROR");
                failedTxError.process(ctx, request, out);
                PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

                out.close();
                break tryBlock;
            }
            this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL = executeSQL;

            // 之前有缓存记录
            if (parsedStatement.resultSet != null)
            {
                // 保留原有SqlId不变
                // 记录到SQL历史中
                nSqlHistoryId = insertSqlHistory(ctx);

                ResultSet rs = parsedStatement.resultSet;
                DataRow dataRow = new DataRow(this.dbInstance);
                // Portal 续取：RowDescription 之前已经发过，这里只需要一个行编码器。
                // 列级元数据只解析一次，行循环里不再重复做类型判断与格式化器构造。
                RowEncoder encoder = new RowEncoder(rs.getMetaData(), true, this.dbInstance.logger);

                int rowsReturned = 0;
                // 累计尚未刷出的数据行字节数，达到阈值后再 flush
                int pendingBytes = 0;
                while (rs.next()) {
                    // 绑定列的信息
                    dataRow.setColumns(encoder.encode(rs));
                    dataRow.process(ctx, request, out);
                    dataRow.setColumns(null);

                    // 批量写入数据行（仅 write，不 flush），累计达到阈值后再一次性刷出。
                    // 既避免逐行 flush 带来的每行一次 TCP 系统调用，也避免整个结果集堆在出站缓冲区。
                    pendingBytes += PostgresMessage.write(ctx, DataRow.class.getSimpleName(), out, this.dbInstance.logger);
                    if (pendingBytes >= PostgresMessage.FLUSH_THRESHOLD_BYTES) {
                        ctx.flush();
                        pendingBytes = 0;
                    }

                    rowsReturned = rowsReturned + 1;
                    if (maximumRowsReturned != 0 && rowsReturned >= maximumRowsReturned) {
                        // 如果要求分批返回，则不再继续，分批返回
                        // writeAndFlush 会把上面尚未刷出的数据行一并送出
                        PortalSuspended portalSuspended = new PortalSuspended(this.dbInstance);
                        portalSuspended.process(ctx, request, out);
                        PostgresMessage.writeAndFlush(ctx, PortalSuspended.class.getSimpleName(), out, this.dbInstance.logger);
                        out.close();
                        parsedStatement.nRowsAffected += rowsReturned;
                        // 更新SQL历史信息
                        if (nSqlHistoryId != -1)
                        {
                            updateSqlHistory(nSqlHistoryId, 0, rowsReturned, "");
                        }
                        break tryBlock;
                    }
                }
                // 所有记录写入完毕，刷出剩余尚未发送的数据行
                ctx.flush();
                rs.close();
                nRowsAffected = parsedStatement.nRowsAffected + rowsReturned;
            }
            else
            {
                // 记录一个新的SqlID, 和当前正在执行的句柄（便于取消）
                this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSqlId.incrementAndGet();
                this.dbInstance.getSession(getCurrentSessionId(ctx)).executingPreparedStatement = parsedStatement.preparedStatement;
                // 同时登记到统一的"正在执行"注册表：让取消逻辑只依赖注册表一处，
                // 不必再区分"扩展协议靠 parsedStatements、简单查询靠注册表"两套来源。
                runningHandleId = this.dbInstance.getSession(getCurrentSessionId(ctx))
                        .registerRunningStatement(parsedStatement.preparedStatement);

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
                    statementReturnedRows = true;
                    // 处理有结果集的情况
                    DataRow dataRow = new DataRow(this.dbInstance);

                    ResultSet rs = parsedStatement.preparedStatement.getResultSet();
                    parsedStatement.resultSet = rs;

                    // 列级元数据只解析一次：类型码、formatCode 都由它统一产出，
                    // 行循环里不再做 getColumnTypeName / toUpperCase / 格式化器构造等重复工作。
                    ResultSetMetaData rsmd = rs.getMetaData();
                    RowEncoder encoder = new RowEncoder(rsmd, true, this.dbInstance.logger);

                    // 注意：这里**不**发 RowDescription —— 描述信息已经由 Describe 报文
                    // 即时应答（见 DescribeHandler）。改造前把 RowDescription 推迟到这里发，
                    // 一旦客户端只 Describe 不 Execute 就永远收不到回应（BUG-8）。

                    // 累计尚未刷出的数据行字节数，达到阈值后再 flush
                    int pendingBytes = 0;
                    while (rs.next()) {
                        // 绑定列的信息
                        dataRow.setColumns(encoder.encode(rs));
                        dataRow.process(ctx, request, out);
                        dataRow.setColumns(null);

                        // 批量写入数据行（仅 write，不 flush），累计达到阈值后再一次性刷出。
                        // 既避免逐行 flush 带来的每行一次 TCP 系统调用，也避免整个结果集堆在出站缓冲区。
                        pendingBytes += PostgresMessage.write(ctx, DataRow.class.getSimpleName(), out, this.dbInstance.logger);
                        if (pendingBytes >= PostgresMessage.FLUSH_THRESHOLD_BYTES) {
                            ctx.flush();
                            pendingBytes = 0;
                        }

                        rowsReturned = rowsReturned + 1;
                        if (maximumRowsReturned != 0 && rowsReturned >= maximumRowsReturned) {
                            // 如果要求分批返回，则不再继续，分批返回
                            // writeAndFlush 会把上面尚未刷出的数据行一并送出
                            PortalSuspended portalSuspended = new PortalSuspended(this.dbInstance);
                            portalSuspended.process(ctx, request, out);
                            PostgresMessage.writeAndFlush(ctx, PortalSuspended.class.getSimpleName(), out, this.dbInstance.logger);
                            // 返回等待下一次ExecuteRequest
                            out.close();

                            parsedStatement.nRowsAffected += rowsReturned;
                            // 更新SQL历史信息
                            if (nSqlHistoryId != -1)
                            {
                                updateSqlHistory(nSqlHistoryId, 0, rowsReturned, "");
                            }
                            break tryBlock;
                        }
                    }
                    // 所有记录写入完毕，刷出剩余尚未发送的数据行
                    ctx.flush();
                    rs.close();
                    nRowsAffected = parsedStatement.nRowsAffected + rowsReturned;
                }
                else
                {
                    // 无结果集：NoData 同样已由 Describe 即时应答，这里不重复发送。
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
            // 更新事务状态机
            String upperExecuteSql = executeSQL.toUpperCase();
            if (upperExecuteSql.startsWith("BEGIN") || upperExecuteSql.startsWith("START TRANSACTION")) {
                this.dbInstance.getSession(getCurrentSessionId(ctx)).beginTransaction();
            } else if (upperExecuteSql.startsWith("END")
                    || upperExecuteSql.startsWith("COMMIT")
                    || upperExecuteSql.startsWith("ROLLBACK")
                    || upperExecuteSql.startsWith("ABORT")) {
                this.dbInstance.getSession(getCurrentSessionId(ctx)).endTransaction();
            }

            CommandComplete commandComplete = new CommandComplete(this.dbInstance);
            // 命令标签由 CommandTag 统一生成（与 QueryRequest 共用一份规则）。
            // 改造前这里靠字符串前缀猜，DELETE/DDL/SET/WITH..SELECT 全被误报成 "UPDATE <n>"，
            // 空语句还会发出非法空标签。
            commandComplete.setCommandResult(CommandTag.of(executeSQL, nRowsAffected, statementReturnedRows));
            commandComplete.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, this.dbInstance.logger);

            // 更新SQL历史信息
            if (nSqlHistoryId != -1)
            {
                updateSqlHistory(nSqlHistoryId, 0, nRowsAffected, "");
            }
        }
        catch (PlSqlException e)
        {
            // PL/SQL 的结构化错误：SQLSTATE 由引擎给出（42601 语法 / 57014 取消 / P0001 用户 RAISE ...）。
            // 取消与失败都会让事务进入 aborted 状态（PG 语义），与 SQLException 分支保持一致。
            this.dbInstance.getSession(getCurrentSessionId(ctx)).markTransactionFailed();

            String message = e.getMessage() + e.positionSuffix();

            ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
            errorResponse.setErrorFile("ExecuteRequest");
            errorResponse.setErrorResponse(e.getSqlState(), message);
            errorResponse.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

            // 更新SQL历史信息
            if (nSqlHistoryId != -1)
            {
                updateSqlHistory(nSqlHistoryId, -1, nRowsAffected, message);
            }
        }
        catch (SQLException e) {
            // 事务块中的语句失败 → 事务进入 aborted 状态，ReadyForQuery 之后要回 'E'（BUG-15）
            this.dbInstance.getSession(getCurrentSessionId(ctx)).markTransactionFailed();

            // 生成一个错误消息
            ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
            errorResponse.setErrorFile("ExecuteRequest");
            errorResponse.setErrorResponse(SqlStateMapper.fromException(e), e.getMessage());
            errorResponse.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

            // 更新SQL历史信息
            if (nSqlHistoryId != -1)
            {
                updateSqlHistory(nSqlHistoryId, e.getErrorCode(), nRowsAffected, e.getMessage());
            }
        }
        finally {
            // 无论正常结束、分批挂起（PortalSuspended）还是出错，都要摘掉"正在执行"的句柄。
            // 放在 finally 里是为了覆盖所有 break tryBlock / 异常退出路径；
            // 挂起后下一次 Execute 会重新登记，因此这里摘掉不会影响 portal 续取。
            // 会话可能已被并发摘除，这里必须容忍 null（否则会在 finally 里抛出 NPE 掩盖原始异常）。
            DBSession executingSession = this.dbInstance.getSession(getCurrentSessionId(ctx));
            if (executingSession != null) {
                executingSession.unregisterRunningStatement(runningHandleId);
            }
            out.close();
        }

        // 取消会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = "";
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL = "";
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = null;
    }

    /**
     * 执行多语句脚本（SQL 与匿名块混合）。
     *
     * <p>由 {@code ParsedStatement.plSqlScript} 携带的语句列表驱动；每条语句都登记
     * "正在执行"句柄，因此脚本中途也能被 {@code CancelRequest} 打断。</p>
     *
     * <p>结果集不返回给客户端：扩展协议里一次 Execute 只能对应一个响应。
     * 需要结果集请拆成多条语句执行（简单查询协议路径支持逐条返回，见 {@code QueryRequest}）。</p>
     *
     * @return 最后一条语句的命令标签（块为 {@code DO}）
     */
    private String executeScriptStatements(DBSession session, PlSqlHostImpl host,
                                           List<PlSqlStatement> statements) throws SQLException {
        String lastTag = "DO";
        for (PlSqlStatement statement : statements) {
            session.clearCancelRequested();
            session.executingSqlId.incrementAndGet();

            if (statement.isBlock()) {
                session.executingSQL = statement.body();
                PlSqlEngine.execute(host, statement.body());
                lastTag = "DO";
                continue;
            }

            // 脚本中的 SQL 与单语句路径一致：同样经过 SQLReplacer
            String statementSql = SQLReplacer.replaceSQL(this.dbInstance, statement.sql());
            if (statementSql.isEmpty()) {
                lastTag = CommandTag.of(statement.sql(), 0, false);
                continue;
            }
            session.executingSQL = statementSql;

            try (PreparedStatement scriptStatement = session.dbConnection.prepareStatement(statementSql)) {
                long handleId = session.registerRunningStatement(scriptStatement);
                try {
                    boolean hasResultSet;
                    try {
                        hasResultSet = scriptStatement.execute();
                    }
                    catch (SQLException e) {
                        if (!e.getMessage().contains("no transaction is active")) {
                            throw e;
                        }
                        hasResultSet = false;
                    }

                    long affectedRows = -1;
                    if (hasResultSet) {
                        // 必须消费掉结果集，否则下一条语句可能在 DuckDB 侧被挂住
                        ResultSet resultSet = scriptStatement.getResultSet();
                        long rows = 0;
                        while (resultSet.next()) {
                            rows++;
                        }
                        resultSet.close();
                        affectedRows = rows;
                    }
                    else if (!scriptStatement.isClosed()) {
                        affectedRows = scriptStatement.getUpdateCount();
                    }
                    lastTag = CommandTag.of(statement.sql(), affectedRows, hasResultSet);
                }
                finally {
                    session.unregisterRunningStatement(handleId);
                }
            }

            // 事务状态机：与单语句路径保持一致的判定
            String upperSql = statementSql.toUpperCase();
            if (upperSql.startsWith("BEGIN") || upperSql.startsWith("START TRANSACTION")) {
                session.beginTransaction();
            } else if (upperSql.startsWith("END") || upperSql.startsWith("COMMIT")
                    || upperSql.startsWith("ROLLBACK") || upperSql.startsWith("ABORT")) {
                session.endTransaction();
            }
        }
        return lastTag;
    }
}
