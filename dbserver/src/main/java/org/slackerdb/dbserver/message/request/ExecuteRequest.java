package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.entity.*;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.response.*;
import org.slackerdb.dbserver.sql.RowEncoder;
import org.slackerdb.plsql.ParseSQLException;
import org.slackerdb.plsql.PlSqlVisitor;
import org.slackerdb.dbserver.server.DBInstance;
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

    public long insertSqlHistory(ChannelHandlerContext ctx)
    {
        // 插入SQL执行历史
        long sqlHistoryId = 0;
        if (!this.dbInstance.serverConfiguration.getAccess_mode().equals("READ_ONLY") &&
                this.dbInstance.serverConfiguration.getSqlHistory().equalsIgnoreCase("ON")) {
            sqlHistoryId = this.dbInstance.backendSqlHistoryId.incrementAndGet();
            SQLHistoryRecord sqlHistoryRecord =
                    new SQLHistoryRecord(
                            "INSERT",
                            sqlHistoryId,
                            ProcessHandle.current().pid(),
                            getCurrentSessionId(ctx),
                            this.dbInstance.getSession(getCurrentSessionId(ctx)).clientAddress,
                            this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL,
                            this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSqlId.get(),
                            LocalDateTime.now(),
                            null,
                            0,
                            0,
                            null
                    );
            this.dbInstance.sqlHistoryList.offer(sqlHistoryRecord);
        }
        return sqlHistoryId;
    }

    public void updateSqlHistory(long sqlHistoryId, int sqlCode, long affectedRows, String errorMsg)
    {
        // 更新SQL执行历史
        if (!this.dbInstance.serverConfiguration.getAccess_mode().equals("READ_ONLY") &&
                this.dbInstance.serverConfiguration.getSqlHistory().equalsIgnoreCase("ON")) {
            SQLHistoryRecord sqlHistoryRecord =
                    new SQLHistoryRecord(
                            "UPDATE",
                            sqlHistoryId,
                            0,
                            0,
                            null,
                            null,
                            0,
                            null,
                            LocalDateTime.now(),
                            sqlCode,
                            affectedRows,
                            errorMsg
                    );
            this.dbInstance.sqlHistoryList.offer(sqlHistoryRecord);
        }
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
                    CommandComplete commandComplete = new CommandComplete(this.dbInstance);
                    commandComplete.setCommandResult("UPDATE 0");
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
                Connection conn = this.dbInstance.getSession(getCurrentSessionId(ctx)).dbConnection;

                // 运行PLSQL代码
                this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL = parsedStatement.sql;
                nSqlHistoryId = insertSqlHistory(ctx);

                PlSqlVisitor.runPlSql(conn, parsedStatement.sql);
                if (nSqlHistoryId != -1) {
                    updateSqlHistory(nSqlHistoryId, 0, -1, "");
                }

                // 返回任务完成的消息
                CommandComplete commandComplete = new CommandComplete(this.dbInstance);
                commandComplete.setCommandResult("UPDATE 0");
                commandComplete.process(ctx, request, out);

                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, this.dbInstance.logger);

                // PLSQL的记录保存到SQL历史中
                this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL = parsedStatement.sql;
                this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSqlId.incrementAndGet();

                break tryBlock;
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
                // 是否之前发送过DescribeRequest
                boolean describeRequestExist = this.dbInstance.getSession(getCurrentSessionId(ctx)).hasDescribeRequest;
                if (isResultSet) {
                    // 处理有结果集的情况
                    DataRow dataRow = new DataRow(this.dbInstance);

                    ResultSet rs = parsedStatement.preparedStatement.getResultSet();
                    parsedStatement.resultSet = rs;

                    // 列级元数据只解析一次：类型码、formatCode、RowDescription 字段都由它统一产出，
                    // 行循环里不再做 getColumnTypeName / toUpperCase / 格式化器构造等重复工作。
                    ResultSetMetaData rsmd = rs.getMetaData();
                    RowEncoder encoder = new RowEncoder(rsmd, true, this.dbInstance.logger);

                    // 之前发送过describeRequest，需要返回RowDescription
                    if (describeRequestExist) {
                        // 每个describeRequest， 对应一个返回
                        this.dbInstance.getSession(getCurrentSessionId(ctx)).hasDescribeRequest = false;

                        RowDescription rowDescription = new RowDescription(this.dbInstance);
                        rowDescription.setFields(encoder.describe());
                        rowDescription.process(ctx, request, out);
                        rowDescription.setFields(null);

                        // 发送并刷新RowsDescription消息
                        PostgresMessage.writeAndFlush(ctx, RowDescription.class.getSimpleName(), out, this.dbInstance.logger);
                    }

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
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).hasDescribeRequest = false;

                    // 之前没有发送过describeRequest，需要返回NO_DATA
                    if (describeRequestExist) {
                        NoData noData = new NoData(this.dbInstance);
                        noData.process(ctx, request, out);

                        // 发送并刷新RowsDescription消息
                        PostgresMessage.writeAndFlush(ctx, NoData.class.getSimpleName(), out, this.dbInstance.logger);
                    }

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
            if (executeSQL.toUpperCase().startsWith("BEGIN")) {
                commandComplete.setCommandResult("BEGIN");
            } else if (executeSQL.toUpperCase().startsWith("END")) {
                commandComplete.setCommandResult("COMMIT");
            } else if (executeSQL.toUpperCase().startsWith("SELECT")) {
                commandComplete.setCommandResult("SELECT " + nRowsAffected);
            } else if (executeSQL.toUpperCase().startsWith("INSERT")) {
                commandComplete.setCommandResult("INSERT 0 " + nRowsAffected);
            } else if (executeSQL.toUpperCase().startsWith("COMMIT")) {
                commandComplete.setCommandResult("COMMIT");
            } else if (executeSQL.toUpperCase().startsWith("ROLLBACK")) {
                commandComplete.setCommandResult("ROLLBACK");
            } else if (executeSQL.toUpperCase().startsWith("ABORT")) {
                    commandComplete.setCommandResult("ROLLBACK");
            }
            else
            {
                commandComplete.setCommandResult("UPDATE " + nRowsAffected);
            }
            commandComplete.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, this.dbInstance.logger);

            // 更新SQL历史信息
            if (nSqlHistoryId != -1)
            {
                updateSqlHistory(nSqlHistoryId, 0, nRowsAffected, "");
            }
        }
        catch (ParseSQLException e)
        {
            // 生成一个错误消息
            ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
            errorResponse.setErrorFile("ExecuteRequest");
            errorResponse.setErrorResponse("PLSQL-ERROR: -1", e.getMessage());
            errorResponse.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

            // 更新SQL历史信息
            if (nSqlHistoryId != -1)
            {
                updateSqlHistory(nSqlHistoryId, -1, nRowsAffected, e.getMessage());
            }
        }
        catch (SQLException e) {
            // 事务块中的语句失败 → 事务进入 aborted 状态，ReadyForQuery 之后要回 'E'（BUG-15）
            this.dbInstance.getSession(getCurrentSessionId(ctx)).markTransactionFailed();

            // 生成一个错误消息
            ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
            errorResponse.setErrorFile("ExecuteRequest");
            errorResponse.setErrorResponse(String.valueOf(e.getErrorCode()), e.getMessage());
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
}
