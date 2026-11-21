package org.slackerdb.dbsyncer.it;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbsyncer.util.ReplicationNames;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQL 端到端集成测试：真实逻辑复制（publication / slot / pgoutput）+ 内存 DuckDB 目标。
 *
 * <p>默认不进回归（Surefire 按 {@code integration} tag 排除）。源库地址可用系统属性覆盖：
 * {@code dbsyncer.it.pg.url / dbsyncer.it.pg.user / dbsyncer.it.pg.password}。</p>
 */
@Tag("integration")
class PostgresSyncIntegrationTest {

    private static final String URL = IntegrationSupport.property("dbsyncer.it.pg.url",
            "jdbc:postgresql://192.168.183.147:5432/postgres");
    private static final String USER = IntegrationSupport.property("dbsyncer.it.pg.user", "postgres");
    private static final String PASSWORD = IntegrationSupport.property("dbsyncer.it.pg.password", "123456");

    @Test
    void snapshotThenCdcAppliesInsertUpdateDeleteAndTruncate() throws Exception {
        String suffix = IntegrationSupport.suffix();
        String schema = "it_pg_" + suffix;
        String jobName = "itpg" + suffix;
        String targetSchema = "sync";
        String targetTable = "orders";

        Connection pg = IntegrationSupport.connect(URL, USER, PASSWORD);
        IntegrationSupport fixture = null;
        try {
            IntegrationSupport.execute(pg, "CREATE SCHEMA " + schema);
            IntegrationSupport.execute(pg, "CREATE TABLE " + schema + ".orders ("
                    + "id BIGINT PRIMARY KEY, name TEXT, note TEXT, amount NUMERIC(12,2))");
            // 强制大文本走行外存储：UPDATE 其它列时 PostgreSQL 才会下发 'u'（未变化 TOAST）。
            IntegrationSupport.execute(pg,
                    "ALTER TABLE " + schema + ".orders ALTER COLUMN note SET STORAGE EXTERNAL");
            for (int i = 1; i <= 5; i++) {
                insertOrder(pg, schema, i, "order-" + i, null, i * 10);
            }

            fixture = IntegrationSupport.start(config(schema, jobName, targetSchema, targetTable));
            final IntegrationSupport started = fixture;
            started.startJob(jobName, true);
            started.awaitState(jobName, "STREAMING", 60_000);

            // 1) 全量快照
            started.await("snapshot rows", 30_000, () -> targetCount(started) == 5);
            assertEquals("order-1", targetString(started, "name", 1));

            // 2) INSERT
            insertOrder(pg, schema, 6, "order-6", null, 60);
            started.await("insert", 30_000,
                    () -> targetCount(started) == 6 && "order-6".equals(targetString(started, "name", 6)));

            // 3) UPDATE（普通列）
            IntegrationSupport.execute(pg,
                    "UPDATE " + schema + ".orders SET name = 'order-6-updated' WHERE id = 6");
            started.await("update", 30_000,
                    () -> "order-6-updated".equals(targetString(started, "name", 6)));

            // 4) UPDATE 带未变化 TOAST：目标库里的大文本不能被覆盖成 NULL
            String bigNote = randomText(20_000);
            insertOrder(pg, schema, 7, "order-7", bigNote, 70);
            started.await("toast insert", 30_000,
                    () -> targetString(started, "note", 7) != null
                            && targetString(started, "note", 7).length() == bigNote.length());
            IntegrationSupport.execute(pg,
                    "UPDATE " + schema + ".orders SET name = 'order-7-updated' WHERE id = 7");
            started.await("toast update", 30_000,
                    () -> "order-7-updated".equals(targetString(started, "name", 7)));
            assertEquals(bigNote, targetString(started, "note", 7),
                    "未变化的 TOAST 列被覆盖了");

            // 5) DELETE
            IntegrationSupport.execute(pg, "DELETE FROM " + schema + ".orders WHERE id = 6");
            started.await("delete", 30_000, () -> targetCount(started) == 6);

            // 6) TRUNCATE
            IntegrationSupport.execute(pg, "TRUNCATE TABLE " + schema + ".orders");
            started.await("truncate", 30_000, () -> targetCount(started) == 0);

            // 7) 作业状态里的位点与计数应可见
            assertEquals("STREAMING", started.module().jobManager().require(jobName).state().name());
            assertTrue(started.module().status().getIntValue("runningJobs") >= 1);
        } finally {
            if (fixture != null) {
                fixture.close();
            }
            dropReplicationObjects(pg, jobName);
            IntegrationSupport.execute(pg, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            pg.close();
        }
    }

    private static String config(String schema, String jobName, String targetSchema, String targetTable) {
        return config(schema, jobName, targetSchema, targetTable, "orders");
    }

    private static String config(String schema, String jobName, String targetSchema, String targetTable,
                                 String sourceTable) {
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
                  pg:
                    type: postgres
                    url: %s
                    username: %s
                    password: "%s"
                    replication:
                      statusIntervalMs: 300
                targets:
                  duck:
                    schema: %s
                jobs:
                  - name: %s
                    source: pg
                    target: duck
                    mode: snapshot+cdc
                    tables:
                      include:
                        - match: "%s.%s"
                          target: "%s"
                          primaryKey: [id]
                """.formatted(URL, USER, PASSWORD, targetSchema, jobName, schema, sourceTable,
                targetTable);
    }

    private static void insertOrder(Connection pg, String schema, int id, String name, String note,
                                    int amount) throws Exception {
        String sql = "INSERT INTO " + schema + ".orders (id, name, note, amount) VALUES (?,?,?,?)";
        try (PreparedStatement statement = pg.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.setString(2, name);
            statement.setString(3, note);
            statement.setBigDecimal(4, java.math.BigDecimal.valueOf(amount));
            statement.executeUpdate();
        }
    }

    /** 不可压缩的随机文本，保证 PostgreSQL 把它放到 TOAST 表里。 */
    private static String randomText(int length) {
        Random random = new Random(42);
        String alphabet = "0123456789abcdef";
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return builder.toString();
    }

    @Test
    void writeFailurePausesWithoutAdvancingCheckpoint() throws Exception {
        String suffix = IntegrationSupport.suffix();
        String schema = "it_pg_fail_" + suffix;
        String jobName = "itpgfail" + suffix;

        Connection pg = IntegrationSupport.connect(URL, USER, PASSWORD);
        IntegrationSupport fixture = null;
        try {
            IntegrationSupport.execute(pg, "CREATE SCHEMA " + schema);
            // 源列是 TEXT，目标用 columns.types 覆盖成 INTEGER：写 'oops' 时目标 CAST 必然失败。
            IntegrationSupport.execute(pg, "CREATE TABLE " + schema + ".orders ("
                    + "id BIGINT PRIMARY KEY, amount TEXT)");
            insertAmount(pg, schema, 1, "1");
            insertAmount(pg, schema, 2, "2");

            fixture = IntegrationSupport.start(configWithTypeOverride(schema, jobName));
            final IntegrationSupport started = fixture;
            started.startJob(jobName, true);
            started.awaitState(jobName, "STREAMING", 60_000);
            started.await("snapshot rows", 30_000, () -> amountCount(started) == 2);

            String checkpointBefore = started.module().jobManager().require(jobName)
                    .status().getJSONObject("checkpoint").getString("value");

            // 触发一次注定失败的目标写入：重试用尽后必须暂停，且不能推进检查点。
            insertAmount(pg, schema, 3, "oops");

            started.await("job paused", 30_000,
                    () -> "PAUSED".equals(started.module().jobManager().require(jobName).state().name()));
            String jobStatus = started.module().jobManager().require(jobName).status().toJSONString();
            assertTrue(jobStatus.contains("batch apply failed"), jobStatus);
            assertEquals(2, amountCount(started), "失败的行不应写进目标库");

            String checkpointAfter = started.module().jobManager().require(jobName)
                    .status().getJSONObject("checkpoint").getString("value");
            assertEquals(checkpointBefore, checkpointAfter, "写入失败不应推进检查点");

            // 重启后仍从同一个检查点开始：坏行还在 WAL 里，会再次失败暂停，而不是被跳过。
            started.await("restart accepted", 10_000, () -> {
                try {
                    started.module().jobManager().start(jobName, false);
                    return true;
                } catch (IllegalStateException stillStopping) {
                    return false;
                }
            });
            started.await("job paused again", 30_000,
                    () -> "PAUSED".equals(started.module().jobManager().require(jobName).state().name()));
            assertEquals(2, amountCount(started), "重放不应丢已提交的数据");
        } finally {
            if (fixture != null) {
                fixture.close();
            }
            dropReplicationObjects(pg, jobName);
            IntegrationSupport.execute(pg, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            pg.close();
        }
    }

    /** 与 {@link #config} 相同，但把 amount 目标类型覆盖成 INTEGER，用于构造目标侧写入失败。 */
    private static String configWithTypeOverride(String schema, String jobName) {
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
                  pg:
                    type: postgres
                    url: %s
                    username: %s
                    password: "%s"
                    replication:
                      statusIntervalMs: 300
                targets:
                  duck:
                    schema: sync
                jobs:
                  - name: %s
                    source: pg
                    target: duck
                    mode: snapshot+cdc
                    tables:
                      include:
                        - match: "%s.orders"
                          target: "orders"
                          primaryKey: [id]
                          columns:
                            types:
                              amount: INTEGER
                """.formatted(URL, USER, PASSWORD, jobName, schema);
    }

    private static void insertAmount(Connection pg, String schema, int id, String amount) throws Exception {
        String sql = "INSERT INTO " + schema + ".orders (id, amount) VALUES (?, ?)";
        try (PreparedStatement statement = pg.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.setString(2, amount);
            statement.executeUpdate();
        }
    }

    private static int amountCount(IntegrationSupport fixture) throws Exception {
        return IntegrationSupport.queryInt(fixture.duckDb(),
                "SELECT count(*) FROM \"memory\".\"sync\".\"orders\"");
    }

    private static int targetCount(IntegrationSupport fixture) throws Exception {
        return IntegrationSupport.queryInt(fixture.duckDb(),
                "SELECT count(*) FROM \"memory\".\"sync\".\"orders\"");
    }

    private static String targetString(IntegrationSupport fixture, String column, long id)
            throws Exception {
        return IntegrationSupport.queryString(fixture.duckDb(),
                "SELECT " + column + " FROM \"memory\".\"sync\".\"orders\" WHERE id = " + id);
    }

    private static void dropReplicationObjects(Connection pg, String jobName) {
        String slot = ReplicationNames.slot(jobName);
        String publication = ReplicationNames.publication(jobName);
        try {
            IntegrationSupport.execute(pg, "SELECT pg_drop_replication_slot(slot_name)"
                    + " FROM pg_replication_slots WHERE slot_name = '" + slot + "'");
        } catch (Exception e) {
            System.out.println("[IT] cannot drop slot " + slot + ": " + e.getMessage());
        }
        try {
            IntegrationSupport.execute(pg, "DROP PUBLICATION IF EXISTS \"" + publication + "\"");
        } catch (Exception e) {
            System.out.println("[IT] cannot drop publication " + publication + ": " + e.getMessage());
        }
    }
}
