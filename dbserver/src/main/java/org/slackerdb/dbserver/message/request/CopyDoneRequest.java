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
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
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

    // ==================================================================
    //  BINARY 路径：单遍流式解码
    // ==================================================================

    /** BINARY 载荷本身不合规（长度不符、列数不足、流被截断）。统一映射为 22P04。 */
    private static final class BinaryFormatException extends RuntimeException {
        BinaryFormatException(String message) {
            super(message);
        }
    }

    /**
     * "载荷被截断"的统一错误文案。
     *
     * <p>校验阶段（{@link #validateBinaryColumnCounts}）与写入阶段（{@link BinaryRowSink}）都用它，
     * 保证同一种损坏在两条路径上报出完全相同的消息 —— 客户端不该因为服务端内部把校验拆成两步
     * 就看到两种说法。文案固定包含 {@code invalid binary COPY data} 与 {@code truncated}。</p>
     */
    private static String binaryTruncatedMessage(String where, int bytesLeft) {
        return "invalid binary COPY data: stream truncated at " + where
                + " (" + Math.max(bytesLeft, 0) + " bytes left)";
    }

    /** 该列类型在 BINARY 通道下不支持（对应原来就地回 0A000 的分支）。 */
    private static final class BinaryUnsupportedTypeException extends RuntimeException {
        BinaryUnsupportedTypeException(String message) {
            super(message);
        }
    }

    /**
     * 单列的解码策略。
     *
     * <p>为什么要有它：改造前是"先整段解析成 {@code List<Object[]>}（每格一个 {@code byte[]}），
     * 再按 {@code columnTypeCode(列类型名)} 逐格做字符串比较决定怎么 append"。那条路径每个单元格
     * 都要付一次 {@code byte[]} 分配、一次 {@code Object[]} 装箱、一次字符串比较和一次列表更新，
     * 而其中只有"解析"和"append"是必需的。</p>
     *
     * <p>现在把"列类型 → 怎么解码"在建流时算一次（{@link #kind} 是编译期常量，JIT 能把它变成
     * 直接分派），结构上也退化成"边解析边 append"，与 CSV 路径的 {@code CopyRowSink} 一致。</p>
     *
     * <p><b>两个"位置"必须分清</b>（{@code COPY t(CNT, ID, ...)} 这种重排列清单下二者不同）：</p>
     * <ul>
     *   <li>{@link #copyPos}：该表列在 <b>COPY 数据流</b>（即一行里的第几个字段）中的下标；</li>
     *   <li>{@link #tablePos}：该表列在 <b>目标表</b> 里的物理下标。</li>
     * </ul>
     * <p>写入必须按 {@code tablePos} 递增的顺序进行（Appender 要求按表的物理列序写满一行），
     * 而取值要用 {@code copyPos} 去数据流里找。改造前正是 {@code row[nPos]} 完成这件事，
     * 这里等价地保留。</p>
     */
    private static final class BinaryColumnDecoder {
        /** 该表列在 COPY 数据流中的字段下标；-1 表示该列不在 COPY 列清单里（走表默认值）。 */
        final int copyPos;
        /** 该表列在目标表里的物理下标，仅用于错误信息与调试。 */
        final int tablePos;
        /** 该表列名，只说错误信息时用。 */
        final String columnName;
        final String columnType;
        /** 见 TYPE_* 常量。 */
        final int kind;
        /** 定长类型期望的字节数；变长类型为 -1。 */
        final int expectedLength;

        private BinaryColumnDecoder(int copyPos, int tablePos, String columnName, String columnType,
                                    int kind, int expectedLength) {
            this.copyPos = copyPos;
            this.tablePos = tablePos;
            this.columnName = columnName;
            this.columnType = columnType;
            this.kind = kind;
            this.expectedLength = expectedLength;
        }
    }

    /**
     * 校验 BINARY 载荷里"每行的列数"是否与目标表列数一致。
     *
     * <p><b>为什么要在建流之前先扫一遍</b>：改造前是"先把整段载荷解析成 {@code List<Object[]>}，
     * 再逐行校验列数、再写入"，所以报"列数不符"时 <b>Appender 还没有写过任何一行</b>。
     * 若改成"边解析边写、遇到不符再报"，报错那一刻 Appender 里已经留了半行数据，
     * 随后统一的 {@code flush()} 会抛出第二个错误（"all columns must be appended to before calling
     * 'endRow'"），把真正的"列数不符"顶掉，客户端拿到的是看不懂的消息。</p>
     *
     * <p>因此解析与写入必须分成两步：先只读地校验列数（不碰 Appender），再进入写入。
     * 代价是多扫一遍行头（只读 2 字节并跳过列数据），远比"错误信息被顶掉"划算。</p>
     *
     * @throws BinaryFormatException 存在列数少于目标表列数的行（只报第一处，与改造前一致）
     */
    private static void validateBinaryColumnCounts(byte[] payload, int expectedColumnCount) {
        if (payload.length < 19) {
            throw new BinaryFormatException(binaryTruncatedMessage(
                    "header (19 bytes expected)", payload.length));
        }

        int pos = 19;
        long rowNumber = 1;
        while (true) {
            if (payload.length - pos < 2) {
                throw new BinaryFormatException(binaryTruncatedMessage(
                        "row " + rowNumber + " (missing column count)", payload.length - pos));
            }
            // int16 列数。注意这里用"无符号聚合 + 与 0xFFFF 比较"来识别 -1 终止符：
            // 若按签名读成 -1 再比较，读法必须一致，否则 0xFFFF 会被当成"列数 65535 的行"。
            int columnCount = ((payload[pos] & 0xFF) << 8) | (payload[pos + 1] & 0xFF);
            pos += 2;
            if (columnCount == 0xFFFF) {
                // 行尾结束标志：本次 COPY 数据流到此为止
                return;
            }
            if (columnCount < expectedColumnCount) {
                throw new BinaryFormatException(binaryColumnCountMismatchMessage(
                        rowNumber, columnCount, expectedColumnCount));
            }

            // 跳过这一行的全部列。注意：数据列数多于期望时维持既有宽松行为（多余的列被忽略），
            // 但这里必须把它们全部消费掉，否则后面的行头会被错位读取。
            for (int i = 0; i < columnCount; i++) {
                if (payload.length - pos < 4) {
                    throw new BinaryFormatException(binaryTruncatedMessage(
                            "row " + rowNumber + " column " + i + " (missing column length)",
                            payload.length - pos));
                }
                int length = ((payload[pos] & 0xFF) << 24) | ((payload[pos + 1] & 0xFF) << 16)
                        | ((payload[pos + 2] & 0xFF) << 8) | (payload[pos + 3] & 0xFF);
                pos += 4;
                if (length == -1) {
                    continue;
                }
                if (length < 0 || payload.length - pos < length) {
                    throw new BinaryFormatException(binaryTruncatedMessage(
                            "row " + rowNumber + " column " + i + " (need " + length + " bytes)",
                            payload.length - pos));
                }
                pos += length;
            }
            rowNumber++;
        }
    }

    /**
     * 把 PG BINARY COPY 载荷<b>一次遍历</b>写进 {@link DuckDBAppender}。
     *
     * <p>与 {@link CopyRowSink}（CSV/TEXT 路径）的分工一致：这里只管 BINARY，字段语义完全按
     * PG 的二进制格式解，不做任何文本解析。</p>
     *
     * <p><b>语义必须与改造前逐条对齐</b>：</p>
     * <ul>
     *   <li>只写 COPY 语句列清单里的列，表里其余列用 {@code appendDefault()}；</li>
     *   <li>列长度不符 / 列数不足 → 明确报错（不再出现裸越界异常）；</li>
     *   <li>数据列数"多于"期望时，多余的列被忽略（维持既有宽松行为）；</li>
     *   <li>TIMESTAMP 的 int64 是"自 <b>2000-01-01</b> 的微秒"（PG 的 {@code timestamp_send} 约定）。
     *       这里加上 {@link PostgresSQLUtil#PG_EPOCH_MICROS} 换成 epoch 微秒，
     *       用 {@code appendEpochMicros} 直接写入，不再为每格构造
     *       {@code Instant}/{@code ZoneId}/{@code LocalDateTime}。</li>
     * </ul>
     */
    private static final class BinaryRowSink {
        private final DuckDBAppender appender;
        private final BinaryColumnDecoder[] decoders;
        private final byte[] payload;
        private final int end;
        private int pos;
        /** 一行里各列的 (起点, 长度)；每行复用，字段数超了才扩容。 */
        private final int[] offsets = new int[16];
        private final int[] lengths = new int[16];
        /** VARCHAR / DECIMAL 的临时缓冲，只有真的遇到这两种列才会分配，并按需增长。 */
        private byte[] scratch;

        BinaryRowSink(DuckDBAppender appender,
                      List<Integer> columnMapPos,
                      List<String> columnTypes,
                      List<String> columnNames,
                      byte[] payload,
                      int expectedColumnCount) {
            this.appender = appender;
            this.payload = payload;
            this.expectedColumnCount = expectedColumnCount;
            if (payload.length < 19) {
                throw new BinaryFormatException(binaryTruncatedMessage(
                        "header (19 bytes expected)", payload.length));
            }
            this.pos = 19;
            this.end = payload.length;

            int tableColumns = columnMapPos.size();
            this.decoders = new BinaryColumnDecoder[tableColumns];
            for (int tablePos = 0; tablePos < tableColumns; tablePos++) {
                String columnType = columnTypes.get(tablePos);
                String columnName = columnNames.get(tablePos);
                int copyPos = columnMapPos.get(tablePos);
                if (copyPos == -1) {
                    // 列不在 COPY 清单里：不解析数据，写表默认值
                    decoders[tablePos] = new BinaryColumnDecoder(-1, tablePos, columnName, columnType,
                            TYPE_UNSUPPORTED, -1);
                    continue;
                }
                switch (columnTypeCode(columnType)) {
                    case TYPE_SMALLINT -> decoders[tablePos] =
                            new BinaryColumnDecoder(copyPos, tablePos, columnName, columnType, TYPE_SMALLINT, 2);
                    case TYPE_INTEGER -> decoders[tablePos] =
                            new BinaryColumnDecoder(copyPos, tablePos, columnName, columnType, TYPE_INTEGER, 4);
                    case TYPE_BIGINT -> decoders[tablePos] =
                            new BinaryColumnDecoder(copyPos, tablePos, columnName, columnType, TYPE_BIGINT, 8);
                    case TYPE_FLOAT -> decoders[tablePos] =
                            new BinaryColumnDecoder(copyPos, tablePos, columnName, columnType, TYPE_FLOAT, 4);
                    case TYPE_DOUBLE -> decoders[tablePos] =
                            new BinaryColumnDecoder(copyPos, tablePos, columnName, columnType, TYPE_DOUBLE, 8);
                    case TYPE_TIMESTAMP -> decoders[tablePos] =
                            new BinaryColumnDecoder(copyPos, tablePos, columnName, columnType, TYPE_TIMESTAMP, 8);
                    case TYPE_BOOLEAN -> decoders[tablePos] =
                            new BinaryColumnDecoder(copyPos, tablePos, columnName, columnType, TYPE_BOOLEAN, 1);
                    case TYPE_VARCHAR -> decoders[tablePos] =
                            new BinaryColumnDecoder(copyPos, tablePos, columnName, columnType, TYPE_VARCHAR, -1);
                    case TYPE_DECIMAL -> decoders[tablePos] =
                            new BinaryColumnDecoder(copyPos, tablePos, columnName, columnType, TYPE_DECIMAL, -1);
                    default -> decoders[tablePos] =
                            new BinaryColumnDecoder(copyPos, tablePos, columnName, columnType,
                                    TYPE_UNSUPPORTED, -1);
                }
            }
        }

        /**
         * 遍历整个载荷并写入。
         *
         * @return 写入的行数
         */
        long writeAll() throws SQLException {
            long rows = 0;
            while (true) {
                require(2, "row " + rows + " (missing column count)");
                short columnCount = getShort();
                if (columnCount == -1) {
                    // 行尾的 -1 结束标志：本次 COPY 数据流到此结束。
                    // 列数不足已由 validateBinaryColumnCounts 在建流前拦下，这里无需再判。
                    return rows;
                }
                if (columnCount < expectedColumnCount) {
                    // 防御：正常路径下 validateBinaryColumnCounts 已经拦下，走到这里说明两处判据不一致。
                    // 明确报错而不是让下面的字段下标裸越界 —— 报错文案与建流校验保持完全一致。
                    throw new BinaryFormatException(binaryColumnCountMismatchMessage(
                            rows + 1, columnCount, expectedColumnCount));
                }

                for (int i = 0; i < columnCount; i++) {
                    require(4, "row " + rows + " column " + i + " (missing column length)");
                    int length = getInt();
                    int start = pos;
                    if (length != -1) {
                        // 负数长度（-1 之外）与越界，改造前都落到"数据被截断"的报错上，这里同样处理
                        if (length < 0 || end - pos < length) {
                            throw new BinaryFormatException(binaryTruncatedMessage(
                                    "row " + rows + " column " + i + " (need " + length + " bytes)",
                                    end - pos));
                        }
                        pos += length;
                    }
                    // 先把这一行的每个字段的位置记下来（字段顺序 = COPY 列清单顺序），
                    // 然后按"表列顺序"取用 —— 两者在 COPY 重排列清单时并不相同。
                    if (i >= fieldStarts.length) {
                        fieldStarts = java.util.Arrays.copyOf(fieldStarts, fieldStarts.length * 2);
                        fieldLengths = java.util.Arrays.copyOf(fieldLengths, fieldLengths.length * 2);
                    }
                    fieldStarts[i] = start;
                    fieldLengths[i] = length;
                }
                fieldCount = columnCount;

                // 按表列顺序写满一行：不在 COPY 列清单里的列用表默认值
                appender.beginRow();
                for (BinaryColumnDecoder decoder : decoders) {
                    if (decoder.copyPos == -1) {
                        appender.appendDefault();
                        continue;
                    }
                    if (decoder.copyPos >= fieldCount) {
                        // 该字段缺失。列数不足已在建流前被 validateBinaryColumnCounts 拦下，
                        // 这里只可能是"表列多于数据列"被别的路径放过，按默认值兜底而不是裸越界。
                        appender.appendDefault();
                        continue;
                    }
                    appendColumn(decoder, fieldLengths[decoder.copyPos], fieldStarts[decoder.copyPos], rows);
                }
                appender.endRow();
                rows++;
            }
        }

        /** 一行的字段位置/长度；每行复用，字段数超了才扩容。下标 = COPY 数据流里的字段序号。 */
        private int[] fieldStarts = new int[16];
        private int[] fieldLengths = new int[16];
        /** 本行实际字段数。 */
        private int fieldCount;
        /**
         * COPY 数据流里每行应有的字段数（= COPY 列清单长度；没有列清单时等于目标表列数）。
         *
         * <p>注意它<b>不等于</b>目标表的列数：{@code COPY t(a,b)} 写一张 6 列的表时，
         * 数据行只有 2 个字段而 {@code decoders} 有 6 项。列数校验必须用本字段作为基准。</p>
         */
        private final int expectedColumnCount;

        /** 把一列追加到 Appender（NULL 由 {@code length == -1} 判定）。 */
        private void appendColumn(BinaryColumnDecoder decoder, int length, int start, long row)
                throws SQLException {
            if (length == -1) {
                appender.appendNull();
                return;
            }
            if (decoder.expectedLength >= 0 && length != decoder.expectedLength) {
                throw new BinaryFormatException(binaryMismatchMessage(
                        decoder.columnName, decoder.columnType, length,
                        decoder.expectedLength == 1 ? "1 byte" : decoder.expectedLength + " bytes"));
            }

            switch (decoder.kind) {
                case TYPE_SMALLINT -> appender.append((short) getShortAt(start));
                case TYPE_INTEGER -> appender.append(getIntAt(start));
                case TYPE_BIGINT -> appender.append(getLongAt(start));
                case TYPE_FLOAT -> appender.append(Float.intBitsToFloat(getIntAt(start)));
                case TYPE_DOUBLE -> appender.append(Double.longBitsToDouble(getLongAt(start)));
                case TYPE_BOOLEAN -> appender.append(payload[start] == 0x01);
                case TYPE_TIMESTAMP -> appender.appendEpochMicros(
                        getLongAt(start) + PostgresSQLUtil.PG_EPOCH_MICROS);
                case TYPE_VARCHAR -> appender.append(new String(payload, start, length, StandardCharsets.UTF_8));
                case TYPE_DECIMAL -> appender.append(PostgresSQLUtil.convertPGByteToBigDecimal(
                        slice(start, length)));
                default -> throw new BinaryUnsupportedTypeException(
                        "Binary Format error (column type not support) . " + decoder.columnType);
            }
        }

        /** VARCHAR / DECIMAL 需要的连续字节切片；行内复用同一个缓冲，按需增长。 */
        private byte[] slice(int start, int length) {
            if (scratch == null || scratch.length < length) {
                scratch = new byte[Math.max(16, length)];
            }
            System.arraycopy(payload, start, scratch, 0, length);
            return scratch;
        }

        private short getShort() {
            short v = getShortAt(pos);
            pos += 2;
            return v;
        }

        private int getInt() {
            int v = getIntAt(pos);
            pos += 4;
            return v;
        }

        // ---- 大端读取：PG 的二进制格式是网络字节序，与 ByteBuffer 默认序一致 ----

        private short getShortAt(int at) {
            return (short) (((payload[at] & 0xFF) << 8) | (payload[at + 1] & 0xFF));
        }

        private int getIntAt(int at) {
            return ((payload[at] & 0xFF) << 24)
                    | ((payload[at + 1] & 0xFF) << 16)
                    | ((payload[at + 2] & 0xFF) << 8)
                    | (payload[at + 3] & 0xFF);
        }

        private long getLongAt(int at) {
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (payload[at + i] & 0xFF);
            }
            return v;
        }

        private void require(int needed, String where) {
            if (end - pos < needed) {
                throw new BinaryFormatException(binaryTruncatedMessage(where, end - pos));
            }
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
                    // BINARY：先把"每行列数"整体校验一遍（只读、不碰 Appender），再单遍流式写入。
                    // 这样"列数不符"与"载荷不合规"都在 Appender 有机会写下任何一行之前就被拒绝，
                    // 与改造前"整段解析后校验"的数据影响完全一致（失败的 COPY 整体不生效）。
                    byte[] payload = session.copyLastRemained.toByteArray();
                    session.copyLastRemained.reset();

                    validateBinaryColumnCounts(payload, session.copyColumnCount);

                    BinaryRowSink sink = new BinaryRowSink(
                            session.copyTableAppender,
                            session.copyTableDbColumnMapPos,
                            session.copyTableDbColumnType,
                            session.copyTableDbColumnName,
                            payload,
                            session.copyColumnCount);
                    nCopiedRows = sink.writeAll();
                } // BINARY
            }
            catch (SQLException | RuntimeException ex)
            {
                // SQL异常: 用错误码作为返回码。
                // RuntimeException: 例如BINARY数据流不完整时抛出的 IllegalArgumentException
                // (缺少固定头/行数据被截断/缺少行尾结束标志)，以及CSV解析异常。这类异常必须在这里
                // 消化掉并回应客户端，否则会一路逃逸到Netty，客户端永远收不到响应
                // (表现为copyIn永久挂起，且该会话不可再用)。
                //
                // 这里只登记失败原因，不在这里回包也不回滚。
                // 统一的收尾（丢弃已写入的部分行 → 回 ErrorResponse/CommandComplete → ReadyForQuery）
                // 放在方法尾部，保证"告诉客户端失败"与"数据不落库"这两件事一起发生。
                copyFailed = true;
                if (ex instanceof SQLException sqlEx) {
                    // 数据库/Appender 报出的写入错误同样走统一的信息整理(不按错误类型特判)
                    errorCode = SqlStateMapper.fromException(sqlEx);
                    errorMessage = copyWriteErrorMessage(sqlEx.getMessage());
                } else if (ex instanceof BinaryUnsupportedTypeException) {
                    // 列类型在 BINARY 通道下不支持：与改造前一致用 0A000（feature_not_supported）
                    errorCode = "0A000";
                    errorMessage = ex.getMessage();
                } else if (ex instanceof BinaryFormatException) {
                    // BINARY 载荷不合规（长度不符、列数不足、流被截断）：22P04（bad_copy_file_format）
                    errorCode = "22P04";
                    errorMessage = ex.getMessage();
                } else {
                    // 其余解析类异常（CSV畸形等）：同样属于"COPY 数据格式不对"，
                    // 用 22P04，而不是 XX000（那意味着服务端自身出错）
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
