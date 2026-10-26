package org.slackerdb.dbdesk.access.chart;

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
import java.util.List;
import java.util.Optional;

/**
 * 图表定义的本地存储（{@code ~/.dbdesk/charts.json}，原子写）。
 *
 * <p>与数据集存储同一套约定：坏文件降级为空、坏条目跳过、同名拒绝、写盘原子。
 * 同样按"以后能搬到服务端"的形状设计（稳定 id + updatedAt + 整段 JSON）。</p>
 */
public final class ChartStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    /** 只有 {@code defaultStore()} 创建的实例才允许走服务端后端；显式传路径的（测试/迁移）始终用文件。 */
    private final boolean useRemoteBackend;

    public ChartStore(Path file) {
        this(file, false);
    }

    private ChartStore(Path file, boolean useRemoteBackend) {
        this.file = file;
        this.useRemoteBackend = useRemoteBackend;
    }

    /** 默认 {@code ~/.dbdesk/charts.json}；可用 {@code -Ddbdesk.charts.file=…} 覆盖。 */
    public static ChartStore defaultStore() {
        String override = System.getProperty("dbdesk.charts.file");
        Path path = override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".dbdesk", "charts.json");
        return new ChartStore(path, true);
    }

    public Path file() {
        return file;
    }

    /** 服务端后端（M5）；文件后端时返回 null。 */
    private DefinitionBackend remote() {
        DefinitionBackend backend = DefinitionBackends.current();
        if (!useRemoteBackend) {
            return null;
        }
        return backend instanceof FileDefinitionBackend ? null : backend;
    }

    public List<Chart> all() {
        DefinitionBackend backend = remote();
        List<Chart> fromServer = backend == null ? null : serverCharts(backend);
        if (fromServer != null) {
            return fromServer;
        }
        List<Chart> charts = load();
        charts.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return charts;
    }

    public List<Chart> byDataset(String datasetId) {
        return all().stream().filter(chart -> chart.datasetId().equals(datasetId)).toList();
    }

    public Optional<Chart> find(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            return Optional.empty();
        }
        for (Chart chart : all()) {
            if (chart.id().equals(idOrName) || chart.name().equals(idOrName)) {
                return Optional.of(chart);
            }
        }
        return Optional.empty();
    }

    public Chart save(Chart chart) throws IOException {
        DefinitionBackend backend = remote();
        if (backend != null) {
            return fromMap(backend.save("charts", toMap(chart)));
        }
        List<Chart> charts = load();
        for (Chart existing : charts) {
            if (!existing.id().equals(chart.id()) && existing.name().equals(chart.name())) {
                throw new IllegalArgumentException("chart name already exists: " + chart.name());
            }
        }
        boolean replaced = false;
        for (int i = 0; i < charts.size(); i++) {
            if (charts.get(i).id().equals(chart.id())) {
                charts.set(i, chart);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            charts.add(chart);
        }
        write(charts);
        return chart;
    }

    public boolean delete(String id) throws IOException {
        DefinitionBackend backend = remote();
        if (backend != null) {
            return backend.delete("charts", id);
        }
        List<Chart> charts = load();
        boolean removed = charts.removeIf(chart -> chart.id().equals(id));
        if (removed) {
            write(charts);
        }
        return removed;
    }

    private List<Chart> serverCharts(DefinitionBackend backend) {
        List<Chart> charts = new ArrayList<>();
        for (java.util.Map<String, Object> item : backend.list("charts")) {
            charts.add(fromMap(item));
        }
        charts.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return charts;
    }

    static java.util.Map<String, Object> toMap(Chart chart) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", chart.id());
        map.put("name", chart.name());
        map.put("datasetId", chart.datasetId());
        map.put("type", chart.type());
        map.put("x", chart.x());
        map.put("y", chart.y());
        map.put("series", chart.series());
        map.put("aggregate", chart.aggregate());
        map.put("updatedAt", chart.updatedAt());
        return map;
    }

    @SuppressWarnings("unchecked")
    static Chart fromMap(java.util.Map<String, Object> map) {
        List<String> y = new ArrayList<>();
        Object rawY = map.get("y");
        if (rawY instanceof List<?> list) {
            for (Object item : list) {
                y.add(item == null ? "" : String.valueOf(item));
            }
        }
        return new Chart(text(map.get("id")), text(map.get("name")), text(map.get("datasetId")),
                text(map.get("type")), emptyToNull(map.get("x")), y, emptyToNull(map.get("series")),
                text(map.get("aggregate")), java.util.Map.of(), text(map.get("updatedAt")));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String emptyToNull(Object value) {
        String text = text(value);
        return text.isBlank() ? null : text;
    }

    private List<Chart> load() {
        List<Chart> charts = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return charts;
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            JsonNode array = root.path("charts");
            if (!array.isArray()) {
                return charts;
            }
            for (JsonNode node : array) {
                try {
                    charts.add(readChart(node));
                } catch (RuntimeException ignored) {
                    // 单条坏定义跳过
                }
            }
        } catch (Exception ignored) {
            // 坏文件当空
        }
        return charts;
    }

    private void write(List<Chart> charts) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", 1);
        ArrayNode array = root.putArray("charts");
        for (Chart chart : charts) {
            ObjectNode node = array.addObject();
            node.put("id", chart.id());
            node.put("name", chart.name());
            node.put("datasetId", chart.datasetId());
            node.put("type", chart.type());
            node.put("x", chart.x());
            ArrayNode y = node.putArray("y");
            chart.y().forEach(y::add);
            node.put("series", chart.series());
            node.put("aggregate", chart.aggregate());
            node.put("updatedAt", chart.updatedAt());
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

    private static Chart readChart(JsonNode node) {
        List<String> y = new ArrayList<>();
        for (JsonNode item : node.path("y")) {
            y.add(item.asString());
        }
        return new Chart(node.path("id").asString(""),
                node.path("name").asString(""),
                node.path("datasetId").asString(""),
                node.path("type").asString("line"),
                node.path("x").asString(null),
                y,
                node.path("series").asString(null),
                node.path("aggregate").asString("none"),
                java.util.Map.of(),
                node.path("updatedAt").asString(""));
    }
}
