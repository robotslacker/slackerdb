package org.slackerdb.plsql.types;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3：类型转换矩阵。
 *
 * <p>期望值<b>显式写在数据表里</b>，不从 {@link Coercions} 推导——避免"同源错误一起绿"。
 * 覆盖：15 类源值 × 6 个代表性目标类型 + 边界/特例。</p>
 */
class TypeCoercionTest {

    /** 一条用例：源值 → 目标类型 → 期望（成功时给规范文本，失败时给 SQLSTATE，前缀 E:）。 */
    record Case(String name, Object source, String targetType, String expected) {
        @Override
        public String toString() {
            return name + " -> " + targetType;
        }
    }

    private static Case ok(String name, Object source, String target, String expected) {
        return new Case(name, source, target, expected);
    }

    private static Case bad(String name, Object source, String target, String sqlState) {
        return new Case(name, source, target, "E:" + sqlState);
    }

    private static String canonical(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Float || value instanceof Double) {
            return new BigDecimal(String.valueOf(value)).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    static Stream<Case> matrix() {
        List<Case> cases = new ArrayList<>();
        String longText = "0123456789abcdef";

        // ---------- 目标 INTEGER ----------
        cases.add(ok("int5", 5, "INTEGER", "5"));
        cases.add(ok("intRounds", new BigDecimal("1.5"), "INTEGER", "2"));
        cases.add(ok("intRoundsNeg", new BigDecimal("-1.5"), "INTEGER", "-2"));
        cases.add(ok("intMax", Integer.MAX_VALUE, "INTEGER", String.valueOf(Integer.MAX_VALUE)));
        cases.add(bad("intOverflow", 2147483648L, "INTEGER", "22003"));
        cases.add(bad("intBigOverflow", new BigInteger("9223372036854775808"), "INTEGER", "22003"));
        cases.add(ok("intFromString", "123", "INTEGER", "123"));
        cases.add(bad("intFromBadString", "abc", "INTEGER", "22P02"));
        cases.add(bad("intFromEmptyString", "", "INTEGER", "22P02"));
        cases.add(bad("intFromBoolean", Boolean.TRUE, "INTEGER", "42804"));
        cases.add(bad("intFromDateString", "2024-07-08", "INTEGER", "22P02"));
        cases.add(ok("intNull", null, "INTEGER", "null"));

        // ---------- 目标 BIGINT ----------
        cases.add(ok("bigintFromLong", 9223372036854775807L, "BIGINT", "9223372036854775807"));
        cases.add(bad("bigintOverflow", new BigInteger("9223372036854775808"), "BIGINT", "22003"));
        cases.add(ok("bigintFromString", "42", "BIGINT", "42"));
        cases.add(ok("bigintNull", null, "BIGINT", "null"));

        // ---------- 目标 SMALLINT ----------
        cases.add(ok("smallintMax", 32767, "SMALLINT", "32767"));
        cases.add(bad("smallintOverflow", 32768, "SMALLINT", "22003"));
        cases.add(ok("smallintNull", null, "SMALLINT", "null"));

        // ---------- 目标 DECIMAL(10,2) ----------
        cases.add(ok("decimalRounds", new BigDecimal("1.005"), "DECIMAL(10,2)", "1.01"));
        cases.add(ok("decimalRoundsDown", new BigDecimal("1.004"), "DECIMAL(10,2)", "1"));
        cases.add(ok("decimalMax", new BigDecimal("99999999.99"), "DECIMAL(10,2)", "99999999.99"));
        cases.add(bad("decimalOverflow", new BigDecimal("100000000.00"), "DECIMAL(10,2)", "22003"));
        cases.add(ok("decimalFromString", "12.345", "DECIMAL(10,2)", "12.35"));
        cases.add(bad("decimalFromBadString", "1.2.3", "DECIMAL(10,2)", "22P02"));
        cases.add(ok("decimalNull", null, "DECIMAL(10,2)", "null"));

        // ---------- 目标 DOUBLE / REAL ----------
        cases.add(ok("doubleFromInt", 3, "DOUBLE", "3"));
        cases.add(ok("doubleFromString", "1.25", "DOUBLE", "1.25"));
        cases.add(ok("realFromDecimal", new BigDecimal("3.04"), "REAL", "3.04"));
        cases.add(bad("doubleFromBadString", "x1", "DOUBLE", "22P02"));
        cases.add(ok("doubleNull", null, "DOUBLE", "null"));

        // ---------- 目标 VARCHAR(5) / TEXT ----------
        cases.add(ok("varcharOk", "abcde", "VARCHAR(5)", "abcde"));
        cases.add(bad("varcharTooLong", "abcdef", "VARCHAR(5)", "22001"));
        cases.add(bad("varcharTooLongUnicode", "你好世界啊哈", "VARCHAR(5)", "22001"));
        cases.add(ok("varcharUnicodeOk", "你好世界", "VARCHAR(5)", "你好世界"));
        cases.add(ok("varcharFromInt", 12345, "VARCHAR(5)", "12345"));
        cases.add(bad("varcharFromIntTooLong", 123456, "VARCHAR(5)", "22001"));
        cases.add(ok("varcharFromDecimal", new BigDecimal("1.50"), "VARCHAR(5)", "1.50"));
        cases.add(bad("varcharFromBoolean", Boolean.TRUE, "VARCHAR(5)", "42804"));
        cases.add(ok("textFromAnything", new BigDecimal("2.50"), "TEXT", "2.50"));
        cases.add(ok("textLong", longText, "TEXT", longText));
        cases.add(ok("charPadFree", "ab", "CHAR(5)", "ab"));
        cases.add(bad("charTooLong", "abcdef", "CHAR(5)", "22001"));
        cases.add(ok("varcharNull", null, "VARCHAR(5)", "null"));

        // ---------- 目标 BOOLEAN ----------
        cases.add(ok("booleanTrue", Boolean.TRUE, "BOOLEAN", "true"));
        cases.add(ok("booleanFalse", Boolean.FALSE, "BOOLEAN", "false"));
        cases.add(bad("booleanFromStringTrue", "true", "BOOLEAN", "42804"));
        cases.add(bad("booleanFromInt", 1, "BOOLEAN", "42804"));
        cases.add(bad("booleanFromString1", "1", "BOOLEAN", "42804"));
        cases.add(ok("booleanNull", null, "BOOLEAN", "null"));

        // ---------- 目标 DATE ----------
        cases.add(ok("dateFromString", "2024-07-08", "DATE", "2024-07-08"));
        cases.add(ok("dateFromTimestampString", "2024-07-08 23:12:31", "DATE", "2024-07-08"));
        cases.add(ok("dateFromDate", Date.valueOf("2024-07-08"), "DATE", "2024-07-08"));
        cases.add(ok("dateFromTimestamp", Timestamp.valueOf("2024-07-08 23:12:31"), "DATE", "2024-07-08"));
        cases.add(bad("dateFromBadString", "2024/07/08", "DATE", "22007"));
        cases.add(bad("dateFromInt", 20240708, "DATE", "42804"));
        cases.add(ok("dateNull", null, "DATE", "null"));

        // ---------- 目标 TIME ----------
        cases.add(ok("timeFromString", "23:12:31", "TIME", "23:12:31"));
        cases.add(ok("timeFromTime", Time.valueOf("23:12:31"), "TIME", "23:12:31"));
        cases.add(bad("timeFromBadString", "25:99:99", "TIME", "22007"));
        cases.add(ok("timeNull", null, "TIME", "null"));

        // ---------- 目标 TIMESTAMP ----------
        cases.add(ok("tsFromString", "2024-07-08 23:12:31", "TIMESTAMP", "2024-07-08 23:12:31.0"));
        cases.add(ok("tsFromDateOnly", "2024-07-08", "TIMESTAMP", "2024-07-08 00:00:00.0"));
        cases.add(ok("tsFromDate", Date.valueOf("2024-07-08"), "TIMESTAMP", "2024-07-08 00:00:00.0"));
        cases.add(ok("tsFromTimestamp", Timestamp.valueOf("2024-10-08 23:12:31"), "TIMESTAMP",
                "2024-10-08 23:12:31.0"));
        cases.add(bad("tsFromBadString", "not-a-date", "TIMESTAMP", "22007"));
        cases.add(ok("tsNull", null, "TIMESTAMP", "null"));

        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    void conversionMatrix(Case testCase) {
        PlType target = PlType.parse(testCase.targetType());
        assertTrue(target != null, "类型未识别：" + testCase.targetType());

        if (testCase.expected().startsWith("E:")) {
            String expectedState = testCase.expected().substring(2);
            PlSqlTypeException error = assertThrows(PlSqlTypeException.class,
                    () -> Coercions.convert(testCase.source(), target),
                    "期望转换失败：" + testCase);
            assertEquals(expectedState, error.getSqlState(), "SQLSTATE 不符：" + testCase);
            return;
        }

        Object converted = Coercions.convert(testCase.source(), target);
        assertEquals(testCase.expected(), canonical(converted), "转换结果不符：" + testCase);
    }

    /** 类型文本解析（声明里怎么写都能认）。 */
    @Test
    void typeParsing() {
        assertEquals(PlType.INTEGER, PlType.parse("int"));
        assertEquals(PlType.INTEGER, PlType.parse("INTEGER"));
        assertEquals(PlType.SMALLINT, PlType.parse("smallint"));
        assertEquals(PlType.BIGINT, PlType.parse("BigInt"));
        assertEquals(PlType.REAL, PlType.parse("float"));
        assertEquals(PlType.REAL, PlType.parse("real"));
        assertEquals(PlType.DOUBLE, PlType.parse("double precision"));
        assertEquals(PlType.TEXT, PlType.parse("text"));
        assertEquals(PlType.BOOLEAN, PlType.parse("boolean"));
        assertEquals(PlType.DATE, PlType.parse("date"));
        assertEquals(PlType.TIME, PlType.parse("time"));
        assertEquals(PlType.TIMESTAMP, PlType.parse("timestamp"));
        assertEquals(PlType.TIMESTAMP_TZ, PlType.parse("timestamp with time zone"));
        assertEquals(PlType.TIMESTAMP, PlType.parse("TIMESTAMP WITHOUT TIME ZONE"));
        assertEquals(PlType.INTERVAL, PlType.parse("interval"));
        assertEquals(PlType.varchar(10), PlType.parse("varchar(10)"));
        assertEquals(PlType.varchar(10), PlType.parse(" VARCHAR ( 10 ) "));
        assertEquals(PlType.character(3), PlType.parse("char(3)"));
        assertEquals(PlType.decimal(10, 2), PlType.parse("decimal(10,2)"));
        assertEquals(PlType.decimal(10, 2), PlType.parse("NUMERIC(10, 2)"));
        // 未知类型：由调用方报 42601
        assertNull(PlType.parse("nosuchtype"));
    }

    /** 未初始化变量 = NULL（修正历史实现默认 0 的语义）。 */
    @Test
    void uninitializedIsNull() {
        PlValue value = PlValue.nullOf(PlType.INTEGER);
        assertTrue(value.isNull());
        assertNull(Coercions.convert(null, PlType.INTEGER));
    }

    /** 数值收窄的四舍五入语义要显式锁住（后端 CAST 行为可能随版本变化）。 */
    @Test
    void roundingIsHalfUp() {
        assertEquals(2, Coercions.convert(new BigDecimal("1.5"), PlType.INTEGER));
        assertEquals(-2, Coercions.convert(new BigDecimal("-1.5"), PlType.INTEGER));
        assertEquals("2.5", canonical(Coercions.convert(new BigDecimal("2.5"), PlType.decimal(38, 10))));
    }

    /** 大数保持双精度（不做十进制展开）。 */
    @Test
    void largeDoubleStaysDouble() {
        Object converted = Coercions.convert(1e308d, PlType.DOUBLE);
        assertEquals(1e308d, (Double) converted);
    }
}
