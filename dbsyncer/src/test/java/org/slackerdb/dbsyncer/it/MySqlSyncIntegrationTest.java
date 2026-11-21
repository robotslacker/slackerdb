package org.slackerdb.dbsyncer.it;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 端到端集成测试：真实 ROW binlog + DDL 自动应用 + 内存 DuckDB 目标。
 *
 * <p>注意：测试库必须是源端 binlog 允许记录的库（{@code --binlog-do-db}）。
 * 默认用 {@code jtls_sdc}，测试只创建/删除带唯一前缀的表，不动库里的其它对象。</p>
 *
 * <p>默认不进回归（Surefire 按 {@code integration} tag 排除）。可用系统属性覆盖：
 * {@code dbsyncer.it.mysql.url / .user / .password / .db}。</p>
 */
@Tag("integration")
class MySqlSyncIntegrationTest {

    private static final String SERVER_URL = IntegrationSupport.property("dbsyncer.it.mysql.url",
            "jdbc:mysql://192.168.183.147:3406/?connectTimeout=5000&socketTimeout=10000"
                    + "&allowPublicKeyRetrieval=true&useSSL=false");
    private static final String USER = IntegrationSupport.property("dbsyncer.it.mysql.user", "root");
    private static final String PASSWORD = IntegrationSupport.property("dbsyncer.it.mysql.password", "123456");
    private static final String DATABASE = IntegrationSupport.property("dbsyncer.it.mysql.db", "jtls_sdc");

    @Test
    void snapshotThenCdcAppliesDmlAndDdl() throws Exception {
        String suffix = IntegrationSupport.suffix();
        String prefix = "it" + suffix + "_";
        String jobName = "itmy" + suffix;
        String targetSchema = "sync";
        String orders = prefix + "orders";
        String created = prefix + "orders_new";
        String renamed = prefix + "orders_renamed";

        Connection mysql = IntegrationSupport.connect(SERVER_URL, USER, PASSWORD);
        IntegrationSupport fixture = null;
        try {
            requireBinlogCovers(mysql);
            IntegrationSupport.execute(mysql, "CREATE TABLE " + DATABASE + "." + orders
                    + " (id BIGINT PRIMARY KEY, name VARCHAR(64), note VARCHAR(255)) ENGINE=InnoDB");
            for (int i = 1; i <= 5; i++) {
                insertOrder(mysql, orders, i, "order-" + i, null);
            }

            fixture = IntegrationSupport.start(config(prefix, jobName, targetSchema));
            final IntegrationSupport started = fixture;
            started.startJob(jobName, true);
            started.awaitState(jobName, "STREAMING", 90_000);

            // 1) 全量快照
            started.await("snapshot rows", 30_000, () -> count(started, orders) == 5);
            assertEquals("order-1", name(started, orders, 1));

            // 2) INSERT / UPDATE / DELETE
            insertOrder(mysql, orders, 6, "order-6", null);
            started.await("insert", 30_000,
                    () -> count(started, orders) == 6 && "order-6".equals(name(started, orders, 6)));
            IntegrationSupport.execute(mysql,
                    "UPDATE " + DATABASE + "." + orders + " SET name = 'order-6-updated' WHERE id = 6");
            started.await("update", 30_000,
                    () -> "order-6-updated".equals(name(started, orders, 6)));
            IntegrationSupport.execute(mysql,
                    "DELETE FROM " + DATABASE + "." + orders + " WHERE id = 6");
            started.await("delete", 30_000, () -> count(started, orders) == 5);

            // 3) ALTER TABLE ... ADD COLUMN（DDL 自动应用）
            IntegrationSupport.execute(mysql,
                    "ALTER TABLE " + DATABASE + "." + orders + " ADD COLUMN extra VARCHAR(32) NULL");
            started.await("add column", 30_000,
                    () -> IntegrationSupport.columnExists(started.duckDb(), targetSchema, orders, "extra"));
            IntegrationSupport.execute(mysql,
                    "UPDATE " + DATABASE + "." + orders + " SET extra = 'x1' WHERE id = 1");
            started.await("row with new column", 30_000,
                    () -> "x1".equals(IntegrationSupport.queryString(started.duckDb(),
                            "SELECT extra FROM \"memory\".\"sync\".\"" + orders + "\" WHERE id = 1")));

            // 3b) ADD COLUMN ... AFTER：行事件是按位置解码的，插到中间后必须仍然对齐
            //     （曾经的 bug：b/c 两列的值会被互换，且不报错）
            IntegrationSupport.execute(mysql,
                    "ALTER TABLE " + DATABASE + "." + orders
                            + " ADD COLUMN mid VARCHAR(16) NULL AFTER id");
            started.await("mid column", 30_000, () -> IntegrationSupport.columnExists(
                    started.duckDb(), targetSchema, orders, "mid"));
            IntegrationSupport.execute(mysql, "INSERT INTO " + DATABASE + "." + orders
                    + " (id, mid, name, note) VALUES (200, 'MID', 'NAME', 'NOTE')");
            started.await("row after mid column", 30_000,
                    () -> "MID".equals(IntegrationSupport.queryString(started.duckDb(),
                            "SELECT mid FROM \"memory\".\"sync\".\"" + orders + "\" WHERE id = 200")));
            assertEquals("NAME", IntegrationSupport.queryString(started.duckDb(),
                    "SELECT name FROM \"memory\".\"sync\".\"" + orders + "\" WHERE id = 200"));
            assertEquals("NOTE", IntegrationSupport.queryString(started.duckDb(),
                    "SELECT note FROM \"memory\".\"sync\".\"" + orders + "\" WHERE id = 200"));
            IntegrationSupport.execute(mysql,
                    "DELETE FROM " + DATABASE + "." + orders + " WHERE id = 200");

            // 4) CREATE TABLE（动态建目标表；曾因单写线程自锁而挂死）
            IntegrationSupport.execute(mysql, "CREATE TABLE " + DATABASE + "." + created
                    + " (id BIGINT PRIMARY KEY, name VARCHAR(64)) ENGINE=InnoDB");
            started.await("create table", 30_000,
                    () -> IntegrationSupport.tableExists(started.duckDb(), "memory", targetSchema, created));
            IntegrationSupport.execute(mysql,
                    "INSERT INTO " + DATABASE + "." + created + " (id, name) VALUES (100, 'created')");
            started.await("insert into new table", 30_000,
                    () -> "created".equals(IntegrationSupport.queryString(started.duckDb(),
                            "SELECT name FROM \"memory\".\"sync\".\"" + created + "\" WHERE id = 100")));

            // 5) RENAME TABLE（目标表按模板改名）
            IntegrationSupport.execute(mysql,
                    "RENAME TABLE " + DATABASE + "." + created + " TO " + DATABASE + "." + renamed);
            started.await("rename table", 30_000,
                    () -> IntegrationSupport.tableExists(started.duckDb(), "memory", targetSchema, renamed));

            // 6) DROP TABLE
            IntegrationSupport.execute(mysql, "DROP TABLE " + DATABASE + "." + renamed);
            started.await("drop table", 30_000,
                    () -> !IntegrationSupport.tableExists(started.duckDb(), "memory", targetSchema, renamed));

            // 7) TRUNCATE TABLE
            IntegrationSupport.execute(mysql, "TRUNCATE TABLE " + DATABASE + "." + orders);
            started.await("truncate", 30_000, () -> count(started, orders) == 0);

            assertEquals("STREAMING", started.module().jobManager().require(jobName).state().name());
            assertTrue(started.module().status().getIntValue("runningJobs") >= 1);
        } finally {
            if (fixture != null) {
                fixture.close();
            }
            dropTables(mysql, orders, created, renamed);
            mysql.close();
        }
    }

    /** 源端开了 --binlog-do-db 时，测试库必须在允许列表里，否则永远收不到增量事件。 */
    private static void requireBinlogCovers(Connection mysql) throws Exception {
        try (Statement statement = mysql.createStatement();
             ResultSet rs = statement.executeQuery("SHOW BINARY LOG STATUS")) {
            if (!rs.next() || rs.getMetaData().getColumnCount() < 3) {
                return;
            }
            String allowed = rs.getString(3);
            if (allowed != null && !allowed.isBlank() && !allowed.equalsIgnoreCase(DATABASE)) {
                throw new AssertionError("MySQL source filters binlog to [" + allowed
                        + "] but the test database is [" + DATABASE
                        + "]; set -Ddbsyncer.it.mysql.db to a database included in --binlog-do-db");
            }
        } catch (AssertionError e) {
            throw e;
        } catch (Exception ignored) {
            // 老版本没有 SHOW BINARY LOG STATUS 时跳过这项前置检查。
        }
    }

    private static String config(String prefix, String jobName, String targetSchema) {
        String jdbcUrl = "jdbc:mysql://192.168.183.147:3406/" + DATABASE
                + "?useCursorFetch=true&useServerPrepStmts=true&characterEncoding=UTF-8"
                + "&serverTimezone=UTC&tinyInt1isBit=false&allowPublicKeyRetrieval=true&useSSL=false";
        String url = IntegrationSupport.property("dbsyncer.it.mysql.jdbcUrl", jdbcUrl);
        return """
                version: 1
                defaults:
                  batch:
                    maxRows: 500
                    flushIntervalMs: 300
                    maxBufferedEvents: 1000
                    maxAttempts: 2
                    retryIntervalMs: 200
                  reconnect:
                    maxAttempts: 2
                    intervalMs: 500
                  snapshot:
                    mode: replace
                    fetchSize: 1000
                connections:
                  mysql:
                    type: mysql
                    url: %s
                    username: %s
                    password: "%s"
                targets:
                  duck:
                    schema: %s
                jobs:
                  - name: %s
                    source: mysql
                    target: duck
                    mode: snapshot+cdc
                    tables:
                      include:
                        - match: "%s.%s{.*}"
                          primaryKey: [id]
                """.formatted(url, USER, PASSWORD, targetSchema, jobName, DATABASE, prefix);
    }

    private static void insertOrder(Connection mysql, String table, int id, String name, String note)
            throws Exception {
        String sql = "INSERT INTO " + DATABASE + "." + table + " (id, name, note) VALUES (?,?,?)";
        try (PreparedStatement statement = mysql.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.setString(2, name);
            statement.setString(3, note);
            statement.executeUpdate();
        }
    }

    private static void dropTables(Connection mysql, String... tables) {
        for (String table : tables) {
            try {
                IntegrationSupport.execute(mysql, "DROP TABLE IF EXISTS " + DATABASE + "." + table);
            } catch (Exception e) {
                System.out.println("[IT] cannot drop table " + table + ": " + e.getMessage());
            }
        }
    }

    private static int count(IntegrationSupport fixture, String table) throws Exception {
        return IntegrationSupport.queryInt(fixture.duckDb(),
                "SELECT count(*) FROM \"memory\".\"sync\".\"" + table + "\"");
    }

    private static String name(IntegrationSupport fixture, String table, long id) throws Exception {
        return IntegrationSupport.queryString(fixture.duckDb(),
                "SELECT name FROM \"memory\".\"sync\".\"" + table + "\" WHERE id = " + id);
    }
}
