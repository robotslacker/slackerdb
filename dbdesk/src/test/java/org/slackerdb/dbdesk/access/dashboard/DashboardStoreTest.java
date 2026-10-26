package org.slackerdb.dbdesk.access.dashboard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 仪表板定义存储：增删改查、同名拒绝、栅格坐标钳位、坏文件降级。 */
class DashboardStoreTest {

    @TempDir
    Path tempDir;

    private DashboardStore store() {
        return new DashboardStore(tempDir.resolve("dashboards.json"));
    }

    @Test
    @DisplayName("保存 / 查找 / 排序")
    void savesAndFinds() throws Exception {
        DashboardStore store = store();
        Dashboard ops = store.save(new Dashboard(null, "运营看板",
                List.of(new Dashboard.Filter("from", "date", "2026-01-01")),
                List.of(new Dashboard.Card("chart-1", 0, 0, 6, 4)), null));
        store.save(new Dashboard(null, "财务看板", List.of(), List.of(), null));

        assertEquals(2, store.all().size());
        assertEquals("运营看板", store.find(ops.id()).orElseThrow().name());
        assertEquals("运营看板", store.find("运营看板").orElseThrow().name());
        assertFalse(store.find("不存在").isPresent());
    }

    @Test
    @DisplayName("同名不同 id 拒绝")
    void rejectsDuplicateName() throws Exception {
        DashboardStore store = store();
        store.save(new Dashboard(null, "重名", List.of(), List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> store.save(new Dashboard(null, "重名", List.of(), List.of(), null)));
    }

    @Test
    @DisplayName("栅格坐标会被钳到合法范围（防越界样式）")
    void clampsGridCoordinates() {
        Dashboard.Card card = new Dashboard.Card("c", -5, -1, 99, 0);
        assertEquals(0, card.x());
        assertEquals(0, card.y());
        assertEquals(12, card.w());
        assertEquals(1, card.h());
    }

    @Test
    @DisplayName("追加卡片落在最后一行下方，默认半宽")
    void appendsCardBelowLastRow() {
        Dashboard dashboard = new Dashboard(null, "看板", List.of(), List.of(), null)
                .withCard("c1")
                .withCard("c2");
        assertEquals(2, dashboard.cards().size());
        assertEquals(0, dashboard.cards().get(0).y());
        assertEquals(1, dashboard.cards().get(1).y());
        assertEquals(6, dashboard.cards().get(1).w());
    }

    @Test
    @DisplayName("坏文件 / 坏条目降级")
    void degradesOnCorruption() throws Exception {
        Path file = tempDir.resolve("dashboards.json");
        Files.writeString(file, "{not json", StandardCharsets.UTF_8);
        assertTrue(new DashboardStore(file).all().isEmpty());
    }
}
