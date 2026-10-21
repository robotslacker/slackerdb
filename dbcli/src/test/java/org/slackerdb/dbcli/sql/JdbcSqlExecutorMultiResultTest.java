package org.slackerdb.dbcli.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbcli.assertion.ResultModel;

import java.lang.reflect.Proxy;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多结果集循环（JdbcSqlExecutor#readAll(Statement, String)）：以前只读第一个结果，
 * 后面的被静默丢弃。这里用一个假 {@code Statement} 冻结"每个结果都收、顺序不变"。
 */
class JdbcSqlExecutorMultiResultTest {

    @Test
    @DisplayName("两个 update count 都被收下，顺序与驱动给的一致")
    void collectsEveryUpdateCount() throws Exception {
        int[] counts = {3, 5, -1};
        int[] position = {0};
        Statement statement = (Statement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWarnings" -> null;
                    case "execute" -> Boolean.FALSE;
                    case "getUpdateCount" -> counts[Math.min(position[0], counts.length - 1)];
                    case "getMoreResults" -> {
                        // 假驱动：后续都是 update count（不是 ResultSet），因此恒 false，
                        // 循环靠 getUpdateCount() == -1 收尾
                        position[0]++;
                        yield Boolean.FALSE;
                    }
                    case "close" -> null;
                    default -> null;
                });

        List<ResultModel> models = new JdbcSqlExecutor().readAll(statement, "insert ...");

        assertEquals(2, models.size(), "两个 update count 都要有结果: " + models.size());
        assertEquals(3, models.get(0).rowCount());
        assertEquals(5, models.get(1).rowCount());
        assertEquals(0, models.get(0).errorCode());
    }

    @Test
    @DisplayName("DDL（update count 一直为 -1）仍然产出一个 0 行的状态结果")
    void ddlStillProducesOneResult() throws Exception {
        Statement statement = (Statement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWarnings" -> null;
                    case "execute" -> Boolean.FALSE;
                    case "getUpdateCount" -> -1;
                    case "getMoreResults" -> Boolean.FALSE;
                    case "close" -> null;
                    default -> null;
                });

        List<ResultModel> models = new JdbcSqlExecutor().readAll(statement, "create table t (id int)");

        assertEquals(1, models.size());
        assertEquals(0, models.get(0).rowCount());
        assertTrue(models.get(0).status().startsWith("0 row"), models.get(0).status());
    }
}
