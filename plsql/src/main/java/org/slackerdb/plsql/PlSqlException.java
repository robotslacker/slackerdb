package org.slackerdb.plsql;

import org.slackerdb.plsql.spi.PlSqlCanceledException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PL/SQL 的结构化错误。
 */
public class PlSqlException extends RuntimeException {

    /** 语法错误 / 未声明变量 / 非法赋值目标等编译期错误。 */
    public static final String SYNTAX_ERROR = "42601";
    /** 会话被取消。 */
    public static final String QUERY_CANCELED = "57014";
    /** 除零。 */
    public static final String ZERO_DIVIDE = "22012";
    /** SELECT INTO 无数据。 */
    public static final String NO_DATA_FOUND = "P0002";
    /** SELECT INTO 多行。 */
    public static final String TOO_MANY_ROWS = "P0003";
    /** 游标状态非法 / INTO 目标数不匹配。 */
    public static final String INVALID_CURSOR_STATE = "24000";
    /** 语句预算耗尽（疑似死循环）。 */
    public static final String PROGRAM_LIMIT_EXCEEDED = "54000";
    /** 用户 RAISE / 未捕获自定义异常。 */
    public static final String RAISE_ERROR = "P0001";
    /** 内部错误（后端未给出 SQLSTATE 时）。 */
    public static final String INTERNAL_ERROR = "XX000";
    /** EXECUTE IMMEDIATE：USING 的个数与动态 SQL 占位符不匹配。 */
    public static final String INVALID_BIND_COUNT = "07001";

    private static final Pattern POSITION =
            Pattern.compile("line\\s+(\\d+)\\s*:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private final String sqlState;
    private final int line;
    private final int column;
    private final String sqlText;

    public PlSqlException(String message, String sqlState, Throwable cause) {
        this(message, sqlState, 0, 0, null, cause);
    }

    public PlSqlException(String message, String sqlState, int line, int column, String sqlText, Throwable cause) {
        super(message, cause);
        this.sqlState = sqlState;
        this.line = line;
        this.column = column;
        this.sqlText = sqlText;
    }

    public String getSqlState() {
        return sqlState;
    }

    /** 出错行（1 起）；0 表示未知。 */
    public int getLine() {
        return line;
    }

    /** 出错列（0 起）；0 表示未知。 */
    public int getColumn() {
        return column;
    }

    /** 出错语句原文；可能为 null。 */
    public String getSqlText() {
        return sqlText;
    }

    /** 人类可读的位置后缀，例如 {@code " (line 3, column 5)"}。 */
    public String positionSuffix() {
        return line > 0 ? " (line " + line + ", column " + (column + 1) + ")" : "";
    }

    /**
     * 把引擎抛出的任意异常归一化为 {@link PlSqlException}。
     *
     */
    public static PlSqlException from(Throwable error) {
        if (error instanceof PlSqlException plSqlException) {
            return plSqlException;
        }
        if (error instanceof PlSqlCanceledException) {
            return new PlSqlException(error.getMessage(), QUERY_CANCELED, error);
        }

        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getName();
        }
        int line = 0;
        int column = 0;
        Matcher matcher = POSITION.matcher(message);
        if (matcher.find()) {
            try {
                line = Integer.parseInt(matcher.group(1));
                column = Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException ignored) {
                line = 0;
            }
        }
        return new PlSqlException(message, SYNTAX_ERROR, line, column, null, error);
    }
}
