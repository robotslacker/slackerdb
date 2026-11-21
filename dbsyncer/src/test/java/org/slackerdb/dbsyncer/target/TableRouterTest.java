package org.slackerdb.dbsyncer.target;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 常量列的类型推导（README 承诺"由字面量推导"，这里守住该行为）。 */
class TableRouterTest {

    @Test
    void infersConstantTypesFromLiterals() {
        assertEquals("BIGINT", TableRouter.inferConstantType("42"));
        assertEquals("BIGINT", TableRouter.inferConstantType("-7"));
        assertEquals("DECIMAL(3,2)", TableRouter.inferConstantType("1.25"));
        assertEquals("DECIMAL(2,2)", TableRouter.inferConstantType(".50"));
        assertEquals("DOUBLE", TableRouter.inferConstantType("1.5e3"));
        assertEquals("DOUBLE", TableRouter.inferConstantType("-2E-4"));
        assertEquals("BOOLEAN", TableRouter.inferConstantType("TRUE"));
        assertEquals("BOOLEAN", TableRouter.inferConstantType("false"));
        assertEquals("VARCHAR", TableRouter.inferConstantType("pg_app"));
        assertEquals("VARCHAR", TableRouter.inferConstantType("2026-01-02"));
        assertEquals("VARCHAR", TableRouter.inferConstantType(" "));
        assertEquals("VARCHAR", TableRouter.inferConstantType(null));
        // 超出 long 但没有超过 38 位：用 DECIMAL(38,0) 保精度
        assertEquals("DECIMAL(38,0)", TableRouter.inferConstantType("123456789012345678901"));
    }
}
