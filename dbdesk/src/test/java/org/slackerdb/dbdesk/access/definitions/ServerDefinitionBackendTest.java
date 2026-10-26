package org.slackerdb.dbdesk.access.definitions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slackerdb.dbdesk.access.dataset.Dataset;
import org.slackerdb.dbdesk.access.dataset.DatasetStore;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5：服务端定义后端（真服务端）与数据集存储的切换。
 *
 * <pre>
 *   mvn -Pdbdesk -pl dbdesk test "-Ddbdesk.management.url=http://127.0.0.1:8409" "-Dtest=ServerDefinitionBackendTest"
 * </pre>
 *
 * <p>需要服务端已升级到带 {@code /definitions/*} 的版本（`features.definitions=true`）。</p>
 */
@EnabledIfSystemProperty(named = "dbdesk.management.url", matches = ".+")
class ServerDefinitionBackendTest {

    private static final String URL = System.getProperty("dbdesk.management.url");
    private ServerDefinitionBackend backend;

    @BeforeEach
    void setUp() {
        backend = new ServerDefinitionBackend(URL);
        DefinitionBackends.override(backend);
    }

    @AfterEach
    void tearDown() {
        DefinitionBackends.override(null);
    }

    private Map<String, Object> definition(String name) {
        return Map.of("name", name, "sql", "select 1",
                "jdbcUrl", "jdbc:postgresql://127.0.0.1:4409/m0");
    }

    @Test
    @DisplayName("CRUD 往返：create → list → find → update → delete")
    void crudRoundTrip() {
        String name = "M5 后端 " + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> created = backend.save("datasets", definition(name));
        String id = String.valueOf(created.get("id"));
        assertFalse(id.isBlank(), "服务端应生成 id");
        assertEquals(name, created.get("name"));

        assertTrue(backend.list("datasets").stream()
                        .anyMatch(item -> id.equals(String.valueOf(item.get("id")))),
                "列表里应包含刚创建的定义");
        assertTrue(backend.find("datasets", id).isPresent());

        Map<String, Object> toUpdate = new java.util.LinkedHashMap<>(created);
        toUpdate.put("name", name + "-改");
        Map<String, Object> updated = backend.save("datasets", toUpdate);
        assertEquals(name + "-改", updated.get("name"));

        assertTrue(backend.delete("datasets", id));
        assertFalse(backend.find("datasets", id).isPresent());
        assertFalse(backend.delete("datasets", id), "重复删除应返回 false（幂等）");
    }

    @Test
    @DisplayName("并发冲突：用过期 updatedAt 更新 → ConflictException")
    void staleUpdatedAtConflicts() {
        String name = "M5 冲突 " + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> created = backend.save("datasets", definition(name));

        Map<String, Object> stale = new java.util.LinkedHashMap<>(created);
        stale.put("updatedAt", "1999-01-01T00:00:00Z");

        assertThrows(ServerDefinitionBackend.ConflictException.class,
                () -> backend.save("datasets", stale));
        backend.delete("datasets", String.valueOf(created.get("id")));
    }

    @Test
    @DisplayName("同 kind 重名 → ConflictException")
    void duplicateNameConflicts() {
        String name = "M5 重名 " + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> first = backend.save("datasets", definition(name));
        assertThrows(ServerDefinitionBackend.ConflictException.class,
                () -> backend.save("datasets", definition(name)));
        backend.delete("datasets", String.valueOf(first.get("id")));
    }

    @Test
    @DisplayName("DatasetStore 在服务端后端下读写：页面对它无感知")
    void datasetStoreDelegatesToServer() throws Exception {
        String name = "M5 数据集 " + UUID.randomUUID().toString().substring(0, 8);
        DatasetStore store = DatasetStore.defaultStore();

        Dataset saved = store.save(new Dataset(null, name, "select 42",
                URL.replace("8409", "4409").replace("http://", "jdbc:postgresql://") + "/m0",
                List.of(), List.of(), null));

        // 服务端上确实有这条（绕过 Store 直接查）
        assertTrue(backend.find("datasets", saved.id()).isPresent(), "数据集应已写到服务端");
        assertTrue(store.all().stream().anyMatch(item -> item.id().equals(saved.id())));
        assertTrue(store.find(name).isPresent());
        assertEquals("select 42", store.find(saved.id()).orElseThrow().sql());

        assertTrue(store.delete(saved.id()));
        assertFalse(store.find(saved.id()).isPresent());
    }

    @Test
    @DisplayName("图表/门户/仪表板 Store 同样走服务端（页面无感知）")
    void otherStoresDelegateToServer() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var chartStore = org.slackerdb.dbdesk.access.chart.ChartStore.defaultStore();
        var chart = chartStore.save(new org.slackerdb.dbdesk.access.chart.Chart(null,
                "M5 图表 " + suffix, "ds-1", "bar", "month", List.of("amount"), null, "none", Map.of(), null));
        assertTrue(backend.find("charts", chart.id()).isPresent(), "图表应写到服务端");
        assertTrue(chartStore.all().stream().anyMatch(item -> item.id().equals(chart.id())));

        var portalStore = org.slackerdb.dbdesk.access.portal.PortalStore.defaultStore();
        var portal = portalStore.save(new org.slackerdb.dbdesk.access.portal.Portal(null,
                "M5 门户 " + suffix, "dash-1", null, 30, null));
        assertTrue(backend.find("portals", portal.id()).isPresent(), "门户应写到服务端");
        assertTrue(portalStore.findByToken(portal.token()).isPresent());

        var dashboardStore = org.slackerdb.dbdesk.access.dashboard.DashboardStore.defaultStore();
        var dashboard = dashboardStore.save(new org.slackerdb.dbdesk.access.dashboard.Dashboard(null,
                "M5 看板 " + suffix, List.of(), List.of(
                new org.slackerdb.dbdesk.access.dashboard.Dashboard.Card(chart.id(), 0, 0, 6, 4)), null));
        assertTrue(backend.find("dashboards", dashboard.id()).isPresent(), "看板应写到服务端");
        assertEquals(1, dashboardStore.find(dashboard.id()).orElseThrow().cards().size());

        dashboardStore.delete(dashboard.id());
        portalStore.delete(portal.id());
        chartStore.delete(chart.id());
    }

    @Test
    @DisplayName("迁移：本地文件 → 服务端，幂等（第二次全部 skipped）")
    void migratesLocalFilesToServer(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir)
            throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        // 先在临时目录里造一份"本地定义"（用的就是 M4 的文件格式）
        FileDefinitionBackend local = new FileDefinitionBackend(tempDir);
        local.save("datasets", Map.of("name", "迁移数据集 " + suffix, "sql", "select 1"));
        local.save("charts", Map.of("name", "迁移图表 " + suffix, "datasetId", "ds-1",
                "type", "bar", "x", "month", "y", List.of("amount")));

        DefinitionMigration.Summary first = DefinitionMigration.fileToServer(local, backend);
        assertEquals(2, first.copied(), "两份定义都应迁移：" + first.failed());
        assertTrue(first.failed().isEmpty(), "不应有失败：" + first.failed());

        DefinitionMigration.Summary second = DefinitionMigration.fileToServer(local, backend);
        assertEquals(0, second.copied(), "第二次不应再拷贝");
        assertEquals(2, second.skipped(), "第二次应全部跳过（幂等）");

        // 收尾：把迁移过去的两条删掉
        for (Map<String, Object> item : local.list("datasets")) {
            backend.delete("datasets", String.valueOf(item.get("id")));
        }
        for (Map<String, Object> item : local.list("charts")) {
            backend.delete("charts", String.valueOf(item.get("id")));
        }
    }
}
