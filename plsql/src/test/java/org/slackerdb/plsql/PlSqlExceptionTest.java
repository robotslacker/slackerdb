package org.slackerdb.plsql;

import org.junit.jupiter.api.Test;
import org.slackerdb.plsql.spi.DefaultJdbcHost;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlSqlException#getSqlText()}：出错语句原文。
 *
 * <p>由后端 SQL 失败产生的错误会带上触发它的语句（内嵌 SQL 用原文，动态 SQL 用运行期文本），
 * 便于客户端与日志定位；其它错误（编译期、类型转换等）没有"出错语句"语义，保持 null。</p>
 */
class PlSqlExceptionTest {

    @Test
    void sqlTextCarriesTheFailingStatement() throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb::memory:", "", "")) {
            connection.setAutoCommit(false);
            DefaultJdbcHost host = new DefaultJdbcHost(connection);

            // 1) 内嵌（被遮罩的）SQL 失败：原文即块里写的那一句
            PlSqlException masked = assertThrows(PlSqlException.class, () ->
                    PlSqlEngine.execute(host, """
                            begin
                              insert into no_such_table_sqltext values (1);
                            end;"""));
            assertNotNull(masked.getSqlText(), "内嵌 SQL 出错时必须带上语句原文");
            assertTrue(masked.getSqlText().contains("no_such_table_sqltext"),
                    "getSqlText() 实际为：" + masked.getSqlText());

            // 2) 动态 SQL 失败：带的是运行期拼出来的 SQL 文本
            PlSqlException dynamic = assertThrows(PlSqlException.class, () ->
                    PlSqlEngine.execute(host, """
                            declare
                              v_table text := 'no_such_table_dyn_sqltext';
                            begin
                              execute immediate 'insert into ' || v_table || ' values (?)' using 1;
                            end;"""));
            assertNotNull(dynamic.getSqlText(), "动态 SQL 出错时必须带上运行期文本");
            assertTrue(dynamic.getSqlText().contains("no_such_table_dyn_sqltext"),
                    "getSqlText() 实际为：" + dynamic.getSqlText());
        }
    }
}
