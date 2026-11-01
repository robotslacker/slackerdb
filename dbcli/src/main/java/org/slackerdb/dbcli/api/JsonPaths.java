package org.slackerdb.dbcli.api;

import com.alibaba.fastjson2.JSON;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 JSON 响应展开成 {@code API.JSON.<path>} 标量变量。
 *
 * <p>只展开标量叶子：字符串、数字、布尔。对象/数组本身不注册（避免把整棵子树复制成变量）；
 * {@code null} 不注册（断言取值 {@code null}）；数组下标用点号（{@code items.0.id}）。</p>
 *
 * <p>限制：最多 {@value #MAX_VALUES} 个叶子、深度 {@value #MAX_DEPTH}；超出部分静默忽略，
 * 不影响请求本身的成功/失败。</p>
 */
public final class JsonPaths {

    /** 最多展开的标量叶子数。 */
    public static final int MAX_VALUES = 100_000;

    /** 最大递归深度。 */
    public static final int MAX_DEPTH = 64;

    private JsonPaths() {
    }

    /**
     * 展开 JSON 文本；非法 JSON 返回空表（不抛异常）。
     */
    public static Map<String, String> flatten(String json) {
        Map<String, String> values = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return values;
        }
        Object root;
        try {
            root = JSON.parse(json);
        } catch (RuntimeException invalidJson) {
            return values;
        }
        int[] count = {0};
        walk(root, "", 0, values, count);
        return values;
    }

    private static void walk(Object value, String path, int depth,
                             Map<String, String> values, int[] count) {
        if (count[0] >= MAX_VALUES || depth > MAX_DEPTH || value == null) {
            return;
        }
        if (value instanceof Map<?, ?> object) {
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                walk(entry.getValue(), child(path, key), depth + 1, values, count);
                if (count[0] >= MAX_VALUES) {
                    return;
                }
            }
            return;
        }
        if (value instanceof List<?> array) {
            for (int index = 0; index < array.size(); index++) {
                walk(array.get(index), child(path, String.valueOf(index)), depth + 1, values, count);
                if (count[0] >= MAX_VALUES) {
                    return;
                }
            }
            return;
        }
        String text = scalarText(value);
        if (text != null && !path.isEmpty()) {
            values.put(path, text);
            count[0] = count[0] + 1;
        }
    }

    private static String child(String parent, String key) {
        return parent.isEmpty() ? key : parent + "." + key;
    }

    private static String scalarText(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Boolean bool) {
            return bool ? "true" : "false";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof BigInteger integer) {
            return integer.toString();
        }
        if (value instanceof Double || value instanceof Float) {
            return BigDecimal.valueOf(((Number) value).doubleValue()).toPlainString();
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        return null;
    }
}
