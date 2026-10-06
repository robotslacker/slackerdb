package org.slackerdb.plsql.types;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Locale;

/**
 * PL/SQL 类型。
 *
 */
public record PlType(Kind kind, int precision, int scale, int length, boolean withTimeZone) {

    public enum Kind {
        INTEGER, SMALLINT, BIGINT, DECIMAL, REAL, DOUBLE,
        VARCHAR, CHAR, TEXT,
        BOOLEAN, DATE, TIME, TIMESTAMP, TIMESTAMP_TZ, INTERVAL,
        /**
         * 未确定类型（NULL 字面量、尚未赋值的场景）。
         */
        UNKNOWN
    }

    public static final PlType INTEGER = new PlType(Kind.INTEGER, 0, 0, 0, false);
    public static final PlType SMALLINT = new PlType(Kind.SMALLINT, 0, 0, 0, false);
    public static final PlType BIGINT = new PlType(Kind.BIGINT, 0, 0, 0, false);
    public static final PlType REAL = new PlType(Kind.REAL, 0, 0, 0, false);
    public static final PlType DOUBLE = new PlType(Kind.DOUBLE, 0, 0, 0, false);
    public static final PlType TEXT = new PlType(Kind.TEXT, 0, 0, 0, false);
    public static final PlType BOOLEAN = new PlType(Kind.BOOLEAN, 0, 0, 0, false);
    public static final PlType DATE = new PlType(Kind.DATE, 0, 0, 0, false);
    public static final PlType TIME = new PlType(Kind.TIME, 0, 0, 0, false);
    public static final PlType TIMESTAMP = new PlType(Kind.TIMESTAMP, 0, 0, 0, false);
    public static final PlType TIMESTAMP_TZ = new PlType(Kind.TIMESTAMP_TZ, 0, 0, 0, true);
    public static final PlType INTERVAL = new PlType(Kind.INTERVAL, 0, 0, 0, false);
    public static final PlType UNKNOWN = new PlType(Kind.UNKNOWN, 0, 0, 0, false);

    public static PlType decimal(int precision, int scale) {
        return new PlType(Kind.DECIMAL, precision, scale, 0, false);
    }

    public static PlType varchar(int length) {
        return new PlType(Kind.VARCHAR, 0, 0, length, false);
    }

    public static PlType character(int length) {
        return new PlType(Kind.CHAR, 0, 0, length, false);
    }

    public static PlType timestamp(boolean withTimeZone) {
        return withTimeZone ? TIMESTAMP_TZ : TIMESTAMP;
    }

    public boolean isNumeric() {
        return switch (kind) {
            case INTEGER, SMALLINT, BIGINT, DECIMAL, REAL, DOUBLE -> true;
            default -> false;
        };
    }

    public boolean isString() {
        return switch (kind) {
            case VARCHAR, CHAR, TEXT -> true;
            default -> false;
        };
    }

    public boolean isUnknown() {
        return kind == Kind.UNKNOWN;
    }

    /**
     * 解析声明的类型文本（大小写不敏感、允许括号参数）。
     *
     * @return 无法识别的类型返回 {@code null}（由调用方报 42601）
     */
    public static PlType parse(String typeText) {
        if (typeText == null) {
            return null;
        }
        String text = typeText.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        String base = text;
        int open = text.indexOf('(');
        if (open >= 0) {
            base = text.substring(0, open).trim();
        }
        if (base.startsWith("TIMESTAMP")) {
            return text.contains("WITH TIME ZONE") ? TIMESTAMP_TZ : TIMESTAMP;
        }
        return switch (base) {
            case "INT", "INTEGER", "INT4" -> INTEGER;
            case "SMALLINT", "INT2" -> SMALLINT;
            case "BIGINT", "INT8" -> BIGINT;
            case "REAL", "FLOAT", "FLOAT4" -> REAL;
            case "DOUBLE", "DOUBLE PRECISION", "FLOAT8" -> DOUBLE;
            case "TEXT", "STRING" -> TEXT;
            case "BOOLEAN", "BOOL" -> BOOLEAN;
            case "DATE" -> DATE;
            case "TIME" -> TIME;
            case "INTERVAL" -> INTERVAL;
            case "VARCHAR", "CHARACTER VARYING" -> varchar(arg(text, -1, 0));
            case "CHAR", "CHARACTER" -> character(arg(text, -1, 1));
            case "DECIMAL", "NUMERIC" -> decimal(arg(text, 0, 38), arg(text, 1, 0));
            // 无参数的 NUMBER：PL/SQL 里是任意精度；这里退化为 DOUBLE（保留小数位），
            // 需要精确十进制请显式写 DECIMAL(p,s)。
            case "NUMBER" -> DOUBLE;
            default -> null;
        };
    }

    /**
     * 取括号内第 {@code index} 个参数（{@code index < 0} 表示第 0 个）；缺失时返回 {@code fallback}。
     */
    private static int arg(String text, int index, int fallback) {
        int open = text.indexOf('(');
        int close = text.indexOf(')', open + 1);
        if (open < 0 || close < 0) {
            return fallback;
        }
        String[] parts = text.substring(open + 1, close).split(",");
        int position = Math.max(index, 0);
        if (position >= parts.length) {
            return fallback;
        }
        try {
            return Integer.parseInt(parts[position].trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 由运行时值推断类型（用于列表形式 FOR 循环等无法从声明获知类型的场景）。
     */
    public static PlType of(Object value) {
        if (value == null) {
            return UNKNOWN;
        }
        if (value instanceof Boolean) {
            return BOOLEAN;
        }
        if (value instanceof Short || value instanceof Integer) {
            return INTEGER;
        }
        if (value instanceof Long || value instanceof BigInteger) {
            return BIGINT;
        }
        if (value instanceof BigDecimal) {
            return decimal(38, 10);
        }
        if (value instanceof Float) {
            return REAL;
        }
        if (value instanceof Double) {
            return DOUBLE;
        }
        if (value instanceof Date) {
            return DATE;
        }
        if (value instanceof Time) {
            return TIME;
        }
        if (value instanceof Timestamp) {
            return TIMESTAMP;
        }
        return TEXT;
    }

    /**
     * 用于错误消息与 SQL 转换的类型名。
     */
    public String displayName() {
        return switch (kind) {
            case DECIMAL -> "DECIMAL(" + precision + "," + scale + ")";
            case VARCHAR -> length > 0 ? "VARCHAR(" + length + ")" : "VARCHAR";
            case CHAR -> "CHAR(" + length + ")";
            case TIMESTAMP_TZ -> "TIMESTAMP WITH TIME ZONE";
            default -> kind.name();
        };
    }

    @Override
    public String toString() {
        return displayName();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PlType that)) {
            return false;
        }
        return kind == that.kind && precision == that.precision && scale == that.scale
                && length == that.length && withTimeZone == that.withTimeZone;
    }

}
