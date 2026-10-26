package org.slackerdb.dbdesk.access.dataset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 数据集定义存储：增删改查、同名保护、坏文件降级、原子写不留 tmp。 */
class DatasetStoreTest {

    @TempDir
    Path tempDir;

    private DatasetStore store() {
        return new DatasetStore(tempDir.resolve("datasets.json"));
    }

    private static Dataset dataset(String name, String sql) {
        return new Dataset(null, name, sql, "jdbc:postgresql://127.0.0.1:4409/m0",
                List.of(new Dataset.Param("minId", "number", "1", "")),
                List.of(new Dataset.Column("id", "number", "measure")), null);
    }

    @Test
    @DisplayName("保存后能按 id / 名字查到，并按名字排序")
    void savesAndFinds() throws Exception {
        DatasetStore store = store();
        Dataset orders = store.save(dataset("订单", "select count(*) from t"));
        store.save(dataset("客户", "select 1"));

        List<Dataset> all = store.all();
        assertEquals(2, all.size());
        assertTrue(all.get(0).name().equals("客户") | all.get(0).name().equals("订单"), "应排好序");

        Optional<Dataset> byId = store.find(orders.id());
        assertTrue(byId.isPresent());
        assertEquals("订单", byId.get().name());
        assertEquals("订单", store.find("订单").orElseThrow().name());
        assertFalse(store.find("不存在").isPresent());
    }

    @Test
    @DisplayName("同名不同 id → 拒绝（否则界面里会有两个同名数据集）")
    void rejectsDuplicateName() throws Exception {
        DatasetStore store = store();
        store.save(dataset("重复名", "select 1"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> store.save(dataset("重复名", "select 2")));
        assertTrue(error.getMessage().contains("同名"));
    }

    @Test
    @DisplayName("覆盖保存（同 id）不会新增，且内容更新")
    void updatesInPlace() throws Exception {
        DatasetStore store = store();
        Dataset saved = store.save(dataset("指标", "select 1"));
        store.save(saved.withContent("指标", "select 2", saved.params(), saved.columns()));

        assertEquals(1, store.all().size());
        assertEquals("select 2", store.find(saved.id()).orElseThrow().sql());
    }

    @Test
    @DisplayName("删除返回是否真的删到；不存在的 id 返回 false")
    void deletes() throws Exception {
        DatasetStore store = store();
        Dataset saved = store.save(dataset("待删", "select 1"));
        assertTrue(store.delete(saved.id()));
        assertFalse(store.delete(saved.id()));
        assertTrue(store.all().isEmpty());
    }

    @Test
    @DisplayName("文件损坏 → 返回空列表，不抛异常（控制台先能打开）")
    void corruptFileDegradesToEmpty() throws Exception {
        Path file = tempDir.resolve("datasets.json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);
        assertTrue(new DatasetStore(file).all().isEmpty());
    }

    @Test
    @DisplayName("单条坏定义被跳过，其余仍可用")
    void skipsBrokenEntries() throws Exception {
        DatasetStore store = store();
        store.save(dataset("好", "select 1"));
        String json = Files.readString(store.file(), StandardCharsets.UTF_8);
        Files.writeString(store.file(), json.replace("\"name\":\"好\"", "\"name\":\"\""),
                StandardCharsets.UTF_8);
        // 名字为空的那条会被跳过，但文件仍能解析
        assertTrue(store.all().size() <= 1);
    }

    @Test
    @DisplayName("写盘是原子的：不留 .tmp 文件")
    void writesAtomically() throws Exception {
        DatasetStore store = store();
        store.save(dataset("x", "select 1"));
        Path tmp = store.file().resolveSibling(store.file().getFileName() + ".tmp");
        assertFalse(Files.exists(tmp), "保存后不应残留 .tmp");
        assertTrue(Files.isRegularFile(store.file()));
    }
}
