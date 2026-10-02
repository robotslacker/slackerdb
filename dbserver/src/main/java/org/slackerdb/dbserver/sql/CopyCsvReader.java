package org.slackerdb.dbserver.sql;

/**
 * PG COPY 的<b>文本类方言</b>字节级增量解析器：{@code FORMAT text} 与 {@code FORMAT csv}。
 *
 * <p>之所以自己写：</p>
 * <ul>
 *   <li>{@code commons-csv} 的对象模型会为每行生成 {@code CSVRecord} + {@code String[]}、为每个字段生成
 *       {@code String}，实测在 100 万行 × 3 列上解析耗时约 470~490 ms（端到端约 1.0~1.06 s），
 *       是本路径最主要的开销；</li>
 *   <li>它<b>不暴露"该字段是否加过引号"</b>，因而无法实现 PG 语义里"未加引号的空字段 = NULL、
 *       {@code ""} = 空字符串"的区分，也会让空数值字段变成 {@code NumberFormatException}。</li>
 * </ul>
 *
 * <p><b>两种方言的差别</b>（都由 {@link CopyDialect} 决定）：</p>
 * <table border="1">
 *   <caption>text 与 csv 的差别</caption>
 *   <tr><th></th><th>csv</th><th>text</th></tr>
 *   <tr><td>默认分隔符</td><td>{@code ,}</td><td>{@code \t}（制表符）</td></tr>
 *   <tr><td>引号</td><td>{@code "}，引号内可用 {@code ""}（或 ESCAPE 字符）转义，字段可含分隔符与换行</td>
 *       <td>没有引号概念</td></tr>
 *   <tr><td>转义</td><td>{@code ""} → {@code "}</td>
 *       <td>反斜杠：{@code \b \f \n \r \t \v \\ \.}、八进制 {@code \123}、十六进制 {@code \xHH}</td></tr>
 *   <tr><td>默认 NULL</td><td>未加引号的空字段</td><td>{@code \N}（原样比较，未反转义）</td></tr>
 * </table>
 *
 * <p>本实现的契约：</p>
 * <ul>
 *   <li>字段以 {@code (off, len, isNull)} <b>视图</b>交给 {@link FieldSink}，不复制字节；
 *       取值时用 {@link #buffer()} 配合 {@code new String(buf, off, len, charset)}。
 *       <b>NULL 已由本类判定完毕</b>（见上表），调用方不再需要自己推导。</li>
 *   <li><b>可增量</b>：一行被任意切分（例如 TCP 分片）都能续解，调用方只需把新到的字节 {@link #append} 进来
 *       —— 因此不必把整个 COPY 载荷留在内存里。</li>
 *   <li>记录分隔符为 {@code \n}、{@code \r\n} 或裸 {@code \r}；<b>完全空行被跳过</b>
 *       （否则载荷末尾的换行会变成一条假记录）。</li>
 *   <li><b>畸形输入显式报错</b>（{@link CsvFormatException}）：引号到结尾仍未闭合、闭引号后出现非分隔字符、
 *       转义序列不完整。静默接受会造成"看起来导入成功但内容错了"。</li>
 * </ul>
 */
public final class CopyCsvReader {

    /** 字段回调。{@code off/len} 指向 {@link #buffer()} 中的区间；{@code isNull} 表示该字段是 NULL。 */
    public interface FieldSink {
        void field(int off, int len, boolean isNull);

        void endRow();
    }

    /** 畸形的文本输入（引号/转义不合法）。 */
    public static final class CsvFormatException extends RuntimeException {
        public CsvFormatException(String message) {
            super(message);
        }
    }

    private static final int DEFAULT_CAPACITY = 64 * 1024;

    private byte[] buf;
    private int limit;
    private int scanPos;
    private boolean eof;

    // ---- 方言 ----
    private final boolean csvMode;
    private final byte delimiter;
    private final byte quote;
    private final byte escape;
    private final byte[] nullString;

    // ---- 当前行/字段的状态：跨 append 保留，使得一行被分片切断时可以续解 ----
    private boolean rowInProgress;
    private int fieldBegin;        // 字段起点（若带引号，指向开引号）
    private int valueStart;        // 字段值起点（带引号的字段已跳过开引号）
    private boolean inQuotes;
    private boolean fieldQuoted;
    private boolean quoteClosed;   // 闭引号已消费：此后只允许分隔符/行尾
    private boolean fieldHasEscape; // 出现过转义序列，需要就地展开
    private long completedRows;    // 已完成的记录数，仅用于报错定位

    public CopyCsvReader() {
        this(DEFAULT_CAPACITY, null);
    }

    public CopyCsvReader(int capacity) {
        this(capacity, null);
    }

    public CopyCsvReader(int capacity, CopyDialect dialect) {
        this.buf = new byte[Math.max(64, capacity)];
        CopyDialect effective = (dialect == null) ? CopyDialect.defaultCsv() : dialect;
        this.csvMode = effective.csv;
        this.delimiter = (byte) effective.delimiter;
        this.quote = (byte) effective.quote;
        this.escape = (byte) effective.escape;
        this.nullString = effective.nullString.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 接管一个字节数组作为解析输入（不复制）。调用方此后不得再使用该数组。 */
    public CopyCsvReader(byte[] data, int length) {
        this(data, length, null);
    }

    /** 接管一个字节数组作为解析输入（不复制），按给定方言解析。 */
    public CopyCsvReader(byte[] data, int length, CopyDialect dialect) {
        this(0, dialect);
        this.buf = data;
        this.limit = length;
    }

    public byte[] buffer() {
        return buf;
    }

    /** 追加一段新收到的字节。 */
    public void append(byte[] data, int off, int len) {
        if (len <= 0) {
            return;
        }
        if (limit + len > buf.length) {
            // 行内不能压缩（会破坏字段偏移），只能在行间压缩；仍不够就扩容
            if (!rowInProgress && scanPos > 0) {
                System.arraycopy(buf, scanPos, buf, 0, limit - scanPos);
                limit -= scanPos;
                scanPos = 0;
            }
            if (limit + len > buf.length) {
                int newCap = Math.max(buf.length * 2, limit + len);
                byte[] bigger = new byte[newCap];
                System.arraycopy(buf, 0, bigger, 0, limit);
                buf = bigger;
            }
        }
        System.arraycopy(data, off, buf, limit, len);
        limit += len;
    }

    /** 标记输入结束（客户端已发 CopyDone）。 */
    public void markEof() {
        eof = true;
    }

    /** 是否还有尚未消费的字节。 */
    public int remainingBytes() {
        return limit - scanPos;
    }

    /**
     * 解析下一条记录。
     *
     * @return {@code true} 表示已产出一条完整记录（已调用 {@link FieldSink#endRow()}）；
     *         {@code false} 表示输入不足（需要更多数据）或已经结束。
     */
    public boolean nextRow(FieldSink sink) {
        while (true) {
            if (!rowInProgress) {
                if (!skipEmptyLines()) {
                    return false;
                }
                startRow();
            }

            // 扫描当前字段，直到遇到分隔符或行尾
            while (scanPos < limit) {
                byte c = buf[scanPos];

                if (inQuotes) {
                    // 引号优先，且与改造前逐字节等价：引号后面还是引号 → 一个转义引号；
                    // 否则就是闭引号。（默认 ESCAPE 就是引号本身，这条路覆盖 PG 的 `""`。）
                    if (c == quote) {
                        if (scanPos + 1 >= limit) {
                            if (!eof) {
                                return false;   // 可能是 "" 的前半，等更多数据
                            }
                            inQuotes = false;
                            quoteClosed = true;
                            scanPos++;
                            continue;
                        }
                        if (buf[scanPos + 1] == quote) {
                            fieldHasEscape = true;
                            scanPos += 2;
                            continue;
                        }
                        inQuotes = false;
                        quoteClosed = true;
                        scanPos++;
                        continue;
                    }
                    // ESCAPE 被指定成别的字符时，它用来转义引号或它自己
                    if (escape != quote && c == escape) {
                        if (scanPos + 1 >= limit) {
                            if (!eof) {
                                return false;   // 转义序列被分片切断
                            }
                            scanPos++;
                            continue;
                        }
                        fieldHasEscape = true;
                        scanPos += 2;
                        continue;
                    }
                    scanPos++;
                    continue;
                }

                if (!csvMode && c == '\\') {
                    // text 方言：反斜杠转义。被转义的字符可能是分隔符甚至换行，
                    // 因此这里必须连同下一个字节一起吃掉，不能留给后面的分支。
                    if (scanPos + 1 >= limit) {
                        if (!eof) {
                            return false;   // 转义序列被分片切断
                        }
                        throw new CsvFormatException(
                                "TEXT 格式错误：第 " + (completedRows + 1)
                                        + " 行以未完成的反斜杠转义结尾（输入已结束）");
                    }
                    fieldHasEscape = true;
                    scanPos += 2;
                    continue;
                }

                if (csvMode && c == quote && scanPos == fieldBegin && !fieldQuoted && !quoteClosed) {
                    inQuotes = true;
                    fieldQuoted = true;
                    scanPos++;
                    valueStart = scanPos;
                    continue;
                }

                if (c == delimiter) {
                    emitField(sink);
                    scanPos++;
                    fieldBegin = scanPos;
                    valueStart = scanPos;
                    fieldQuoted = false;
                    quoteClosed = false;
                    fieldHasEscape = false;
                    continue;
                }

                if (c == '\n') {
                    emitField(sink);
                    scanPos++;
                    return finishRow(sink);
                }

                if (c == '\r') {
                    if (scanPos + 1 >= limit && !eof) {
                        return false;   // 可能是 \r\n 的前半
                    }
                    emitField(sink);
                    if (scanPos + 1 < limit && buf[scanPos + 1] == '\n') {
                        scanPos += 2;
                    } else {
                        scanPos++;
                    }
                    return finishRow(sink);
                }

                if (quoteClosed) {
                    // 闭引号之后只允许分隔符或行尾（PG 同样拒绝这种输入）
                    throw new CsvFormatException(
                            "CSV 格式错误：第 " + (completedRows + 1) + " 行闭引号之后出现了非法字符 '"
                                    + (char) (c & 0xFF) + "'");
                }

                scanPos++;
            }

            // 数据用尽
            if (!eof) {
                return false;
            }
            if (inQuotes) {
                throw new CsvFormatException(
                        "CSV 格式错误：第 " + (completedRows + 1) + " 行的引号没有闭合（输入已结束）");
            }
            if (!rowInProgress) {
                return false;
            }
            // 最后一行没有以换行结尾
            emitField(sink);
            return finishRow(sink);
        }
    }

    /** 跳过空行；返回 false 表示需要更多数据（或输入已结束）。 */
    private boolean skipEmptyLines() {
        while (true) {
            if (scanPos >= limit) {
                return false;
            }
            byte c = buf[scanPos];
            if (c == '\n') {
                scanPos++;
                continue;
            }
            if (c == '\r') {
                if (scanPos + 1 >= limit) {
                    if (!eof) {
                        return false;
                    }
                    scanPos++;
                    continue;
                }
                scanPos += (buf[scanPos + 1] == '\n') ? 2 : 1;
                continue;
            }
            return true;
        }
    }

    private void startRow() {
        rowInProgress = true;
        fieldBegin = scanPos;
        valueStart = scanPos;
        inQuotes = false;
        fieldQuoted = false;
        quoteClosed = false;
        fieldHasEscape = false;
    }

    private boolean finishRow(FieldSink sink) {
        rowInProgress = false;
        completedRows++;
        sink.endRow();
        return true;
    }

    /**
     * 产出当前字段视图。
     *
     * <p>引号字段去掉首尾引号并展开转义；text 方言的字段展开反斜杠转义。
     * 两种情况都是"原地收缩"（转义序列变短），不需要额外缓冲。</p>
     */
    private void emitField(FieldSink sink) {
        if (!fieldQuoted) {
            // 未加引号：字段范围是 [fieldBegin, scanPos)。
            // NULL 判定用**原始字节**：text 方言的 \N 就是原始输入里的两个字符，
            // csv 方言则拿未加引号的字段与 null 串比较（默认 null 串为空 → 空字段即 NULL）。
            int len = scanPos - fieldBegin;
            boolean isNull = rawEqualsNull(fieldBegin, len);
            if (!csvMode && fieldHasEscape && !isNull) {
                sink.field(fieldBegin, unescapeText(fieldBegin, scanPos) - fieldBegin, false);
                return;
            }
            sink.field(fieldBegin, len, isNull);
            return;
        }

        int start = valueStart;
        int end = quoteClosed ? Math.max(start, scanPos - 1) : scanPos;
        if (fieldHasEscape) {
            sink.field(start, expandCsvEscape(start, end) - start, false);
        } else {
            sink.field(start, Math.max(0, end - start), false);
        }
    }

    /** 未加引号字段是否等于 NULL 串。 */
    private boolean rawEqualsNull(int off, int len) {
        if (len != nullString.length) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (buf[off + i] != nullString[i]) {
                return false;
            }
        }
        return true;
    }

    /** CSV 转义展开：{@code escape+quote} / {@code escape+escape} → 对应单个字符。返回新的结束位置。 */
    private int expandCsvEscape(int start, int end) {
        int w = start;
        for (int r = start; r < end; r++) {
            byte c = buf[r];
            if (c == escape && r + 1 < end && (buf[r + 1] == quote || buf[r + 1] == escape)) {
                buf[w++] = buf[++r];
            } else {
                buf[w++] = c;
            }
        }
        return w;
    }

    /**
     * text 方言的反斜杠转义展开，返回新的结束位置。
     *
     * <p>支持的序列与 PG 一致：{@code \b \f \n \r \t \v \\}、八进制 {@code \123}（1~3 位）、
     * 十六进制 {@code \xHH}（1~2 位）；其他字符按字面处理（例如 {@code \.} → {@code .}）。</p>
     */
    private int unescapeText(int start, int end) {
        int w = start;
        for (int r = start; r < end; r++) {
            byte c = buf[r];
            if (c != '\\' || r + 1 >= end) {
                buf[w++] = c;
                continue;
            }
            byte n = buf[++r];
            switch (n) {
                case 'b' -> buf[w++] = '\b';
                case 'f' -> buf[w++] = '\f';
                case 'n' -> buf[w++] = '\n';
                case 'r' -> buf[w++] = '\r';
                case 't' -> buf[w++] = '\t';
                case 'v' -> buf[w++] = 0x0B;
                case '\\' -> buf[w++] = '\\';
                case 'x' -> {
                    int value = 0;
                    int digits = 0;
                    while (digits < 2 && r + 1 < end && isHexDigit(buf[r + 1])) {
                        value = value * 16 + hexValue(buf[++r]);
                        digits++;
                    }
                    if (digits == 0) {
                        throw new CsvFormatException(
                                "TEXT 格式错误：第 " + (completedRows + 1) + " 行的 \\x 后面缺少十六进制数字");
                    }
                    buf[w++] = (byte) value;
                }
                default -> {
                    if (n >= '0' && n <= '7') {
                        int value = n - '0';
                        int digits = 1;
                        while (digits < 3 && r + 1 < end && buf[r + 1] >= '0' && buf[r + 1] <= '7') {
                            value = value * 8 + (buf[++r] - '0');
                            digits++;
                        }
                        buf[w++] = (byte) value;
                    } else {
                        // PG：反斜杠后面的其他字符按字面值处理（\. → .）
                        buf[w++] = n;
                    }
                }
            }
        }
        return w;
    }

    private static boolean isHexDigit(byte c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static int hexValue(byte c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        return c - 'A' + 10;
    }

    // ---------------------------------------------------------------- 字节区间数值解析
    //
    // 直接从字节解析可以省掉"为每个数值单元格生成一个 String"的分配。语义与
    // Integer/Long.parseShort 等保持一致：允许前导 '+'/'-'、不允许空白、溢出与非法字符都抛
    // NumberFormatException。（空字段在调用方已按 NULL 处理，不会走到这里。）

    public static int parseInt(byte[] b, int off, int len) {
        if (len <= 0) {
            throw numberFormat(b, off, len);
        }
        int i = off;
        int end = off + len;
        boolean negative = false;
        byte first = b[i];
        if (first == '-') {
            negative = true;
            i++;
        } else if (first == '+') {
            i++;
        }
        if (i == end) {
            throw numberFormat(b, off, len);
        }
        int limit = negative ? Integer.MIN_VALUE : -Integer.MAX_VALUE;
        int multmin = limit / 10;
        int result = 0;
        while (i < end) {
            int digit = b[i++] - '0';
            if (digit < 0 || digit > 9 || result < multmin) {
                throw numberFormat(b, off, len);
            }
            result *= 10;
            if (result < limit + digit) {
                throw numberFormat(b, off, len);
            }
            result -= digit;
        }
        return negative ? result : -result;
    }

    public static long parseLong(byte[] b, int off, int len) {
        if (len <= 0) {
            throw numberFormat(b, off, len);
        }
        int i = off;
        int end = off + len;
        boolean negative = false;
        byte first = b[i];
        if (first == '-') {
            negative = true;
            i++;
        } else if (first == '+') {
            i++;
        }
        if (i == end) {
            throw numberFormat(b, off, len);
        }
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long multmin = limit / 10;
        long result = 0;
        while (i < end) {
            int digit = b[i++] - '0';
            if (digit < 0 || digit > 9 || result < multmin) {
                throw numberFormat(b, off, len);
            }
            result *= 10;
            if (result < limit + digit) {
                throw numberFormat(b, off, len);
            }
            result -= digit;
        }
        return negative ? result : -result;
    }

    /** 与 {@code Short.parseShort} 一致：超出 short 范围同样抛 NumberFormatException。 */
    public static short parseShort(byte[] b, int off, int len) {
        int v = parseInt(b, off, len);
        if (v < Short.MIN_VALUE || v > Short.MAX_VALUE) {
            throw numberFormat(b, off, len);
        }
        return (short) v;
    }

    private static NumberFormatException numberFormat(byte[] b, int off, int len) {
        return new NumberFormatException("For input string: \""
                + new String(b, off, Math.max(0, len), java.nio.charset.StandardCharsets.US_ASCII) + "\"");
    }
}
