package org.slackerdb.dbserver.sql;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/**
 * {@code COPY ... TO STDOUT} 的行编码器：把 {@link ResultSet}（或 {@link CopyOutChunkReader}）
 * 的一行编码成 COPY 文本行。
 *
 * <p>本类是 {@link CopyCsvReader} 的<b>逆运算</b>：凡是这里写出去的字节，都要能被
 * {@code COPY ... FROM STDIN} 原样读回来（测试里用真实往返钉死这一点）。因此转义规则
 * 严格对齐读取侧的实现，而不是照抄 PG 文档的直觉：</p>
 *
 * <ul>
 *   <li><b>TEXT</b>：转义 {@code \} {@code \n} {@code \r} {@code \t}，以及<b>分隔符本身</b>；
 *       NULL 写成 {@link CopyDialect#nullString}（默认 {@code \N}）。
 *       读取侧在 text 方言下对 {@code \} + 任意字符都做展开（{@code CopyCsvReader} 第 206-220、
 *       393-430 行），所以对分隔符转义是必需的，否则含分隔符的值会被切成两列。</li>
 *   <li><b>CSV</b>：字段含分隔符 / 引号 / {@code \r} / {@code \n} 时加引号，引号按
 *       {@code escape} + {@code quote} 写法转义（默认即 PG 的引号翻倍）；
 *       NULL 写成未加引号的 {@code nullString}（默认空字段）。
 *       <b>空字符串必须加引号</b>：读取侧用"未加引号且等于 null 串 ⇒ NULL"判定
 *       （{@code CopyCsvReader} 第 336-371 行），不加引号的空字段会被当成 NULL，
 *       空串与 NULL 就区分不开了。</li>
 * </ul>
 *
 * <p>列值的文本形态用 {@link ResultSet#getString(int)}；chunk 路径由
 * {@link CopyOutChunkReader#getString(int, long)} 返回逐字符一致的字符串，
 * 两条路径只在"怎么取值"上不同。唯一例外是二进制列
 * （BLOB/BYTEA）：PG 的 bytea 文本表示是 {@code \x} + 十六进制，而 {@code getString()}
 * 对 BLOB 会走驱动自己的编码，因此这里按字节手工生成 —— 与
 * {@code RowEncoder} 输出 bytea 的方式保持一致。</p>
 *
 * <p><b>BINARY 格式不在本类</b>：{@link CopyDialect} 能解析出 {@code FORMAT binary}，
 * 但二进制 COPY OUT 没有分隔符/转义，每个值要按 PostgreSQL 各类型的 {@code *send}
 * 函数编码 —— 见 {@link CopyOutBinaryEncoder}。本类只负责 TEXT / CSV。</p>
 */
public final class CopyOutEncoder {

    /** 二进制列在 text/CSV 下的 PG 文本表示前缀。 */
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private final ResultSetMetaData metaData;
    private final CopyDialect dialect;
    private final int columnCount;
    /** 预先算好的二进制列下标（1-based），避免逐行做类型名判断。 */
    private final boolean[] binaryColumn;

    public CopyOutEncoder(ResultSetMetaData pMetaData, CopyDialect pDialect) throws SQLException {
        this.metaData = pMetaData;
        this.dialect = pDialect;
        this.columnCount = pMetaData.getColumnCount();
        this.binaryColumn = new boolean[columnCount + 1];
        for (int i = 1; i <= columnCount; i++) {
            binaryColumn[i] = isBinaryType(pMetaData.getColumnTypeName(i));
        }
    }

    public int getColumnCount() {
        return columnCount;
    }

    private static boolean isBinaryType(String typeName) {
        if (typeName == null) {
            return false;
        }
        return switch (typeName.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "BLOB", "BYTEA", "BINARY", "VARBINARY", "LONGVARBINARY" -> true;
            default -> false;
        };
    }

    /**
     * 写入 CSV 的表头行（仅当 {@code HEADER} 生效且为 CSV 格式时由调用方触发）。
     *
     * <p>PG 只允许 CSV 用 HEADER；文本格式带 HEADER 会被 PG 拒绝，这里同样交给
     * {@link CopyDialect} 把关。</p>
     */
    public void encodeHeader(ByteArrayOutputStream out) throws SQLException {
        for (int i = 1; i <= columnCount; i++) {
            if (i > 1) {
                out.write(dialect.delimiter);
            }
            writeCsvField(out, metaData.getColumnName(i));
        }
        out.write('\n');
    }

    /** 把结果集当前行编码进 {@code out}（含行尾换行）。 */
    public void encodeRow(ResultSet rs, ByteArrayOutputStream out) throws SQLException {
        for (int i = 1; i <= columnCount; i++) {
            if (i > 1) {
                out.write(dialect.delimiter);
            }
            if (binaryColumn[i]) {
                byte[] value = rs.getBytes(i);
                if (value == null) {
                    writeNull(out);
                } else {
                    writeBinaryAsText(out, value);
                }
                continue;
            }
            writeValue(rs.getString(i), out);
        }
        out.write('\n');
    }

    /**
     * 把 chunk 读取器当前块的第 {@code row} 行编码进 {@code out}（含行尾换行）。
     *
     * <p>与 {@link #encodeRow} 共用同一套 NULL/CSV/text 转义，保证两条路径的字节形态
     * 完全一致（chunk 路径只改变"怎么取值"，不改变"怎么写值"）。</p>
     */
    public void encodeChunkRow(CopyOutChunkReader reader, long row, ByteArrayOutputStream out) {
        for (int i = 1; i <= columnCount; i++) {
            if (i > 1) {
                out.write(dialect.delimiter);
            }
            // chunk API 的列下标是 0 基，这里的列号是 1 基
            writeValue(reader.getString(i - 1, row), out);
        }
        out.write('\n');
    }

    private void writeValue(String value, ByteArrayOutputStream out) {
        if (value == null) {
            writeNull(out);
        } else if (dialect.csv) {
            writeCsvField(out, value);
        } else {
            writeTextField(out, value);
        }
    }

    private void writeNull(ByteArrayOutputStream out) {
        // NULL 标记必须**原样写出、不能走转义**：
        // text 方言的 null 串是 `\N`，读取侧靠"未加引号且等于 null 串 ⇒ NULL"判定。
        // 若把它交给 writeTextField 转义，会写成 `\\N`，读回来就变成**字面量** `\N`（值与 NULL 互换）。
        out.writeBytes(dialect.nullString.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * text 方言字段：转义反斜杠、制表符、换行、回车，以及分隔符。
     *
     * <p><b>反斜杠必须无条件翻倍</b>（与 PG 的输出一致）。曾经试过"只在必要时翻倍"
     * （后一个字符是转义字符时才翻），结果是数据损坏，实测两种情形都会错：</p>
     * <ul>
     *   <li>库里存 {@code back\\slash}（两个反斜杠）时只翻第一个 → 导出 3 个反斜杠
     *       → 读回来只剩 1 个（丢数据）；</li>
     *   <li>库里存<b>字面量</b> {@code \N}（反斜杠 + N）时后一个字符是普通的 {@code N}，
     *       于是原样导出 → 读取侧把未加引号的 {@code \N} 当成 NULL（值变成 NULL）。
     *       而"值恰好是 null 串"和"NULL"在 text 方言里本来就只能靠转义区分。</li>
     * </ul>
     * 无条件翻倍的代价只是 {@code \s} 这类值导出后不似 PG 那样"最小化"，
     * 但它是唯一能保证 <b>任意值往返无损</b>的形态 —— 而往返无损才是导出功能的底线。</p>
     */
    private void writeTextField(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            char c = (char) (b & 0xFF);
            switch (c) {
                case '\\' -> { out.write('\\'); out.write('\\'); }
                case '\n' -> { out.write('\\'); out.write('n'); }
                case '\r' -> { out.write('\\'); out.write('r'); }
                case '\t' -> { out.write('\\'); out.write('t'); }
                default -> {
                    if (c == dialect.delimiter) {
                        // 分隔符必须转义，否则一行会被解析成多列
                        out.write('\\');
                    }
                    out.write(b);
                }
            }
        }
    }

    /** CSV 字段：按需加引号，引号用 {@code escape + quote} 写法转义。 */
    private void writeCsvField(ByteArrayOutputStream out, String value) {
        boolean needsQuote = value.isEmpty()
                || value.indexOf(dialect.delimiter) >= 0
                || value.indexOf(dialect.quote) >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuote) {
            out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
            return;
        }

        out.write(dialect.quote);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == dialect.quote || c == dialect.escape) {
                // 读取侧的 expandCsvEscape 把 escape+quote / escape+escape 还原成单字符，
                // 因此这两个字符都要加 escape 前缀。默认 escape == quote，即 PG 的引号翻倍。
                out.write(dialect.escape);
            }
            out.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
        }
        out.write(dialect.quote);
    }

    /** 二进制列的 PG 文本表示：{@code \x} + 小写十六进制。 */
    private void writeBinaryAsText(ByteArrayOutputStream out, byte[] value) {
        out.write('\\');
        out.write('x');
        for (byte b : value) {
            out.write(HEX_DIGITS[(b >> 4) & 0x0F]);
            out.write(HEX_DIGITS[b & 0x0F]);
        }
    }
}
