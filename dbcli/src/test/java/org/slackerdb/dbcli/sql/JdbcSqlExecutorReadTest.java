package org.slackerdb.dbcli.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结果集读取与 {@code max-rows} 截断（不需要数据库：用 {@link Proxy} 造一个最小 {@link ResultSet}）。
 *
 * <p>为什么值得这样测：截断标记是"客户端自己的账"——它决定状态行里到底写不写
 * {@code (truncated at max-rows n)}。这一条如果错了，使用者会把"被截断的结果"当成完整结果，
 * 比不设上限更危险。用代理而不是真实数据库，测试因此是 hermetic 的。</p>
 */
class JdbcSqlExecutorReadTest {

    /** 一个只有 2 列、给定行数的结果集；{@code next()} 超过末尾返回 false。 */
    private static ResultSet fakeResultSet(List<List<Object>> rows) {
        int[] cursor = {-1};
        ResultSetMetaData metaData = (ResultSetMetaData) Proxy.newProxyInstance(
                JdbcSqlExecutorReadTest.class.getClassLoader(),
                new Class<?>[]{ResultSetMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getColumnCount" -> 2;
                    case "getColumnLabel" -> args[0].equals(1) ? "id" : "name";
                    case "getColumnType" -> args[0].equals(1) ? Types.INTEGER : Types.VARCHAR;
                    case "getColumnTypeName" -> args[0].equals(1) ? "integer" : "varchar";
                    default -> null;
                });
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "next" -> {
                cursor[0] = cursor[0] + 1;
                yield cursor[0] < rows.size();
            }
            case "getObject" -> rows.get(cursor[0]).get((Integer) args[0] - 1);
            case "getMetaData" -> metaData;
            case "close" -> null;
            default -> null;
        };
        return (ResultSet) Proxy.newProxyInstance(JdbcSqlExecutorReadTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, handler);
    }

    private static List<List<Object>> rows(int count) {
        List<List<Object>> rows = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            rows.add(List.of(index, "row-" + index));
        }
        return rows;
    }

    @Test
    @DisplayName("执行层错误只补一次 ERROR: 前缀（驱动消息已带前缀时不重复）")
    void errorTextDoesNotDuplicateThePrefix() {
        assertEquals("ERROR: Parser Error: x", JdbcSqlExecutor.errorText("ERROR: Parser Error: x"));
        assertEquals("ERROR: boom", JdbcSqlExecutor.errorText("boom"));
        assertEquals("ERROR: unknown failure.", JdbcSqlExecutor.errorText(null));
        assertEquals("ERROR: unknown failure.", JdbcSqlExecutor.errorText("   "));
    }

    @Test
    @DisplayName("timestamptz 按 OffsetDateTime 读：时区不丢（JDBC 类型同样是 TIMESTAMP）")
    void timestampWithTimeZoneIsReadAsOffsetDateTime() throws SQLException {
        java.time.OffsetDateTime value = java.time.OffsetDateTime.of(
                2026, 1, 1, 19, 4, 5, 0, java.time.ZoneOffset.UTC);
        int[] cursor = {-1};
        ResultSetMetaData metaData = (ResultSetMetaData) Proxy.newProxyInstance(
                JdbcSqlExecutorReadTest.class.getClassLoader(),
                new Class<?>[]{ResultSetMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getColumnCount" -> 1;
                    case "getColumnLabel" -> "at";
                    // 驱动对 timestamptz 也报 Types.TIMESTAMP，只能靠类型名区分
                    case "getColumnType" -> Types.TIMESTAMP;
                    case "getColumnTypeName" -> "timestamptz";
                    default -> null;
                });
        java.sql.Timestamp naive = java.sql.Timestamp.valueOf("2026-01-02 03:04:05");
        ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(
                JdbcSqlExecutorReadTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        cursor[0] = cursor[0] + 1;
                        yield cursor[0] < 1;
                    }
                    case "getObject" -> {
                        if (args.length == 2 && args[1] == java.time.OffsetDateTime.class) {
                            yield value;
                        }
                        yield naive;
                    }
                    case "getMetaData" -> metaData;
                    case "close" -> null;
                    default -> null;
                });

        JdbcSqlExecutor.StoredResult stored = JdbcSqlExecutor.read(resultSet);

        assertEquals(List.of(List.of(value)), stored.rows(),
                "timestamptz 必须按 OffsetDateTime 读，否则渲染时区会丢");
    }

    @Test
    @DisplayName("读完所有行：列信息与行都如实返回")
    void readsEverything() throws SQLException {
        JdbcSqlExecutor.StoredResult stored = JdbcSqlExecutor.read(fakeResultSet(rows(5)));

        assertEquals(List.of("id", "name"), stored.headers());
        assertEquals(List.of("INTEGER", "VARCHAR"), stored.columnTypes());
        assertEquals(5, stored.rows().size());
    }

    @Test
    @DisplayName("超时设置把负数夹成 0（不限）")
    void negativeTimeoutsAreClamped() {
        JdbcSqlExecutor executor = new JdbcSqlExecutor();
        executor.setQueryTimeout(-2);

        assertEquals(0, executor.queryTimeoutSeconds());
    }
}
