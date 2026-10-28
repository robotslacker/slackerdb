package org.slackerdb.dbdesk.access.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbDeskProfileStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void recordsOnlyExplicitlySavedProfilesAndKeepsMostRecentFirst() throws Exception {
        Path file = tempDir.resolve("profiles.json");
        DbDeskProfileStore store = new DbDeskProfileStore(file);

        assertTrue(store.load().profiles().isEmpty(), "新目录不应有默认连接");

        DbDeskProfile first = new DbDeskProfile("first", "127.0.0.1", 4409, 8409, "m0");
        store.recordSuccessful(first);
        DbDeskProfile second = new DbDeskProfile("second", "10.0.0.8", 5409, null, "prod");
        store.recordSuccessful(second);

        DbDeskProfileStore.Snapshot snapshot = store.load();
        assertEquals("second", snapshot.lastAlias());
        assertEquals(2, snapshot.profiles().size());
        assertEquals("second", snapshot.profiles().get(0).alias());
        assertNull(snapshot.profiles().get(0).managementPort());
        assertEquals("m0", snapshot.profiles().get(1).database());
    }

    @Test
    void ignoresOldSchemaInsteadOfConvertingIt() throws Exception {
        Path file = tempDir.resolve("profiles.json");
        Files.writeString(file, """
                {"version":1,"defaultProfile":"old","profiles":[{"name":"old","host":"127.0.0.1","port":4409,"database":"m0"}]}
                """);

        assertTrue(new DbDeskProfileStore(file).load().profiles().isEmpty());
    }

    @Test
    void databaseNameIsRequiredWhenDatabasePortIsPresent() {
        assertThrows(org.slackerdb.dbdesk.i18n.LocalizedException.class,
                () -> new DbDeskProfile("x", "127.0.0.1", 4409, 8409, " "));
    }

    @Test
    void clusterOnlyProfileRoundTripsWithoutDatabasePort() throws Exception {
        Path file = tempDir.resolve("profiles.json");
        DbDeskProfileStore store = new DbDeskProfileStore(file);

        store.recordSuccessful(new DbDeskProfile("proxy", "127.0.0.1", null, 8409, null));

        DbDeskProfile loaded = store.load().profiles().get(0);
        assertEquals("proxy", loaded.alias());
        assertFalse(loaded.hasDatabasePort());
        assertEquals(8409, loaded.managementPort());
        assertNull(loaded.database());
        assertEquals("http://127.0.0.1:8409", loaded.managementUrl());
        assertFalse(Files.readString(file).contains("databasePort"));
    }

    @Test
    void ignoresLegacyManagementPathAndKeepsRootUrl() throws Exception {
        Path file = tempDir.resolve("profiles.json");
        Files.writeString(file, """
                {"version":1,"lastAlias":"proxy","profiles":[
                  {"alias":"proxy","host":"127.0.0.1","databasePort":4409,"managementPort":8409,
                   "database":"sales","managementPath":"/sales"}
                ]}
                """);

        DbDeskProfile loaded = new DbDeskProfileStore(file).load().profiles().get(0);
        assertEquals("sales", loaded.database());
        assertEquals("http://127.0.0.1:8409", loaded.managementUrl(),
                "旧 managementPath 读取时忽略，管理 URL 由探测阶段按连接类型推导");
    }

    @Test
    void loadsProfilesWithoutManagementPath() throws Exception {
        Path file = tempDir.resolve("profiles.json");
        Files.writeString(file, """
                {"version":1,"lastAlias":"local","profiles":[
                  {"alias":"local","host":"127.0.0.1","databasePort":4409,"managementPort":8409,"database":"m0"}
                ]}
                """);

        DbDeskProfile loaded = new DbDeskProfileStore(file).load().profiles().get(0);
        assertEquals("local", loaded.alias());
        assertEquals("http://127.0.0.1:8409", loaded.managementUrl());
    }
}
