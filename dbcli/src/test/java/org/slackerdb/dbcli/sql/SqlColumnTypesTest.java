package org.slackerdb.dbcli.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 列类型名映射的契约测试：类型名由 {@code java.sql.Types} 的常量反射得到，
 * 表格对齐规则依赖这些**大写**名字。
 */
class SqlColumnTypesTest {

    @Test
    @DisplayName("反射到的类型表足够大，且常见类型名是大写")
    void reflectsJavaSqlTypes() {
        assertTrue(SqlColumnTypes.registeredCount() > 20,
                "应当反射到 java.sql.Types 的几十个常量，实际 " + SqlColumnTypes.registeredCount());
        assertEquals("VARCHAR", SqlColumnTypes.nameOf(Types.VARCHAR, "varchar"));
        assertEquals("INTEGER", SqlColumnTypes.nameOf(Types.INTEGER, "int4"));
        assertEquals("DATE", SqlColumnTypes.nameOf(Types.DATE, "date"));
        assertEquals("ARRAY", SqlColumnTypes.nameOf(Types.ARRAY, "array"));
    }

    @Test
    @DisplayName("jdbcType==0 → null；未登记类型 → 用驱动返回的名字")
    void fallbacks() {
        assertNull(SqlColumnTypes.nameOf(0, "whatever"), "类型 0 表示 SQL NULL，返回 null");
        assertEquals("timestamptz", SqlColumnTypes.nameOf(Integer.MAX_VALUE, "timestamptz"),
                "表里没有的类型要回退到驱动名字");
    }
}
