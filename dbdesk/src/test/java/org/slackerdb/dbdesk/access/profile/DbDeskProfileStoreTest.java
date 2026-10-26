package org.slackerdb.dbdesk.access.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(snapshot.profiles().get(0).managementPort() == null);
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
    void databaseNameIsRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> new DbDeskProfile("x", "127.0.0.1", 4409, 8409, " "));
    }
}
