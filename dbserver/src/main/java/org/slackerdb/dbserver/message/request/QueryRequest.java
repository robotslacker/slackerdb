package org.slackerdb.dbserver.message.request;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.netty.channel.ChannelHandlerContext;
import org.duckdb.DuckDBConnection;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.response.*;
import org.slackerdb.dbserver.sql.RowEncoder;
import org.slackerdb.dbserver.sql.SQLReplacer;
import org.slackerdb.dbserver.sql.SqlCommentStripper;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.server.DBSession;
import org.slackerdb.dbserver.sql.antlr.CopyVisitor;

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

        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        // 记录会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = this.getClass().getSimpleName();
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL = sql;
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = LocalDateTime.now();

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        tryBlock:
        try {
            JSONObject parseObject = CopyVisitor.parseCopyStatement(sql);
            if (parseObject.getInteger("errorCode") == 0)
            {
                // 这次一个Copy语句
                if (!parseObject.getString("copyDirection").equals("FROM") ||
                        !parseObject.getString("copyFilePath").equals("STDIN") ||
                        !parseObject.getString("copyType").equals("table")
                )
                {
                    ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
                    errorResponse.setErrorResponse(
                            "SLACKER-0099",
                            "Feature not supported. Only support COPY .. FROM STDIN");
                    errorResponse.setErrorSeverity("ERROR");
                    errorResponse.process(ctx, request, out);

                    // 发送并刷新返回消息
                    PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

                    // 发送ReadyForQuery
                    ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
                    readyForQuery.process(ctx, request, out);

                    // 发送并刷新返回消息
                    PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);

                    return;
                }
                // 获取导入的用户表和表名
                String copyTableName = parseObject.getString("table");
                String targetTableName;
                String targetSchemaName;
                if (copyTableName.split("\\.").length > 1) {
                    targetSchemaName = copyTableName.split("\\.")[0];
                    targetTableName = copyTableName.split("\\.")[1];
                } else {
                    targetSchemaName = "";
                    targetTableName = copyTableName;
                }

                // 获取需要导入的列名
                Map<String, Integer> targetColumnMap = new HashMap<>();
                JSONArray columnsJson = parseObject.getJSONArray("columns");
                if (columnsJson != null) {
                    for (int i = 0; i < columnsJson.size(); i++) {
                        targetColumnMap.put(columnsJson.get(i).toString().trim().toUpperCase(), i);
                    }
                }

                // 获取COPY的文件类型
                JSONObject copyOptions = parseObject.getJSONObject("options");
                String copyTableFormat = null;
                if (copyOptions != null && copyOptions.containsKey("FORMAT"))
                {
                    copyTableFormat = copyOptions.getString("FORMAT").toUpperCase();
                }
                if (copyTableFormat == null ||
                        (!copyTableFormat.equals("CSV") && !copyTableFormat.equals("BINARY") )
                )
                {
                    ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
                    errorResponse.setErrorResponse(
                            "SLACKER-0099",
                            "Feature not supported. Only support FORMAT CSV|BINARY");
                    errorResponse.setErrorSeverity("ERROR");
                    errorResponse.process(ctx, request, out);

                    // 发送并刷新返回消息
                    PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

                    // 发送ReadyForQuery
                    ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
                    readyForQuery.process(ctx, request, out);

                    // 发送并刷新返回消息
                    PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);

                    return;
                }

                DuckDBConnection conn = (DuckDBConnection) this.dbInstance.getSession(getCurrentSessionId(ctx)).dbConnection;

                // 获取表名的实际表名，DUCK并不支持部分字段的Appender操作。所以要追加列表中不存在的相关信息
                List<Integer> copyTableDbColumnMapPos = new ArrayList<>();
                List<String> copyTableDbColumnType = new ArrayList<>();
                List<String> copyTableDbColumnName = new ArrayList<>();
                String executeSql;
                if (targetSchemaName.isEmpty()) {
                    executeSql = "SELECT * FROM " + targetTableName + " LIMIT 0";
                } else {
                    executeSql = "SELECT * FROM " + targetSchemaName + "." + targetTableName + " LIMIT 0";
                }
                PreparedStatement ps = conn.prepareStatement(executeSql);
                ResultSet rs = ps.executeQuery();
                rs.next();
                for (int i = 0; i < rs.getMetaData().getColumnCount(); i++) {
                    if (!targetColumnMap.isEmpty()) {
                        // 指定了字段名称，则其余字段填-1
                        copyTableDbColumnMapPos.add(targetColumnMap.getOrDefault(
                                rs.getMetaData().getColumnName(i + 1).toUpperCase(), -1));
                    }
                    else
                    {
                        copyTableDbColumnMapPos.add(i);
                    }
                    copyTableDbColumnType.add(rs.getMetaData().getColumnTypeName(i+1));
                    copyTableDbColumnName.add(rs.getMetaData().getColumnName(i+1));
                }

                rs.close();
                ps.close();

                // 校验COPY语句中指定的列必须存在于目标表中，并且不能被重复指定(与PostgreSQL的行为保持一致)。
                // 如果不做校验，那么不存在的列会被静默忽略，客户端无法感知到数据导入出现了错误。
                if (columnsJson != null && !columnsJson.isEmpty())
                {
                    Set<String> tableColumnNames = new HashSet<>();
                    for (String dbColumnName : copyTableDbColumnName) {
                        tableColumnNames.add(dbColumnName.toUpperCase());
                    }

                    Set<String> parsedColumnNames = new HashSet<>();
                    String   columnErrorMessage = null;
                    for (Object o : columnsJson) {
                        String copyColumnName = o.toString().trim();
                        if (!tableColumnNames.contains(copyColumnName.toUpperCase())) {
                            // 例如: column "EVENT_TYPE" of relation "test_binary_copy7" does not exist
                            columnErrorMessage = "column \"" + copyColumnName + "\" of relation \"" +
                                    targetTableName + "\" does not exist";
                            break;
                        }
                        if (!parsedColumnNames.add(copyColumnName.toUpperCase())) {
                            // 例如: column "ID" specified more than once
                            columnErrorMessage = "column \"" + copyColumnName + "\" specified more than once";
                            break;
                        }
                    }

                    if (columnErrorMessage != null)
                    {
                        ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
                        errorResponse.setErrorResponse("SLACKER-0099", columnErrorMessage);
                        errorResponse.setErrorSeverity("ERROR");
                        errorResponse.process(ctx, request, out);

                        // 发送并刷新返回消息
                        PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

                        // 发送ReadyForQuery
                        ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
                        readyForQuery.process(ctx, request, out);

                        // 发送并刷新返回消息
                        PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);

                        return;
                    }
                }

                // 将解析信息记录到Session会话中
                this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableFormat = copyTableFormat;

                // 防御：上一个 COPY 若没有正常收尾（客户端没发 CopyDone 就直接发了别的语句），
                // 这里先丢弃它的未提交数据并回滚自己开的事务。否则遗留的 Appender 会泄漏，
                // 且下面的 BEGIN 会在已有事务中再次开启事务而报错。
                this.dbInstance.getSession(getCurrentSessionId(ctx)).discardUncommittedCopy();

                // 用服务端自己的显式事务包裹本次 COPY。
                // 原因：DuckDBAppender 在事务之外 close() 会立刻提交，只要后续某一行出错，
                // 已经 append 的行就成了"半截数据"（PG 语义下失败的 COPY 应当整体不生效）。
                // 放进显式事务后失败可整体 ROLLBACK；注意 JDBC 的 setAutoCommit(false)+rollback()
                // 对 appender 无效，必须走显式 BEGIN/COMMIT/ROLLBACK。
                // 客户端已经处于事务块中时不另开事务：数据留在客户端事务里，由客户端自行提交/回滚。
                if (!this.dbInstance.getSession(getCurrentSessionId(ctx)).inTransaction()) {
                    try (Statement beginStatement = conn.createStatement()) {
                        beginStatement.execute("BEGIN TRANSACTION");
                    }
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).copyOwnTransaction = true;
                }

                this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableAppender = conn.createAppender(targetSchemaName, targetTableName);
                this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained.reset();
                this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableDbColumnMapPos = copyTableDbColumnMapPos;
                this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableDbColumnType = copyTableDbColumnType;
                this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableDbColumnName = copyTableDbColumnName;
                // 记录COPY数据中每行的字段数量(没有指定列的时候，就是目标表的列数量)
                if (targetColumnMap.isEmpty()) {
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).copyColumnCount = copyTableDbColumnName.size();
                }
                else
                {
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).copyColumnCount = columnsJson.size();
                }

                // 发送CopyInResponse
                CopyInResponse copyInResponse = new CopyInResponse(this.dbInstance);
                copyInResponse.copyColumnCount = (short) this.dbInstance.getSession(getCurrentSessionId(ctx)).copyColumnCount;
                copyInResponse.process(ctx, request, out);

                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, CopyInResponse.class.getSimpleName(), out, this.dbInstance.logger);

                out.close();
                break tryBlock;

            }

            // 在执行之前需要做替换
            sql = SQLReplacer.replaceSQL(this.dbInstance, sql);

            // 事务块处于失败状态时，除 COMMIT/ROLLBACK/ABORT（用于结束事务块）之外的语句一律拒绝。
            // 这是 PG 的语义（SQLSTATE 25P02），也是 ReadyForQuery 上报 'E' 的配套行为：
            // 只上报状态而不拒绝后续语句，客户端仍会拿到 DuckDB 层面的晦涩错误。
            DBSession currentSession = this.dbInstance.getSession(getCurrentSessionId(ctx));
            if (currentSession.getTransactionState() == DBSession.TransactionState.FAILED
                    && !isTransactionEndStatement(sql)) {
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
                CommandComplete commandComplete = new CommandComplete(this.dbInstance);
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

            CommandComplete commandComplete = new CommandComplete(this.dbInstance);
            if (sql.toUpperCase().startsWith("BEGIN")) {
                commandComplete.setCommandResult("BEGIN");
            } else if (sql.toUpperCase().startsWith("END")) {
                commandComplete.setCommandResult("COMMIT");
            } else if (sql.toUpperCase().startsWith("SELECT")) {
                commandComplete.setCommandResult("SELECT " + nAffectedRows);
            } else if (sql.toUpperCase().startsWith("INSERT")) {
                commandComplete.setCommandResult("INSERT 0 " + nAffectedRows);
            } else if (sql.toUpperCase().startsWith("COMMIT")) {
                commandComplete.setCommandResult("COMMIT");
            } else if (sql.toUpperCase().startsWith("ROLLBACK")) {
                commandComplete.setCommandResult("ROLLBACK");
            } else if (sql.toUpperCase().startsWith("ABORT")) {
                commandComplete.setCommandResult("ROLLBACK");
            }
            else
            {
                commandComplete.setCommandResult("UPDATE " + nAffectedRows);
            }
            commandComplete.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, this.dbInstance.logger);

            // 发送ReadyForQuery
            ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
            readyForQuery.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);
        } catch (SQLException se) {
            // 事务块中的语句失败 → 事务进入 aborted 状态，ReadyForQuery 之后要回 'E'（BUG-15）。
            // 自动提交模式下（不在事务块中）标记是空操作。
            this.dbInstance.getSession(getCurrentSessionId(ctx)).markTransactionFailed();

            StackTraceElement[] stackTrace = se.getStackTrace();

            // 生成一个错误消息
            ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
            errorResponse.setErrorResponse(String.valueOf(se.getErrorCode()), se.getMessage());
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
}
