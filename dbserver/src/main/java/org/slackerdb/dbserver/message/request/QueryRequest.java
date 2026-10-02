package org.slackerdb.dbserver.message.request;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.netty.channel.ChannelHandlerContext;
import org.duckdb.DuckDBConnection;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.response.*;
import org.slackerdb.dbserver.sql.CommandTag;
import org.slackerdb.dbserver.sql.CopyProtocolHandler;
import org.slackerdb.dbserver.sql.PlSqlHostImpl;
import org.slackerdb.dbserver.sql.RowEncoder;
import org.slackerdb.dbserver.sql.SQLReplacer;
import org.slackerdb.dbserver.sql.SqlCommentStripper;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.sql.SqlStateMapper;
import org.slackerdb.dbserver.server.DBSession;
import org.slackerdb.dbserver.sql.antlr.CopyVisitor;
import org.slackerdb.plsql.PlSqlEngine;
import org.slackerdb.plsql.PlSqlException;
import org.slackerdb.plsql.detect.PlSqlDetector;
import org.slackerdb.plsql.detect.PlSqlScript;
import org.slackerdb.plsql.detect.PlSqlStatement;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * PostgreSQL简单查询请求处理类。
 * 处理客户端发送的'Q'类型消息（简单查询），执行SQL语句并返回结果。
 * 支持SELECT、INSERT、UPDATE、DELETE等SQL语句，以及COPY命令。
 *
 * <p>查询执行流程：</p>
 * <ol>
 *   <li>解析查询消息</li>
 *   <li>对SQL进行必要的替换处理</li>
 *   <li>执行SQL语句</li>
 *   <li>格式化结果集</li>
 *   <li>通过PostgreSQL协议返回结果</li>
 * </ol>
 *
 * @see PostgresRequest
 */
public class QueryRequest  extends PostgresRequest {
    private String      sql = "";

    /**
     * 构造函数，创建查询请求处理器。
     *
     * @param pDbInstance 数据库实例对象
     */
    public QueryRequest(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    /**
     * 解码查询请求消息。
     * 解析PostgreSQL简单查询消息（'Q'类型），提取SQL查询字符串。
     *
     * <p>消息格式：</p>
     * <pre>
     *   Query (F)
     *     Byte1('Q')
     *       Identifies the message as a simple query.
     *     Int32
     *       Length of message contents in bytes, including self.
     *     String
     *       The query string itself.
     * </pre>
     *
     * @param data 原始消息字节数据
     */
    /**
     * 判断语句是否为"结束事务块"的语句（COMMIT / ROLLBACK / ABORT / END）。
     *
     * <p>处于失败事务块时，只有这类语句仍应被允许执行——它们用于把会话从事务块中解救出来。</p>
     */
    static boolean isTransactionEndStatement(String sql) {
        if (sql == null) {
            return false;
        }
        String normalized = SqlCommentStripper.stripComments(sql).trim().toUpperCase();
        return normalized.startsWith("COMMIT")
                || normalized.startsWith("ROLLBACK")
                || normalized.startsWith("ABORT")
                || normalized.startsWith("END");
    }

    @Override
    public void decode(byte[] data) {
        sql = new String(data, StandardCharsets.UTF_8);

        // 简单查询消息的 SQL 是 NUL 结尾的字符串：这里必须把结尾的 NUL 去掉，
        // 否则 "BEGIN\0" 这类文本会带着不可见字节进入后续解析（历史上它会让
        // PL/SQL 检测误判成匿名块，并把 NUL 交给 SQL 层）。
        int end = sql.length();
        while (end > 0 && sql.charAt(end - 1) == '\0') {
            end--;
        }
        if (end != sql.length()) {
            sql = sql.substring(0, end);
        }

        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        // 记录会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = this.getClass().getSimpleName();
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL = sql;
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = LocalDateTime.now();

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 本次语句在 SQL 历史表里的记录 ID：-1 = 还没登记。
        // 声明在 try 之外，这样异常分支也能把它收尾（否则失败的语句会永远停在"进行中"）。
        long sqlHistoryId = -1;

        tryBlock:
        try {
            // COPY ... FROM STDIN：简单查询路径。
            // 与扩展协议（ExecuteRequest）共用 CopyProtocolHandler，避免两条路径行为分叉。
            CopyProtocolHandler.Result copyResult = CopyProtocolHandler.beginCopyIn(this, ctx, sql);
            if (copyResult == CopyProtocolHandler.Result.COPY_STARTED) {
                // 已发出 CopyInResponse：本回合到此结束，后续由 CopyData/CopyDone 收尾
                out.close();
                break tryBlock;
            }
            if (copyResult == CopyProtocolHandler.Result.REJECTED) {
                // CopyProtocolHandler 已经发过错误与 ReadyForQuery
                out.close();
                break tryBlock;
            }

            // 命令标签必须基于**客户端原文**判断，而不是 SQLReplacer 改写后的 SQL。
            // 原因：SET/SHOW 这类语句会被 SQLReplacer 改写（SET 被改写成空串、SHOW 被改写成
            // 等价的 SELECT），改写后就再也认不出原始命令了，标签会退化成 "SELECT 0"。
            String originalSql = sql;

            // PL/SQL 检测：简单查询协议下也必须支持匿名块与多语句脚本。
            // 历史实现完全没有这条分支，`DO $$...$$` 会被原样丢给 DuckDB 并报错。
            PlSqlScript plsqlScript = PlSqlDetector.analyze(sql);
            if (plsqlScript.hasError()) {
                this.dbInstance.getSession(getCurrentSessionId(ctx)).markTransactionFailed();
                sqlHistoryId = insertSqlHistory(ctx);
                updateSqlHistory(sqlHistoryId, 0, 0, "42601: " + plsqlScript.error());

                ErrorResponse lexicalError = new ErrorResponse(this.dbInstance);
                lexicalError.setErrorFile("QueryRequest");
                lexicalError.setErrorResponse("42601", plsqlScript.error());
                lexicalError.process(ctx, request, out);
                PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

                ReadyForQuery lexicalReady = new ReadyForQuery(this.dbInstance);
                lexicalReady.process(ctx, request, out);
                PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);

                out.close();
                break tryBlock;
            }

            if (plsqlScript.isSingleBlock() || plsqlScript.isScript()) {
                // 匿名块 / 多语句脚本：逐条执行并逐条回 CommandComplete（PG 简单查询语义）
                sqlHistoryId = insertSqlHistory(ctx);
                long scriptRows = executePlSqlScript(ctx, request, out, plsqlScript);
                updateSqlHistory(sqlHistoryId, 0, scriptRows, null);

                ReadyForQuery scriptReady = new ReadyForQuery(this.dbInstance);
                scriptReady.process(ctx, request, out);
                PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);

                out.close();
                break tryBlock;
            }

            if (plsqlScript.isEmpty()) {
                // 空脚本 / 只有注释：走下面的空语句分支（避免把纯注释丢给 DuckDB）
                sql = "";
            }

            // 在执行之前需要做替换
            sql = SQLReplacer.replaceSQL(this.dbInstance, sql);

            // 事务块处于失败状态时，除 COMMIT/ROLLBACK/ABORT（用于结束事务块）之外的语句一律拒绝。
            // 这是 PG 的语义（SQLSTATE 25P02），也是 ReadyForQuery 上报 'E' 的配套行为：
            // 只上报状态而不拒绝后续语句，客户端仍会拿到 DuckDB 层面的晦涩错误。
            DBSession currentSession = this.dbInstance.getSession(getCurrentSessionId(ctx));
            if (currentSession.getTransactionState() == DBSession.TransactionState.FAILED
                    && !isTransactionEndStatement(sql)) {
                // 被拒绝的语句同样要进审计：客户端确实发过这条语句，只是没执行
                sqlHistoryId = insertSqlHistory(ctx);
                updateSqlHistory(sqlHistoryId, 0, 0,
                        "25P02: current transaction is aborted, commands ignored until end of transaction block");

                ErrorResponse failedTxError = new ErrorResponse(this.dbInstance);
                failedTxError.setErrorResponse("25P02",
                        "current transaction is aborted, commands ignored until end of transaction block");
                failedTxError.setErrorSeverity("ERROR");
                failedTxError.process(ctx, request, out);
                PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

                ReadyForQuery failedTxReady = new ReadyForQuery(this.dbInstance);
                failedTxReady.process(ctx, request, out);
                PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);

                out.close();
                break tryBlock;
            }

            // 取出上次解析的SQL，如果为空语句，则直接返回
            if (sql.isEmpty()) {
                // 空语句（客户端发的空串，或被 SQLReplacer 改写掉的 SET/SHOW）也要进审计：
                // 审计记录的是**客户端原文**，所以这里仍然能看出到底发了什么。
                sqlHistoryId = insertSqlHistory(ctx);
                updateSqlHistory(sqlHistoryId, 0, 0, null);

                CommandComplete commandComplete = new CommandComplete(this.dbInstance);
                // 空标签在协议上非法（客户端无法解析），统一回一个可解析的标签。
                // 注意用客户端原文判断：SET/RESET 等被 SQLReplacer 改写成空串，
                // 此时标签应当是 "SET" 而不是 "SELECT 0"。
                commandComplete.setCommandResult(CommandTag.of(originalSql, 0, false));
                commandComplete.process(ctx, request, out);

                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, this.dbInstance.logger);

                // 发送ReadyForQuery
                ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
                readyForQuery.process(ctx, request, out);

                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);

                out.close();

                break tryBlock;
            }

            // 理解为简单查询
            long nAffectedRows = 0;
            DBSession simpleQuerySession = this.dbInstance.getSession(getCurrentSessionId(ctx));
            // 每条简单查询都算一个新语句：SqlId 递增后，历史记录才能唯一标识这次执行
            simpleQuerySession.executingSqlId.incrementAndGet();
            // 先登记再 prepare/execute：prepare 阶段就可能失败（语法错误），
            // 登记放在前面才能保证"失败的语句也进历史"，并且会被 catch 分支收尾。
            sqlHistoryId = insertSqlHistory(ctx);
            PreparedStatement preparedStatement =
                    simpleQuerySession.dbConnection.prepareStatement(sql);
            // 登记"正在执行"的句柄，让 CancelRequest / KILL SESSION 能真正取消简单查询。
            // 改造前这里不登记任何句柄（parsedStatements 只服务于扩展协议），
            // 导致简单查询路径（psql、JDBC 的 Statement.execute）无法被取消。
            long runningHandleId = simpleQuerySession.registerRunningStatement(preparedStatement);
            simpleQuerySession.executingPreparedStatement = preparedStatement;
            boolean isResultSet = false;
            try {
                try {
                    isResultSet = preparedStatement.execute();
                }
                catch (SQLException e) {
                    if (!e.getMessage().contains("no transaction is active"))
                    {
                        preparedStatement.close();
                        throw e;
                    }
                }
                if (isResultSet) {
                    ResultSet rs = preparedStatement.getResultSet();

                    // 列级元数据只解析一次；simple query 路径一律文本返回（binaryAllowed = false）
                    RowEncoder encoder = new RowEncoder(rs.getMetaData(), false, this.dbInstance.logger);

                    RowDescription rowDescription = new RowDescription(this.dbInstance);
                    rowDescription.setFields(encoder.describe());
                    rowDescription.process(ctx, request, out);
                    rowDescription.setFields(null);

                    // 发送并刷新RowsDescription消息
                    PostgresMessage.writeAndFlush(ctx, RowDescription.class.getSimpleName(), out, this.dbInstance.logger);

                    DataRow dataRow = new DataRow(this.dbInstance);
                    // 累计尚未刷出的数据行字节数，达到阈值后再 flush
                    int pendingBytes = 0;
                    while (rs.next()) {
                        // 绑定列的信息
                        dataRow.setColumns(encoder.encode(rs));
                        dataRow.process(ctx, request, out);
                        dataRow.setColumns(null);

                        nAffectedRows ++;
                        // 批量写入数据行（仅 write，不 flush），累计达到阈值后再一次性刷出，
                        // 避免整个结果集都堆在 Netty 出站缓冲区里
                        pendingBytes += PostgresMessage.write(ctx, DataRow.class.getSimpleName(), out, this.dbInstance.logger);
                        if (pendingBytes >= PostgresMessage.FLUSH_THRESHOLD_BYTES) {
                            ctx.flush();
                            pendingBytes = 0;
                        }
                    }
                    rs.close();
                    // 刷出剩余尚未发送的数据行
                    ctx.flush();
                }
                else
                {
                    if (preparedStatement.isClosed()) {
                        nAffectedRows = -1;
                    }
                    else
                    {
                        nAffectedRows = preparedStatement.getUpdateCount();
                    }
                }
            }
            finally {
                // 语句已结束（正常或异常），先摘掉句柄再关闭。
                // 顺序很重要：先注销，避免取消请求拿到一个正在被关闭的句柄。
                simpleQuerySession.unregisterRunningStatement(runningHandleId);
                simpleQuerySession.executingPreparedStatement = null;
                try {
                    preparedStatement.close();
                }
                catch (SQLException closeException) {
                    // 关闭失败不应掩盖真正的执行异常（例如"语句被取消"）
                    this.dbInstance.logger.warn(
                            "[SERVER] Close prepared statement after simple query failed. {}",
                            closeException.getMessage());
                }
            }

            // 执行完成后更新事务状态机。
            // 注意：这里必须放在"错误分支"之外判断失败——事务块内任何语句失败都会
            // 走 catch (SQLException) 分支，由那里调用 markTransactionFailed()。
            DBSession querySession = this.dbInstance.getSession(getCurrentSessionId(ctx));
            String upperSql = sql.toUpperCase();
            if (upperSql.startsWith("BEGIN") || upperSql.startsWith("START TRANSACTION")) {
                querySession.beginTransaction();
            } else if (upperSql.startsWith("END")
                    || upperSql.startsWith("COMMIT")
                    || upperSql.startsWith("ROLLBACK")
                    || upperSql.startsWith("ABORT")) {
                querySession.endTransaction();
            }

            // 语句执行完毕（成功），用最终行数收尾审计
            updateSqlHistory(sqlHistoryId, 0, nAffectedRows, null);

            CommandComplete commandComplete = new CommandComplete(this.dbInstance);
            // 命令标签由 CommandTag 统一生成：只有 DML/查询系列带行数，
            // DDL 与工具类命令回命令名本身。改造前这里靠字符串前缀猜，
            // DELETE/DDL/SET/WITH..SELECT 全部被误报成 "UPDATE <n>"。
            commandComplete.setCommandResult(CommandTag.of(originalSql, nAffectedRows, isResultSet));
            commandComplete.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, this.dbInstance.logger);

            // 发送ReadyForQuery
            ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
            readyForQuery.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);
        } catch (PlSqlException pe) {
            // PL/SQL 结构化错误：SQLSTATE 由引擎给出（42601 / 57014 / P0001 ...）
            this.dbInstance.getSession(getCurrentSessionId(ctx)).markTransactionFailed();

            String message = pe.getMessage() + pe.positionSuffix();
            if (sqlHistoryId != -1) {
                updateSqlHistory(sqlHistoryId, -1, 0, pe.getSqlState() + ":" + message);
            }

            ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
            errorResponse.setErrorFile("QueryRequest");
            errorResponse.setErrorResponse(pe.getSqlState(), message);
            errorResponse.setErrorSeverity("ERROR");
            errorResponse.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

            ReadyForQuery plsqlReady = new ReadyForQuery(this.dbInstance);
            plsqlReady.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);
        } catch (SQLException se) {
            // 事务块中的语句失败 → 事务进入 aborted 状态，ReadyForQuery 之后要回 'E'（BUG-15）。
            // 自动提交模式下（不在事务块中）标记是空操作。
            this.dbInstance.getSession(getCurrentSessionId(ctx)).markTransactionFailed();

            // 失败的语句同样要收尾审计（sqlHistoryId == -1 表示异常发生在登记之前）
            if (sqlHistoryId != -1) {
                updateSqlHistory(sqlHistoryId, se.getErrorCode(), 0,
                        SqlStateMapper.fromException(se) + ":" + se.getMessage());
            }

            StackTraceElement[] stackTrace = se.getStackTrace();

            // 生成一个错误消息
            ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
            errorResponse.setErrorResponse(SqlStateMapper.fromException(se), se.getMessage());
            errorResponse.setErrorSeverity("ERROR");
            errorResponse.setErrorFile(stackTrace[0].getFileName());
            errorResponse.setErrorLine(String.valueOf(stackTrace[0].getLineNumber()));
            errorResponse.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

            // 发送ReadyForQuery
            ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
            readyForQuery.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);
        } finally {
            out.close();
        }

        // 取消会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = "";
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL = "";
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = null;
    }

    /**
     * 简单查询协议下逐条执行脚本（匿名块与 SQL 混合），并逐条回 {@code CommandComplete}。
     *
     * <p>这是 {@code psql} / {@code Statement.execute} 发多语句脚本的路径：
     * 历史实现把整串文本丢给 DuckDB prepare，必然失败；PL/SQL 块更是完全没有分支。</p>
     *
     * @return 所有语句返回的结果行数之和（用于审计收尾）
     */
    private long executePlSqlScript(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out,
                                    PlSqlScript script) throws IOException, SQLException {
        DBSession session = this.dbInstance.getSession(getCurrentSessionId(ctx));
        PlSqlHostImpl host = new PlSqlHostImpl(this.dbInstance, session);
        long totalRows = 0;
        int pendingBytes = 0;

        for (PlSqlStatement statement : script.statements()) {
            session.clearCancelRequested();
            session.executingSqlId.incrementAndGet();

            if (statement.isBlock()) {
                session.executingSQL = statement.body();
                PlSqlEngine.execute(host, statement.body());

                CommandComplete blockComplete = new CommandComplete(this.dbInstance);
                blockComplete.setCommandResult("DO");
                blockComplete.process(ctx, request, out);
                PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, this.dbInstance.logger);
                continue;
            }

            String statementSql = SQLReplacer.replaceSQL(this.dbInstance, statement.sql());
            if (statementSql.isEmpty()) {
                CommandComplete emptyComplete = new CommandComplete(this.dbInstance);
                emptyComplete.setCommandResult(CommandTag.of(statement.sql(), 0, false));
                emptyComplete.process(ctx, request, out);
                PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, this.dbInstance.logger);
                continue;
            }
            session.executingSQL = statementSql;

            long affectedRows = -1;
            boolean returnedRows = false;
            try (PreparedStatement preparedStatement = session.dbConnection.prepareStatement(statementSql)) {
                long handleId = session.registerRunningStatement(preparedStatement);
                try {
                    boolean hasResultSet;
                    try {
                        hasResultSet = preparedStatement.execute();
                    }
                    catch (SQLException e) {
                        if (!e.getMessage().contains("no transaction is active")) {
                            throw e;
                        }
                        hasResultSet = false;
                    }

                    if (hasResultSet) {
                        returnedRows = true;
                        ResultSet resultSet = preparedStatement.getResultSet();
                        RowEncoder encoder = new RowEncoder(resultSet.getMetaData(), false, this.dbInstance.logger);

                        RowDescription rowDescription = new RowDescription(this.dbInstance);
                        rowDescription.setFields(encoder.describe());
                        rowDescription.process(ctx, request, out);
                        PostgresMessage.writeAndFlush(ctx, RowDescription.class.getSimpleName(), out, this.dbInstance.logger);
                        rowDescription.setFields(null);

                        DataRow dataRow = new DataRow(this.dbInstance);
                        long rows = 0;
                        while (resultSet.next()) {
                            dataRow.setColumns(encoder.encode(resultSet));
                            dataRow.process(ctx, request, out);
                            dataRow.setColumns(null);
                            rows++;
                            pendingBytes += PostgresMessage.write(ctx, DataRow.class.getSimpleName(), out, this.dbInstance.logger);
                            if (pendingBytes >= PostgresMessage.FLUSH_THRESHOLD_BYTES) {
                                ctx.flush();
                                pendingBytes = 0;
                            }
                        }
                        resultSet.close();
                        ctx.flush();
                        affectedRows = rows;
                        totalRows += rows;
                    }
                    else if (!preparedStatement.isClosed()) {
                        affectedRows = preparedStatement.getUpdateCount();
                    }
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

            CommandComplete commandComplete = new CommandComplete(this.dbInstance);
            commandComplete.setCommandResult(CommandTag.of(statement.sql(), affectedRows, returnedRows));
            commandComplete.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, this.dbInstance.logger);
        }
        return totalRows;
    }
}
