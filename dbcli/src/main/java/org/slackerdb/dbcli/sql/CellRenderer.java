package org.slackerdb.dbcli.sql;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * 单元格值的**唯一**渲染规则：把一个 JDBC 返回的 {@code Object} 变成稳定、可比的文本。
 *
 * <h2>为什么要归一</h2>
 * <p>结果输出既要给人看，也要当 golden 比对（{@code --ref}）。而 {@code String.valueOf} 的默认行为
 * 依赖 JVM 与类型实现：{@code BigDecimal} 可能变成 {@code 1E+10}、{@code Timestamp} 的文本形式随实现变化、
 * {@code byte[]} 打印成 {@code [B@1a2b}。这些都不稳定，等于让 golden 自己抖。因此这里按类型定死格式。</p>
 *
 * <h2>规则（逐字节冻结）</h2>
 * <table border="1">
 *   <caption>类型 → 文本</caption>
 *   <tr><th>类型</th><th>渲染</th></tr>
 *   <tr><td>{@code BigDecimal}</td><td>{@code toPlainString()}（永不科学计数法）</td></tr>
 *   <tr><td>{@code BigInteger}</td><td>{@code toString()}</td></tr>
 *   <tr><td>{@code Double} / {@code Float}</td><td>{@code BigDecimal.valueOf(...)} 再 {@code toPlainString()}
 *       （避免 {@code 1.0E10}；浮点误差本身不在这里处理）</td></tr>
 *   <tr><td>{@code java.sql.Timestamp} / {@code LocalDateTime}</td>
 *       <td>{@code yyyy-MM-dd HH:mm:ss}，有毫秒时加 {@code .SSS}（**不做时区转换**：值本身无时区）</td></tr>
 *   <tr><td>{@code Date} / {@code LocalDate}</td><td>{@code yyyy-MM-dd}</td></tr>
 *   <tr><td>{@code Time} / {@code LocalTime}</td><td>{@code HH:mm:ss}</td></tr>
 *   <tr><td>{@code Instant} / {@code OffsetDateTime} / {@code ZonedDateTime}</td>
 *       <td>归一为 UTC：{@code yyyy-MM-dd HH:mm:ss[.SSS]Z}（跨机器稳定）</td></tr>
 *   <tr><td>{@code byte[]}</td><td>{@code 0x<hex>(N bytes)}</td></tr>
 *   <tr><td>其它</td><td>{@code String.valueOf}（{@code Date} 之外的类型没有更好的通用规则）</td></tr>
 * </table>
 *
 * <p>NULL 不在这里处理：由调用方用 {@code --null} 的文本替换（表格与竖排都要能配置）。</p>
 */
public final class CellRenderer {

    /** 本地时间（无时区）格式。 */
    private static final DateTimeFormatter LOCAL_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 带时区的值统一到 UTC，并带 {@code Z} 标记。 */
    private static final DateTimeFormatter UTC_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final DateTimeFormatter LOCAL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private CellRenderer() {
    }

    /**
     * 渲染一个非 null 值；调用方负责 NULL。
     *
     * @param value JDBC 返回的对象
     * @return 稳定的文本形式
     */
    public static String render(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof BigInteger integer) {
            return integer.toString();
        }
        if (value instanceof Double number) {
            // NaN / ±Infinity 没有十进制形式，BigDecimal.valueOf 会抛 NumberFormatException。
            // 它们又是合法的 DOUBLE 取值，因此按 Java 的固定写法渲染（NaN / Infinity / -Infinity）：
            // 一个数据值不能把整次运行变成"未捕获异常 + 255"。
            return Double.isFinite(number)
                    ? BigDecimal.valueOf(number).toPlainString() : number.toString();
        }
        if (value instanceof Float number) {
            return Float.isFinite(number)
                    ? new BigDecimal(Float.toString(number)).toPlainString() : number.toString();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return localTimestamp(timestamp.toLocalDateTime());
        }
        if (value instanceof LocalDateTime dateTime) {
            return localTimestamp(dateTime);
        }
        if (value instanceof java.sql.Date date) {
            return LOCAL_DATE.format(date.toLocalDate());
        }
        if (value instanceof LocalDate date) {
            return LOCAL_DATE.format(date);
        }
        if (value instanceof java.sql.Time time) {
            return LOCAL_TIME.format(time.toLocalTime());
        }
        if (value instanceof LocalTime time) {
            return LOCAL_TIME.format(time);
        }
        if (value instanceof Instant instant) {
            return utcTimestamp(instant);
        }
        if (value instanceof OffsetDateTime dateTime) {
            return utcTimestamp(dateTime.toInstant());
        }
        if (value instanceof ZonedDateTime dateTime) {
            return utcTimestamp(dateTime.toInstant());
        }
        if (value instanceof byte[] bytes) {
            return "0x" + HexFormat.of().formatHex(bytes).toUpperCase(java.util.Locale.ROOT)
                    + "(" + bytes.length + " bytes)";
        }
        // LOB / ARRAY / SQLXML / ROWID：驱动允许把 getObject 的结果给成这些接口的实现类，
        // 而它们的 toString() 是身份哈希（每次运行都不同 → golden 抖动）。这里按内容渲染。
        if (value instanceof java.sql.Clob clob) {
            return clobText(clob);
        }
        if (value instanceof java.sql.Blob blob) {
            return blobText(blob);
        }
        if (value instanceof java.sql.Array array) {
            return arrayText(array);
        }
        if (value instanceof java.sql.SQLXML xml) {
            return sqlxmlText(xml);
        }
        if (value instanceof java.sql.RowId rowId) {
            try {
                return "0x" + HexFormat.of().formatHex(rowId.getBytes())
                        .toUpperCase(java.util.Locale.ROOT);
            } catch (RuntimeException unreadable) {
                return unreadable("ROWID", unreadable);
            }
        }
        return String.valueOf(value);
    }

    /** CLOB 内容按文本渲染（读不到时给出可读占位，而不是身份哈希）。 */
    private static String clobText(java.sql.Clob clob) {
        try {
            long length = clob.length();
            // JDBC 的 getSubString 用 int 下标：超长 CLOB 只取前 Integer.MAX_VALUE 个字符
            int size = (int) Math.min(length, Integer.MAX_VALUE);
            return clob.getSubString(1, size);
        } catch (java.sql.SQLException unreadable) {
            return unreadable("CLOB", unreadable);
        } finally {
            freeQuietly(clob);
        }
    }

    /** BLOB 内容按字节渲染（与 byte[] 同一形态：`0x…(N bytes)`）。 */
    private static String blobText(java.sql.Blob blob) {
        try {
            long length = blob.length();
            int size = (int) Math.min(length, Integer.MAX_VALUE);
            byte[] bytes = blob.getBytes(1, size);
            return "0x" + HexFormat.of().formatHex(bytes).toUpperCase(java.util.Locale.ROOT)
                    + "(" + bytes.length + " bytes)";
        } catch (java.sql.SQLException unreadable) {
            return unreadable("BLOB", unreadable);
        } finally {
            freeQuietly(blob);
        }
    }

    /** SQL ARRAY 按 `{a,b}` 渲染（元素各自走本类规则）。 */
    private static String arrayText(java.sql.Array array) {
        try {
            Object content = array.getArray();
            if (content instanceof Object[] items) {
                StringBuilder builder = new StringBuilder("{");
                for (int index = 0; index < items.length; index++) {
                    if (index > 0) {
                        builder.append(',');
                    }
                    builder.append(render(items[index]));
                }
                return builder.append('}').toString();
            }
            return String.valueOf(content);
        } catch (java.sql.SQLException unreadable) {
            return unreadable("ARRAY", unreadable);
        } finally {
            freeQuietly(array);
        }
    }

    /** SQLXML 按字符串渲染。 */
    private static String sqlxmlText(java.sql.SQLXML xml) {
        try {
            return xml.getString();
        } catch (java.sql.SQLException unreadable) {
            return unreadable("SQLXML", unreadable);
        } finally {
            freeQuietly(xml);
        }
    }

    /** 读不出来的 LOB：给出可读占位（保留类型与原因，便于排查；绝不打身份哈希）。 */
    private static String unreadable(String type, Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return "<unreadable " + type
                + (message == null || message.isBlank() ? "" : ": " + message) + ">";
    }

    /** 尽力释放 LOB/ARRAY（驱动侧临时资源；不支持 free 的实现会抛 SQLException）。 */
    private static void freeQuietly(java.sql.Clob clob) {
        try {
            clob.free();
        } catch (java.sql.SQLException | RuntimeException ignored) {
            // free 是"尽力"：失败不影响已经渲染出来的文本
        }
    }

    private static void freeQuietly(java.sql.Blob blob) {
        try {
            blob.free();
        } catch (java.sql.SQLException | RuntimeException ignored) {
            // 同上
        }
    }

    private static void freeQuietly(java.sql.Array array) {
        try {
            array.free();
        } catch (java.sql.SQLException | RuntimeException ignored) {
            // 同上
        }
    }

    private static void freeQuietly(java.sql.SQLXML xml) {
        try {
            xml.free();
        } catch (java.sql.SQLException | RuntimeException ignored) {
            // 同上
        }
    }

    /**
     * 本地时间：秒级；有毫秒时补 {@code .SSS}。
     *
     * <p>只保留毫秒精度是刻意的：纳秒部分在 JDBC 往返里本来就可能被截断，把它打出来会让
     * 同一份数据在两次运行里不同。</p>
     */
    private static String localTimestamp(LocalDateTime value) {
        String text = LOCAL_TIMESTAMP.format(value);
        return value.getNano() == 0 ? text : text + "." + millis(value.getNano());
    }

    /** UTC 时间：与本地时间同形，末尾加 {@code Z}。 */
    private static String utcTimestamp(Instant value) {
        LocalDateTime utc = LocalDateTime.ofInstant(value, java.time.ZoneOffset.UTC);
        String text = UTC_TIMESTAMP.format(utc);
        return utc.getNano() == 0 ? text + "Z" : text + "." + millis(utc.getNano()) + "Z";
    }

    /** 纳秒 → 三位毫秒文本（四舍五入到毫秒）。 */
    private static String millis(int nano) {
        int value = (nano + 500_000) / 1_000_000;
        if (value >= 1000) {
            value = 999;
        }
        return String.format(java.util.Locale.ROOT, "%03d", value);
    }

    /**
     * 按显示宽度截断（超出时以 {@code ...} 结尾）；{@code maxWidth <= 0} 表示不截断。
     *
     * <p>宽度按**显示列**算（CJK 算两列），与表格对齐用的是同一把尺子；截断后的总宽度（含
     * {@code ...}）不超过 {@code maxWidth}。截断只用于"给人看"的格式（table / vertical），
     * CSV 导出不截断。</p>
     */
    public static String truncate(String text, int maxWidth) {
        if (text == null || maxWidth <= 0) {
            return text;
        }
        if (SqlTableRenderer.displayWidth(text) <= maxWidth) {
            return text;
        }
        int budget = maxWidth - TRUNCATION_MARKER.length();
        if (budget <= 0) {
            return ".".repeat(maxWidth);
        }
        StringBuilder builder = new StringBuilder();
        int used = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            int width = SqlTableRenderer.displayWidth(new String(Character.toChars(codePoint)));
            if (used + width > budget) {
                break;
            }
            builder.appendCodePoint(codePoint);
            used += width;
            index += Character.charCount(codePoint);
        }
        return builder.append(TRUNCATION_MARKER).toString();
    }

    /** 截断标记（三个点；不用单字符省略号，方便 grep 与逐字节比对）。 */
    public static final String TRUNCATION_MARKER = "...";
}
