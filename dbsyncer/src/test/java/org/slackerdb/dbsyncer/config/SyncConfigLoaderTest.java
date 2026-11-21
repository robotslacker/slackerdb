package org.slackerdb.dbsyncer.config;

import org.junit.jupiter.api.Test;
import org.slackerdb.dbsyncer.DbSyncerConfigException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncConfigLoaderTest {

    @Test
    void loadsValidConfiguration() throws Exception {
        SyncConfig config = SyncConfigLoader.load(Path.of("src/test/resources/dbsyncer-valid.yml"));
        assertEquals(1, config.version);
        assertEquals(1, config.jobs.size());
        assertEquals("pg_orders", config.jobs.get(0).name);
        assertEquals("sync", config.targets.get("duck_local").schema);
        assertEquals(100, config.defaults.batch.maxRows);
        assertEquals(1, config.jobs.get(0).tables.include.size());
    }

    @Test
    void rejectsUnknownKey() {
        String yaml = baseYaml().replace("    schema: sync", "    schema: sync\n    unknownKey: 1");
        DbSyncerConfigException error = assertThrows(DbSyncerConfigException.class,
                () -> SyncConfigLoader.loadText(yaml, "test"));
        assertTrue(error.getMessage().contains("unknown key"), error.getMessage());
    }

    @Test
    void rejectsLegacyTargetType() {
        String yaml = baseYaml().replace("    schema: sync", "    type: duckdb\n    schema: sync");
        DbSyncerConfigException error = assertThrows(DbSyncerConfigException.class,
                () -> SyncConfigLoader.loadText(yaml, "test"));
        assertTrue(error.getMessage().contains("unknown key"), error.getMessage());
    }

    @Test
    void rejectsInvalidMatchPattern() {
        String yaml = baseYaml().replace("match: \"public.orders\"", "match: \"public.orders{.*\"");
        DbSyncerConfigException error = assertThrows(DbSyncerConfigException.class,
                () -> SyncConfigLoader.loadText(yaml, "test"));
        assertTrue(error.getMessage().contains("invalid table pattern"), error.getMessage());
    }

    @Test
    void requiresTargetSchema() {
        String yaml = baseYaml().replace("    schema: sync", "    schema: \"\"");
        DbSyncerConfigException error = assertThrows(DbSyncerConfigException.class,
                () -> SyncConfigLoader.loadText(yaml, "test"));
        assertTrue(error.getMessage().contains("schema"), error.getMessage());
    }

    @Test
    void rejectsMissingJobReference() {
        String yaml = baseYaml().replace("    target: duck_local", "    target: missing");
        DbSyncerConfigException error = assertThrows(DbSyncerConfigException.class,
                () -> SyncConfigLoader.loadText(yaml, "test"));
        assertTrue(error.getMessage().contains("unknown target"), error.getMessage());
    }

    @Test
    void rejectsMultipleDocuments() {
        String yaml = baseYaml() + "\n---\nversion: 1\n";
        DbSyncerConfigException error = assertThrows(DbSyncerConfigException.class,
                () -> SyncConfigLoader.loadText(yaml, "test"));
        assertTrue(error.getMessage().contains("exactly one"), error.getMessage());
    }

    @Test
    void appliesDefaultsToJob() throws Exception {
        String yaml = baseYaml().replace(
                "    mode: snapshot+cdc\n",
                "    mode: snapshot+cdc\n    snapshot:\n      fetchSize: 50\n");
        SyncConfig config = SyncConfigLoader.loadText(yaml, "test");
        SyncConfig.JobConfig job = config.jobs.get(0);
        assertEquals(50, job.snapshot.fetchSize);
    }

    @Test
    void acceptsMySqlJob() throws Exception {
        String yaml = """
                version: 1
                connections:
                  my:
                    type: mysql
                    url: jdbc:mysql://127.0.0.1:3306/appdb
                    username: sync_user
                    password: secret
                targets:
                  duck:
                    schema: sync
                jobs:
                  - name: mysql_orders
                    source: my
                    target: duck
                    tables:
                      include:
                        - match: "appdb.orders"
                          target: "orders"
                          primaryKey: [id]
                """;
        SyncConfig config = SyncConfigLoader.loadText(yaml, "test");
        assertEquals(SyncConfig.SourceType.MYSQL, config.connections.get("my").type);
    }

    @Test
    void rejectsPasswordVariableSubstitution() {
        String yaml = baseYaml().replace("password: secret", "password: ${FILE:/tmp/secret}");
        DbSyncerConfigException error = assertThrows(DbSyncerConfigException.class,
                () -> SyncConfigLoader.loadText(yaml, "test"));
        assertTrue(error.getMessage().contains("plaintext"), error.getMessage());
    }

    @Test
    void rejectsDdlConfig() {
        String yaml = baseYaml().replace("    mode: snapshot+cdc\n",
                "    mode: snapshot+cdc\n    ddl:\n      policy: apply\n");
        DbSyncerConfigException error = assertThrows(DbSyncerConfigException.class,
                () -> SyncConfigLoader.loadText(yaml, "test"));
        assertTrue(error.getMessage().contains("unknown key"), error.getMessage());
    }

    @Test
    void rejectsDerivedReplicationSlotCollision() {
        // 派生名会归一化：orders-eu 与 orders_eu 都会得到 sdb_pub_orders_eu / sdb_slot_orders_eu。
        DbSyncerConfigException error = assertThrows(DbSyncerConfigException.class,
                () -> SyncConfigLoader.loadText(twoJobsYaml("orders-eu", "orders_eu", null), "test"));
        assertTrue(error.getMessage().contains("already uses"), error.getMessage());
    }

    @Test
    void rejectsExplicitReplicationSlotCollision() {
        DbSyncerConfigException error = assertThrows(DbSyncerConfigException.class,
                () -> SyncConfigLoader.loadText(twoJobsYaml("a1", "a2", "shared_slot"), "test"));
        assertTrue(error.getMessage().contains("replication slot"), error.getMessage());
    }

    @Test
    void rejectsInvalidConnectionLevelReplicationName() {
        String yaml = baseYaml().replace("      statusIntervalMs: 5000",
                "      statusIntervalMs: 5000\n      slot: BadSlot");
        DbSyncerConfigException error = assertThrows(DbSyncerConfigException.class,
                () -> SyncConfigLoader.loadText(yaml, "test"));
        assertTrue(error.getMessage().contains("must match"), error.getMessage());
    }

    @Test
    void allowsDistinctJobsOnPostgres() throws Exception {
        SyncConfig config = SyncConfigLoader.loadText(twoJobsYaml("job_one", "job_two", null), "test");
        assertEquals(2, config.jobs.size());
    }

    /**
     * 两个 PG 作业共用一个连接，各同步不同的源表/目标表；slot 为 null 时都走派生名。
     */
    private static String twoJobsYaml(String firstName, String secondName, String slot) {
        StringBuilder yaml = new StringBuilder("""
                version: 1
                connections:
                  pg:
                    type: postgres
                    url: jdbc:postgresql://127.0.0.1:5432/appdb
                    username: sync_user
                    password: secret
                targets:
                  duck:
                    schema: sync
                jobs:
                """);
        yaml.append(job(firstName, "a", slot));
        yaml.append(job(secondName, "b", slot));
        return yaml.toString();
    }

    private static String job(String name, String table, String slot) {
        StringBuilder job = new StringBuilder()
                .append("  - name: ").append(name).append('\n')
                .append("    source: pg\n")
                .append("    target: duck\n");
        if (slot != null) {
            job.append("    replication:\n").append("      slot: ").append(slot).append('\n');
        }
        return job
                .append("    tables:\n")
                .append("      include:\n")
                .append("        - match: \"public.").append(table).append("\"\n")
                .append("          target: \"").append(table).append("\"\n")
                .toString();
    }

    @Test
    void shippedExampleConfigurationIsValid() throws Exception {
        Path example = Path.of("..", "dbserver", "conf", "dbsyncer.example.yml");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(example),
                "example config not present");
        SyncConfig config = SyncConfigLoader.load(example);
        assertEquals(2, config.jobs.size());
    }

    @Test
    void jobSnapshotPartiallyOverridesDefaults() throws Exception {
        // 作业只写 fetchSize：mode 必须继续继承 defaults（曾经会被重置成 replace，
        // 导致目标表被 TRUNCATE）。
        String yaml = baseYaml()
                .replace("    mode: replace\n    fetchSize: 100",
                        "    mode: skipIfExists\n    fetchSize: 100")
                .replace("    mode: snapshot+cdc\n",
                        "    mode: snapshot+cdc\n    snapshot:\n      fetchSize: 50\n");
        SyncConfig config = SyncConfigLoader.loadText(yaml, "test");
        assertEquals(SyncConfig.SnapshotMode.SKIP_IF_EXISTS, config.jobs.get(0).snapshot.mode);
        assertEquals(50, config.jobs.get(0).snapshot.fetchSize);
    }

    @Test
    void jobSnapshotOverridesModeOnly() throws Exception {
        String yaml = baseYaml()
                .replace("    mode: snapshot+cdc\n",
                        "    mode: snapshot+cdc\n    snapshot:\n      mode: append\n");
        SyncConfig config = SyncConfigLoader.loadText(yaml, "test");
        assertEquals(SyncConfig.SnapshotMode.APPEND, config.jobs.get(0).snapshot.mode);
        assertEquals(100, config.jobs.get(0).snapshot.fetchSize, "fetchSize 应继续继承 defaults");
    }

    private static String baseYaml() {
        try {
            return Files.readString(Path.of("src/test/resources/dbsyncer-valid.yml"), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
