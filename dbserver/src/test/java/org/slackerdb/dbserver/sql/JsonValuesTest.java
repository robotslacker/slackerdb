package org.slackerdb.dbserver.sql;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JsonValues} 单元测试。
 *
 * <p>回归背景：DATE/TIME/TIMESTAMPTZ/BLOB/LIST/JSON 这些 DuckDB 取值直接交给 JSON
 * 序列化器会失败；HTTP 面表现为 500/内部错误，SQL REPL 面更糟——异常被吞掉，客户端
 * 完全收不到响应。这里锁定"一定会被转成 JSON 安全形态"的契约。</p>
 */
class JsonValuesTest {

    @Test
    void scalarValuesPassThrough() {
        assertEquals("x", JsonValues.toJsonValue("x"));
        assertEquals(Boolean.TRUE, JsonValues.toJsonValue(Boolean.TRUE));
        assertEquals(42, JsonValues.toJsonValue(42));
        assertEquals(new BigDecimal("1.25"), JsonValues.toJsonValue(new BigDecimal("1.25")));
        assertNull(JsonValues.toJsonValue(null));
    }

    @Test
    void temporalValuesBecomeIsoStrings() {
        assertEquals("2026-10-09", JsonValues.toJsonValue(LocalDate.of(2026, 10, 9)));
        assertEquals("12:34:56", JsonValues.toJsonValue(LocalTime.of(12, 34, 56)));
        assertEquals("2026-10-09T12:34:56", JsonValues.toJsonValue(LocalDateTime.of(2026, 10, 9, 12, 34, 56)));
        assertEquals("2026-10-09T12:34:56+08:00",
                JsonValues.toJsonValue(OffsetDateTime.parse("2026-10-09T12:34:56+08:00")));
        // java.sql 家族（早于 java.util.Date 判断，不能被当成 epoch 毫秒）
        assertEquals("2026-10-09T12:34:56", JsonValues.toJsonValue(Timestamp.valueOf("2026-10-09 12:34:56")));
        assertEquals("2026-10-09", JsonValues.toJsonValue(java.sql.Date.valueOf("2026-10-09")));
        assertEquals("12:34:56", JsonValues.toJsonValue(java.sql.Time.valueOf("12:34:56")));
    }

    @Test
    void binaryBecomesBase64() {
        assertEquals("3q0=", JsonValues.toJsonValue(new byte[]{(byte) 0xDE, (byte) 0xAD}));
        assertEquals("3q0=", JsonValues.toJsonValue(
                new ByteArrayInputStream(new byte[]{(byte) 0xDE, (byte) 0xAD})));
    }

    @Test
    void uuidAndPlainDateBecomeStrings() {
        UUID uuid = UUID.fromString("2f8b1b1e-4a5b-4c6d-8e7f-0a1b2c3d4e5f");
        assertEquals(uuid.toString(), JsonValues.toJsonValue(uuid));
        long millis = 1786883696000L;
        assertEquals(java.time.Instant.ofEpochMilli(millis).toString(),
                JsonValues.toJsonValue(new java.util.Date(millis)));
    }

    @Test
    void containersAreConvertedRecursively() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("d", LocalDate.of(2026, 10, 9));
        input.put("list", List.of(1, LocalTime.of(1, 2, 3)));
        input.put("nested", Map.of("raw", new byte[]{1}));

        Object converted = JsonValues.toJsonValue(input);
        assertInstanceOf(Map.class, converted);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) converted;
        assertEquals("2026-10-09", map.get("d"));
        assertEquals(List.of(1, "01:02:03"), map.get("list"));
        assertInstanceOf(Map.class, map.get("nested"));
    }

    @Test
    void fastjson2JsonBecomesPlainJson() {
        Object converted = JsonValues.toJsonValue(JSON.parse("{\"a\":[1,true,\"x\"],\"b\":null}"));
        assertInstanceOf(Map.class, converted);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) converted;
        assertEquals(List.of(1, Boolean.TRUE, "x"), map.get("a"));
        assertNull(map.get("b"));
    }

    @Test
    void structFieldNamesAreParsedFromTypeName() {
        assertEquals(List.of("a", "b"), JsonValues.structFieldNames("STRUCT(a INTEGER, b VARCHAR)", 2));
        assertEquals(List.of("a", "s"), JsonValues.structFieldNames("STRUCT(a INTEGER, s STRUCT(x INTEGER))", 2));
        assertEquals(List.of("a"), JsonValues.structFieldNames("STRUCT(a INTEGER)", 1));
        // 解析不出来时退化为 f1..fN，绝不抛异常
        assertEquals(List.of("f1", "f2"), JsonValues.structFieldNames("VARCHAR", 2));
        assertEquals(List.of("f1", "f2"), JsonValues.structFieldNames(null, 2));
        assertEquals(List.of("f1", "f2"), JsonValues.structFieldNames("STRUCT(a INTEGER)", 2));
    }

    @Test
    void unknownTypesDegradeToStringInsteadOfFailing() {
        Object weird = new Object() {
            @Override
            public String toString() {
                return "weird-value";
            }
        };
        assertEquals("weird-value", JsonValues.toJsonValue(weird));
        // SQLException 实现了 Iterable<Throwable>（遍历 cause 链），无 cause 时迭代器返回自身；
        // 若按 Iterable 递归会直接栈溢出。
        assertTrue(JsonValues.toJsonValue(new java.sql.SQLException("boom")) instanceof String);
        assertTrue(JsonValues.toJsonValue(new IllegalStateException("nope")).toString().contains("nope"));
    }

    @Test
    void jsonTextBecomesPlainJson() {
        // DuckDB 的 JSON 列在运行时是 org.duckdb.JsonNode，该分支通过软检测触发；
        // 这里用等价的 fastjson2 JSON 值覆盖同样的语义。
        assertEquals("plain", JsonValues.toJsonValue(JSON.parse("\"plain\"")));
        Object one = JsonValues.toJsonValue(JSON.parse("1"));
        assertInstanceOf(Number.class, one);
        assertEquals(1, ((Number) one).intValue());
        assertEquals(List.of(1, 2), JsonValues.toJsonValue(JSON.parse("[1,2]")));
        assertEquals(new BigDecimal("1.25"),
                JsonValues.toJsonValue(JSON.parse("1.25")));
    }
}
