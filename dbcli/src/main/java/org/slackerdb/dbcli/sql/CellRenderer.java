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
        return String.valueOf(value);
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
