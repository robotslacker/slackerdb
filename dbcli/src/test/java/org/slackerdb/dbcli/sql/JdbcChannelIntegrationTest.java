package org.slackerdb.dbcli.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDBC 通道集成测试。
 *
 * <p><b>默认不运行</b>：只有显式给出服务器地址时才启用，这样 {@code mvn test} 不需要任何外部依赖
 * （CI 上是绿的、本机不必起服务）。运行方式：</p>
 *
 * <pre>
 *   # 1) 先起一个可连接的 SlackerDB 服务端实例（工作目录放在仓库外，避免与 mvn clean 冲突）
 *   # 2) 带属性跑
 *   mvn -pl dbcli -am test "-Ddbcli.it.url=jdbc:slackerdb://127.0.0.1:43175/db1"
 * </pre>
 *
 * <p>覆盖：驱动注册、建表/插入/查询往返、元数据（列标签与 JDBC 类型名）、
 * 以及 <b>COMMIT / ROLLBACK</b> 作为标准 SQL 语句发出去确实生效。</p>
 */
@EnabledIfSystemProperty(named = "dbcli.it.url", matches = ".+")
class JdbcChannelIntegrationTest {

    private Connection connect() throws Exception {
        String url = System.getProperty("dbcli.it.url");
        String user = System.getProperty("dbcli.it.user", "main");
        return DriverManager.getConnection(url, user, "");
    }

    @Test
    @DisplayName("驱动可用 + 建表/插入/查询往返 + 列元数据")
    void roundTrip() throws Exception {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute("drop table if exists dbcli_it_tab");
            stmt.execute("create table dbcli_it_tab(id int, name varchar(20))");
            assertEquals(2, stmt.executeUpdate("insert into dbcli_it_tab values (1, 'alpha'), (2, 'beta')"));

            try (ResultSet rs = stmt.executeQuery("select id, name from dbcli_it_tab order by id")) {
                assertEquals("id", rs.getMetaData().getColumnLabel(1));
                assertEquals("name", rs.getMetaData().getColumnLabel(2));
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("alpha", rs.getString(2));
                assertTrue(rs.next());
                assertEquals("beta", rs.getString(2));
                assertTrue(!rs.next(), "只应有 2 行");
            }
        }
    }

    @Test
    @DisplayName("COMMIT 作为 SQL 语句生效：提交后其它连接可见")
    void commitStatementIsEffective() throws Exception {
        String url = System.getProperty("dbcli.it.url");
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute("drop table if exists dbcli_it_tx");
            stmt.execute("create table dbcli_it_tx(id int)");
            conn.setAutoCommit(false);
            stmt.executeUpdate("insert into dbcli_it_tx values (1)");
            stmt.execute("COMMIT");

            try (Connection other = DriverManager.getConnection(url, System.getProperty("dbcli.it.user", "main"), "");
                 Statement otherStmt = other.createStatement();
                 ResultSet rs = otherStmt.executeQuery("select count(*) from dbcli_it_tx")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "COMMIT 之后其它连接应当看得到这一行");
            }
        }
    }

    @Test
    @DisplayName("ROLLBACK 作为 SQL 语句生效：未提交的插入被丢弃")
    void rollbackStatementIsEffective() throws Exception {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute("drop table if exists dbcli_it_rb");
            stmt.execute("create table dbcli_it_rb(id int)");
            stmt.executeUpdate("insert into dbcli_it_rb values (1)");
            conn.setAutoCommit(false);
            stmt.executeUpdate("insert into dbcli_it_rb values (2)");
            stmt.execute("ROLLBACK");

            try (ResultSet rs = stmt.executeQuery("select count(*) from dbcli_it_rb")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "ROLLBACK 之后第 2 行应当不存在");
            }
        }
    }
}
