package org.slackerdb.dbserver.sql;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.netty.channel.ChannelHandlerContext;
import org.duckdb.DuckDBConnection;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.request.CopyDataRequest;
import org.slackerdb.dbserver.message.request.CopyDoneRequest;
import org.slackerdb.dbserver.message.response.CommandComplete;
import org.slackerdb.dbserver.message.response.CopyDone;
import org.slackerdb.dbserver.message.response.CopyInResponse;
import org.slackerdb.dbserver.message.response.CopyOutResponse;
import org.slackerdb.dbserver.message.response.ErrorResponse;
import org.slackerdb.dbserver.message.response.ReadyForQuery;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.server.DBSession;
import org.slackerdb.dbserver.server.PooledConnection;
import org.slackerdb.dbserver.sql.antlr.CopyVisitor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
            return "FROM".equalsIgnoreCase(parsed.getString("copyDirection"))
                    && "STDIN".equalsIgnoreCase(parsed.getString("copyFilePath"));
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

        // COPY ... TO STDOUT 是**导出**方向，不属于本方法：直接让位给 beginCopyOut。
        // 没有这一句的话，下面的方向校验会把它当成"不受支持的 COPY"回 0A000，
        // 调用方随即结束本回合，beginCopyOut 根本轮不到执行。
        if (isCopyToStdout(sql)) {
            return Result.NOT_COPY;
        }

        // 只支持 COPY <table> FROM STDIN。
        //
        // 两个例外：
        //  1) 文件路径形态（`COPY t TO '/f.csv'` / `FROM '/f.csv'`）直接让位。
        //     这类"服务端读写文件"交给 DuckDB 自己执行 —— 它的导出（parquet/分区/压缩）比 PG 的 COPY 强，
        //     我们没理由拦，也不该引入"客户端可写服务端任意路径"的安全面。
        //     注意：历史上这里被 TO 方向的校验一并拦掉，所以文件导出一直是 0A000。
        //  2) 其余情况（例如 `COPY t TO STDOUT` 已由上面提前让位；走到这里的 TO STDOUT 一定是
        //     FROM/TO 之外的方向）按不支持处理。
        String copyFilePathText = parseObject.getString("copyFilePath");
        if (copyFilePathText != null
                && !"STDIN".equalsIgnoreCase(copyFilePathText)
                && !"STDOUT".equalsIgnoreCase(copyFilePathText)) {
            return Result.NOT_COPY;
        }

        // 比较一律用 equalsIgnoreCase：PG 的关键字不分大小写，`copy t from stdin` 必须与
        // `COPY t FROM STDIN` 等价（否则用户拿到的是"Only support COPY .. FROM STDIN"这种
        // 自己打自己脸的报错）。
        if (!"FROM".equalsIgnoreCase(parseObject.getString("copyDirection"))
                || !"STDIN".equalsIgnoreCase(copyFilePathText)
                || !"table".equalsIgnoreCase(parseObject.getString("copyType"))) {
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

        if (!(session.dbConnection instanceof DuckDBConnection)) {
            // 会话连接由连接池发放，是对外句柄（PooledConnection），不是 DuckDBConnection。
            // 先经 unwrap 取回底层物理连接再做类型判定，否则 COPY 会被误判成"当前连接不可用"。
            if (!(PooledConnection.unwrapOrSame(session.dbConnection) instanceof DuckDBConnection)) {
                sendErrorAndReady(request, ctx, out, "0A000",
                        "COPY is not available on the current connection");
                return Result.REJECTED;
            }
        }
        DuckDBConnection conn = (DuckDBConnection) PooledConnection.unwrapOrSame(session.dbConnection);

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

    /**
     * 判断语句是否为 {@code COPY ... TO STDOUT}（用于 Parse 阶段决定"不要交给 JDBC prepare"）。
     *
     * <p><b>只认 STDOUT</b>：{@code COPY t TO '/path/f.csv'} 这类"服务端写文件"的导出
     * 一律返回 false，交给 DuckDB 自己执行 —— DuckDB 原生导出（含 parquet/分区/压缩）比
     * PG 的 COPY 强，我们没有理由拦住它，也不该引入"客户端可写服务端任意路径"的安全面。</p>
     *
     * <p>{@code COPY (query) TO STDOUT} 同样返回 true：语法里 {@code copyType} 是 {@code query}，
     * 历史上完全没被识别，语句被直接丢给 DuckDB 并报
     * {@code IO Error: Cannot open file "/dev/stdout"}。</p>
     */
    public static boolean isCopyToStdout(String sql) {
        if (sql == null || sql.isEmpty()) {
            return false;
        }
        try {
            JSONObject parsed = CopyVisitor.parseCopyStatement(sql);
            if (parsed.getInteger("errorCode") != 0) {
                return false;
            }
            return "TO".equalsIgnoreCase(parsed.getString("copyDirection"))
                    && "STDOUT".equalsIgnoreCase(parsed.getString("copyFilePath"));
        }
        catch (Exception e) {
            // 解析失败按"不是 COPY TO STDOUT"处理，交给原有流程报语法错误
            return false;
        }
    }

    /**
     * 执行一条 {@code COPY ... TO STDOUT} 并**同步**把全部数据推给客户端。
     *
     * <p>与 COPY IN 不同，COPY OUT 是服务端单向推流（客户端在这一阶段只接收），
     * 因此不需要子协议状态机：本方法从 CopyOutResponse 一直写到 CommandComplete 就结束了，
     * 之后由调用方补 ReadyForQuery。</p>
     *
     * <p>报文序列：{@code CopyOutResponse('H')} → {@code CopyData('d')} × N →
     * {@code CopyDone('c')} → {@code CommandComplete("COPY n")}。</p>
     *
     * <p><b>内存</b>：逐行编码、累计到 {@link PostgresMessage#FLUSH_THRESHOLD_BYTES} 就 flush，
     * 绝不整表缓冲 —— 大表导出不能靠内存兜。</p>
     *
     * @return {@link Result#COPY_STARTED}：本回合已完整结束（含 CommandComplete），
     *         调用方<b>不要</b>再追加 CommandComplete；{@link Result#REJECTED}：错误与
     *         ReadyForQuery 已发出；{@link Result#NOT_COPY}：不是 COPY TO STDOUT。
     */
    public static Result beginCopyOut(PostgresRequest request, ChannelHandlerContext ctx, ByteArrayOutputStream out,
                                      String sql) throws IOException {

        if (!isCopyToStdout(sql)) {
            return Result.NOT_COPY;
        }

        DBInstance dbInstance = request.getDbInstance();
        DBSession session = dbInstance.getSession(request.getCurrentSessionId(ctx));
        if (session == null) {
            return Result.NOT_COPY;
        }

        JSONObject parseObject = CopyVisitor.parseCopyStatement(sql);
        String copyType = parseObject.getString("copyType");

        // 选项解析与 COPY IN 共用同一份实现，两个方向的语义不会分叉
        CopyDialect copyDialect;
        try {
            copyDialect = CopyDialect.fromOptions(parseObject.getJSONObject("options"));
        }
        catch (CopyDialect.InvalidOptionException e) {
            sendErrorAndReady(request, ctx, out, "22023", e.getMessage());
            return Result.REJECTED;
        }

        if (copyDialect.binary) {
            // 二进制 COPY OUT 尚未实现（需要按 DuckDB 实际类型逐列编码）。
            // 显式报错，而不是发一段客户端解不开的字节。
            sendErrorAndReady(request, ctx, out, "0A000",
                    "COPY ... TO STDOUT with FORMAT binary is not supported yet; use text or csv");
            return Result.REJECTED;
        }

        // 组出等价的结果集查询：表形态 → SELECT（可带列清单）；子查询形态 → 去掉最外层括号的原文
        String querySql;
        if ("table".equalsIgnoreCase(copyType)) {
            String table = parseObject.getString("table");
            JSONArray columnsJson = parseObject.getJSONArray("columns");
            StringBuilder select = new StringBuilder("SELECT ");
            if (columnsJson == null || columnsJson.isEmpty()) {
                select.append('*');
            } else {
                for (int i = 0; i < columnsJson.size(); i++) {
                    if (i > 0) {
                        select.append(", ");
                    }
                    select.append(columnsJson.get(i).toString().trim());
                }
            }
            select.append(" FROM ").append(table);
            querySql = select.toString();
        } else {
            // CopyVisitor 存的是含最外层括号的文本（grammar 的 query 规则捕获了括号）
            querySql = stripOuterParentheses(parseObject.getString("query"));
        }

        long sqlHistoryId = request.insertSqlHistory(ctx);
        long exportedRows = 0;
        int pendingBytes = 0;

        try {
            try (PreparedStatement ps = session.dbConnection.prepareStatement(querySql)) {
                long handleId = session.registerRunningStatement(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    // 列数必须先于 CopyOutResponse 拿到（报文里就要带），
                    // 因此这里先读一次元数据，再据此构造编码器。
                    CopyOutEncoder encoder = new CopyOutEncoder(rs.getMetaData(), copyDialect);

                    CopyOutResponse copyOutResponse = new CopyOutResponse(dbInstance);
                    copyOutResponse.copyColumnCount = (short) encoder.getColumnCount();
                    copyOutResponse.binary = false;
                    copyOutResponse.process(ctx, request, out);
                    PostgresMessage.writeAndFlush(ctx, CopyOutResponse.class.getSimpleName(),
                            out, dbInstance.logger);

                    // 只有 CSV 支持 HEADER（PG 语义，CopyDialect 已把关）
                    if (copyDialect.csv && copyDialect.header) {
                        encoder.encodeHeader(out);
                        pendingBytes += writeCopyData(ctx, out, dbInstance);
                    }

                    while (rs.next()) {
                        encoder.encodeRow(rs, out);
                        pendingBytes += writeCopyData(ctx, out, dbInstance);
                        exportedRows++;
                        if (pendingBytes >= PostgresMessage.FLUSH_THRESHOLD_BYTES) {
                            ctx.flush();
                            pendingBytes = 0;
                        }
                    }
                }
                finally {
                    session.unregisterRunningStatement(handleId);
                }
            }
        }
        catch (SQLException se) {
            // 结果集还没推（或只推了一部分）就失败：错误响应与 ReadyForQuery 必须发出，
            // 否则客户端会一直等数据。审计也要收尾。
            request.updateSqlHistory(sqlHistoryId, -1, 0,
                    SqlStateMapper.fromException(se) + ":" + se.getMessage());
            sendErrorAndReady(request, ctx, out, SqlStateMapper.fromException(se), se.getMessage());
            return Result.REJECTED;
        }

        // CopyDone → CommandComplete（COPY n）→ ReadyForQuery。
        // 客户端靠 CopyDone 确认数据流结束，靠 ReadyForQuery 确认"本回合结束、可以发下一条语句"。
        // 最后那个 Z 必须在这里发：调用方拿到 COPY_STARTED 就结束本回合（不会自己补 Z）。
        CopyDone copyDone = new CopyDone(dbInstance);
        copyDone.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, CopyDone.class.getSimpleName(), out, dbInstance.logger);

        CommandComplete commandComplete = new CommandComplete(dbInstance);
        commandComplete.setCommandResult("COPY " + exportedRows);
        commandComplete.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, dbInstance.logger);

        ReadyForQuery copyOutReady = new ReadyForQuery(dbInstance);
        copyOutReady.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, dbInstance.logger);

        request.updateSqlHistory(sqlHistoryId, 0, exportedRows, null);

        dbInstance.logger.debug("[SERVER][COPY       ] COPY OUT finished: type=[{}] rows={} format=[{}]",
                copyType, exportedRows, copyDialect.format);

        return Result.COPY_STARTED;
    }

    /**
     * 把 {@code out} 里已编码好的一行包装成 {@code CopyData('d')} 报文发出。
     *
     * @return 本次写出的字节数（供调用方累计 flush 阈值）
     */
    private static long writeCopyData(ChannelHandlerContext ctx, ByteArrayOutputStream out, DBInstance dbInstance)
            throws IOException {
        byte[] payload = out.toByteArray();
        out.reset();
        out.write((byte)'d');
        out.write(org.slackerdb.common.utils.Utils.int32ToBytes(4 + payload.length));
        out.writeBytes(payload);
        return PostgresMessage.write(ctx, "CopyData", out, dbInstance.logger);
    }

    /** 去掉子查询文本最外层的一对圆括号（{@code (SELECT ...)} → {@code SELECT ...}）。 */
    private static String stripOuterParentheses(String query) {
        if (query == null) {
            return "";
        }
        String trimmed = query.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '('
                && trimmed.charAt(trimmed.length() - 1) == ')') {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
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
