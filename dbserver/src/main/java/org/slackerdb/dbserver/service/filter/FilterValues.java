package org.slackerdb.dbserver.service.filter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * filter 求值用到的值语义：类型归一、比较、LIKE 与白名单函数。
 *
 * <p>值来自 {@code JsonValues.toJsonValue}，因此只有 Number / String / Boolean /
 * null / List / Map 这几类。比较规则（与设计文档一致）：</p>
 * <ul>
 *   <li>数字按 {@link BigDecimal} 比较；数字与可解析为数字的字符串按数值比较；</li>
 *   <li>字符串先尝试按 ISO-8601 时间比较，否则按 Unicode 码点（等价于 UTF-8 字节序）比较；</li>
 *   <li>布尔按 false &lt; true；List/Map 不可直接比较（可用 length()）；</li>
 *   <li>其余类型组合一律是客户端可读的类型错误。</li>
 * </ul>
 */
final class FilterValues {

    private static final int KIND_DATE = 1;
    private static final int KIND_TIME = 2;
    private static final int KIND_LOCAL_DATETIME = 3;
    private static final int KIND_OFFSET_DATETIME = 4;

    private FilterValues() {
    }

    // ---------------------------------------------------------------- 比较

    static int compare(Object left, Object right, String leftLabel, String rightLabel, int position)
            throws FilterException {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return compareNumbers(leftNumber, rightNumber);
        }
        if (left instanceof Number leftNumber && right instanceof String rightText) {
            BigDecimal parsed = parseNumber(rightText);
            if (parsed == null) {
                throw typeError(left, right, leftLabel, rightLabel, position);
            }
            return compareNumbers(leftNumber, parsed);
        }
        if (left instanceof String leftText && right instanceof Number rightNumber) {
            BigDecimal parsed = parseNumber(leftText);
            if (parsed == null) {
                throw typeError(left, right, leftLabel, rightLabel, position);
            }
            return compareNumbers(parsed, rightNumber);
        }
        if (left instanceof Boolean leftBoolean && right instanceof Boolean rightBoolean) {
            return Boolean.compare(leftBoolean, rightBoolean);
        }
        if (left instanceof String leftText && right instanceof String rightText) {
            Temporal leftTemporal = temporalOf(leftText);
            Temporal rightTemporal = temporalOf(rightText);
            if (leftTemporal != null && rightTemporal != null) {
                Integer temporalOrder = compareTemporal(leftTemporal, rightTemporal);
                if (temporalOrder != null) {
                    return temporalOrder;
                }
            }
            return compareCodePoints(leftText, rightText);
        }
        throw typeError(left, right, leftLabel, rightLabel, position);
    }

    private static int compareNumbers(Number left, Number right) {
        if (isNonFinite(left) || isNonFinite(right)) {
            return Double.compare(left.doubleValue(), right.doubleValue());
        }
        return toBigDecimal(left).compareTo(toBigDecimal(right));
    }

    private static boolean isNonFinite(Number value) {
        if (value instanceof Double doubleValue) {
            return !Double.isFinite(doubleValue);
        }
        if (value instanceof Float floatValue) {
            return !Float.isFinite(floatValue);
        }
        return false;
    }

    private static BigDecimal toBigDecimal(Number value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(value.toString());
    }

    private static BigDecimal parseNumber(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static FilterException typeError(Object left, Object right,
                                            String leftLabel, String rightLabel, int position) {
        return FilterException.at("cannot compare " + leftLabel + " (" + typeName(left) + ") with "
                + rightLabel + " (" + typeName(right) + ")", position);
    }

    /** 按 Unicode 码点比较（与 DuckDB 默认的 UTF-8 二进制排序一致）。 */
    static int compareCodePoints(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftCodePoint = left.codePointAt(leftIndex);
            int rightCodePoint = right.codePointAt(rightIndex);
            if (leftCodePoint != rightCodePoint) {
                return Integer.compare(leftCodePoint, rightCodePoint);
            }
            leftIndex += Character.charCount(leftCodePoint);
            rightIndex += Character.charCount(rightCodePoint);
        }
        return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
    }

    // ---------------------------------------------------------------- 时间

    private record Temporal(int kind, Object value) {
    }

    private static Temporal temporalOf(String text) {
        if (text.length() == 10 && text.charAt(4) == '-' && text.charAt(7) == '-') {
            try {
                return new Temporal(KIND_DATE, LocalDate.parse(text));
            } catch (DateTimeParseException notADate) {
                return null;
            }
        }
        if (text.length() >= 8 && text.charAt(2) == ':' && text.charAt(5) == ':') {
            try {
                return new Temporal(KIND_TIME, LocalTime.parse(text));
            } catch (DateTimeParseException notATime) {
                return null;
            }
        }
        if (text.length() >= 16 && text.charAt(4) == '-' && text.charAt(7) == '-'
                && (text.charAt(10) == 'T' || text.charAt(10) == ' ')) {
            String normalized = text.charAt(10) == ' '
                    ? text.substring(0, 10) + "T" + text.substring(11)
                    : text;
            try {
                return new Temporal(KIND_OFFSET_DATETIME, OffsetDateTime.parse(normalized));
            } catch (DateTimeParseException notOffsetDateTime) {
                // 继续尝试不带时区的本地时间
            }
            try {
                return new Temporal(KIND_LOCAL_DATETIME, LocalDateTime.parse(normalized));
            } catch (DateTimeParseException notLocalDateTime) {
                return null;
            }
        }
        return null;
    }

    private static Integer compareTemporal(Temporal left, Temporal right) {
        if (left.kind() == right.kind()) {
            return switch (left.kind()) {
                case KIND_DATE -> ((LocalDate) left.value()).compareTo((LocalDate) right.value());
                case KIND_TIME -> ((LocalTime) left.value()).compareTo((LocalTime) right.value());
                case KIND_LOCAL_DATETIME ->
                        ((LocalDateTime) left.value()).compareTo((LocalDateTime) right.value());
                case KIND_OFFSET_DATETIME ->
                        ((OffsetDateTime) left.value()).toInstant()
                                .compareTo(((OffsetDateTime) right.value()).toInstant());
                default -> null;
            };
        }
        if (left.kind() == KIND_DATE && right.kind() == KIND_LOCAL_DATETIME) {
            return ((LocalDate) left.value()).atStartOfDay()
                    .compareTo((LocalDateTime) right.value());
        }
        if (left.kind() == KIND_LOCAL_DATETIME && right.kind() == KIND_DATE) {
            return ((LocalDateTime) left.value())
                    .compareTo(((LocalDate) right.value()).atStartOfDay());
        }
        return null;
    }

    // ---------------------------------------------------------------- LIKE

    static Pattern compileLike(String pattern, String escape, int position) throws FilterException {
        StringBuilder regex = new StringBuilder();
        boolean lastWasAny = false;
        int index = 0;
        while (index < pattern.length()) {
            int codePoint = pattern.codePointAt(index);
            String current = new String(Character.toChars(codePoint));
            int width = Character.charCount(codePoint);
            if (escape != null && current.equals(escape)) {
                index += width;
                if (index >= pattern.length()) {
                    throw FilterException.at("LIKE pattern ends with the escape character", position);
                }
                int escaped = pattern.codePointAt(index);
                regex.append(Pattern.quote(new String(Character.toChars(escaped))));
                index += Character.charCount(escaped);
                lastWasAny = false;
            } else if ("%".equals(current)) {
                // 连续的 % 只编译成一个 .*，避免 A%B%C%D... 触发正则回溯
                if (!lastWasAny) {
                    regex.append(".*");
                }
                lastWasAny = true;
                index += width;
            } else if ("_".equals(current)) {
                regex.append('.');
                lastWasAny = false;
                index += width;
            } else {
                regex.append(Pattern.quote(current));
                lastWasAny = false;
                index += width;
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    // ---------------------------------------------------------------- 函数

    static Object applyFunction(String name, Object value, int position) throws FilterException {
        switch (name) {
            case "lower":
                return requireString(name, value, position).toLowerCase(Locale.ROOT);
            case "upper":
                return requireString(name, value, position).toUpperCase(Locale.ROOT);
            case "trim": {
                String text = requireString(name, value, position);
                int start = 0;
                int end = text.length();
                while (start < end && text.charAt(start) == ' ') {
                    start++;
                }
                while (end > start && text.charAt(end - 1) == ' ') {
                    end--;
                }
                return text.substring(start, end);
            }
            case "length": {
                if (value instanceof String text) {
                    return text.codePointCount(0, text.length());
                }
                if (value instanceof List<?> list) {
                    return list.size();
                }
                throw FilterException.at("function 'length' expects a string or list, got "
                        + typeName(value), position);
            }
            default:
                throw FilterException.at("unknown function '" + name + "'", position);
        }
    }

    private static String requireString(String name, Object value, int position) throws FilterException {
        if (value instanceof String text) {
            return text;
        }
        throw FilterException.at("function '" + name + "' expects a string, got " + typeName(value),
                position);
    }

    // ---------------------------------------------------------------- 展示

    static String typeName(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof String) {
            return "VARCHAR";
        }
        if (value instanceof Boolean) {
            return "BOOLEAN";
        }
        if (value instanceof BigDecimal) {
            return "DECIMAL";
        }
        if (value instanceof Double || value instanceof Float) {
            return "DOUBLE";
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return "INTEGER";
        }
        if (value instanceof Long) {
            return "BIGINT";
        }
        if (value instanceof List) {
            return "LIST";
        }
        if (value instanceof java.util.Map) {
            return "MAP";
        }
        return value.getClass().getSimpleName().toUpperCase(Locale.ROOT);
    }

    static String literalLabel(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof String text) {
            return "'" + text + "'";
        }
        return String.valueOf(value);
    }
}
