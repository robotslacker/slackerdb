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
import org.slackerdb.dbserver.sql.CopyDialect;
import org.slackerdb.dbserver.sql.CopyValueWriters;
import org.slackerdb.dbserver.sql.SqlStateMapper;
import org.slackerdb.dbserver.sql.PostgresSQLUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

public class CopyDoneRequest extends PostgresRequest {

    // ================= BINARY COPY 专用的列类型编码 =================
    // 只服务 PG 二进制载荷的解码（与 CSV 的文本解析是两套东西）。
    // CSV 路径按 DuckDB 类型名构造 CopyValueWriters.ColumnWriter，不再用这些编码。
    private static final int TYPE_UNSUPPORTED = 0;
    private static final int TYPE_SMALLINT = 1;
    private static final int TYPE_INTEGER = 2;
    private static final int TYPE_BIGINT = 3;
    private static final int TYPE_VARCHAR = 4;
    private static final int TYPE_FLOAT = 5;
    private static final int TYPE_DOUBLE = 6;
    private static final int TYPE_DECIMAL = 7;
    private static final int TYPE_TIMESTAMP = 8;
    private static final int TYPE_BOOLEAN = 9;

    /**
     * 把列类型名称解析为 PG 二进制载荷解码用的类型编码，未支持的类型返回 {@link #TYPE_UNSUPPORTED}。
     */
    private static int columnTypeCode(String columnType) {
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
            // 控制流异常：关掉栈回溯的开销（列数不符时每行都会走到这里）
            super(null, null, false, false);
        }
    }

    /**
     * 把 {@link CopyCsvReader} 产出的字段视图逐行写进 {@link DuckDBAppender}。
     *
     * <p>字段视图是"指向解析缓冲的 (off,len)"，因此这里不做无谓的复制：每个列的写入器
     * （{@link CopyValueWriters.ColumnWriter}，按 DuckDB 列类型名构造一次）自己决定怎么解析。</p>
     *
     * <p>NULL 由解析器按方言判定后通过 {@code isNull} 传入：CSV 是"未加引号的空字段"
     * （或未加引号且等于 NULL 串的字段），TEXT 是"等于 NULL 串的字段"（默认 {@code \N}）。
     * 因此这里不再自行推导，避免两处对 NULL 的理解漂移。</p>
     *
     * <p>{@code HEADER} 选项要求跳过第一条记录（表头）：表头不写库，
     * 也不计入 {@code COPY n} 的行数。</p>
     */
    private static final class CopyRowSink implements CopyCsvReader.FieldSink {
        private final CopyCsvReader reader;
        private final DuckDBAppender appender;
        private final CopyValueWriters.ColumnWriter[] columnWriters;
        private final int[] columnMapPos;      // 表列 i -> COPY 数据中的列号，-1 表示该列不在 COPY 列表里
        private final int expectedColumnCount;

        private int[] offsets = new int[8];
        private int[] lengths = new int[8];
        private boolean[] nulls = new boolean[8];
        private int fieldCount;

        /** 还需要跳过的表头行数（HEADER 选项） */
        private int headerRowsToSkip;
        /** 实际写入的行数（表头行不计入） */
        long writtenRows;

        /** 列数不符时记录实际列数与该行内容（只记第一次），供调用方报错 */
        int mismatchActualCount = -1;
        String mismatchRowText = null;

        CopyRowSink(CopyCsvReader reader, DuckDBAppender appender,
                    CopyValueWriters.ColumnWriter[] columnWriters, List<Integer> columnMapPos,
                    int expectedColumnCount, int headerRowsToSkip) {
            this.reader = reader;
            this.appender = appender;
            this.columnWriters = columnWriters;
            this.columnMapPos = new int[columnMapPos.size()];
            for (int i = 0; i < columnMapPos.size(); i++) {
                this.columnMapPos[i] = columnMapPos.get(i);
            }
            this.expectedColumnCount = expectedColumnCount;
            this.headerRowsToSkip = headerRowsToSkip;
        }

        @Override
        public void field(int off, int len, boolean isNull) {
            if (fieldCount == offsets.length) {
                int newLen = offsets.length * 2;
                offsets = java.util.Arrays.copyOf(offsets, newLen);
                lengths = java.util.Arrays.copyOf(lengths, newLen);
                nulls = java.util.Arrays.copyOf(nulls, newLen);
            }
            offsets[fieldCount] = off;
            lengths[fieldCount] = len;
            nulls[fieldCount] = isNull;
            fieldCount++;
        }

        @Override
        public void endRow() {
            try {
                if (headerRowsToSkip > 0) {
                    // HEADER：第一条记录是表头，直接丢弃（不校验列数，与 PG 一致）
                    headerRowsToSkip--;
                    return;
                }
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
                    if (nulls[nPos]) {
                        appender.appendNull();
                        continue;
                    }
                    columnWriters[i].write(appender, reader.buffer(), offsets[nPos], lengths[nPos]);
                }
                appender.endRow();
                writtenRows++;
            } catch (SQLException sqlException) {
                // 还原给调用方，保持与改造前一致的 SQLException 处理路径
                throw new RuntimeException(sqlException);
            } finally {
                fieldCount = 0;
            }
        }

        /** 用于列数不符时的错误信息（把该行各字段拼出来）。 */
        private String rowText() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < fieldCount; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                if (nulls[i]) {
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
     *
     * <p>默认给 {@code 22P04}（bad_copy_file_format）：这些调用点都是"数据流本身不合规"
     * （二进制长度不符、列数不符、列类型不支持）。</p>
     */
    private void sendErrorAndReady(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out, String message)
            throws IOException
    {
        sendErrorAndReady(ctx, request, out, "22P04", message);
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

    /**
     * 构造BINARY格式下"数据列数不足"的错误信息。
     *
     * <p>改造前这种情况会以 {@code ArrayIndexOutOfBoundsException} 的形式逃逸，客户端只能看到
     * {@code ERROR: Index 6 out of bounds for length 6}，既不知道是哪一行、也不知道到底缺什么；
     * 这里改成与CSV路径同样的"[实际] vs [期望]"格式，并补一句人话说明。</p>
     *
     * @param rowNumber           出错的行号(从1开始，仅本次COPY的数据流内计数)
     * @param actualColumnCount   该行数据里实际的列数
     * @param expectedColumnCount 期望的列数(没有指定列清单时即目标表的列数)
     */
    private static String binaryColumnCountMismatchMessage(long rowNumber, int actualColumnCount,
                                                           int expectedColumnCount)
    {
        return "Binary Format error (column size not match. [" + actualColumnCount
                + "] vs [" + expectedColumnCount + "]). Row " + rowNumber + " has " + actualColumnCount
                + " columns, but " + expectedColumnCount + " columns are expected.";
    }

    /**
     * 构造 Appender 写入失败的错误信息。
     *
     * <p>这里刻意<b>不按错误类型做任何特判</b>：DuckDB 报什么原因就带给客户端什么原因，
     * 无论是 NOT NULL、主键/唯一冲突、CHECK、类型转换还是IO错误，走的都是同一条路径。</p>
     *
     * <p>只做一件与错误类型无关的事情：剥掉 Appender 自己的固定外壳
     * （形如 {@code Appender error, catalog: 'null', schema: 'null', table: 't', message: }，
     * 其中 catalog/schema/table 与真正的原因重复，且 catalog/schema 恒为字符串 'null'），
     * 然后在前面统一加一个 {@code Copy failed:} 前缀，说明这是 COPY 写入阶段的失败。
     * 外壳格式一旦变化，就原样透传整条信息，不丢内容。</p>
     */
    private static String copyWriteErrorMessage(String message)
    {
        if (message == null || message.isBlank()) {
            return "Copy failed: failed to write data into DuckDB.";
        }

        final String appenderWrapperMarker = "message: ";
        int markerPos = message.indexOf(appenderWrapperMarker);
        String reason = (markerPos >= 0)
                ? message.substring(markerPos + appenderWrapperMarker.length()).trim()
                : message.trim();

        return "Copy failed: " + reason;
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

        // 本次 COPY 已经判失败（例如缓冲超过 CopyProtocolHandler.maxPayloadBytes）：
        // 错误与 ReadyForQuery 当时就发过了，这里只做清理，**绝不**能回 CommandComplete ——
        // 那会让客户端看到 "COPY 0" 的"成功"，把失败当成功。
        if (session.copyAborted) {
            session.discardUncommittedCopy();
            session.resetCopyState();
            this.dbInstance.logger.debug(
                    "[SERVER][COPY       ] CopyDone after an aborted COPY is ignored (no CommandComplete).");
            resetSessionExecutionState(ctx);
            out.close();
            return;
        }

        long nCopiedRows = 0;
        // 本次 COPY 是否已经失败。PG 协议里 CommandComplete 与 ErrorResponse 互斥：
        // 失败时既要跳过 CommandComplete，又要把本次已写入的部分行整体丢弃（否则留下"半截数据"）。
        boolean copyFailed = false;
        String errorCode = SqlStateMapper.INTERNAL_ERROR;
        String errorMessage = null;
        if (session.copyLastRemained.size() != 0) {
            try {
                // 文本类格式（TEXT / CSV）共用同一个解析器，方言（分隔符/引号/转义/NULL 串/表头）
                // 来自 COPY 语句的选项。改造前只认 CSV，且分隔符/引号/NULL 全是硬编码，
                // 连 "FORMAT text"（PG 默认格式）都进不来。
                CopyDialect dialect = session.copyDialect;
                String formatName = (dialect == null) ? session.copyTableFormat
                        : dialect.format;
                if (dialect != null && dialect.isRowBased()) {
                    // 用字节级解析器替换 commons-csv：不再把整段载荷 toString()、不再为每行构造
                    // CSVRecord/String[]、不再为数值字段生成中间 String。
                    // 注意 CopyCsvReader(byte[],int,dialect) 是"接管"语义（会就地展开转义），
                    // 而 toByteArray() 返回的是新数组，因此这里安全。
                    byte[] payload = session.copyLastRemained.toByteArray();
                    session.copyLastRemained.reset();

                    DuckDBAppender duckDBAppender = session.copyTableAppender;
                    List<Integer> copyTableDbColumnMapPos = session.copyTableDbColumnMapPos;
                    // COPY数据中每行的字段数量应该和COPY语句中指定的列数量保持一致
                    int expectedColumnCount = session.copyColumnCount;
                    // 每个表列一个写入器：按 DuckDB 列类型名构造一次，逐行复用。
                    // 块 Appender 不做隐式转换，文本 -> Java 值的解析全部由写入器负责。
                    List<String> copyTableDbColumnType = session.copyTableDbColumnType;
                    int mappedColumnCount = copyTableDbColumnMapPos.size();
                    CopyValueWriters.ColumnWriter[] columnWriters =
                            new CopyValueWriters.ColumnWriter[mappedColumnCount];
                    for (int i = 0; i < mappedColumnCount; i++) {
                        if (copyTableDbColumnMapPos.get(i) != -1) {
                            try {
                                columnWriters[i] = CopyValueWriters.writer(copyTableDbColumnType.get(i));
                            }
                            catch (SQLException unsupportedType) {
                                // 类型名识别不了（例如需要扩展的类型）：在任何数据写入之前快速失败
                                sendErrorAndReady(ctx, request, out, "0A000", unsupportedType.getMessage());
                                return;
                            }
                        }
                    }

                    CopyCsvReader reader = new CopyCsvReader(payload, payload.length, dialect);
                    reader.markEof();
                    CopyRowSink sink = new CopyRowSink(reader, duckDBAppender, columnWriters,
                            copyTableDbColumnMapPos, expectedColumnCount, dialect.header ? 1 : 0);
                    try {
                        while (reader.nextRow(sink)) {
                            // 行数由 sink 统计：HEADER 跳过的表头不计入
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
                    nCopiedRows = sink.writtenRows;
                    if (sink.mismatchActualCount != -1) {
                        // 字段数量不对等。
                        // 必须走 sendErrorAndReady：它会先丢弃本次 COPY 已写入的部分行再回错误。
                        // （不能在这里就地拼一个 ErrorResponse 后直接 return——那样前面已 append
                        //   的行会被 Appender 的 close() 提交，留下"半截数据"。）
                        sendErrorAndReady(ctx, request, out, "22P04",
                                formatName + " Format error (column size not match." +
                                        " [" + sink.mismatchActualCount + "] vs [" + expectedColumnCount + "])." +
                                        " [" + sink.mismatchRowText + "].");
                        return;
                    }
                } // TEXT / CSV
                else if (session.copyTableFormat.equalsIgnoreCase("BINARY")) {
                    // BINARY 通道按 PG 二进制逐类型解码（与 CSV 的文本解析是两回事，见 columnTypeCode）
                    List<Object[]> data = PostgresSQLUtil.convertPGByteToRow(session.copyLastRemained.toByteArray());
                    session.copyLastRemained.reset();
                    DuckDBAppender duckDBAppender = session.copyTableAppender;
                    List<Integer> copyTableDbColumnMapPos = session.copyTableDbColumnMapPos;
                    // 列类型/名称同样提前取出，逐单元格循环里不再查询会话
                    List<String> copyTableDbColumnType = session.copyTableDbColumnType;
                    List<String> copyTableDbColumnName = session.copyTableDbColumnName;
                    // COPY数据中每行的字段数量必须能覆盖COPY语句指定的列(没有指定列时就是目标表的列数)。
                    // 数据列数不足时，下面 row[nPos] 会抛出 ArrayIndexOutOfBoundsException，
                    // 客户端只能收到 "Index 6 out of bounds for length 6" 这种看不懂的裸异常；
                    // 因此这里先显式校验，给出"第几行、实际几列、期望几列"的明确错误。
                    // 注意：数据列数多于期望时维持既有行为(多余的列被忽略)，只有"不够"才报错。
                    int expectedColumnCount = session.copyColumnCount;
                    long rowNumber = 1;
                    for (Object[] row : data) {
                        if (row.length < expectedColumnCount) {
                            // 与CSV路径一样走 sendErrorAndReady：先丢弃本次COPY已写入的部分行再回错误。
                            sendErrorAndReady(ctx, request, out,
                                    binaryColumnCountMismatchMessage(rowNumber, row.length, expectedColumnCount));
                            return;
                        }
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
                                    sendErrorAndReady(ctx, request, out, "0A000",
                                            "Binary Format error (column type not support) . " + columnType);
                                    return;
                                }
                            }
                        }
                        duckDBAppender.endRow();
                        nCopiedRows++;
                        rowNumber++;
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
                if (ex instanceof SQLException sqlEx) {
                    // 数据库/Appender 报出的写入错误同样走统一的信息整理(不按错误类型特判)
                    errorCode = SqlStateMapper.fromException(sqlEx);
                    errorMessage = copyWriteErrorMessage(sqlEx.getMessage());
                } else {
                    // 解析类异常(数据流被截断、CSV畸形等)：属于"COPY 数据格式不对"，
                    // 用 22P04（bad_copy_file_format），而不是 XX000（那意味着服务端自身出错）
                    errorCode = "22P04";
                    errorMessage = ex.getMessage();
                }
            }
        }

        // 无论本次COPY是否携带数据、是否已经出错，都必须关闭Appender释放资源，
        // 否则会话将不可再用（成功时客户端才能拿到下面的CommandComplete）。
        // 注意：此时数据仍在本事务内 —— 事务外的 close() 会立即提交，这里因为 QueryRequest
        // 已经 BEGIN 过，close() 只是把数据留在事务里，真正的提交/回滚在下面显式完成。
        try {
            if (session.copyTableAppender != null) {
                // 只有在"看起来一切正常"时才自己 flush()：
                // 1) 必须先显式 flush()，不能只依赖 close() —— duckdb_jdbc 的 DuckDBAppender.close()
                //    内部虽然也会 flush，但它把 flush 抛出的 SQLException 直接吞掉了（见该类的字节码：
                //    close() 调用 flush 的那段落在 catch(SQLException) 里，catch 体什么都不做），
                //    随后 duckdb_appender_close 的返回码也没有检查。于是像 NOT NULL/主键冲突这类
                //    只在 flush 时才暴露的错误，在 close() 时完全看不见 —— 服务端会误报 COPY 成功
                //    （CommandComplete），而 DuckDB 那边事务其实已经变成 aborted，客户端后续语句
                //    全部报 "Current transaction is aborted (please ROLLBACK)"，且拿不到任何原因。
                // 2) 循环里已经出错时不再自己 flush：此时 Appender 可能停在"半行"状态
                //    (beginRow 了但没 endRow)，flush 只会抛出
                //    "'endRow' must be called before calling 'flush'" 这种噪音，反而把真正的原因顶掉。
                // 这一步与具体错误类型无关，任何在 flush 时才暴露的错误都会在这里被带到客户端。
                if (!copyFailed) {
                    session.copyTableAppender.flush();
                }
                session.copyTableAppender.close();
                session.copyTableAppender = null;
                session.copyLastRemained.reset();
            }
        }
        catch (SQLException se) {
            copyFailed = true;
            errorCode = SqlStateMapper.fromException(se);
            errorMessage = copyWriteErrorMessage(se.getMessage());
            // flush 已经失败，Appender 不再可用，这里只做资源释放（close() 会再次吞掉错误）
            if (session.copyTableAppender != null) {
                try {
                    session.copyTableAppender.close();
                }
                catch (SQLException ignored) {
                    // 丢弃Appender时的错误无需上报，真正的原因已经记在 errorMessage 里
                }
                session.copyTableAppender = null;
                session.copyLastRemained.reset();
            }
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
                    errorCode = SqlStateMapper.fromException(se);
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

        // 审计收尾：把 COPY 的最终结果写回历史表（成功记行数，失败记 SQLSTATE + 原因）。
        // 必须放在发送 CommandComplete/ErrorResponse 之前，且在任何 discardUncommittedCopy() 之前，
        // 否则会被那条更笼统的收尾路径抢先（closeCopySqlHistory 是幂等的）。
        session.closeCopySqlHistory(copyFailed ? 0 : nCopiedRows,
                copyFailed ? (errorCode + ":" + errorMessage) : null);

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
