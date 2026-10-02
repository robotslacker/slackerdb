package org.slackerdb.dbserver.sql;

import com.alibaba.fastjson2.JSONObject;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * {@code COPY ... FROM STDIN} 的数据格式与选项（PG 语义）。
 *
 * <p><b>为什么需要它</b>：改造前 {@code CopyProtocolHandler} 只认 {@code options.FORMAT}
 * 这一个键，而且是把值原样 {@code toUpperCase()} 后与 {@code CSV}/{@code BINARY} 比较。
 * 于是：</p>
 * <ol>
 *   <li>{@code FORMAT 'csv'}（PG 文档推荐写法，值带引号）比不过 {@code CSV} → 被拒；</li>
 *   <li>不写 {@code FORMAT} 时（PG 的默认格式是 <b>text</b>）直接报"只支持 CSV|BINARY"；</li>
 *   <li>{@code HEADER}/{@code DELIMITER}/{@code QUOTE}/{@code NULL}/{@code ESCAPE} 这些选项
 *       虽然被语法解析出来，却从来没有人读 → 表头被当成数据行、分隔符写错就整行错位。</li>
 * </ol>
 *
 * <p>本类把"选项 → 实际解析参数"这一步收敛到一处：默认值、单字符校验、组合校验、
 * 以及对不认识选项的登记（返回给调用方打日志，而不是假装没看见）。</p>
 *
 * <p>校验失败的统一出口是 {@link InvalidOptionException}，消息对齐 PostgreSQL 的措辞
 * （例如 {@code COPY format "x" not recognized}），调用方转成 {@code ErrorResponse}。</p>
 */
public final class CopyDialect {

    public static final String FORMAT_TEXT = "TEXT";
    public static final String FORMAT_CSV = "CSV";
    public static final String FORMAT_BINARY = "BINARY";

    /** PG text 格式的默认 NULL 表示：反斜杠 + N（两个字符，不是转义） */
    public static final String DEFAULT_TEXT_NULL = "\\N";

    /** 认识并且会真正生效的选项。其余选项登记到 {@link #getIgnoredOptions()} 并打 WARN。 */
    private static final Set<String> KNOWN_OPTIONS = Set.of(
            "FORMAT", "DELIMITER", "NULL", "QUOTE", "ESCAPE", "HEADER");

    /** 选项值非法或不支持（消息可直接回给客户端）。 */
    public static final class InvalidOptionException extends IllegalArgumentException {
        public InvalidOptionException(String message) {
            super(message);
        }
    }

    public final String format;        // TEXT / CSV / BINARY（大写）
    public final boolean binary;
    public final boolean csv;
    public final boolean text;
    public final char delimiter;
    public final char quote;
    public final char escape;
    public final String nullString;
    public final boolean header;
    private final Set<String> ignoredOptions;

    private CopyDialect(String format, char delimiter, char quote, char escape,
                        String nullString, boolean header, Set<String> ignoredOptions) {
        this.format = format;
        this.binary = FORMAT_BINARY.equals(format);
        this.csv = FORMAT_CSV.equals(format);
        this.text = FORMAT_TEXT.equals(format);
        this.delimiter = delimiter;
        this.quote = quote;
        this.escape = escape;
        this.nullString = nullString;
        this.header = header;
        this.ignoredOptions = Collections.unmodifiableSet(ignoredOptions);
    }

    public boolean isBinary() {
        return binary;
    }

    /** CSV / TEXT 都是"按行解析"的文本类格式（BINARY 走另一条路径）。 */
    public boolean isRowBased() {
        return !binary;
    }

    public Set<String> getIgnoredOptions() {
        return ignoredOptions;
    }

    /** 日志/错误信息里用的简短描述。 */
    public String describe() {
        if (binary) {
            return "BINARY";
        }
        return format + " delimiter=[" + escapeForLog(delimiter) + "]"
                + " quote=[" + escapeForLog(quote) + "]"
                + " escape=[" + escapeForLog(escape) + "]"
                + " null=[" + nullString + "]"
                + " header=" + header;
    }

    private static String escapeForLog(char c) {
        return switch (c) {
            case '\t' -> "\\t";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            default -> String.valueOf(c);
        };
    }

    /**
     * PG 默认的 CSV 方言（与改造前 {@code CopyCsvReader} 里硬编码的那一套完全一致：
     * 分隔符 {@code ,}、引号 {@code "}、未加引号的空字段为 NULL）。
     */
    public static CopyDialect defaultCsv() {
        return new CopyDialect(FORMAT_CSV, ',', '"', '"', "", false, Collections.emptySet());
    }

    /**
     * 由 {@code CopyVisitor} 解析出的 {@code options} 构造方言。
     *
     * @throws InvalidOptionException 选项值非法（消息可直接回给客户端）
     */
    public static CopyDialect fromOptions(JSONObject options) {
        Set<String> ignored = new LinkedHashSet<>();
        if (options != null) {
            for (String key : options.keySet()) {
                if (!KNOWN_OPTIONS.contains(key)) {
                    ignored.add(key);
                }
            }
        }

        // ---- FORMAT：默认 text（PG 的默认值），三种取值大小写不敏感 ----
        String rawFormat = options == null ? null : options.getString("FORMAT");
        String format = rawFormat == null ? FORMAT_TEXT : rawFormat.trim().toUpperCase(Locale.ROOT);
        if (!format.equals(FORMAT_TEXT) && !format.equals(FORMAT_CSV) && !format.equals(FORMAT_BINARY)) {
            throw new InvalidOptionException("COPY format \"" + rawFormat + "\" not recognized");
        }
        boolean isBinary = format.equals(FORMAT_BINARY);
        boolean isCsv = format.equals(FORMAT_CSV);

        // ---- 默认值：text 用 \t + \N，csv 用 , + " + 空串 ----
        char delimiter = isCsv ? ',' : '\t';
        char quote = '"';
        char escape = '"';
        String nullString = isCsv ? "" : DEFAULT_TEXT_NULL;
        boolean header = false;

        if (options != null) {
            if (options.containsKey("DELIMITER")) {
                if (isBinary) {
                    throw new InvalidOptionException(
                            "COPY delimiter is only valid in TEXT or CSV mode");
                }
                delimiter = singleChar("delimiter", options.getString("DELIMITER"));
                if (delimiter == '\r' || delimiter == '\n') {
                    throw new InvalidOptionException(
                            "COPY delimiter cannot be newline or carriage return");
                }
            }

            if (options.containsKey("NULL")) {
                if (isBinary) {
                    throw new InvalidOptionException(
                            "COPY null representation is only valid in TEXT or CSV mode");
                }
                nullString = options.getString("NULL");
                if (nullString == null) {
                    throw new InvalidOptionException("COPY null string must not be null");
                }
            }

            if (options.containsKey("QUOTE")) {
                if (!isCsv) {
                    throw new InvalidOptionException("COPY quote available only in CSV mode");
                }
                quote = singleChar("quote", options.getString("QUOTE"));
            }

            if (options.containsKey("ESCAPE")) {
                if (!isCsv) {
                    throw new InvalidOptionException("COPY escape available only in CSV mode");
                }
                escape = singleChar("escape", options.getString("ESCAPE"));
            }

            if (isCsv && delimiter == quote) {
                throw new InvalidOptionException("COPY delimiter and quote must be different");
            }

            if (options.containsKey("HEADER")) {
                // 裸 HEADER 在 CopyVisitor 里被规范成 "TRUE"
                header = parseBoolean("HEADER", options.getString("HEADER"));
            }
        }

        return new CopyDialect(format, delimiter, quote, escape, nullString, header, ignored);
    }

    /**
     * 解析单字符选项（DELIMITER/QUOTE/ESCAPE）。
     *
     * <p>PG 要求"单个字节"，因此这里只接受 ASCII 单字符；同时也接受 CSV 场景下常见的
     * 双字符转义写法（{@code '\t'} 表示制表符 —— PG 本身要求写 {@code E'\t'}，
     * 这里放宽为 {@code '\t'} 也能识别，属于安全超集）。</p>
     */
    private static char singleChar(String optionName, String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new InvalidOptionException(
                    "COPY " + optionName + " must be a single one-byte character");
        }
        if (raw.length() == 2 && raw.charAt(0) == '\\') {
            char decoded = switch (raw.charAt(1)) {
                case 't' -> '\t';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'v' -> 0x0B;
                case '\\' -> '\\';
                default -> 0;
            };
            if (decoded != 0) {
                return decoded;
            }
        }
        if (raw.length() != 1 || raw.charAt(0) > 0x7F) {
            throw new InvalidOptionException(
                    "COPY " + optionName + " must be a single one-byte character");
        }
        return raw.charAt(0);
    }

    /** 解析布尔选项（PG 的 bool 字面量：true/false、on/off、yes/no、1/0，大小写不敏感）。 */
    private static boolean parseBoolean(String optionName, String raw) {
        String value = raw == null ? "TRUE" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "TRUE", "T", "ON", "YES", "Y", "1" -> true;
            case "FALSE", "F", "OFF", "NO", "N", "0" -> false;
            case "MATCH" -> throw new InvalidOptionException(
                    "COPY " + optionName + " MATCH is not supported for COPY FROM table");
            default -> throw new InvalidOptionException(
                    "COPY " + optionName + " requires a Boolean value");
        };
    }
}
