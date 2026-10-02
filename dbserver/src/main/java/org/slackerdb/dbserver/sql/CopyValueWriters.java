package org.slackerdb.dbserver.sql;

import org.duckdb.DuckDBAppender;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 把 COPY 的 CSV 字段文本写进 {@code DuckDBAppender}。
 *
 * <p><b>为什么需要它</b>：块 Appender（{@code DuckDBAppender}）是<b>按列物理类型分派的二进制写入器，
 * 不做任何隐式转换</b>——{@code append(String)} 只接受 VARCHAR/ENUM 列，其余列报
 * {@code invalid column type, expected one of: '[DUCKDB_TYPE_VARCHAR, DUCKDB_TYPE_ENUM]'}；
 * 复合类型内部的元素同样要求精确的 Java 类型（{@code List<String>} 进 {@code INTEGER[]} 会报
 * {@code class java.lang.String cannot be cast to class java.lang.Integer}）。所以服务端必须按
 * DuckDB 的类型名把文本解析成正确类型的 Java 值/对象图，再调用对应的 append 重载。</p>
 *
 * <p>本类只依赖块 Appender 的公开 API（{@code append(...)} 各重载、{@code beginStruct/endStruct}、
 * {@code beginUnion/endUnion}，以及嵌套 UNION 需要的 {@code SimpleEntry(tag, 值)}），不使用任何已废弃的入口。</p>
 */
public final class CopyValueWriters {

    /** 时间戳文本（可带 0~9 位小数秒）。 */
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .toFormatter();

    /** 时间文本（可带 0~9 位小数秒）。 */
    private static final DateTimeFormatter TIME_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .toFormatter();

    /** 带时区偏移的几种写法。 */
    private static final List<DateTimeFormatter> OFFSET_TIMESTAMP_FORMATTERS = List.of(
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            offsetFormatter("yyyy-MM-dd HH:mm:ss", "+HH:MM"),
            offsetFormatter("yyyy-MM-dd HH:mm:ss", "+HHMM"),
            offsetFormatter("yyyy-MM-dd HH:mm:ss", "+HH"));

    private static final List<DateTimeFormatter> OFFSET_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ISO_OFFSET_TIME,
            offsetFormatter("HH:mm:ss", "+HH:MM"),
            offsetFormatter("HH:mm:ss", "+HHMM"),
            offsetFormatter("HH:mm:ss", "+HH"));

    private CopyValueWriters() {
    }

    private static DateTimeFormatter offsetFormatter(String dateTimePattern, String offsetPattern) {
        return new DateTimeFormatterBuilder()
                .appendPattern(dateTimePattern)
                .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
                .appendOffset(offsetPattern, "Z")
                .toFormatter();
    }

    /** 把字段文本写进目标列（顶层；NULL 由调用方用 {@code appendNull()} 处理）。 */
    public interface ColumnWriter {
        void write(DuckDBAppender appender, byte[] buffer, int off, int len) throws SQLException;
    }

    /**
     * 按 DuckDB 列类型名构造写入器。
     *
     * @throws SQLException 类型名无法识别（例如需要扩展的类型），错误信息面向客户端
     */
    public static ColumnWriter writer(String duckDbTypeName) throws SQLException {
        String typeName = duckDbTypeName == null ? "" : duckDbTypeName.trim();
        String upper = typeName.toUpperCase(Locale.ROOT);

        // 数组 / LIST：INTEGER[]、VARCHAR[]、INTEGER[3]、INTEGER[][]
        if (typeName.endsWith("]")) {
            int open = matchingOpenBracket(typeName);
            if (open <= 0) {
                throw unsupported(typeName);
            }
            return new ListWriter(writer(typeName.substring(0, open).trim()));
        }
        if (upper.startsWith("STRUCT(") && typeName.endsWith(")")) {
            return structWriter(typeName);
        }
        if (upper.startsWith("MAP(") && typeName.endsWith(")")) {
            List<String> args = splitTopLevel(inner(typeName), ',');
            if (args.size() != 2) {
                throw unsupported(typeName);
            }
            return new MapWriter(writer(args.get(0)), writer(args.get(1)));
        }
        if (upper.startsWith("UNION(") && typeName.endsWith(")")) {
            return unionWriter(typeName);
        }
        return scalarWriter(typeName, upper);
    }

    // ------------------------------------------------------------------ 类型名解析

    private static int matchingOpenBracket(String typeName) {
        int depth = 0;
        for (int i = typeName.length() - 1; i >= 0; i--) {
            char ch = typeName.charAt(i);
            if (ch == ']') {
                depth++;
            }
            else if (ch == '[') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String inner(String typeName) {
        return typeName.substring(typeName.indexOf('(') + 1, typeName.length() - 1);
    }

    private static ColumnWriter structWriter(String typeName) throws SQLException {
        List<FieldWriter> fields = new ArrayList<>();
        for (String rawField : splitTopLevel(inner(typeName), ',')) {
            String field = rawField.trim();
            int split = firstTopLevelWhitespace(field);
            if (split < 0) {
                throw unsupported(typeName);
            }
            String fieldName = unquoteIdentifier(field.substring(0, split).trim());
            fields.add(new FieldWriter(fieldName, writer(field.substring(split + 1).trim())));
        }
        return new StructWriter(fields);
    }

    private static ColumnWriter unionWriter(String typeName) throws SQLException {
        List<FieldWriter> members = new ArrayList<>();
        for (String rawMember : splitTopLevel(inner(typeName), ',')) {
            String member = rawMember.trim();
            int split = firstTopLevelWhitespace(member);
            if (split < 0) {
                throw unsupported(typeName);
            }
            members.add(new FieldWriter(unquoteIdentifier(member.substring(0, split).trim()),
                    writer(member.substring(split + 1).trim())));
        }
        return new UnionWriter(members);
    }

    private static int firstTopLevelWhitespace(String text) {
        int depth = 0;
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inQuote) {
                if (ch == quoteChar) {
                    inQuote = false;
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                inQuote = true;
                quoteChar = ch;
            }
            else if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            }
            else if (ch == ')' || ch == ']' || ch == '}') {
                depth--;
            }
            else if (depth == 0 && Character.isWhitespace(ch)) {
                return i;
            }
        }
        return -1;
    }

    /** 按深度 0 的 {@code separator} 切分（忽略括号/引号内部的字符）。 */
    static List<String> splitTopLevel(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inQuote = false;
        char quoteChar = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inQuote) {
                if (ch == quoteChar) {
                    if (i + 1 < text.length() && text.charAt(i + 1) == quoteChar) {
                        i++;   // 转义后的引号
                    }
                    else {
                        inQuote = false;
                    }
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                inQuote = true;
                quoteChar = ch;
            }
            else if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            }
            else if (ch == ')' || ch == ']' || ch == '}') {
                depth--;
            }
            else if (depth == 0 && ch == separator) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    // ------------------------------------------------------------------ 各类型写入器

    private record FieldWriter(String name, ColumnWriter writer) {}

    /** 标量：按类型名解析文本并调用对应的 append 重载。 */
    private static final class ScalarWriter implements ColumnWriter {
        private final String typeName;
        private final ScalarKind kind;

        ScalarWriter(String typeName, ScalarKind kind) {
            this.typeName = typeName;
            this.kind = kind;
        }

        @Override
        public void write(DuckDBAppender appender, byte[] buffer, int off, int len) throws SQLException {
            // 定长整数：直接从字节区间解析，省掉"每个单元格一个 String"的分配
            // （与改造前 CSV 快路径的语义/代价一致；空字段已被调用方按 NULL 处理）
            try {
                switch (kind) {
                    case SMALLINT -> {
                        appender.append(CopyCsvReader.parseShort(buffer, off, len));
                        return;
                    }
                    case INTEGER -> {
                        appender.append(CopyCsvReader.parseInt(buffer, off, len));
                        return;
                    }
                    case BIGINT -> {
                        appender.append(CopyCsvReader.parseLong(buffer, off, len));
                        return;
                    }
                    default -> {
                        // 其余类型走文本解析
                    }
                }
            }
            catch (NumberFormatException numberFormatException) {
                throw new SQLException("Invalid input for column type [" + typeName + "]: ["
                        + new String(buffer, off, len, StandardCharsets.UTF_8) + "]");
            }
            writeValue(appender, parseText(new String(buffer, off, len, StandardCharsets.UTF_8), false));
        }

        /**
         * 解析字段文本。
         *
         * @param nested true 表示文本来自复合类型内部（{@code {'a': 1, 'b': 'x'}} 这种），
         *               此时需要去掉包裹的引号；顶层字段由 CSV 解析器负责引号/转义，
         *               必须<b>原样</b>使用（否则会破坏首尾空格这类有效内容）
         */
        Object parseText(String rawText, boolean nested) throws SQLException {
            String text = nested ? unquote(rawText) : rawText;
            if (kind != ScalarKind.VARCHAR && kind != ScalarKind.JSON && kind != ScalarKind.ENUM) {
                text = text.trim();
            }
            try {
                return switch (kind) {
                    case BOOLEAN -> parseBoolean(text);
                    case TINYINT -> Byte.valueOf(text);
                    case SMALLINT -> Short.valueOf(text);
                    case INTEGER -> Integer.valueOf(text);
                    case BIGINT -> Long.valueOf(text);
                    case HUGEINT -> new BigInteger(text);
                    // 无符号类型做值域校验：宁可报错，也不能静默回绕（-1 写成 255 这类事故最难查）
                    case UTINYINT -> unsignedByte(text);
                    case USMALLINT -> unsignedShort(text);
                    case UINTEGER -> unsignedInt(text);
                    case UBIGINT -> unsignedLongBits(text);
                    case UHUGEINT -> unsignedHugeInt(text);
                    case FLOAT -> Float.valueOf(text);
                    case DOUBLE -> Double.valueOf(text);
                    case DECIMAL -> new BigDecimal(text);
                    case VARCHAR, JSON, ENUM -> text;
                    case BLOB -> parseBlob(text);
                    case UUID -> UUID.fromString(text);
                    case DATE -> LocalDate.parse(text);
                    case TIME -> LocalTime.parse(text, TIME_FORMATTER);
                    case TIMETZ -> parseOffsetTime(text);
                    case TIMESTAMP -> LocalDateTime.parse(normalizeTimestampText(text), TIMESTAMP_FORMATTER);
                    case TIMESTAMPTZ -> parseOffsetDateTime(text);
                };
            }
            catch (RuntimeException e) {
                throw new SQLException("Invalid input for column type [" + typeName + "]: [" + rawText + "]");
            }
        }

        /** 顶层写入：UHUGEINT 超出 int128 时 BigDecimal/BigInteger 重载都不行，要用 appendHugeInt。 */
        void writeValue(DuckDBAppender appender, Object value) throws SQLException {
            if (value instanceof BigInteger huge) {
                if (kind == ScalarKind.UHUGEINT && huge.bitLength() > 127) {
                    BigInteger low = huge.and(UNSIGNED_LONG_MASK);
                    BigInteger high = huge.shiftRight(64);
                    appender.appendHugeInt(low.longValue(), high.longValue());
                    return;
                }
                appender.append(huge);
                return;
            }
            appendTyped(appender, value);
        }
    }

    private static final BigInteger UNSIGNED_LONG_MASK = new BigInteger("FFFFFFFFFFFFFFFF", 16);

    private enum ScalarKind {
        BOOLEAN, TINYINT, SMALLINT, INTEGER, BIGINT, HUGEINT,
        UTINYINT, USMALLINT, UINTEGER, UBIGINT, UHUGEINT,
        FLOAT, DOUBLE, DECIMAL, VARCHAR, JSON, ENUM, BLOB, UUID,
        DATE, TIME, TIMETZ, TIMESTAMP, TIMESTAMPTZ
    }

    private static ColumnWriter scalarWriter(String typeName, String upper) throws SQLException {
        if (upper.startsWith("DECIMAL") || upper.startsWith("NUMERIC(")) {
            return new ScalarWriter(typeName, ScalarKind.DECIMAL);
        }
        // VARCHAR(20) / CHAR(10) 这类带长度的写法（DuckDB 的 JDBC 元数据一般只给 VARCHAR，这里兜底）
        if (upper.startsWith("VARCHAR(") || upper.startsWith("CHAR(") || upper.startsWith("BPCHAR(")
                || upper.startsWith("STRING(") || upper.startsWith("TEXT(")) {
            return new ScalarWriter(typeName, ScalarKind.VARCHAR);
        }
        ScalarKind kind = switch (upper) {
            case "BOOLEAN", "BOOL", "LOGICAL" -> ScalarKind.BOOLEAN;
            case "TINYINT", "INT1" -> ScalarKind.TINYINT;
            case "SMALLINT", "INT2", "SHORT" -> ScalarKind.SMALLINT;
            case "INTEGER", "INT4", "INT", "SIGNED" -> ScalarKind.INTEGER;
            case "BIGINT", "INT8", "LONG" -> ScalarKind.BIGINT;
            case "HUGEINT" -> ScalarKind.HUGEINT;
            case "UTINYINT" -> ScalarKind.UTINYINT;
            case "USMALLINT" -> ScalarKind.USMALLINT;
            case "UINTEGER" -> ScalarKind.UINTEGER;
            case "UBIGINT" -> ScalarKind.UBIGINT;
            case "UHUGEINT" -> ScalarKind.UHUGEINT;
            case "FLOAT", "FLOAT4", "REAL" -> ScalarKind.FLOAT;
            case "DOUBLE", "FLOAT8" -> ScalarKind.DOUBLE;
            case "VARCHAR", "TEXT", "STRING", "CHAR", "BPCHAR" -> ScalarKind.VARCHAR;
            case "JSON" -> ScalarKind.JSON;
            case "ENUM" -> ScalarKind.ENUM;
            case "BLOB", "BYTEA", "BINARY", "VARBINARY" -> ScalarKind.BLOB;
            case "UUID" -> ScalarKind.UUID;
            case "DATE" -> ScalarKind.DATE;
            case "TIME" -> ScalarKind.TIME;
            case "TIME WITH TIME ZONE", "TIMETZ" -> ScalarKind.TIMETZ;
            case "TIMESTAMP", "DATETIME", "TIMESTAMP_S", "TIMESTAMP_MS", "TIMESTAMP_NS" -> ScalarKind.TIMESTAMP;
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ" -> ScalarKind.TIMESTAMPTZ;
            default -> null;
        };
        if (kind == null) {
            throw unsupported(typeName);
        }
        return new ScalarWriter(typeName, kind);
    }

    /** LIST / ARRAY：元素必须是与元素类型精确匹配的 Java 值。 */
    private static final class ListWriter implements ColumnWriter {
        private final ColumnWriter element;

        ListWriter(ColumnWriter element) {
            this.element = element;
        }

        @Override
        public void write(DuckDBAppender appender, byte[] buffer, int off, int len) throws SQLException {
            String text = new String(buffer, off, len, StandardCharsets.UTF_8).trim();
            appender.append(parseList(text));
        }

        private List<Object> parseList(String text) throws SQLException {
            String body = stripOuter(text, '[', ']');
            List<Object> values = new ArrayList<>();
            if (body.isEmpty()) {
                return values;
            }
            for (String item : splitTopLevel(body, ',')) {
                String token = item.trim();
                if (isNullToken(token)) {
                    values.add(null);
                    continue;
                }
                if (element instanceof ScalarWriter scalar) {
                    values.add(scalar.parseText(token, true));
                }
                else if (element instanceof StructWriter struct) {
                    values.add(struct.value(token));
                }
                else if (element instanceof ListWriter nested) {
                    // 注意：把带方括号的原始 token 交给它，parseList 自己会 stripOuter
                    values.add(nested.parseList(token.trim()));
                }
                else if (element instanceof MapWriter map) {
                    values.add(map.value(token));
                }
                else if (element instanceof UnionWriter union) {
                    values.add(union.value(token));
                }
                else {
                    throw new SQLException("Unsupported LIST element writer for token [" + token + "]");
                }
            }
            return values;
        }
    }

    /** 顶层 STRUCT 用 beginStruct/endStruct；作为集合元素时用 Map。 */
    private static final class StructWriter implements ColumnWriter {
        private final List<FieldWriter> fields;

        StructWriter(List<FieldWriter> fields) {
            this.fields = fields;
        }

        @Override
        public void write(DuckDBAppender appender, byte[] buffer, int off, int len) throws SQLException {
            String text = new String(buffer, off, len, StandardCharsets.UTF_8);
            Map<String, String> rawValues = splitFields(text);
            appender.beginStruct();
            for (FieldWriter field : fields) {
                String token = rawValues.get(field.name().toUpperCase(Locale.ROOT));
                writeField(appender, field.writer(), token);
            }
            appender.endStruct();
        }

        Map<String, Object> value(String text) throws SQLException {
            Map<String, String> rawValues = splitFields(text);
            Map<String, Object> result = new LinkedHashMap<>();
            for (FieldWriter field : fields) {
                String token = rawValues.get(field.name().toUpperCase(Locale.ROOT));
                result.put(field.name(), valueOf(field.writer(), token));
            }
            return result;
        }

        private Map<String, String> splitFields(String text) throws SQLException {
            Map<String, String> values = new LinkedHashMap<>();
            String body = stripOuter(text.trim(), '{', '}');
            if (body.isEmpty()) {
                return values;
            }
            for (String field : splitTopLevel(body, ',')) {
                int colon = indexOfTopLevelSeparator(field);
                if (colon < 0) {
                    continue;
                }
                String name = unquoteIdentifier(field.substring(0, colon).trim());
                values.put(name.toUpperCase(Locale.ROOT), field.substring(colon + 1).trim());
            }
            return values;
        }
    }

    /** MAP：值形态统一用 LinkedHashMap。 */
    private static final class MapWriter implements ColumnWriter {
        private final ColumnWriter keyWriter;
        private final ColumnWriter valueWriter;

        MapWriter(ColumnWriter keyWriter, ColumnWriter valueWriter) {
            this.keyWriter = keyWriter;
            this.valueWriter = valueWriter;
        }

        @Override
        public void write(DuckDBAppender appender, byte[] buffer, int off, int len) throws SQLException {
            appender.append(value(new String(buffer, off, len, StandardCharsets.UTF_8)));
        }

        Map<Object, Object> value(String text) throws SQLException {
            Map<Object, Object> result = new LinkedHashMap<>();
            String body = stripOuter(text.trim(), '{', '}');
            if (body.isEmpty()) {
                return result;
            }
            for (String entry : splitTopLevel(body, ',')) {
                int separator = indexOfTopLevelSeparator(entry);
                if (separator < 0) {
                    continue;
                }
                String rawKey = entry.substring(0, separator).trim();
                String rawValue = entry.substring(separator + 1).trim();
                result.put(valueOf(keyWriter, rawKey), valueOf(valueWriter, rawValue));
            }
            return result;
        }
    }

    /**
     * UNION：DuckDB 的文本形态只带成员值、不带 tag，所以按声明的成员顺序尝试解析，
     * 取第一个能解析成功的标量成员作为 tag。
     *
     * <p>写进去的形态取决于位置：</p>
     * <ul>
     *   <li>顶层列（以及顶层 STRUCT 的字段）：{@code beginUnion(tag)/endUnion}；</li>
     *   <li>集合/结构体内部（LIST 元素、MAP 的键值、被物化成 Map 的 STRUCT 字段）：只能是
     *       {@code SimpleEntry(tag, 值)} —— 块 Appender 没有 beginList/beginMap，
     *       内部值必须一次性给全，插不进 beginUnion。DuckDB 自己的报错就是这个意思：
     *       {@code union values must be specified as an instance of
     *       'java.util.AbstractMap.SimpleEntry<String, Object>'}。</li>
     * </ul>
     */
    private static final class UnionWriter implements ColumnWriter {
        private final List<FieldWriter> members;

        UnionWriter(List<FieldWriter> members) {
            this.members = members;
        }

        @Override
        public void write(DuckDBAppender appender, byte[] buffer, int off, int len) throws SQLException {
            String text = new String(buffer, off, len, StandardCharsets.UTF_8).trim();
            FieldWriter member = inferMember(text);
            ScalarWriter scalar = (ScalarWriter) member.writer();
            appender.beginUnion(member.name());
            scalar.writeValue(appender, scalar.parseText(text, true));
            appender.endUnion();
        }

        /** 嵌套位置的取值形态：{@code SimpleEntry(tag, 值)}。 */
        Object value(String text) throws SQLException {
            String trimmed = text == null ? "" : text.trim();
            FieldWriter member = inferMember(trimmed);
            ScalarWriter scalar = (ScalarWriter) member.writer();
            return new AbstractMap.SimpleEntry<>(member.name(), scalar.parseText(trimmed, true));
        }

        /** 文本不带 tag：按声明顺序取第一个能解析成功的标量成员。 */
        private FieldWriter inferMember(String text) throws SQLException {
            for (FieldWriter member : members) {
                if (member.writer() instanceof ScalarWriter scalar) {
                    try {
                        scalar.parseText(text, true);
                        return member;
                    }
                    catch (SQLException notThisMember) {
                        // 这个成员不匹配，换下一个
                    }
                }
            }
            throw new SQLException("Cannot determine UNION member for value [" + text
                    + "]; only scalar members can be inferred from text");
        }
    }

    // ------------------------------------------------------------------ 写值工具

    private static void writeField(DuckDBAppender appender, ColumnWriter writer, String token)
            throws SQLException {
        if (isNullToken(token)) {
            appender.appendNull();
            return;
        }
        // 标量：文本来自复合类型内部，需要按"嵌套值"解析（去引号）
        if (writer instanceof ScalarWriter scalar) {
            scalar.writeValue(appender, scalar.parseText(token, true));
            return;
        }
        // 复合类型自己负责 stripOuter/trim 与递归
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        writer.write(appender, bytes, 0, bytes.length);
    }

    private static Object valueOf(ColumnWriter writer, String token) throws SQLException {
        if (isNullToken(token)) {
            return null;
        }
        if (writer instanceof ScalarWriter scalar) {
            return scalar.parseText(token, true);
        }
        if (writer instanceof StructWriter struct) {
            return struct.value(token);
        }
        if (writer instanceof MapWriter map) {
            return map.value(token);
        }
        if (writer instanceof ListWriter list) {
            return list.parseList(token);
        }
        if (writer instanceof UnionWriter union) {
            return union.value(token);
        }
        throw new SQLException("Unsupported nested value writer for token [" + token + "]");
    }

    /** 把已经解析好的 Java 值交给对应的 append 重载。 */
    private static void appendTyped(DuckDBAppender appender, Object value) throws SQLException {
        if (value == null) {
            appender.appendNull();
        }
        else if (value instanceof Boolean bool) {
            appender.append(bool.booleanValue());
        }
        else if (value instanceof Byte number) {
            appender.append(number.byteValue());
        }
        else if (value instanceof Short number) {
            appender.append(number.shortValue());
        }
        else if (value instanceof Integer number) {
            appender.append(number.intValue());
        }
        else if (value instanceof Long number) {
            appender.append(number.longValue());
        }
        else if (value instanceof BigInteger number) {
            appender.append(number);
        }
        else if (value instanceof BigDecimal number) {
            appender.append(number);
        }
        else if (value instanceof Float number) {
            appender.append(number.floatValue());
        }
        else if (value instanceof Double number) {
            appender.append(number.doubleValue());
        }
        else if (value instanceof String text) {
            appender.append(text);
        }
        else if (value instanceof byte[] bytes) {
            // 注意：BLOB 列要用 append(byte[])；appendByteArray(byte[]) 是给 ARRAY/LIST 的
            // （实测对 BLOB 列报 invalid column type, expected one of: '[DUCKDB_TYPE_ARRAY, DUCKDB_TYPE_LIST]'）
            appender.append(bytes);
        }
        else if (value instanceof UUID uuid) {
            appender.append(uuid);
        }
        else if (value instanceof LocalDate date) {
            appender.append(date);
        }
        else if (value instanceof LocalTime time) {
            appender.append(time);
        }
        else if (value instanceof OffsetTime time) {
            appender.append(time);
        }
        else if (value instanceof LocalDateTime dateTime) {
            appender.append(dateTime);
        }
        else if (value instanceof OffsetDateTime dateTime) {
            appender.append(dateTime);
        }
        else if (value instanceof Collection<?> collection) {
            appender.append(collection);
        }
        else if (value instanceof Map<?, ?> map) {
            appender.append(map);
        }
        else {
            throw new SQLException("Unsupported Java value for appender: " + value.getClass().getName());
        }
    }

    // ---- 无符号类型：值域校验 + 取位模式（Appender 的 append(byte/short/int/long) 只认位模式） ----

    private static byte unsignedByte(String text) throws SQLException {
        return (byte) parseUnsigned(text, 255, "UTINYINT");
    }

    private static short unsignedShort(String text) throws SQLException {
        return (short) parseUnsigned(text, 65535, "USMALLINT");
    }

    private static int unsignedInt(String text) throws SQLException {
        return (int) parseUnsigned(text, 4294967295L, "UINTEGER");
    }

    private static long unsignedLongBits(String text) throws SQLException {
        return parseUnsignedBig(text, 64, "UBIGINT").longValue();
    }

    private static BigInteger unsignedHugeInt(String text) throws SQLException {
        return parseUnsignedBig(text, 128, "UHUGEINT");
    }

    private static long parseUnsigned(String text, long max, String typeName) throws SQLException {
        long value = Long.parseLong(text.trim());
        if (value < 0 || value > max) {
            throw new SQLException("Value [" + text + "] is out of range for column type ["
                    + typeName + "]");
        }
        return value;
    }

    private static BigInteger parseUnsignedBig(String text, int maxBits, String typeName)
            throws SQLException {
        BigInteger value = new BigInteger(text.trim());
        if (value.signum() < 0 || value.bitLength() > maxBits) {
            throw new SQLException("Value [" + text + "] is out of range for column type ["
                    + typeName + "]");
        }
        return value;
    }

    private static String normalizeTimestampText(String text) {
        int t = text.indexOf('T');
        return t < 0 ? text : text.substring(0, t) + " " + text.substring(t + 1);
    }

    private static Boolean parseBoolean(String text) throws SQLException {
        String value = text.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "true", "t", "yes", "y", "1" -> Boolean.TRUE;
            case "false", "f", "no", "n", "0" -> Boolean.FALSE;
            default -> throw new SQLException("Invalid boolean value [" + text + "]");
        };
    }

    private static OffsetDateTime parseOffsetDateTime(String text) throws SQLException {
        String normalized = normalizeTimestampText(text);
        for (DateTimeFormatter formatter : OFFSET_TIMESTAMP_FORMATTERS) {
            try {
                return OffsetDateTime.parse(normalized, formatter);
            }
            catch (DateTimeParseException ignored) {
                // 换下一种形态
            }
        }
        // 不带偏移：按 UTC 解释（与 DuckDB 把裸时间戳当会话时区不同，这里显式约定为 UTC）
        try {
            return LocalDateTime.parse(normalized, TIMESTAMP_FORMATTER).atOffset(ZoneOffset.UTC);
        }
        catch (DateTimeParseException e) {
            throw new SQLException("Invalid TIMESTAMPTZ value [" + text + "]");
        }
    }

    private static OffsetTime parseOffsetTime(String text) throws SQLException {
        for (DateTimeFormatter formatter : OFFSET_TIME_FORMATTERS) {
            try {
                return OffsetTime.parse(text, formatter);
            }
            catch (DateTimeParseException ignored) {
                // 换下一种形态
            }
        }
        try {
            return LocalTime.parse(text, TIME_FORMATTER).atOffset(ZoneOffset.UTC);
        }
        catch (DateTimeParseException e) {
            throw new SQLException("Invalid TIMETZ value [" + text + "]");
        }
    }

    /**
     * 接受 {@code \xDE\xAD\xBE\xEF}、{@code \xDEADBEEF} 与 {@code 0xDEADBEEF} 三种十六进制写法。
     *
     * <p>只跳过真正的 {@code \x} / 起始 {@code 0x} 前缀，不把普通字符里的 x 当分隔符
     * （否则 {@code "ABxCD"} 这种非法输入会被静默改写成合法值）。</p>
     */
    private static byte[] parseBlob(String text) throws SQLException {
        StringBuilder hex = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\\') {
                if (i + 1 < text.length() && (text.charAt(i + 1) == 'x' || text.charAt(i + 1) == 'X')) {
                    i++;
                    continue;
                }
                throw new SQLException("Invalid BLOB hex value [" + text + "]");
            }
            if (ch == '0' && hex.isEmpty() && i + 1 < text.length()
                    && (text.charAt(i + 1) == 'x' || text.charAt(i + 1) == 'X')) {
                i++;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                continue;
            }
            hex.append(ch);
        }
        if (hex.length() % 2 != 0) {
            throw new SQLException("Invalid BLOB hex value [" + text + "]");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new SQLException("Invalid BLOB hex value [" + text + "]");
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    // ------------------------------------------------------------------ 文本工具

    static boolean isNullToken(String token) {
        if (token == null) {
            return true;
        }
        String trimmed = token.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        if (trimmed.charAt(0) == '\'' || trimmed.charAt(0) == '"') {
            // 带引号的空串是「空字符串」，不是 NULL
            return false;
        }
        return trimmed.equalsIgnoreCase("NULL") || trimmed.equals("\\N");
    }

    static String unquote(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '\'' || first == '"') && first == last) {
                String body = trimmed.substring(1, trimmed.length() - 1);
                return body.replace(String.valueOf(first) + first, String.valueOf(first));
            }
        }
        return trimmed;
    }

    static String unquoteIdentifier(String text) {
        String trimmed = text.trim();
        if (trimmed.length() >= 2 && (trimmed.charAt(0) == '"' || trimmed.charAt(0) == '\'')
                && trimmed.charAt(trimmed.length() - 1) == trimmed.charAt(0)) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    static String stripOuter(String text, char open, char close) throws SQLException {
        String trimmed = text.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == open && trimmed.charAt(trimmed.length() - 1) == close) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        throw new SQLException("Expected [" + open + " ... " + close + "] but got [" + text + "]");
    }

    /** 找第一个深度 0 的 {@code :} 或 {@code =}（STRUCT / MAP 的键值分隔）。 */
    static int indexOfTopLevelSeparator(String text) {
        int depth = 0;
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inQuote) {
                if (ch == quoteChar) {
                    inQuote = false;
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                inQuote = true;
                quoteChar = ch;
            }
            else if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            }
            else if (ch == ')' || ch == ']' || ch == '}') {
                depth--;
            }
            else if (depth == 0 && (ch == ':' || ch == '=')) {
                return i;
            }
        }
        return -1;
    }

    static SQLException unsupported(String typeName) {
        return new SQLException("COPY does not support column type [" + typeName + "]");
    }
}
