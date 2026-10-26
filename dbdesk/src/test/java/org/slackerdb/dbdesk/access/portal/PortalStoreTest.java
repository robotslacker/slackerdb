package org.slackerdb.dbdesk.access.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 门户存储与 token：唯一性、按 token 查、坏文件降级。 */
class PortalStoreTest {

    @TempDir
    Path tempDir;

    private PortalStore store() {
        return new PortalStore(tempDir.resolve("portals.json"));
    }

    @Test
    @DisplayName("保存后能按 token 找到；token 大小写不敏感")
    void savesAndFindsByToken() throws Exception {
        PortalStore store = store();
        Portal portal = store.save(new Portal(null, "运营看板（对外）", "dash-1", null, 60, null));

        assertTrue(store.findByToken(portal.token()).isPresent());
        assertTrue(store.findByToken(portal.token().toUpperCase(java.util.Locale.ROOT)).isPresent());
        assertFalse(store.findByToken("deadbeef").isPresent());
        assertFalse(store.findByToken("").isPresent());
    }

    @Test
    @DisplayName("token 每次生成都不一样（128 位随机）")
    void tokensAreRandom() {
        String a = Portal.newToken();
        String b = Portal.newToken();
        assertEquals(32, a.length(), "32 个十六进制字符");
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("同名或同 token 的不同门户被拒绝")
    void rejectsDuplicateNameOrToken() throws Exception {
        PortalStore store = store();
        Portal first = store.save(new Portal(null, "门户", "dash-1", null, 0, null));

        assertThrows(IllegalArgumentException.class,
                () -> store.save(new Portal(null, "门户", "dash-2", null, 0, null)));
        assertThrows(IllegalArgumentException.class,
                () -> store.save(new Portal(null, "另一个门户", "dash-2", first.token(), 0, null)));
    }

    @Test
    @DisplayName("删除后链接即失效")
    void deleteRemovesToken() throws Exception {
        PortalStore store = store();
        Portal portal = store.save(new Portal(null, "临时门户", "dash-1", null, 0, null));
        assertTrue(store.delete(portal.id()));
        assertFalse(store.findByToken(portal.token()).isPresent());
    }

    @Test
    @DisplayName("自动刷新间隔被钳到 0..3600")
    void clampsRefresh() {
        assertEquals(0, new Portal(null, "p", "d", null, -10, null).autoRefreshSeconds());
        assertEquals(3_600, new Portal(null, "p", "d", null, 99_999, null).autoRefreshSeconds());
    }

    @Test
    @DisplayName("坏文件降级为空")
    void degradesOnCorruption() throws Exception {
        Path file = tempDir.resolve("portals.json");
        Files.writeString(file, "not-json", StandardCharsets.UTF_8);
        assertTrue(new PortalStore(file).all().isEmpty());
    }

    @Test
    @DisplayName("列表按名字排序")
    void sortsByName() throws Exception {
        PortalStore store = store();
        store.save(new Portal(null, "B 门户", "d1", null, 0, null));
        store.save(new Portal(null, "A 门户", "d2", null, 0, null));
        List<Portal> all = store.all();
        assertEquals("A 门户", all.get(0).name());
    }
}
