package org.slackerdb.dbserver.message.request;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.netty.channel.ChannelHandlerContext;
import org.duckdb.DuckDBConnection;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.response.*;
import org.slackerdb.dbserver.sql.CommandTag;
import org.slackerdb.dbserver.sql.CursorHandler;
import org.slackerdb.dbserver.sql.CursorSql;
import org.slackerdb.dbserver.sql.DeallocateSql;
import org.slackerdb.dbserver.sql.SessionSettingResponder;
import org.slackerdb.dbserver.sql.SessionSettingSql;
import org.slackerdb.dbserver.sql.TransactionHandler;
import org.slackerdb.dbserver.sql.TransactionSql;
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

    /**
     * 判断本消息是否为"多条语句的批次"。
     *
     * <p>PG 的简单查询消息允许携带多条以 {@code ;} 分隔的语句，服务端要逐条执行、逐条回
     * CommandComplete。下面的游标分支与事务分支都是<b>单语句</b>拦截器：它们只看消息开头就
     * 认定"这条语句是什么"，然后执行并收尾整条消息。对批次而言这是错的 ——
     * {@code begin; select 1; commit;} 会因为开头是 {@code BEGIN} 而被当成"一条事务控制语句"，
     * 执行 BEGIN 后就把消息收尾掉，<b>后面的语句被静默丢弃</b>（不报错、不提示，
     * 客户端只看到一个 BEGIN 标签，数据静默丢失）。</p>
     *
     * <p>因此批次必须绕开这两个单语句拦截器，交给下面的脚本路径逐条执行
     * （{@code plsqlScript.isScript()} 分支）。注意 {@code psql -f script.sql} 不受影响：
     * psql 读文件时是在客户端按分号逐条发送的，每条都是单语句消息。</p>
     */
    private boolean isMultiStatementBatch(String pSql) {
        if (pSql == null) {
            return false;
        }
        return SQLReplacer.splitSQLWithSemicolon(pSql).size() > 1;
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

            // COPY ... TO STDOUT：简单查询路径的导出方向。
            // 与 COPY IN 不同，这一步会**同步推完**全部数据（CopyOutResponse → CopyData×N →
            // CopyDone → CommandComplete），所以返回 COPY_STARTED 时本回合只剩 ReadyForQuery。
            CopyProtocolHandler.Result copyOutResult = CopyProtocolHandler.beginCopyOut(this, ctx, out, sql);
            if (copyOutResult == CopyProtocolHandler.Result.COPY_STARTED
                    || copyOutResult == CopyProtocolHandler.Result.REJECTED) {
                break tryBlock;
            }

            // 命令标签必须基于**客户端原文**判断，而不是 SQLReplacer 改写后的 SQL。
            // 原因：SET/SHOW 这类语句会被 SQLReplacer 改写（SET 被改写成空串、SHOW 被改写成
            // 等价的 SELECT），改写后就再也认不出原始命令了，标签会退化成 "SELECT 0"。
            String originalSql = sql;

            // 审计与可观测性一律记录**客户端原文**：START TRANSACTION / DEALLOCATE / SET 这类语句会被
            // SQLReplacer 改写成空串（或改成别的查询），若记改写后的文本，SQL 历史里就会出现空行，
            // 排查问题（尤其排查 postgres_fdw 发了什么）时看不出客户端到底发了什么。
            this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL = originalSql;

            // B1：PG 游标族（DECLARE ... CURSOR FOR / FETCH / MOVE / CLOSE）。
            // 必须放在 PL/SQL 检测**之前**：PlSqlDetector 会把任何以 DECLARE 开头的语句当成
            // PL/SQL 块，于是 `DECLARE c CURSOR FOR ...` 在语法分析阶段就报 42601，
            // 根本到不了执行层 —— 而 postgres_fdw 取远端数据走的正是这条语句。
            // 多语句批次不走这里（见 isMultiStatementBatch）：否则
            // `declare c cursor ...; fetch all from c;` 会在 DECLARE 之后截断。
            if (!isMultiStatementBatch(originalSql) && CursorSql.isCursorStatement(originalSql)) {
                DBSession cursorSession = this.dbInstance.getSession(getCurrentSessionId(ctx));
                if (cursorSession.getTransactionState() == DBSession.TransactionState.FAILED) {
                    // 与后面"失败事务"分支同语义：事务块失败后除 COMMIT/ROLLBACK 外一律 25P02
                    sqlHistoryId = insertSqlHistory(ctx);
                    updateSqlHistory(sqlHistoryId, 0, 0,
                            "25P02: current transaction is aborted, commands ignored until end of transaction block");

                    ErrorResponse cursorFailedTx = new ErrorResponse(this.dbInstance);
                    cursorFailedTx.setErrorResponse("25P02",
                            "current transaction is aborted, commands ignored until end of transaction block");
                    cursorFailedTx.process(ctx, request, out);
                    PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out,
                            this.dbInstance.logger);

                    ReadyForQuery cursorFailedReady = new ReadyForQuery(this.dbInstance);
                    cursorFailedReady.process(ctx, request, out);
                    PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out,
                            this.dbInstance.logger);

                    out.close();
                    break tryBlock;
                }

                sqlHistoryId = insertSqlHistory(ctx);
                CursorHandler.CursorResult cursorResult = CursorHandler.handle(
                        this.dbInstance, ctx, request, out, cursorSession, originalSql, RowEncoder.ALL_TEXT, true);
                if (cursorResult != null) {
                    updateSqlHistory(sqlHistoryId, cursorResult.isError() ? -1 : 0, cursorResult.rows(),
                            cursorResult.isError()
                                    ? cursorResult.sqlState() + ": " + cursorResult.errorMessage()
                                    : null);
                    if (cursorResult.isError()) {
                        // 游标语句失败同样让事务块进入 aborted（PG 语义）
                        cursorSession.markTransactionFailed();

                        ErrorResponse cursorError = new ErrorResponse(this.dbInstance);
                        cursorError.setErrorFile("QueryRequest");
                        cursorError.setErrorResponse(cursorResult.sqlState(), cursorResult.errorMessage());
                        cursorError.process(ctx, request, out);
                        PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out,
                                this.dbInstance.logger);
                    }

                    ReadyForQuery cursorReady = new ReadyForQuery(this.dbInstance);
                    cursorReady.process(ctx, request, out);
                    PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out,
                            this.dbInstance.logger);

                    out.close();
                    break tryBlock;
                }
            }

            // P2：事务控制语句（START TRANSACTION / BEGIN / COMMIT / ROLLBACK / ABORT / END / SET TRANSACTION）。
            // 必须在 PL/SQL 检测之前：`BEGIN` 既可能是事务开始、也可能是 PL/SQL 块的开头，
            // 这里按 PG 的事务语法先判定（有歧义时优先当事务）。
            // 也必须在"失败事务"分支之前：失败事务里的 COMMIT/ROLLBACK 必须照常结束事务块。
            //
            // 但**多语句批次**不能走这里：`begin; select 1; commit;` 的开头也是 BEGIN，
            // 直接当"一条事务控制语句"处理会把后面的语句静默丢弃（见 isMultiStatementBatch）。
            if (!isMultiStatementBatch(originalSql) && TransactionSql.parse(originalSql) != null) {
                DBSession transactionSession = this.dbInstance.getSession(getCurrentSessionId(ctx));
                sqlHistoryId = insertSqlHistory(ctx);
                TransactionHandler.TransactionResult transactionResult = TransactionHandler.handle(
                        this.dbInstance, ctx, request, out, transactionSession, originalSql, true);
                if (transactionResult != null) {
                    updateSqlHistory(sqlHistoryId, transactionResult.isError() ? -1 : 0, 0,
                            transactionResult.isError()
                                    ? transactionResult.sqlState() + ": " + transactionResult.errorMessage()
                                    : null);
                    if (transactionResult.isError()) {
                        transactionSession.markTransactionFailed();
                    }
                    out.close();
                    break tryBlock;
                }
            }

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

            // 客户端（pgjdbc 系驱动，含本项目 dbdriver）常用 SET application_name 上报来源，
            // 而该语句会被 SQLReplacer 丢弃 —— 必须在替换前把值记到会话上，供 /status 辨识来源。
            String requestedApplicationName = SQLReplacer.extractApplicationName(sql);
            if (requestedApplicationName != null) {
                this.dbInstance.getSession(getCurrentSessionId(ctx)).applicationName = requestedApplicationName;
            }

            // 在执行之前需要做替换
            sql = SQLReplacer.replaceSQL(this.dbInstance, sql);
            // catalog introspection 的未限定名（pg_namespace/pg_attribute）需要额外指到
            // fake catalog；SQLReplacer 的 REGEX 规则是整串匹配，做不到语句中间的裸表名替换。
            sql = SQLReplacer.rewriteCatalogIntrospection(this.dbInstance, sql);

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

            // T2：PG 特有的会话设置/复位语句（SET <PG 独有参数> / RESET / RESET ALL / DISCARD ALL）。
            // DuckDB 对它们要么没有参数（3F000）、要么整条语法都不认（0A000 / 42601），
            // 而连接池 / ORM / IDE 建连时会例行发送。这里接管：记进会话表 + 回成功 + NOTICE 说明不生效。
            // 注意用客户端原文判断：部分 SET 已被 SQLReplacer 改写成空串。
            SessionSettingSql.Takeover settingTakeover = SessionSettingSql.apply(
                    this.dbInstance.getSession(getCurrentSessionId(ctx)), originalSql);
            if (settingTakeover != null) {
                sqlHistoryId = insertSqlHistory(ctx);
                updateSqlHistory(sqlHistoryId, 0, 0, settingTakeover.isError()
                        ? settingTakeover.errorSqlState() + ": " + settingTakeover.errorMessage()
                        : null);
                SessionSettingResponder.respond(this.dbInstance, ctx, request, out, settingTakeover, true);
                out.close();
                break tryBlock;
            }

            // T2：SHOW <本服务端能回答的参数>。DuckDB 不认识这些 PG 参数名，
            // 会把参数名当表名 → 42P01；这里合成一行返回（列名 = 参数名）。
            String showTarget = SessionSettingSql.showTarget(originalSql);
            if (showTarget != null) {
                sqlHistoryId = insertSqlHistory(ctx);
                updateSqlHistory(sqlHistoryId, 0, 0, null);
                SessionSettingResponder.respondShow(this.dbInstance, ctx, request, out, showTarget,
                        SessionSettingSql.showValue(this.dbInstance.getSession(getCurrentSessionId(ctx)),
                                showTarget),
                        true, true);
                out.close();
                break tryBlock;
            }

            // DEALLOCATE ALL：DuckDB 不支持这条语句（42601 "DEALLOCATE requires a name"），
            // 而 postgres_fdw 在出错清理路径会发它。由服务端接管：
            // 清空协议级语句/门户缓存，并回命令标签 "DEALLOCATE ALL"。
            if (DeallocateSql.isDeallocateAll(sql) || DeallocateSql.isDeallocateAll(originalSql)) {
                this.dbInstance.getSession(getCurrentSessionId(ctx)).clearProtocolLevelStatements();

                sqlHistoryId = insertSqlHistory(ctx);
                updateSqlHistory(sqlHistoryId, 0, 0, null);

                CommandComplete deallocateComplete = new CommandComplete(this.dbInstance);
                deallocateComplete.setCommandResult("DEALLOCATE ALL");
                deallocateComplete.process(ctx, request, out);
                PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, this.dbInstance.logger);

                ReadyForQuery deallocateReady = new ReadyForQuery(this.dbInstance);
                deallocateReady.process(ctx, request, out);
                PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);

                out.close();
                break tryBlock;
            }

            // DEALLOCATE <name>：DuckDB 会静默成功，但只作用于它自己的 SQL 级命名空间，
            // 动不了我们协议级缓存里的同名语句（postgres_fdw 重建预备语句前会发它）。
            // 这里顺手释放协议级缓存里的同名条目；语句本身仍照常交给 DuckDB 执行。
            String deallocateTarget = DeallocateSql.deallocateName(sql);
            if (deallocateTarget != null) {
                this.dbInstance.getSession(getCurrentSessionId(ctx)).releaseProtocolStatement(deallocateTarget);
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

                    // 列级元数据只解析一次；simple query 路径一律文本返回
                    RowEncoder encoder = new RowEncoder(rs.getMetaData(), RowEncoder.ALL_TEXT, this.dbInstance.logger);

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
        } catch (RuntimeException re) {
            // 未预期的运行时异常（例如行编码阶段 DuckDB JDBC 抛出的 NumberFormatException）。
            //
            // 这一类异常**不是** SQLException，原先没有任何分支接住它：它会一路穿出 process()
            // 到 Netty 的 channelRead，被那里"只记日志"的 catch 吞掉。此时服务端往往已经发出
            // 了 RowDescription，却永远不会再发 DataRow / ErrorResponse / ReadyForQuery ——
            // 客户端（psql / psycopg2 / pgjdbc…）就会一直等一条永不到来的报文，表现为"查询永久挂起"，
            // 且连接作废、statement_timeout 也救不回来。这正是 DECIMAL[] 挂起的放大器。
            //
            // 这里与上面的 catch (SQLException) 保持同一收尾语义：回 ErrorResponse + ReadyForQuery，
            // 把"永久挂起"降级为"客户端收到一个明确的错误"。
            this.dbInstance.getSession(getCurrentSessionId(ctx)).markTransactionFailed();

            String message = re.getMessage() == null ? re.toString() : re.getMessage();
            if (sqlHistoryId != -1) {
                updateSqlHistory(sqlHistoryId, -1, 0, "XX000:" + message);
            }

            this.dbInstance.logger.error("[SERVER][PG PROTOCOL]: Unexpected error while encoding/executing "
                    + "a simple query, answering XX000 instead of dropping the request.", re);

            ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
            errorResponse.setErrorFile("QueryRequest");
            errorResponse.setErrorResponse("XX000", message);
            errorResponse.setErrorSeverity("ERROR");
            errorResponse.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

            // 发送ReadyForQuery
            ReadyForQuery runtimeReady = new ReadyForQuery(this.dbInstance);
            runtimeReady.process(ctx, request, out);

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

            // 事务控制语句不能走 DuckDB 的 PreparedStatement：DuckDB 不认 PG 的
            // BEGIN ... ISOLATION LEVEL ...，事务状态也必须由会话显式驱动。
            // 多语句批次里同样要处理（典型：DuckDB postgres 扩展加载 catalog 时发送
            // BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ; SELECT pg_...; ...），
            // 否则整个批次会因这一条语法错误而失败。
            TransactionHandler.TransactionResult transactionResult = TransactionHandler.handle(
                    this.dbInstance, ctx, request, out, session, statement.sql(), false);
            if (transactionResult != null) {
                session.executingSQL = statement.sql();
                // handle 已经写好了 CommandComplete 或 ErrorResponse，直接继续下一条。
                continue;
            }

            String statementSql = SQLReplacer.replaceSQL(this.dbInstance, statement.sql());
            // catalog introspection 的未限定名（pg_namespace/pg_attribute）需要额外指到
            // fake catalog；SQLReplacer 的 REGEX 规则是整串匹配，做不到语句中间的裸表名替换。
            statementSql = SQLReplacer.rewriteCatalogIntrospection(this.dbInstance, statementSql);
            if (statementSql.isEmpty()) {
                CommandComplete emptyComplete = new CommandComplete(this.dbInstance);
                emptyComplete.setCommandResult(CommandTag.of(statement.sql(), 0, false));
                emptyComplete.process(ctx, request, out);
                PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, this.dbInstance.logger);
                continue;
            }
            session.executingSQL = statementSql;

            // 失败事务块语义（与单语句路径的 25P02 分支一致）：事务块中一旦有语句报错，
            // 后续语句一律以 25P02 拒绝，直到遇到 COMMIT/ROLLBACK 把事务块结束。
            // **必须继续走完整个脚本**，不能像以前那样让异常冲破循环 —— 否则脚本里的
            // BEGIN 会一直挂着（事务状态没被收尾），且后面的 COMMIT/ROLLBACK 永远执行不到，
            // 事务块泄漏给下一个消息。
            if (session.getTransactionState() == DBSession.TransactionState.FAILED
                    && !isTransactionEndStatement(statementSql)) {
                ErrorResponse abortedTx = new ErrorResponse(this.dbInstance);
                abortedTx.setErrorFile("QueryRequest");
                abortedTx.setErrorResponse("25P02",
                        "current transaction is aborted, commands ignored until end of transaction block");
                abortedTx.setErrorSeverity("ERROR");
                abortedTx.process(ctx, request, out);
                PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);
                continue;
            }

            long affectedRows = -1;
            boolean returnedRows = false;
            boolean statementFailed = false;
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
                        RowEncoder encoder = new RowEncoder(resultSet.getMetaData(), RowEncoder.ALL_TEXT, this.dbInstance.logger);

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
            catch (Exception statementError) {
                // 单条语句失败：如实回错误响应、把事务块标记为失败，然后**继续执行脚本剩余语句**。
                // 这与单语句路径的 catch(SQLException) 分支同语义（那里也是 markTransactionFailed
                // + 报错 + ReadyForQuery），也是 psql -f 逐条发送时的既有行为（psql 默认
                // 出错后继续）。不这样做就会出现"错误把 COMMIT 一起吞掉、事务块泄漏"的坏状态。
                final String affectedSql = statementSql;
                statementFailed = true;
                session.markTransactionFailed();

                ErrorResponse statementErrorResponse = new ErrorResponse(this.dbInstance);
                statementErrorResponse.setErrorFile("QueryRequest");
                if (statementError instanceof SQLException sqlError) {
                    statementErrorResponse.setErrorResponse(
                            SqlStateMapper.fromException(sqlError), sqlError.getMessage());
                } else if (statementError instanceof PlSqlException plsqlError) {
                    statementErrorResponse.setErrorResponse(
                            plsqlError.getSqlState(), plsqlError.getMessage() + plsqlError.positionSuffix());
                } else {
                    statementErrorResponse.setErrorResponse("XX000", statementError.getMessage());
                }
                statementErrorResponse.setErrorSeverity("ERROR");
                statementErrorResponse.process(ctx, request, out);
                PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);
                this.dbInstance.logger.debug(
                        "[SERVER] 脚本中的语句执行失败，继续执行剩余语句: [{}]", affectedSql);
            }

            // 事务状态机：与单语句路径保持一致的判定
            String upperSql = statementSql.toUpperCase();
            if (upperSql.startsWith("BEGIN") || upperSql.startsWith("START TRANSACTION")) {
                session.beginTransaction();
            } else if (upperSql.startsWith("END") || upperSql.startsWith("COMMIT")
                    || upperSql.startsWith("ROLLBACK") || upperSql.startsWith("ABORT")) {
                session.endTransaction();
            }

            if (!statementFailed) {
                CommandComplete commandComplete = new CommandComplete(this.dbInstance);
                commandComplete.setCommandResult(CommandTag.of(statement.sql(), affectedRows, returnedRows));
                commandComplete.process(ctx, request, out);
                PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, this.dbInstance.logger);
            }
        }
        return totalRows;
    }
}
