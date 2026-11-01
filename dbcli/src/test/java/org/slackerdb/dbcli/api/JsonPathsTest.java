package org.slackerdb.dbcli.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonPathsTest {

    @Test
    @DisplayName("嵌套对象/数组只展开标量叶子")
    void flattensScalarLeaves() {
        Map<String, String> values = JsonPaths.flatten("""
                {
                  "token": "abc",
                  "user": {"id": 42, "name": "demo", "active": true},
                  "items": [{"id": 1}, {"id": 2}],
                  "nothing": null,
                  "object": {"nested": "x"}
                }
                """);

        assertEquals("abc", values.get("token"));
        assertEquals("42", values.get("user.id"));
        assertEquals("demo", values.get("user.name"));
        assertEquals("true", values.get("user.active"));
        assertEquals("1", values.get("items.0.id"));
        assertEquals("2", values.get("items.1.id"));
        assertEquals("x", values.get("object.nested"));
        assertFalse(values.containsKey("nothing"));
        assertFalse(values.containsKey("object"));
    }

    @Test
    @DisplayName("数字不用科学计数法；根数组下标从 0")
    void formatsNumbersAndRootArray() {
        Map<String, String> values = JsonPaths.flatten("[1.5, 100000000000000000000, false]");

        assertEquals("1.5", values.get("0"));
        assertEquals("100000000000000000000", values.get("1"));
        assertEquals("false", values.get("2"));
    }

    @Test
    @DisplayName("非法 JSON 返回空表，不抛异常")
    void invalidJsonIsIgnored() {
        assertTrue(JsonPaths.flatten("not json").isEmpty());
        assertTrue(JsonPaths.flatten("").isEmpty());
        assertTrue(JsonPaths.flatten(null).isEmpty());
    }

    @Test
    @DisplayName("JSON 键区分大小写")
    void keysAreCaseSensitive() {
        Map<String, String> values = JsonPaths.flatten("{\"Token\":\"x\",\"token\":\"y\"}");

        assertEquals("x", values.get("Token"));
        assertEquals("y", values.get("token"));
    }
}
