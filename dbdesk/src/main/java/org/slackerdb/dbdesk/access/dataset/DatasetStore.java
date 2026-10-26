package org.slackerdb.dbdesk.access.dataset;

import org.slackerdb.dbdesk.access.definitions.DefinitionBackend;
import org.slackerdb.dbdesk.access.definitions.DefinitionBackends;
import org.slackerdb.dbdesk.access.definitions.FileDefinitionBackend;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据集的本地存储（{@code ~/.dbdesk/datasets.json}，原子写）。
 *
 * <p><b>为什么先存文件</b>：M4 的定义只有本机在用；存文件可以零服务端改动上线。
 * 但**数据模型按"将来能搬到服务端"设计**：id 稳定、带 updatedAt、整个定义是一段 JSON，
 * 以后换成服务端 API 只换实现，不动调用方与数据形状。</p>
 *
 * <p>坏文件不抛异常：读不出来就当空列表（控制台先能打开，比"少几个图表"重要）。</p>
 */
public final class DatasetStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    /** 只有 {@code defaultStore()} 创建的实例才允许走服务端后端；显式传路径的（测试/迁移）始终用文件。 */
    private final boolean useRemoteBackend;

    public DatasetStore(Path file) {
        this(file, false);
    }

    private DatasetStore(Path file, boolean useRemoteBackend) {
        this.file = file;
        this.useRemoteBackend = useRemoteBackend;
    }

    /** 默认 {@code ~/.dbdesk/datasets.json}；可用 {@code -Ddbdesk.datasets.file=…} 覆盖（测试/CI）。 */
    public static DatasetStore defaultStore() {
        String override = System.getProperty("dbdesk.datasets.file");
        Path path = override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".dbdesk", "datasets.json");
        return new DatasetStore(path, true);
    }

    public Path file() {
        return file;
    }

    /** 服务端后端（M5）；文件后端时返回 null，走原有文件逻辑。 */
    private DefinitionBackend remote() {
        DefinitionBackend backend = DefinitionBackends.current();
        if (!useRemoteBackend) {
            return null;
        }
        return backend instanceof FileDefinitionBackend ? null : backend;
    }

    /** 全部数据集（按名字排序，界面直接可用）。 */
    public List<Dataset> all() {
        DefinitionBackend backend = remote();
        if (backend != null) {
            List<Dataset> result = new ArrayList<>();
            for (Map<String, Object> item : backend.list("datasets")) {
                result.add(fromMap(item));
            }
            result.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
            return result;
        }
        Map<String, Dataset> byName = new LinkedHashMap<>();
        for (Dataset dataset : load()) {
            byName.put(dataset.name(), dataset);
        }
        List<Dataset> result = new ArrayList<>(byName.values());
        result.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return result;
    }

    /** 按 id 或名字查（两种都试，界面里两处都可能用到）。 */
    public Optional<Dataset> find(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            return Optional.empty();
        }
        DefinitionBackend backend = remote();
        if (backend != null) {
            for (Dataset dataset : all()) {
                if (dataset.id().equals(idOrName) || dataset.name().equals(idOrName)) {
                    return Optional.of(dataset);
                }
            }
            return Optional.empty();
        }
        for (Dataset dataset : load()) {
            if (dataset.id().equals(idOrName) || dataset.name().equals(idOrName)) {
                return Optional.of(dataset);
            }
        }
        return Optional.empty();
    }

    /** 新增或按 id 覆盖；名字重复但 id 不同 → 抛异常（避免两个同名数据集）。 */
    public Dataset save(Dataset dataset) throws IOException {
        DefinitionBackend backend = remote();
        if (backend != null) {
            return fromMap(backend.save("datasets", toMap(dataset)));
        }
        List<Dataset> datasets = load();
        for (Dataset existing : datasets) {
            if (!existing.id().equals(dataset.id()) && existing.name().equals(dataset.name())) {
                throw new IllegalArgumentException("已存在同名数据集：" + dataset.name());
            }
        }
        boolean replaced = false;
        for (int i = 0; i < datasets.size(); i++) {
            if (datasets.get(i).id().equals(dataset.id())) {
                datasets.set(i, dataset);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            datasets.add(dataset);
        }
        write(datasets);
        return dataset;
    }

    public boolean delete(String id) throws IOException {
        DefinitionBackend backend = remote();
        if (backend != null) {
            return backend.delete("datasets", id);
        }
        List<Dataset> datasets = load();
        boolean removed = datasets.removeIf(dataset -> dataset.id().equals(id));
        if (removed) {
            write(datasets);
        }
        return removed;
    }

    // ========== 与服务端后端互换的映射（与文件 JSON 字段一致） ==========

    static Map<String, Object> toMap(Dataset dataset) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", dataset.id());
        map.put("name", dataset.name());
        map.put("sql", dataset.sql());
        map.put("jdbcUrl", dataset.jdbcUrl());
        List<Map<String, Object>> params = new ArrayList<>();
        for (Dataset.Param param : dataset.params()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", param.name());
            item.put("type", param.type());
            item.put("defaultValue", param.defaultValue());
            item.put("description", param.description());
            params.add(item);
        }
        map.put("params", params);
        List<Map<String, Object>> columns = new ArrayList<>();
        for (Dataset.Column column : dataset.columns()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", column.name());
            item.put("type", column.type());
            item.put("role", column.role());
            columns.add(item);
        }
        map.put("columns", columns);
        map.put("updatedAt", dataset.updatedAt());
        return map;
    }

    @SuppressWarnings("unchecked")
    static Dataset fromMap(Map<String, Object> map) {
        List<Dataset.Param> params = new ArrayList<>();
        Object rawParams = map.get("params");
        if (rawParams instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> row = (Map<String, Object>) item;
                params.add(new Dataset.Param(text(row.get("name")), text(row.get("type")),
                        text(row.get("defaultValue")), text(row.get("description"))));
            }
        }
        List<Dataset.Column> columns = new ArrayList<>();
        Object rawColumns = map.get("columns");
        if (rawColumns instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> row = (Map<String, Object>) item;
                columns.add(new Dataset.Column(text(row.get("name")), text(row.get("type")),
                        text(row.get("role"))));
            }
        }
        return new Dataset(text(map.get("id")), text(map.get("name")), text(map.get("sql")),
                text(map.get("jdbcUrl")), params, columns, text(map.get("updatedAt")));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
    // ========== 序列化 ==========

    private List<Dataset> load() {
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            JsonNode array = root.path("datasets");
            List<Dataset> datasets = new ArrayList<>();
            if (!array.isArray()) {
                return datasets;
            }
            for (JsonNode node : array) {
                try {
                    datasets.add(readDataset(node));
                } catch (RuntimeException ignored) {
                    // 单条坏了跳过：不要让一个坏定义挡住整页
                }
            }
            return datasets;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void write(List<Dataset> datasets) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", 1);
        ArrayNode array = root.putArray("datasets");
        for (Dataset dataset : datasets) {
            array.add(writeDataset(dataset));
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, JSON.writeValueAsString(root), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveUnsupported) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static ObjectNode writeDataset(Dataset dataset) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", dataset.id());
        node.put("name", dataset.name());
        node.put("sql", dataset.sql());
        node.put("jdbcUrl", dataset.jdbcUrl());
        node.put("updatedAt", dataset.updatedAt());
        ArrayNode params = node.putArray("params");
        for (Dataset.Param param : dataset.params()) {
            ObjectNode p = params.addObject();
            p.put("name", param.name());
            p.put("type", param.type());
            p.put("defaultValue", param.defaultValue());
            p.put("description", param.description());
        }
        ArrayNode columns = node.putArray("columns");
        for (Dataset.Column column : dataset.columns()) {
            ObjectNode c = columns.addObject();
            c.put("name", column.name());
            c.put("type", column.type());
            c.put("role", column.role());
        }
        return node;
    }

    private static Dataset readDataset(JsonNode node) {
        List<Dataset.Param> params = new ArrayList<>();
        for (JsonNode p : node.path("params")) {
            params.add(new Dataset.Param(p.path("name").asString(""),
                    p.path("type").asString("text"),
                    p.path("defaultValue").asString(""),
                    p.path("description").asString("")));
        }
        List<Dataset.Column> columns = new ArrayList<>();
        for (JsonNode c : node.path("columns")) {
            columns.add(new Dataset.Column(c.path("name").asString(""),
                    c.path("type").asString("other"),
                    c.path("role").asString("dimension")));
        }
        return new Dataset(node.path("id").asString(""),
                node.path("name").asString(""),
                node.path("sql").asString(""),
                node.path("jdbcUrl").asString(""),
                params, columns, node.path("updatedAt").asString(""));
    }
}
