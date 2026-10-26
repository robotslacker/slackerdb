package org.slackerdb.common.profiles;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约 C-4：{@code profiles.json} 的读写、默认项、旧格式迁移与坏文件降级。
 */
class ProfileStoreTest {

    @TempDir
    Path tempDir;

    private ProfileStore store() {
        return new ProfileStore(tempDir.resolve("profiles.json"));
    }

    @Test
    @DisplayName("save -> load round trip, defaultProfile is honoured")
    void roundTrip() throws Exception {
        ProfileStore store = store();
        store.save(List.of(
                ConnectionProfile.of("local", "127.0.0.1", 4409, 8409, "m0", ""),
                ConnectionProfile.of("prod", "db.local", 5432, 0, "app", "reader")), "prod");

        assertEquals("prod", store.defaultProfileName());
        ConnectionProfile profile = store.defaultProfile();
        assertEquals("db.local", profile.host());
        assertEquals(5432, profile.port());
        assertEquals("jdbc:postgresql://db.local:5432/app", profile.jdbcUrl());
        assertEquals("", profile.managementUrl(), "portX=0 means no management port");
    }

    @Test
    @DisplayName("jdbcUrl / managementUrl assembly")
    void urlBuilding() {
        ConnectionProfile profile = new ConnectionProfile("p", "server", "127.0.0.1", 4409, 8409,
                "m0", "", Map.of("sslmode", "disable"));
        assertEquals("jdbc:postgresql://127.0.0.1:4409/m0?sslmode=disable", profile.jdbcUrl());
        assertEquals("http://127.0.0.1:8409", profile.managementUrl());
    }

    @Test
    @DisplayName("legacy connections.json is readable (upgraded to the new format on next save)")
    void migratesLegacyConnectionsFile() throws Exception {
        Path file = tempDir.resolve("profiles.json");
        Files.writeString(file, """
                {"connections":[
                  {"name":"local m0","jdbcUrl":"jdbc:postgresql://127.0.0.1:4409/m0",
                   "managementUrl":"http://127.0.0.1:8409"}
                ]}
                """, StandardCharsets.UTF_8);

        ProfileStore store = new ProfileStore(file);
        List<ConnectionProfile> profiles = store.profiles();

        assertEquals(1, profiles.size());
        ConnectionProfile profile = profiles.get(0);
        assertEquals("local m0", profile.name());
        assertEquals("127.0.0.1", profile.host());
        assertEquals(4409, profile.port());
        assertEquals(8409, profile.portX());
        assertEquals("m0", profile.database());
    }

    @Test
    @DisplayName("a corrupt file degrades to an empty list instead of throwing")
    void corruptFileDegrades() throws Exception {
        Path file = tempDir.resolve("profiles.json");
        Files.writeString(file, "{ not json", StandardCharsets.UTF_8);

        assertTrue(new ProfileStore(file).profiles().isEmpty());
        assertNull(new ProfileStore(file).defaultProfileName());
    }

    @Test
    @DisplayName("upsert / remove maintain defaultProfile")
    void upsertAndRemove() throws Exception {
        ProfileStore store = store();
        store.save(List.of(ConnectionProfile.of("a", "h1", 1, 0, "d", "")), "a");

        store.upsert(ConnectionProfile.of("b", "h2", 2, 0, "d", ""), null);
        assertEquals(List.of("a", "b"), store.profiles().stream().map(ConnectionProfile::name).toList());
        assertEquals("a", store.defaultProfileName(), "the previous default is kept when no default is passed");

        store.remove("a");
        assertEquals(List.of("b"), store.profiles().stream().map(ConnectionProfile::name).toList());
        assertEquals("b", store.defaultProfileName(), "removing the default falls back to the first remaining entry");
    }

    @Test
    @DisplayName("writing is atomic and leaves no .tmp behind")
    void atomicWrite() throws Exception {
        ProfileStore store = store();
        store.save(List.of(ConnectionProfile.of("a", "h", 1, 0, "d", "")), "a");

        assertTrue(Files.isRegularFile(tempDir.resolve("profiles.json")));
        assertFalse(Files.exists(tempDir.resolve("profiles.json.tmp")));
    }

    @Test
    @DisplayName("required fields are validated")
    void validatesRequiredFields() {
        assertThrows(IllegalArgumentException.class, () -> ConnectionProfile.of("", "h", 1, 0, "d", ""));
        assertThrows(IllegalArgumentException.class, () -> ConnectionProfile.of("n", "", 1, 0, "d", ""));
        assertThrows(IllegalArgumentException.class, () -> ConnectionProfile.of("n", "h", 1, 0, "", ""));
    }
}
