package org.slackerdb.dbcli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.common.profiles.ConnectionProfile;
import org.slackerdb.common.profiles.ProfileStore;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 契约 L-2（偏差实现）：{@code --connect-profile <name>} 从 {@code profiles.json} 取连接串。
 *
 * <p>名字不是 {@code --profile}：dbcli 的 {@code --profile} 已经是"启动前执行的 profile 脚本"。</p>
 */
class ConnectProfileTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("connection profile -> _connect string: [user@]host:port/database")
    void convertsProfileToLogonString() {
        assertEquals("127.0.0.1:4409/m0",
                Cli.logonStringFor(ConnectionProfile.of("local", "127.0.0.1", 4409, 0, "m0", "")));
        assertEquals("reader@db.local:5432/app",
                Cli.logonStringFor(ConnectionProfile.of("prod", "db.local", 5432, 0, "app", "reader")));
    }

    @Test
    @DisplayName("ProfileStore finds the connection dbcli needs by name")
    void findsProfileByName() throws Exception {
        ProfileStore store = new ProfileStore(tempDir.resolve("profiles.json"));
        store.save(List.of(ConnectionProfile.of("local", "127.0.0.1", 4409, 8409, "m0", ""),
                ConnectionProfile.of("prod", "db.local", 5432, 0, "app", "")), "local");

        ConnectionProfile profile = store.find("prod");
        assertEquals("db.local", profile.host());
        assertNull(store.find("nope"));
    }
}
