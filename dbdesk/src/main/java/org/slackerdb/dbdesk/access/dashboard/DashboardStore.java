package org.slackerdb.dbdesk.access.dashboard;

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
 * 仪表板定义的本地存储（{@code ~/.dbdesk/dashboards.json}，原子写）。
 * 约定与数据集/图表存储一致：坏文件降级、坏条目跳过、同名拒绝。
 */
public final class DashboardStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    /** 只有 {@code defaultStore()} 创建的实例才允许走服务端后端；显式传路径的（测试/迁移）始终用文件。 */
    private final boolean useRemoteBackend;

    public DashboardStore(Path file) {
        this(file, false);
    }

    private DashboardStore(Path file, boolean useRemoteBackend) {
        this.file = file;
        this.useRemoteBackend = useRemoteBackend;
    }

    /** 默认 {@code ~/.dbdesk/dashboards.json}；可用 {@code -Ddbdesk.dashboards.file=…} 覆盖。 */
    public static DashboardStore defaultStore() {
        String override = System.getProperty("dbdesk.dashboards.file");
        Path path = override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".dbdesk", "dashboards.json");
        return new DashboardStore(path, true);
    }

    public Path file() {
        return file;
    }

    /** 服务端后端（M5）；文件后端时返回 null。 */
    private DefinitionBackend remote() {
        if (!useRemoteBackend) {
            return null;
        }
        DefinitionBackend backend = DefinitionBackends.current();
        return backend instanceof FileDefinitionBackend ? null : backend;
    }

    public List<Dashboard> all() {
        DefinitionBackend backend = remote();
        if (backend != null) {
            List<Dashboard> dashboards = new ArrayList<>();
            for (java.util.Map<String, Object> item : backend.list("dashboards")) {
                dashboards.add(fromMap(item));
            }
            dashboards.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
            return dashboards;
        }
        List<Dashboard> dashboards = load();
        dashboards.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return dashboards;
    }

    public Optional<Dashboard> find(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            return Optional.empty();
        }
        for (Dashboard dashboard : all()) {
            if (dashboard.id().equals(idOrName) || dashboard.name().equals(idOrName)) {
                return Optional.of(dashboard);
            }
        }
        return Optional.empty();
    }

    public Dashboard save(Dashboard dashboard) throws IOException {
        DefinitionBackend backend = remote();
        if (backend != null) {
            return fromMap(backend.save("dashboards", toMap(dashboard)));
        }
        List<Dashboard> dashboards = load();
        for (Dashboard existing : dashboards) {
            if (!existing.id().equals(dashboard.id()) && existing.name().equals(dashboard.name())) {
                throw new IllegalArgumentException("dashboard name already exists: " + dashboard.name());
            }
        }
        boolean replaced = false;
        for (int i = 0; i < dashboards.size(); i++) {
            if (dashboards.get(i).id().equals(dashboard.id())) {
                dashboards.set(i, dashboard);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            dashboards.add(dashboard);
        }
        write(dashboards);
        return dashboard;
    }

    public boolean delete(String id) throws IOException {
        DefinitionBackend backend = remote();
        if (backend != null) {
            return backend.delete("dashboards", id);
        }
        List<Dashboard> dashboards = load();
        boolean removed = dashboards.removeIf(dashboard -> dashboard.id().equals(id));
        if (removed) {
            write(dashboards);
        }
        return removed;
    }

    static java.util.Map<String, Object> toMap(Dashboard dashboard) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", dashboard.id());
        map.put("name", dashboard.name());
        java.util.List<java.util.Map<String, Object>> filters = new ArrayList<>();
        for (Dashboard.Filter filter : dashboard.filters()) {
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("name", filter.name());
            item.put("type", filter.type());
            item.put("defaultValue", filter.defaultValue());
            filters.add(item);
        }
        map.put("filters", filters);
        java.util.List<java.util.Map<String, Object>> cards = new ArrayList<>();
        for (Dashboard.Card card : dashboard.cards()) {
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("chartId", card.chartId());
            item.put("x", card.x());
            item.put("y", card.y());
            item.put("w", card.w());
            item.put("h", card.h());
            cards.add(item);
        }
        map.put("cards", cards);
        map.put("updatedAt", dashboard.updatedAt());
        return map;
    }

    @SuppressWarnings("unchecked")
    static Dashboard fromMap(java.util.Map<String, Object> map) {
        java.util.List<Dashboard.Filter> filters = new ArrayList<>();
        if (map.get("filters") instanceof java.util.List<?> list) {
            for (Object item : list) {
                java.util.Map<String, Object> row = (java.util.Map<String, Object>) item;
                filters.add(new Dashboard.Filter(text(row.get("name")), text(row.get("type")),
                        text(row.get("defaultValue"))));
            }
        }
        java.util.List<Dashboard.Card> cards = new ArrayList<>();
        if (map.get("cards") instanceof java.util.List<?> list) {
            for (Object item : list) {
                java.util.Map<String, Object> row = (java.util.Map<String, Object>) item;
                cards.add(new Dashboard.Card(text(row.get("chartId")), number(row.get("x")),
                        number(row.get("y")), number(row.get("w")), number(row.get("h"))));
            }
        }
        return new Dashboard(text(map.get("id")), text(map.get("name")), filters, cards,
                text(map.get("updatedAt")));
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<Dashboard> load() {
        List<Dashboard> dashboards = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return dashboards;
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            JsonNode array = root.path("dashboards");
            if (!array.isArray()) {
                return dashboards;
            }
            for (JsonNode node : array) {
                try {
                    dashboards.add(readDashboard(node));
                } catch (RuntimeException ignored) {
                    // 单条坏定义跳过
                }
            }
        } catch (Exception ignored) {
            // 坏文件当空
        }
        return dashboards;
    }

    private void write(List<Dashboard> dashboards) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", 1);
        ArrayNode array = root.putArray("dashboards");
        for (Dashboard dashboard : dashboards) {
            ObjectNode node = array.addObject();
            node.put("id", dashboard.id());
            node.put("name", dashboard.name());
            node.put("updatedAt", dashboard.updatedAt());
            ArrayNode filters = node.putArray("filters");
            for (Dashboard.Filter filter : dashboard.filters()) {
                ObjectNode f = filters.addObject();
                f.put("name", filter.name());
                f.put("type", filter.type());
                f.put("defaultValue", filter.defaultValue());
            }
            ArrayNode cards = node.putArray("cards");
            for (Dashboard.Card card : dashboard.cards()) {
                ObjectNode c = cards.addObject();
                c.put("chartId", card.chartId());
                c.put("x", card.x());
                c.put("y", card.y());
                c.put("w", card.w());
                c.put("h", card.h());
            }
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

    private static Dashboard readDashboard(JsonNode node) {
        List<Dashboard.Filter> filters = new ArrayList<>();
        for (JsonNode f : node.path("filters")) {
            filters.add(new Dashboard.Filter(f.path("name").asString(""),
                    f.path("type").asString("text"), f.path("defaultValue").asString("")));
        }
        List<Dashboard.Card> cards = new ArrayList<>();
        for (JsonNode c : node.path("cards")) {
            cards.add(new Dashboard.Card(c.path("chartId").asString(""),
                    c.path("x").asInt(0), c.path("y").asInt(0),
                    c.path("w").asInt(6), c.path("h").asInt(4)));
        }
        return new Dashboard(node.path("id").asString(""),
                node.path("name").asString(""), filters, cards, node.path("updatedAt").asString(""));
    }
}
