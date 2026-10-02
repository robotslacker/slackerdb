package org.slackerdb.plsql.types;

/**
 * 类型转换失败（SQLSTATE）。
 */
public class PlSqlTypeException extends RuntimeException {

    /** 数值溢出 / 精度超限。 */
    public static final String NUMERIC_OUT_OF_RANGE = "22003";
    /** 字符串超长。 */
    public static final String STRING_TOO_LONG = "22001";
    /** 字符串无法按目标类型解析（数值）。 */
    public static final String INVALID_TEXT_REPRESENTATION = "22P02";
    /** 日期时间解析失败。 */
    public static final String INVALID_DATETIME = "22007";
    /** 类型之间不能隐式转换。 */
    public static final String DATATYPE_MISMATCH = "42804";
    /** NOT NULL 变量被置为 NULL。 */
    public static final String NULL_NOT_ALLOWED = "22004";

    private final String sqlState;

    public PlSqlTypeException(String sqlState, String message) {
        super(message);
        this.sqlState = sqlState;
    }

    public String getSqlState() {
        return sqlState;
    }

    public static PlSqlTypeException mismatch(Object value, PlType target) {
        String source = value == null ? "NULL" : value.getClass().getSimpleName();
        return new PlSqlTypeException(DATATYPE_MISMATCH,
                "Cannot convert " + source + " to " + target.displayName());
    }

    public static PlSqlTypeException outOfRange(Object value, PlType target) {
        return new PlSqlTypeException(NUMERIC_OUT_OF_RANGE,
                "Value " + value + " is out of range for " + target.displayName());
    }

    public static PlSqlTypeException tooLong(String value, PlType target) {
        return new PlSqlTypeException(STRING_TOO_LONG,
                "Value of length " + value.length() + " is too long for " + target.displayName());
    }

    public static PlSqlTypeException invalidNumber(String value, PlType target) {
        return new PlSqlTypeException(INVALID_TEXT_REPRESENTATION,
                "Invalid number '" + value + "' for " + target.displayName());
    }

    public static PlSqlTypeException invalidDateTime(String value, PlType target) {
        return new PlSqlTypeException(INVALID_DATETIME,
                "Invalid datetime '" + value + "' for " + target.displayName());
    }

    public static PlSqlTypeException nullNotAllowed(String name) {
        return new PlSqlTypeException(NULL_NOT_ALLOWED, "Variable [" + name + "] is NOT NULL but was set to NULL");
    }
}
