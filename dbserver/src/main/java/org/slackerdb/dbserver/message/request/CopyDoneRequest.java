package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.response.CommandComplete;
import org.slackerdb.dbserver.message.response.ErrorResponse;
import org.slackerdb.dbserver.message.response.ReadyForQuery;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.server.DBSession;
import org.slackerdb.dbserver.sql.CopyCsvReader;
import org.slackerdb.dbserver.sql.PostgresSQLUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class CopyDoneRequest extends PostgresRequest {
    // 列类型编码：在进入逐行/逐单元格循环之前，把列类型名称解析成整数编码，
    // 避免在内层循环中对每个单元格重复做多次字符串比较。
    private static final int TYPE_UNSUPPORTED = 0;
    private static final int TYPE_SMALLINT    = 1;
    private static final int TYPE_INTEGER     = 2;
    private static final int TYPE_BIGINT      = 3;
    private static final int TYPE_VARCHAR     = 4;
    private static final int TYPE_FLOAT       = 5;
    private static final int TYPE_DOUBLE      = 6;
    private static final int TYPE_DECIMAL     = 7;
    private static final int TYPE_TIMESTAMP   = 8;
    private static final int TYPE_BOOLEAN     = 9;

    // CSV COPY 的时间戳格式。原来是每个单元格 new 一次（与已修的 H2 同类问题），这里提为常量。
    private static final DateTimeFormatter COPY_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 把列类型名称解析为类型编码，未支持的类型返回 {@link #TYPE_UNSUPPORTED}。 */
    private static int columnTypeCode(String columnType)
    {
        if ("SMALLINT".equals(columnType)) {
            return TYPE_SMALLINT;
        }
        if ("INTEGER".equals(columnType)) {
            return TYPE_INTEGER;
        }
        if ("BIGINT".equals(columnType)) {
            return TYPE_BIGINT;
        }
        if ("VARCHAR".equals(columnType)) {
            return TYPE_VARCHAR;
        }
        if ("FLOAT".equals(columnType)) {
            return TYPE_FLOAT;
        }
        if ("DOUBLE".equals(columnType)) {
            return TYPE_DOUBLE;
        }
        if (columnType != null && columnType.startsWith("DECIMAL")) {
            return TYPE_DECIMAL;
        }
        if ("TIMESTAMP".equals(columnType)) {
            return TYPE_TIMESTAMP;
        }
        if ("BOOLEAN".equals(columnType)) {
            return TYPE_BOOLEAN;
        }
        return TYPE_UNSUPPORTED;
    }

    /** 列数不符时用于立即停止解析（不是错误，由调用方统一报错）。 */
    private static final class StopParsing extends RuntimeException {
        StopParsing() {
            super(null, null, false, false);
        }
    }

    /**
     * 把 {@link CopyCsvReader} 产出的字段视图逐行写入 {@link DuckDBAppender}。
     *
     * <p>字段视图是"指向解析缓冲的 (off,len)"，因此本类不做任何复制；只有文本列需要
     * {@code new String(...)}（Appender 只接受 String），数值列直接从字节解析。</p>
     *
     * <p>空值语义按 PG：<b>未加引号的空字段 = NULL</b>（{@code appendNull()}），
     * 加了引号的空字段是空字符串。这与改造前（commons-csv 无法区分，且空数值字段会抛
     * NumberFormatException）不同，属于已确认的行为变更。</p>
     */
    private static final class CsvRowSink implements CopyCsvReader.FieldSink {
        private final CopyCsvReader reader;
        private final DuckDBAppender appender;
        private final int[] columnMapPos;      // 表列 i -> COPY 数据中的列号，-1 表示该列不在 COPY 列表里
        private final int[] columnTypeCodes;
        private final int expectedColumnCount;

        private int[] offsets = new int[8];
        private int[] lengths = new int[8];
        private boolean[] quoted = new boolean[8];
        private int fieldCount;

        /** 列数不符时记录实际列数与该行内容（只记第一次），供调用方报错 */
        int mismatchActualCount = -1;
        String mismatchRowText = null;

        CsvRowSink(CopyCsvReader reader, DuckDBAppender appender, List<Integer> columnMapPos,
                   int[] columnTypeCodes, int expectedColumnCount) {
            this.reader = reader;
            this.appender = appender;
            this.columnMapPos = new int[columnMapPos.size()];
            for (int i = 0; i < columnMapPos.size(); i++) {
                this.columnMapPos[i] = columnMapPos.get(i);
            }
            this.columnTypeCodes = columnTypeCodes;
            this.expectedColumnCount = expectedColumnCount;
        }

        @Override
        public void field(int off, int len, boolean isQuoted) {
            if (fieldCount == offsets.length) {
                int newLen = offsets.length * 2;
                offsets = java.util.Arrays.copyOf(offsets, newLen);
                lengths = java.util.Arrays.copyOf(lengths, newLen);
                quoted = java.util.Arrays.copyOf(quoted, newLen);
            }
            offsets[fieldCount] = off;
            lengths[fieldCount] = len;
            quoted[fieldCount] = isQuoted;
            fieldCount++;
        }

        @Override
        public void endRow() {
            try {
                if (fieldCount != expectedColumnCount) {
                    mismatchActualCount = fieldCount;
                    mismatchRowText = rowText();
                    throw new StopParsing();
                }
                appender.beginRow();
                for (int i = 0; i < columnMapPos.length; i++) {
                    int nPos = columnMapPos[i];
                    if (nPos == -1) {
                        // 该列不在 COPY 列表里：用表默认值
                        appender.appendDefault();
                        continue;
                    }
                    int off = offsets[nPos];
                    int len = lengths[nPos];
                    if (len == 0 && !quoted[nPos]) {
                        // PG 语义：未加引号的空字段是 NULL（加引号的空串不是）
                        appender.appendNull();
                        continue;
                    }
                    appendValue(columnTypeCodes[i], off, len);
                }
                appender.endRow();
            } catch (SQLException sqlException) {
                // 还原给调用方，保持与改造前一致的 SQLException 处理路径
                throw new RuntimeException(sqlException);
            } finally {
                fieldCount = 0;
            }
        }

        /** 直接从字节区间取值写入 Appender（数值不经过中间 String）。 */
        private void appendValue(int typeCode, int off, int len) throws SQLException {
            byte[] buf = reader.buffer();
            switch (typeCode) {
                case TYPE_SMALLINT -> appender.append(CopyCsvReader.parseShort(buf, off, len));
                case TYPE_INTEGER -> appender.append(CopyCsvReader.parseInt(buf, off, len));
                case TYPE_BIGINT -> appender.append(CopyCsvReader.parseLong(buf, off, len));
                case TYPE_VARCHAR ->
                        appender.append(new String(buf, off, len, StandardCharsets.UTF_8));
                case TYPE_FLOAT ->
                        appender.append(Float.parseFloat(text(buf, off, len)));
                case TYPE_DOUBLE ->
                        appender.append(Double.parseDouble(text(buf, off, len)));
                case TYPE_DECIMAL ->
                        appender.append(new BigDecimal(text(buf, off, len)));
                case TYPE_TIMESTAMP ->
                        appender.append(LocalDateTime.parse(text(buf, off, len), COPY_TIMESTAMP_FORMATTER));
                case TYPE_BOOLEAN ->
                        appender.append(Boolean.parseBoolean(text(buf, off, len)));
                default -> throw new IllegalStateException("unsupported column type code " + typeCode);
            }
        }

        private static String text(byte[] buf, int off, int len) {
            return new String(buf, off, len, StandardCharsets.US_ASCII);
        }

        /** 用于列数不符时的错误信息（把该行各字段拼出来）。 */
        private String rowText() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < fieldCount; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                if (lengths[i] == 0 && !quoted[i]) {
                    sb.append("<null>");
                } else {
                    sb.append(new String(reader.buffer(), offsets[i], lengths[i], StandardCharsets.UTF_8));
                }
            }
            return sb.toString();
        }
    }

    public CopyDoneRequest(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    /** 复位会话上与本次 COPY 相关的执行状态（提前 return 的分支原本会漏掉这一步）。 */
    private void resetSessionExecutionState(ChannelHandlerContext ctx) {
        DBSession session = this.dbInstance.getSession(getCurrentSessionId(ctx));
        session.executingFunction = "";
        session.executingTime = null;
    }

    /**
     * 错误路径的统一收尾：先丢弃未提交的部分写入，再回 ErrorResponse 与 ReadyForQuery。
     *
     * <p>调用方通常紧接着 {@code return}，不会走到 {@code process()} 尾部的收尾逻辑，
     * 因此这里必须自己把全部收尾动作做完（丢弃部分写入 + 复位会话 + 发 E 和 Z）。</p>
     */
    private void sendErrorAndReady(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out,
                                   String errorCode, String message)
            throws IOException
    {
        // 先丢弃部分写入，再告诉客户端失败：不能让客户端以为失败的同时数据还留在表里
        this.dbInstance.getSession(getCurrentSessionId(ctx)).discardUncommittedCopy();
        resetSessionExecutionState(ctx);

        ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
        errorResponse.setErrorResponse(errorCode, message);
        errorResponse.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

        ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
        readyForQuery.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);

        out.close();
    }

    /**
     * 发送错误响应以及ReadyForQuery。
     * BINARY分支中各类型的数据长度校验失败、以及列类型不支持时，收尾动作完全相同，统一由此方法处理。
     */
    private void sendErrorAndReady(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out, String message)
            throws IOException
    {
        sendErrorAndReady(ctx, request, out, "SLACKER-0099", message);
    }

    /**
     * 构造BINARY格式下的数据校验失败信息。
     *
     * @param columnName    被校验的数据所对应的表列名
     * @param columnType    该表列的类型
     * @param actualBytes   实际字节数
     * @param expectedBytes 期望字节数描述(例如 "4 bytes" 或 "1 byte")
     */
    private static String binaryMismatchMessage(String columnName, String columnType,
                                                int actualBytes, String expectedBytes)
    {
        return "Binary Copy data type mismatch: Column [" + columnName
                + "], column type is " + columnType
                + ", but data length is " + actualBytes + " bytes (expected " + expectedBytes + ").";
    }

    //  CopyDone (F & B)
    //    Byte1('c')
    //      Identifies the message as a COPY-complete indicator.
    //    Int32(4)
    //      Length of message contents in bytes, including self.
    @Override
    public void decode(byte[] data) {
        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        // 记录会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = this.getClass().getSimpleName();
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = LocalDateTime.now();

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 会话信息在本次COPY收尾过程中不会变化，提升到循环之外，
        // 避免在内层循环里为每个单元格反复查找会话。
        DBSession session = this.dbInstance.getSession(getCurrentSessionId(ctx));

        long nCopiedRows = 0;
        // 本次 COPY 是否已经失败。PG 协议里 CommandComplete 与 ErrorResponse 互斥：
        // 失败时既要跳过 CommandComplete，又要把本次已写入的部分行整体丢弃（否则留下"半截数据"）。
        boolean copyFailed = false;
        String errorCode = "SLACKER-0099";
        String errorMessage = null;
        if (session.copyLastRemained.size() != 0) {
            try {
                if (session.copyTableFormat.equalsIgnoreCase("CSV")) {
                    // 用字节级解析器替换 commons-csv：不再把整段载荷 toString()、不再为每行构造
                    // CSVRecord/String[]、不再为数值字段生成中间 String。
                    // 注意 CopyCsvReader(byte[],int) 是"接管"语义（会就地展开 "" 转义），
                    // 而 toByteArray() 返回的是新数组，因此这里安全。
                    byte[] payload = session.copyLastRemained.toByteArray();
                    session.copyLastRemained.reset();

                    DuckDBAppender duckDBAppender = session.copyTableAppender;
                    List<Integer> copyTableDbColumnMapPos = session.copyTableDbColumnMapPos;
                    // COPY数据中每行的字段数量应该和COPY语句中指定的列数量保持一致
                    int expectedColumnCount = session.copyColumnCount;
                    // 每一列的列类型只解析一次，逐单元格循环里不再查询会话、不再做字符串比较
                    List<String> copyTableDbColumnType = session.copyTableDbColumnType;
                    int mappedColumnCount = copyTableDbColumnMapPos.size();
                    int[] columnTypeCodes = new int[mappedColumnCount];
                    for (int i = 0; i < mappedColumnCount; i++) {
                        int nPos = copyTableDbColumnMapPos.get(i);
                        if (nPos != -1) {
                            // 循环变量 i 是"该列在表中的位置"，所以类型按 i 取值；
                            // 取值时用 nPos 去 COPY 的数据里找对应的那一列。
                            String columnType = copyTableDbColumnType.get(i);
                            int typeCode = columnTypeCode(columnType);
                            if (typeCode == TYPE_UNSUPPORTED) {
                                // 快速失败：不支持的列类型在任何数据到达之前就报错，
                                // 而不是解析到第 N 行该列时才失败（此时 Appender 已写入部分行）。
                                sendErrorAndReady(ctx, request, out,
                                        "CSV Format error (column type not support) . " + columnType);
                                return;
                            }
                            columnTypeCodes[i] = typeCode;
                        }
                    }

                    CopyCsvReader reader = new CopyCsvReader(payload, payload.length);
                    reader.markEof();
                    CsvRowSink sink = new CsvRowSink(reader, duckDBAppender, copyTableDbColumnMapPos,
                            columnTypeCodes, expectedColumnCount);
                    try {
                        while (reader.nextRow(sink)) {
                            nCopiedRows++;
                        }
                    } catch (StopParsing stopParsing) {
                        // 列数不符，由下面统一报错
                    } catch (RuntimeException runtimeException) {
                        // 把 sink 里包装过的 SQLException 还原出来，保持与改造前一致的错误处理路径
                        if (runtimeException.getCause() instanceof SQLException sqlException) {
                            throw sqlException;
                        }
                        throw runtimeException;
                    }
                    if (sink.mismatchActualCount != -1) {
                        // CSV字段数量不对等。
                        // 必须走 sendErrorAndReady：它会先丢弃本次 COPY 已写入的部分行再回错误。
                        // （不能在这里就地拼一个 ErrorResponse 后直接 return——那样前面已 append
                        //   的行会被 Appender 的 close() 提交，留下"半截数据"。）
                        sendErrorAndReady(ctx, request, out, "SLACKER-0099",
                                "CSV Format error (column size not match." +
                                        " [" + sink.mismatchActualCount + "] vs [" + expectedColumnCount + "])." +
                                        " [" + sink.mismatchRowText + "].");
                        return;
                    }
                } // CSV
                else if (session.copyTableFormat.equalsIgnoreCase("BINARY")) {
                    List<Object[]> data = PostgresSQLUtil.convertPGByteToRow(session.copyLastRemained.toByteArray());
                    session.copyLastRemained.reset();
                    DuckDBAppender duckDBAppender = session.copyTableAppender;
                    List<Integer> copyTableDbColumnMapPos = session.copyTableDbColumnMapPos;
                    // 列类型/名称同样提前取出，逐单元格循环里不再查询会话
                    List<String> copyTableDbColumnType = session.copyTableDbColumnType;
                    List<String> copyTableDbColumnName = session.copyTableDbColumnName;
                    for (Object[] row : data) {
                        duckDBAppender.beginRow();
                        for (int i=0; i<copyTableDbColumnMapPos.size(); i++) {
                            int nPos = copyTableDbColumnMapPos.get(i);
                            if (nPos == -1) {
                                duckDBAppender.appendDefault();
                            } else
                            {
                                // 数据内容
                                Object cell = row[nPos];

                                // 列名/类型都按"表列位置"取值。注意: nPos 是该数据在 COPY 数据流(即 row)中的下标，
                                // 也就是"来源列序号"；i 才是表列位置。
                                String columnType = copyTableDbColumnType.get(i);
                                String columnName = copyTableDbColumnName.get(i);
                                int columnTypeCode = columnTypeCode(columnType);
                                if (cell == null)
                                {
                                    duckDBAppender.appendNull();
                                }
                                else if (columnTypeCode == TYPE_SMALLINT) {
                                    // SMALLINT 期望 2 字节数据
                                    if (((byte[]) cell).length != 2) {
                                        sendErrorAndReady(ctx, request, out,
                                                binaryMismatchMessage(columnName, columnType,
                                                        ((byte[]) cell).length, "2 bytes"));
                                        return;
                                    }
                                    duckDBAppender.append(Utils.bytesToInt16((byte[]) cell));
                                }
                                else if (columnTypeCode == TYPE_INTEGER) {
                                    // INTEGER 期望 4 字节数据
                                    if (((byte[]) cell).length != 4) {
                                        sendErrorAndReady(ctx, request, out,
                                                binaryMismatchMessage(columnName, columnType,
                                                        ((byte[]) cell).length, "4 bytes"));
                                        return;
                                    }
                                    duckDBAppender.append(Utils.bytesToInt32((byte[]) cell));
                                }
                                else if (columnTypeCode == TYPE_BIGINT) {
                                    // BIGINT 期望 8 字节数据
                                    if (((byte[]) cell).length != 8) {
                                        sendErrorAndReady(ctx, request, out,
                                                binaryMismatchMessage(columnName, columnType,
                                                        ((byte[]) cell).length, "8 bytes"));
                                        return;
                                    }
                                    duckDBAppender.append(BigInteger.valueOf(Utils.bytesToInt64((byte[]) cell)).longValue());
                                }
                                else if (columnTypeCode == TYPE_VARCHAR)
                                {
                                    // UTF-8是BINARY COPY唯一支持的字符集，不支持其他的
                                    duckDBAppender.append(new String((byte[])cell, StandardCharsets.UTF_8));
                                }
                                else if (columnTypeCode == TYPE_FLOAT)
                                {
                                    // FLOAT 期望 4 字节数据
                                    if (((byte[]) cell).length != 4) {
                                        sendErrorAndReady(ctx, request, out,
                                                binaryMismatchMessage(columnName, columnType,
                                                        ((byte[]) cell).length, "4 bytes"));
                                        return;
                                    }
                                    duckDBAppender.append(Utils.byteToFloat((byte [])cell));
                                }
                                else if (columnTypeCode == TYPE_DOUBLE)
                                {
                                    // DOUBLE 期望 8 字节数据
                                    if (((byte[]) cell).length != 8) {
                                        sendErrorAndReady(ctx, request, out,
                                                binaryMismatchMessage(columnName, columnType,
                                                        ((byte[]) cell).length, "8 bytes"));
                                        return;
                                    }
                                    duckDBAppender.append(Utils.byteToDouble((byte [])cell));
                                }
                                else if (columnTypeCode == TYPE_DECIMAL)
                                {
                                    duckDBAppender.append(
                                            PostgresSQLUtil.convertPGByteToBigDecimal((byte[]) cell));
                                }
                                else if (columnTypeCode == TYPE_TIMESTAMP)
                                {
                                    // TIMESTAMP 期望 8 字节数据
                                    if (((byte[]) cell).length != 8) {
                                        sendErrorAndReady(ctx, request, out,
                                                binaryMismatchMessage(columnName, columnType,
                                                        ((byte[]) cell).length, "8 bytes"));
                                        return;
                                    }
                                    long epochMilli = Utils.bytesToInt64((byte[]) cell) / 1000;
                                    duckDBAppender.append(Instant.ofEpochMilli(epochMilli).atZone(ZoneId.of("UTC")).toLocalDateTime());
                                }
                                else if (columnTypeCode == TYPE_BOOLEAN)
                                {
                                    // BOOLEAN 期望 1 字节数据
                                    if (((byte[]) cell).length != 1) {
                                        sendErrorAndReady(ctx, request, out,
                                                binaryMismatchMessage(columnName, columnType,
                                                        ((byte[]) cell).length, "1 byte"));
                                        return;
                                    }
                                    duckDBAppender.append(((byte[]) cell)[0] == 0x01);
                                }
                                else
                                {
                                    sendErrorAndReady(ctx, request, out,
                                            "Binary Format error (column type not support) . " + columnType);
                                    return;
                                }
                            }
                        }
                        duckDBAppender.endRow();
                        nCopiedRows++;
                    }
                } // BINARY
            }
            catch (SQLException | RuntimeException ex)
            {
                // SQL异常: 用错误码作为返回码。
                // RuntimeException: 例如BINARY数据流不完整时 convertPGByteToRow 抛出的
                // IllegalArgumentException(缺少固定头/行数据被截断/缺少行尾结束标志)，
                // 以及CSV解析异常。这类异常必须在这里消化掉并回应客户端，
                // 否则会一路逃逸到Netty，客户端永远收不到响应(表现为copyIn永久挂起，且该会话不可再用)。
                // 这里只登记失败原因，不在这里回包也不回滚。
                // 统一的收尾（丢弃已写入的部分行 → 回 ErrorResponse/CommandComplete → ReadyForQuery）
                // 放在方法尾部，保证"告诉客户端失败"与"数据不落库"这两件事一起发生。
                copyFailed = true;
                errorCode = (ex instanceof SQLException sqlEx)
                        ? String.valueOf(sqlEx.getErrorCode()) : "SLACKER-0099";
                errorMessage = ex.getMessage();
            }
        }

        // 无论本次COPY是否携带数据、是否已经出错，都必须关闭Appender释放资源，
        // 否则会话将不可再用（成功时客户端才能拿到下面的CommandComplete）。
        // 注意：此时数据仍在本事务内 —— 事务外的 close() 会立即提交，这里因为 QueryRequest
        // 已经 BEGIN 过，close() 只是把数据留在事务里，真正的提交/回滚在下面显式完成。
        try {
            if (session.copyTableAppender != null) {
                session.copyTableAppender.close();
                session.copyTableAppender = null;
                session.copyLastRemained.reset();
            }
        }
        catch (SQLException se) {
            copyFailed = true;
            errorCode = String.valueOf(se.getErrorCode());
            errorMessage = se.getMessage();
        }

        // 提交或回滚服务端自有的 COPY 事务。
        // 只有在 COMMIT 成功之后才敢回 CommandComplete，避免"报了成功但数据没落库"。
        if (session.copyOwnTransaction) {
            session.copyOwnTransaction = false;
            try (Statement finishStatement =
                         ((DuckDBConnection) session.dbConnection).createStatement()) {
                finishStatement.execute(copyFailed ? "ROLLBACK" : "COMMIT");
            }
            catch (SQLException se) {
                this.dbInstance.logger.warn("[SERVER] Finish COPY transaction failed.", se);
                if (!copyFailed) {
                    copyFailed = true;
                    errorCode = String.valueOf(se.getErrorCode());
                    errorMessage = se.getMessage();
                }
                // COMMIT 失败时事务可能仍是打开/已中止状态，尽力回滚，
                // 避免一个脏事务一直挂在会话上影响后续语句。
                try (Statement rollbackStatement =
                             ((DuckDBConnection) session.dbConnection).createStatement()) {
                    rollbackStatement.execute("ROLLBACK");
                }
                catch (SQLException ignored) {
                    // 已经没有活动事务时 ROLLBACK 会报错，忽略即可
                }
            }
        }

        // 发送CommandComplete —— 仅在成功时发出。
        // 协议规定后端结束 copy-in 后「要么 CommandComplete（成功），要么 ErrorResponse（失败）」，
        // 两者互斥；失败时已经（或将要在下面）发出 ErrorResponse，再补一个 C 会让客户端把失败当成功。
        if (copyFailed) {
            ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
            errorResponse.setErrorResponse(errorCode, errorMessage);
            errorResponse.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);
        }
        else {
            CommandComplete commandComplete = new CommandComplete(this.dbInstance);
            commandComplete.setCommandResult("COPY " + nCopiedRows);
            commandComplete.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, this.dbInstance.logger);
        }

        // 发送ReadyForQuery —— 无论成败都必须发出，且只发一次。
        ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
        readyForQuery.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);

        // 取消会话的开始时间，以及业务类型
        resetSessionExecutionState(ctx);
        out.close();
    }
}
