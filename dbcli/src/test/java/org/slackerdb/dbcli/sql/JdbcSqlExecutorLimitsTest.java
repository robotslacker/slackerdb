package org.slackerdb.dbcli.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code _SET TIMEOUT} 的最后一公里：它到底有没有被套到 JDBC {@code Statement} 上。
 *
 * <p>为什么要单独一个测试类：{@code ScriptRunnerTest} 只能证明"选项值传到了执行层"，
 * 真正让超时生效的是 {@code execute()} 里那一次 {@code Statement.setQueryTimeout}。
 * 这一步如果断了，{@code _SET TIMEOUT 30} 会**静默无效**——比没这个功能更糟，
 * 因为使用者会以为长查询已经被兜住了。用一个代理 {@link Statement} 记录调用，
 * 这条接线因此不需要真实数据库也能被验证。</p>
 */
class JdbcSqlExecutorLimitsTest {

    /** 记录被调用过的方法（{@code setQueryTimeout(30)} 记成 {@code setQueryTimeout(30)}）。 */
    private static final class RecordingStatement {
        final List<String> calls = new ArrayList<>();
        final Statement statement;

        RecordingStatement() {
            this(new SQLException[0]);
        }

        RecordingStatement(SQLException... failures) {
            this.statement = (Statement) Proxy.newProxyInstance(
                    JdbcSqlExecutorLimitsTest.class.getClassLoader(),
                    new Class<?>[]{Statement.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("setQueryTimeout")) {
                            int seconds = (Integer) args[0];
                            calls.add("setQueryTimeout(" + seconds + ")");
                            for (SQLException failure : failures) {
                                throw failure;
                            }
                            return null;
                        }
                        if (name.startsWith("set") || name.equals("cancel") || name.equals("execute")) {
                            calls.add(name + "(" + (args == null ? "" : args[0]) + ")");
                        }
                        return null;
                    });
        }
    }

    @Test
    @DisplayName("设了超时：正好调用一次 setQueryTimeout(秒数)")
    void timeoutIsAppliedToTheStatement() throws SQLException {
        RecordingStatement recording = new RecordingStatement();

        JdbcSqlExecutor.applyLimits(recording.statement, 30);

        assertEquals(List.of("setQueryTimeout(30)"), recording.calls);
    }

    @Test
    @DisplayName("没设超时（0）：一次驱动调用都不发（也不碰 setMaxRows / setFetchSize）")
    void zeroTimeoutTouchesNothing() throws SQLException {
        RecordingStatement recording = new RecordingStatement();

        JdbcSqlExecutor.applyLimits(recording.statement, 0);

        assertTrue(recording.calls.isEmpty(), "0 = 不限，不该打扰驱动: " + recording.calls);
        assertFalse(recording.calls.stream().anyMatch(call -> call.startsWith("setMaxRows")),
                "max-rows 由 read() 自己截断，不能交给驱动: " + recording.calls);
    }

    @Test
    @DisplayName("负数按 0 处理：同样一次驱动调用都不发")
    void negativeTimeoutIsTreatedAsUnlimited() throws SQLException {
        RecordingStatement recording = new RecordingStatement();

        JdbcSqlExecutor.applyLimits(recording.statement, -5);

        assertTrue(recording.calls.isEmpty(), recording.calls.toString());
    }

    @Test
    @DisplayName("驱动不支持超时：异常如实抛给调用方（由 execute 变成一条可读的语句错误，不静默吞掉）")
    void unsupportedTimeoutIsReportedNotSwallowed() {
        RecordingStatement recording = new RecordingStatement(
                new SQLFeatureNotSupportedException("setQueryTimeout is not supported"));

        SQLException failure = assertThrows(SQLException.class,
                () -> JdbcSqlExecutor.applyLimits(recording.statement, 30));

        assertTrue(failure.getMessage().contains("not supported"), failure.getMessage());
    }
}
