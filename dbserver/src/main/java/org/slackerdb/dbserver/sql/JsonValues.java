package org.slackerdb.dbserver.sql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把 JDBC / DuckDB 取出的原始值转换成**可安全 JSON 序列化**的形态。
 *
 * <p>存在的原因：DuckDB JDBC 对 DATE / TIME / TIMESTAMPTZ 返回 {@code java.time} 类型，
 * 对 BLOB 返回 {@code InputStream}，对 LIST/ARRAY 返回 {@code java.sql.Array} 实现，
 * 对 JSON 返回 Jackson {@code JsonNode}，对 STRUCT 返回 {@code java.sql.Struct}。
 * 这些类型直接交给 Jackson 的默认 ObjectMapper 会抛异常（HTTP 面变成 500/内部错误，
 * REPL 面更糟：异常被吞掉，客户端永远收不到响应）。</p>
 *
 * <p>约定（HTTP 数据服务与 SQL REPL 共用同一套转换，保证两个端点返回值一致）：</p>
 * <ul>
 *   <li>日期时间统一为 ISO-8601 字符串：{@code 2026-10-09} / {@code 12:34:56} /
 *       {@code 2026-10-09T12:34:56} / {@code 2026-10-09T12:34:56Z}；</li>
 *   <li>BLOB / 二进制统一为 Base64 字符串；</li>
 *   <li>LIST / ARRAY 为 JSON 数组，MAP / STRUCT 为 JSON 对象（STRUCT 使用字段名）；</li>
 *   <li>JSON 列还原为真实 JSON 值，而不是 JsonNode 的内部字段；</li>
 *   <li>无法识别的类型退化为字符串，绝不因为"序列化不了"而让整个响应失败。</li>
 * </ul>
 */
public final class JsonValues {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * DuckDB 的 JSON 列返回 {@code org.duckdb.JsonNode}——它不是 Jackson 的 JsonNode，
     * 也没有公开的取值方法，只有 {@code isArray()/isObject()/...} 和 {@code toString()}。
     * 用类名做软检测（而不是直接 import），避免把本工具类硬绑定到某个驱动版本。
     */
    private static final Class<?> DUCKDB_JSON_NODE = resolveClass("org.duckdb.JsonNode");

    private JsonValues() {
    }

    private static Class<?> resolveClass(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 转换单个值。返回的对象只包含 String / Number / Boolean / null / Map / List，
     * 因此任何 JSON 序列化器都能处理。
     */
    public static Object toJsonValue(Object value) {
        if (value == null) {
            return null;
        }
        // 已经是 JSON 原生类型
        if (value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        // java.time 家族
        if (value instanceof java.time.LocalDate v) {
            return v.toString();
        }
        if (value instanceof java.time.LocalTime v) {
            return v.toString();
        }
        if (value instanceof java.time.LocalDateTime v) {
            return v.toString();
        }
        if (value instanceof java.time.OffsetDateTime v) {
            return v.toString();
        }
        if (value instanceof java.time.OffsetTime v) {
            return v.toString();
        }
        if (value instanceof java.time.ZonedDateTime v) {
            return v.toString();
        }
        if (value instanceof java.time.Instant v) {
            return v.toString();
        }
        if (value instanceof java.time.Year v) {
            return v.toString();
        }
        if (value instanceof java.time.YearMonth v) {
            return v.toString();
        }
        if (value instanceof java.time.Duration v) {
            return v.toString();
        }
        if (value instanceof java.time.Period v) {
            return v.toString();
        }
        // java.sql 日期时间（必须早于 java.util.Date 判断）
        if (value instanceof java.sql.Timestamp v) {
            return v.toLocalDateTime().toString();
        }
        if (value instanceof java.sql.Date v) {
            return v.toLocalDate().toString();
        }
        if (value instanceof java.sql.Time v) {
            return v.toLocalTime().toString();
        }
        if (value instanceof java.util.Date v) {
            return java.time.Instant.ofEpochMilli(v.getTime()).toString();
        }
        if (value instanceof UUID v) {
            return v.toString();
        }
        // JSON 列
        if (value instanceof JsonNode v) {
            return jsonNode(v);
        }
        if (DUCKDB_JSON_NODE != null && DUCKDB_JSON_NODE.isInstance(value)) {
            return duckDbJsonNode(value);
        }
        // 二进制
        if (value instanceof byte[] v) {
            return Base64.getEncoder().encodeToString(v);
        }
        if (value instanceof InputStream v) {
            return Base64.getEncoder().encodeToString(readBytes(v));
        }
        if (value instanceof Blob v) {
            return Base64.getEncoder().encodeToString(readBlob(v));
        }
        if (value instanceof Clob v) {
            return readClob(v);
        }
        if (value instanceof Reader v) {
            return readReader(v);
        }
        // 复合类型
        // 注意：Throwable 必须早于 Iterable 判断——SQLException 实现了 Iterable<Throwable>
        // （遍历异常的 cause 链），无 cause 时迭代器会返回异常自身，直接递归会栈溢出。
        if (value instanceof Throwable v) {
            return fallback(v);
        }
        if (value instanceof java.sql.Array v) {
            return sqlArray(v);
        }
        if (value instanceof Struct v) {
            return sqlStruct(v);
        }
        if (value instanceof Map<?, ?> v) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : v.entrySet()) {
                if (entry.getValue() == value) {
                    continue;   // 自引用容器：跳过，避免无限递归
                }
                mapped.put(String.valueOf(entry.getKey()), toJsonValue(entry.getValue()));
            }
            return mapped;
        }
        if (value instanceof Iterable<?> v) {
            List<Object> list = new ArrayList<>();
            for (Object element : v) {
                if (element == value) {
                    continue;   // 同上
                }
                list.add(toJsonValue(element));
            }
            return list;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> list = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                list.add(toJsonValue(java.lang.reflect.Array.get(value, i)));
            }
            return list;
        }
        // 其余类型（含各类 DuckDB 私有类型）退化为字符串，宁可表示粗糙也不能让响应整体失败
        return fallback(value);
    }

    /** DuckDB JSON 列：按其 JSON 文本解析成真实 JSON 值（解析失败则退回文本）。 */
    private static Object duckDbJsonNode(Object value) {
        String text = String.valueOf(value);
        try {
            return jsonNode(MAPPER.readTree(text));
        } catch (Exception e) {
            return text;
        }
    }

    private static Object jsonNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            BigDecimal decimal = node.decimalValue();
            return decimal;
        }
        if (node.isBinary()) {
            try {
                return Base64.getEncoder().encodeToString(node.binaryValue());
            } catch (Exception e) {
                return null;
            }
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            for (JsonNode element : node) {
                list.add(jsonNode(element));
            }
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                mapped.put(name, jsonNode(node.get(name)));
            }
            return mapped;
        }
        return node.asText();
    }

    private static Object sqlArray(java.sql.Array array) {
        Object raw;
        try {
            raw = array.getArray();
        } catch (Exception e) {
            return fallback(array);
        }
        if (raw instanceof ResultSet resultSet) {
            // JDBC 允许驱动用 ResultSet 表示数组内容（每行一个元素）
            List<Object> list = new ArrayList<>();
            try (ResultSet rs = resultSet) {
                while (rs.next()) {
                    list.add(toJsonValue(rs.getObject(1)));
                }
            } catch (SQLException e) {
                return fallback(array);
            }
            return list;
        }
        try {
            return toJsonValue(raw);
        } catch (Exception e) {
            return fallback(array);
        }
    }

    private static Object sqlStruct(Struct struct) {
        Object[] attributes;
        String typeName;
        try {
            attributes = struct.getAttributes();
            typeName = struct.getSQLTypeName();
        } catch (Exception e) {
            return fallback(struct);
        }
        if (attributes == null) {
            return null;
        }
        List<String> names = structFieldNames(typeName, attributes.length);
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (int i = 0; i < attributes.length; i++) {
            mapped.put(names.get(i), toJsonValue(attributes[i]));
        }
        return mapped;
    }

    /**
     * 从 DuckDB 的 STRUCT 类型名（如 {@code STRUCT(a INTEGER, b VARCHAR)}）中取出字段名。
     * 任何解析不出来的情况都退化为 {@code f1..fN}，保证不抛异常。
     */
    static List<String> structFieldNames(String typeName, int fieldCount) {
        List<String> fallbackNames = new ArrayList<>(fieldCount);
        for (int i = 0; i < fieldCount; i++) {
            fallbackNames.add("f" + (i + 1));
        }
        if (typeName == null) {
            return fallbackNames;
        }
        String text = typeName.trim();
        if (!text.regionMatches(true, 0, "STRUCT", 0, 6)) {
            return fallbackNames;
        }
        int open = text.indexOf('(');
        int close = text.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return fallbackNames;
        }
        String body = text.substring(open + 1, close);
        List<String> parts = splitTopLevel(body);
        if (parts.size() != fieldCount) {
            return fallbackNames;
        }
        List<String> names = new ArrayList<>(fieldCount);
        for (String part : parts) {
            String field = part.trim();
            int space = field.indexOf(' ');
            String name = space < 0 ? "" : field.substring(0, space).trim();
            if (name.isEmpty() || !isIdentifier(name)) {
                return fallbackNames;
            }
            names.add(name);
        }
        return names;
    }

    /** 按顶层逗号切分（忽略括号内的逗号）。 */
    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'') {
                quoted = !quoted;
            }
            if (!quoted) {
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                } else if (ch == ',' && depth == 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(ch);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static boolean isIdentifier(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean ok = Character.isLetterOrDigit(ch) || ch == '_' || ch == '$';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static byte[] readBlob(Blob blob) {
        try (InputStream in = blob.getBinaryStream()) {
            return readBytes(in);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static String readClob(Clob clob) {
        try (Reader reader = clob.getCharacterStream()) {
            return readReader(reader);
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] readBytes(InputStream in) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static String readReader(Reader reader) {
        try {
            StringBuilder text = new StringBuilder();
            char[] chunk = new char[4096];
            int read;
            while ((read = reader.read(chunk)) > 0) {
                text.append(chunk, 0, read);
            }
            return text.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static Object fallback(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return String.valueOf(value);
        } catch (Exception e) {
            return "<" + value.getClass().getName() + ">";
        }
    }
}
