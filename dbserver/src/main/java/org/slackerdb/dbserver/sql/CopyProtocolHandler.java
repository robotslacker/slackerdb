package org.slackerdb.dbserver.sql;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.netty.channel.ChannelHandlerContext;
import org.duckdb.DuckDBConnection;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.request.CopyDataRequest;
import org.slackerdb.dbserver.message.request.CopyDoneRequest;
import org.slackerdb.dbserver.message.response.CopyInResponse;
import org.slackerdb.dbserver.message.response.ErrorResponse;
import org.slackerdb.dbserver.message.response.ReadyForQuery;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.server.DBSession;
import org.slackerdb.dbserver.sql.antlr.CopyVisitor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code COPY ... FROM STDIN} 子协议的统一入口。
 *
 * <p><b>为什么需要它</b>：COPY 的识别与建流原先只做在简单查询路径（{@code QueryRequest}）上，
 * 扩展协议（Parse/Bind/Execute）完全没有这段逻辑。于是同一条 SQL
 * （{@code COPY t FROM STDIN}）会因驱动走哪条协议而结果完全不同：
 * {@code CopyManager.copyIn()} 走简单查询（{@code QueryExecutorImpl.startCopy} 发送 {@code 'Q'}）
 * 能正常工作；而 {@code Statement.execute(...)} 在默认 {@code preferQueryMode=extended} 下走
 * Parse/Bind/Execute，语句被直接丢给 DuckDB，报
 * {@code IO Error: No files found that match the pattern "/dev/stdin"}。</p>
 *
 * <p>把这段逻辑收敛到一处后，两条路径共用同一套校验与建流代码，不会再出现"只修好一条"的情况。</p>
 *
 * <p>子协议建立之后的报文（{@code 'd'} CopyData / {@code 'c'} CopyDone）与协议路径无关，
 * 仍由 {@link CopyDataRequest} / {@link CopyDoneRequest} 处理。</p>
 */
public final class CopyProtocolHandler {

    /**
     * 单次 {@code COPY ... FROM STDIN} 允许在内存里缓冲的最大字节数（2 GiB）。
     *
     * <p><b>为什么需要它</b>：本项目<b>不做</b>流式摄取 —— CopyData 收到的字节全部堆在
     * {@link DBSession#copyLastRemained} 里，直到 CopyDone 才整体解析。没有上限时，客户端发一个
     * 超大文件的结果是 JVM 去分配一个注定失败的数组：实测抛
     * {@code OutOfMemoryError: Requested array size exceeds VM limit}，异常一路逃逸到 Netty，
     * 客户端既拿不到错误、会话也一起废掉。</p>
     *
     * <p>现在的语义是"有界 + 明确失败"：累计缓冲超过该值即判定本次 COPY 失败
     * （丢弃已缓冲数据、回滚服务端自开的 COPY 事务、回 ErrorResponse + ReadyForQuery），
     * 会话保持可用。</p>
     *
     * <p>2 GiB 同时也是 {@code ByteArrayOutputStream}（int 索引）能表达的物理上限。
     * 非 final：集成测试会临时调小它来验证"超限即失败"。</p>
     */
    public static volatile long maxPayloadBytes = 2L * 1024 * 1024 * 1024;

    private CopyProtocolHandler() {
    }

    /** {@link #beginCopyIn} 的处理结果。 */
    public enum Result {
        /** 已进入 COPY 子协议，调用方应立即结束本次响应（不要追加 CommandComplete/ReadyForQuery）。 */
        COPY_STARTED,
        /** 不是 COPY 语句（或不是受支持的形态），调用方应继续走普通执行路径。 */
        NOT_COPY,
        /** 是 COPY 但不受支持 / 校验失败：错误响应与 ReadyForQuery 已经发出，调用方应结束本次响应。 */
        REJECTED
    }

    /**
     * 判断语句是否为 {@code COPY ... FROM STDIN}（用于 Parse 阶段决定"不要交给 JDBC prepare"）。
     *
     * <p>只看方向与数据源：语法不正确、方向是 TO、或数据源是文件路径时都返回 false，
     * 由后续流程给出各自的错误。</p>
     */
    public static boolean isCopyFromStdin(String sql) {
        if (sql == null || sql.isEmpty()) {
            return false;
        }
        try {
            JSONObject parsed = CopyVisitor.parseCopyStatement(sql);
            if (parsed.getInteger("errorCode") != 0) {
                return false;
            }
            return "FROM".equals(parsed.getString("copyDirection"))
                    && "STDIN".equals(parsed.getString("copyFilePath"));
        }
        catch (Exception e) {
            // 解析失败按"不是 COPY"处理，交给原有流程报语法错误
            return false;
        }
    }

    /**
     * 尝试把一条语句作为 {@code COPY ... FROM STDIN} 建立子协议。
     *
     * <p>成功时返回 {@link Result#COPY_STARTED} 并已把 {@code CopyInResponse} 写出；
     * 调用方必须<b>不再</b>发送 CommandComplete / ReadyForQuery —— 后续由 CopyDone/CopyFail 收尾。</p>
     */
    public static Result beginCopyIn(PostgresRequest request, ChannelHandlerContext ctx, String sql)
            throws IOException {

        DBInstance dbInstance = request.getDbInstance();
        DBSession session = dbInstance.getSession(request.getCurrentSessionId(ctx));
        if (session == null) {
            return Result.NOT_COPY;
        }

        JSONObject parseObject = CopyVisitor.parseCopyStatement(sql);
        if (parseObject.getInteger("errorCode") != 0) {
            // 语法上不是 COPY：交给调用方走普通执行路径
            return Result.NOT_COPY;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 只支持 COPY <table> FROM STDIN
        if (!"FROM".equals(parseObject.getString("copyDirection"))
                || !"STDIN".equals(parseObject.getString("copyFilePath"))
                || !"table".equals(parseObject.getString("copyType"))) {
            sendErrorAndReady(request, ctx, out, "0A000",
                    "Feature not supported. Only support COPY .. FROM STDIN");
            return Result.REJECTED;
        }

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

        Map<String, Integer> targetColumnMap = new HashMap<>();
        JSONArray columnsJson = parseObject.getJSONArray("columns");
        if (columnsJson != null) {
            for (int i = 0; i < columnsJson.size(); i++) {
                targetColumnMap.put(columnsJson.get(i).toString().trim().toUpperCase(), i);
            }
        }

        String copyTableFormat;
        JSONObject copyOptions = parseObject.getJSONObject("options");
        // 选项 → 实际解析参数（默认格式 text、单字符校验、组合校验都在 CopyDialect 里）。
        // 改造前这里只比 FORMAT 一个键，导致 `FORMAT 'csv'`（带引号）、
        // 不写 FORMAT（PG 默认 text）、以及 HEADER/DELIMITER/QUOTE/NULL 全部走不通或被忽略。
        CopyDialect copyDialect;
        try {
            copyDialect = CopyDialect.fromOptions(copyOptions);
        }
        catch (CopyDialect.InvalidOptionException e) {
            sendErrorAndReady(request, ctx, out, "22023", e.getMessage());
            return Result.REJECTED;
        }
        copyTableFormat = copyDialect.format;
        if (!copyDialect.getIgnoredOptions().isEmpty()) {
            dbInstance.logger.warn("[SERVER][COPY       ] COPY options ignored (not implemented): {}",
                    copyDialect.getIgnoredOptions());
        }

        if (!(session.dbConnection instanceof DuckDBConnection conn)) {
            sendErrorAndReady(request, ctx, out, "0A000",
                    "COPY is not available on the current connection");
            return Result.REJECTED;
        }

        // DuckDB 不支持只插入部分列的 Appender，所以要把表中不存在的列映射为 -1
        List<Integer> copyTableDbColumnMapPos = new ArrayList<>();
        List<String> copyTableDbColumnType = new ArrayList<>();
        List<String> copyTableDbColumnName = new ArrayList<>();

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM " + (targetSchemaName.isEmpty() ? "" : targetSchemaName + ".") + targetTableName + " LIMIT 0");
             ResultSet rs = ps.executeQuery()) {

            int columnCount = rs.getMetaData().getColumnCount();
            for (int i = 0; i < columnCount; i++) {
                if (!targetColumnMap.isEmpty()) {
                    copyTableDbColumnMapPos.add(targetColumnMap.getOrDefault(
                            rs.getMetaData().getColumnName(i + 1).toUpperCase(), -1));
                } else {
                    copyTableDbColumnMapPos.add(i);
                }
                copyTableDbColumnType.add(rs.getMetaData().getColumnTypeName(i + 1));
                copyTableDbColumnName.add(rs.getMetaData().getColumnName(i + 1));
            }
        } catch (Exception e) {
            // 目标表读不到列信息：表/库不存在 → 42P01（undefined_table）
            sendErrorAndReady(request, ctx, out, "42P01",
                    "COPY target table [" + targetTableName + "] is not available: " + e.getMessage());
            return Result.REJECTED;
        }

        // 校验列清单：不存在的列或重复指定都必须报错（与 PostgreSQL 行为一致），
        // 否则会被静默忽略，客户端无法感知导入出现了错误。
        if (columnsJson != null && !columnsJson.isEmpty()) {
            Set<String> tableColumnNames = new HashSet<>();
            for (String dbColumnName : copyTableDbColumnName) {
                tableColumnNames.add(dbColumnName.toUpperCase());
            }

            Set<String> parsedColumnNames = new HashSet<>();
            String columnErrorMessage = null;
            String columnErrorState = null;
            for (Object o : columnsJson) {
                String copyColumnName = o.toString().trim();
                if (!tableColumnNames.contains(copyColumnName.toUpperCase())) {
                    columnErrorMessage = "column \"" + copyColumnName + "\" of relation \""
                            + targetTableName + "\" does not exist";
                    columnErrorState = "42703";   // undefined_column
                    break;
                }
                if (!parsedColumnNames.add(copyColumnName.toUpperCase())) {
                    columnErrorMessage = "column \"" + copyColumnName + "\" specified more than once";
                    columnErrorState = "42701";   // duplicate_column
                    break;
                }
            }

            if (columnErrorMessage != null) {
                sendErrorAndReady(request, ctx, out, columnErrorState, columnErrorMessage);
                return Result.REJECTED;
            }
        }

        // ============ 列类型检查：块 Appender 建不起来的类型提前明确拒绝 ============
        // DuckDBAppender 是按"表的物理列类型"校验的：BIT/INTERVAL/TIME_NS/BIGNUM/VARIANT
        // 这几类列只要出现在目标表里，连 createAppender() 都会失败（unsupported C API type），
        // 所以必须按整表检查 —— 哪怕本次 COPY 根本不写那一列。
        for (int i = 0; i < copyTableDbColumnType.size(); i++) {
            String columnType = copyTableDbColumnType.get(i);
            if (CopyColumnTypes.isAppenderUnsupported(columnType)) {
                sendErrorAndReady(request, ctx, out, "0A000",
                        CopyColumnTypes.unsupportedMessage(copyTableDbColumnName.get(i), columnType));
                return Result.REJECTED;
            }
        }

        session.copyTableFormat = copyTableFormat;
        session.copyDialect = copyDialect;

        // 防御：上一个 COPY 若没有正常收尾（客户端没发 CopyDone 就直接发了别的语句），
        // 先丢弃它的未提交数据并回滚自己开的事务，否则残留的 Appender 会泄漏，
        // 且下面的 BEGIN 会在已有事务中再次开启事务而报错。
        session.discardUncommittedCopy();
        // 上一条 COPY 若因超限被判失败，残留的 copyAborted 会让本次 COPY 的数据被整段忽略，
        // 因此这里必须复位（beginCopyIn 就是"新的 COPY 开始"这个同步点）。
        session.copyAborted = false;

        try {
            // 用服务端自己的显式事务包裹本次 COPY：DuckDBAppender 在事务之外 close() 会立刻提交，
            // 只要后续某一行出错就会留下"半截数据"（PG 语义下失败的 COPY 必须整体不生效）。
            // 客户端已经处于事务块中时不另开事务，数据留在客户端事务里由客户端决定。
            if (!session.inTransaction()) {
                try (Statement beginStatement = conn.createStatement()) {
                    beginStatement.execute("BEGIN TRANSACTION");
                }
                session.copyOwnTransaction = true;
            }

            // 唯一的写入通道：块 Appender（字段文本由 CopyValueWriters 解析成精确的 Java 类型）
            session.copyTableAppender = conn.createAppender(targetSchemaName, targetTableName);
        } catch (Exception e) {
            sendErrorAndReady(request, ctx, out, "XX000",
                    "Unable to start COPY into [" + targetTableName + "]: " + e.getMessage());
            return Result.REJECTED;
        }

        session.copyLastRemained.reset();
        session.copyTableDbColumnMapPos = copyTableDbColumnMapPos;
        session.copyTableDbColumnType = copyTableDbColumnType;
        session.copyTableDbColumnName = copyTableDbColumnName;
        // 记录 COPY 数据中每行的字段数量（没有指定列时就是目标表的列数量）
        session.copyColumnCount = targetColumnMap.isEmpty()
                ? copyTableDbColumnName.size()
                : columnsJson.size();

        CopyInResponse copyInResponse = new CopyInResponse(dbInstance);
        copyInResponse.copyColumnCount = (short) session.copyColumnCount;
        copyInResponse.process(ctx, null, out);
        PostgresMessage.writeAndFlush(ctx, CopyInResponse.class.getSimpleName(), out, dbInstance.logger);

        dbInstance.logger.debug("[SERVER][COPY       ] COPY IN started: table=[{}] format=[{}] columns={} dialect=[{}]",
                targetTableName, copyTableFormat, session.copyColumnCount, copyDialect.describe());

        // 审计：COPY 也是一条语句，必须进历史表。
        // 这里用语句原文登记（扩展协议路径此时 executingSQL 还是上一条语句，不能复用），
        // 记录 ID 挂在会话上，由 CopyDone / CopyFail / 会话清理收尾。
        session.executingSQL = sql;
        session.executingSqlId.incrementAndGet();
        session.copySqlHistoryId = request.insertSqlHistory(ctx);

        return Result.COPY_STARTED;
    }

    /** 发错误 + ReadyForQuery（COPY 尚未进入子协议，需要自己收尾这一回合）。 */
    private static void sendErrorAndReady(PostgresRequest request, ChannelHandlerContext ctx,
                                          ByteArrayOutputStream out, String code, String message)
            throws IOException {
        DBInstance dbInstance = request.getDbInstance();

        ErrorResponse errorResponse = new ErrorResponse(dbInstance);
        errorResponse.setErrorResponse(code, message);
        errorResponse.setErrorSeverity("ERROR");
        errorResponse.process(ctx, null, out);
        PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, dbInstance.logger);

        ReadyForQuery readyForQuery = new ReadyForQuery(dbInstance);
        readyForQuery.process(ctx, null, out);
        PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, dbInstance.logger);

        out.close();
    }

    /**
     * COPY IN 子协议<b>中途</b>判定失败（例如缓冲超过 {@link #maxPayloadBytes}）。
     *
     * <p>收尾动作与 PG 的"报错后丢弃到同步点"一致：</p>
     * <ol>
     *   <li>{@link DBSession#discardUncommittedCopy()}：丢弃已写入的部分，失败的 COPY 必须整体不生效；</li>
     *   <li>{@link DBSession#resetCopyState()} 并把 {@link DBSession#copyAborted} 置为 true
     *       —— 此后到达的 CopyData 一律忽略，CopyDone 也不再回 CommandComplete
     *       （否则客户端会看到 {@code COPY 0} 的"成功"）；</li>
     *   <li>回 {@code ErrorResponse} + {@code ReadyForQuery}：立刻告诉客户端失败，并解锁驱动
     *       握着的 copy 锁（{@code QueryExecutorImpl.cancelCopy} 的循环就是靠这个 Z 退出的）。</li>
     * </ol>
     *
     * @return 本次调用的错误消息，便于调用方记日志
     */
    public static String abortCopyIn(DBSession session, DBInstance dbInstance, ChannelHandlerContext ctx,
                                     String errorCode, String message) throws IOException {
        if (session != null) {
            session.discardUncommittedCopy();
            session.resetCopyState();
            session.copyAborted = true;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ErrorResponse errorResponse = new ErrorResponse(dbInstance);
        errorResponse.setErrorResponse(errorCode, message);
        errorResponse.setErrorSeverity("ERROR");
        errorResponse.process(ctx, null, out);
        PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, dbInstance.logger);

        ReadyForQuery readyForQuery = new ReadyForQuery(dbInstance);
        readyForQuery.process(ctx, null, out);
        PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, dbInstance.logger);

        out.close();
        return message;
    }
}
