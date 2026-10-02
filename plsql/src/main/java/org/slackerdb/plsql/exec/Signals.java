package org.slackerdb.plsql.exec;

import org.slackerdb.plsql.PlSqlException;

/**
 * 解释器控制流信号（用异常实现）。
 */
final class Signals {

    private Signals() {
    }

    /** {@code EXIT [label]} / {@code BREAK [label]}。 */
    static final class ExitSignal extends RuntimeException {
        final String label;

        ExitSignal(String label) {
            super(null, null, false, false);
            this.label = label;
        }
    }

    /** {@code CONTINUE [label]}。 */
    static final class ContinueSignal extends RuntimeException {
        final String label;

        ContinueSignal(String label) {
            super(null, null, false, false);
            this.label = label;
        }
    }

    /** {@code RETURN;}：离开当前块（不被循环捕获）。 */
    static final class ReturnSignal extends RuntimeException {
        ReturnSignal() {
            super(null, null, false, false);
        }
    }

    /**
     * PL/SQL 可捕获异常：携带 PL/SQL 异常名与底层 SQLSTATE。
     *
     * <p>例：{@code NO_DATA_FOUND}/{@code TOO_MANY_ROWS}/{@code ZERO_DIVIDE}/{@code OTHERS}
     * 以及用户 {@code RAISE custom_name}。</p>
     */
    static final class RaisedException extends RuntimeException {
        final String name;
        final String sqlState;
        final String message;

        RaisedException(String name, String sqlState, String message, Throwable cause) {
            super(message, cause, false, false);
            this.name = name;
            this.sqlState = sqlState;
            this.message = message;
        }

        static RaisedException of(PlSqlException error) {
            return new RaisedException(errorNameOf(error.getSqlState()), error.getSqlState(),
                    error.getMessage(), error);
        }

        static String errorNameOf(String sqlState) {
            if (sqlState == null) {
                return "OTHERS";
            }
            return switch (sqlState) {
                case PlSqlException.NO_DATA_FOUND -> "NO_DATA_FOUND";
                case PlSqlException.TOO_MANY_ROWS -> "TOO_MANY_ROWS";
                case PlSqlException.ZERO_DIVIDE -> "ZERO_DIVIDE";
                case PlSqlException.INVALID_CURSOR_STATE -> "INVALID_CURSOR";
                default -> "OTHERS";
            };
        }
    }
}
