package org.slackerdb.dbdesk.access.portal;

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
 * 门户定义的本地存储（{@code ~/.dbdesk/portals.json}，原子写）。
 * token 必须全局唯一：重复的 token 会让"分享链接"指向错误的看板。
 */
public final class PortalStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    /** 只有 {@code defaultStore()} 创建的实例才允许走服务端后端；显式传路径的（测试/迁移）始终用文件。 */
    private final boolean useRemoteBackend;

    public PortalStore(Path file) {
        this(file, false);
    }

    private PortalStore(Path file, boolean useRemoteBackend) {
        this.file = file;
        this.useRemoteBackend = useRemoteBackend;
    }

    /** 默认 {@code ~/.dbdesk/portals.json}；可用 {@code -Ddbdesk.portals.file=…} 覆盖。 */
    public static PortalStore defaultStore() {
        String override = System.getProperty("dbdesk.portals.file");
        Path path = override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".dbdesk", "portals.json");
        return new PortalStore(path, true);
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

    public List<Portal> all() {
        DefinitionBackend backend = remote();
        if (backend != null) {
            List<Portal> portals = new ArrayList<>();
            for (java.util.Map<String, Object> item : backend.list("portals")) {
                portals.add(fromMap(item));
            }
            portals.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
            return portals;
        }
        List<Portal> portals = load();
        portals.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return portals;
    }

    /** 门户页面用：按 token 找（大小写不敏感，链接里可能被改大小写）。 */
    public Optional<Portal> findByToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String normalized = token.strip().toLowerCase(java.util.Locale.ROOT);
        return all().stream().filter(portal -> portal.token().equals(normalized)).findFirst();
    }

    public Optional<Portal> find(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            return Optional.empty();
        }
        for (Portal portal : all()) {
            if (portal.id().equals(idOrName) || portal.name().equals(idOrName)) {
                return Optional.of(portal);
            }
        }
        return Optional.empty();
    }

    public Portal save(Portal portal) throws IOException {
        DefinitionBackend backend = remote();
        if (backend != null) {
            return fromMap(backend.save("portals", toMap(portal)));
        }
        List<Portal> portals = load();
        for (Portal existing : portals) {
            if (!existing.id().equals(portal.id()) && existing.name().equals(portal.name())) {
                throw new IllegalArgumentException("portal name already exists: " + portal.name());
            }
            if (!existing.id().equals(portal.id()) && existing.token().equals(portal.token())) {
                throw new IllegalArgumentException("portal token already exists");
            }
        }
        boolean replaced = false;
        for (int i = 0; i < portals.size(); i++) {
            if (portals.get(i).id().equals(portal.id())) {
                portals.set(i, portal);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            portals.add(portal);
        }
        write(portals);
        return portal;
    }

    public boolean delete(String id) throws IOException {
        DefinitionBackend backend = remote();
        if (backend != null) {
            return backend.delete("portals", id);
        }
        List<Portal> portals = load();
        boolean removed = portals.removeIf(portal -> portal.id().equals(id));
        if (removed) {
            write(portals);
        }
        return removed;
    }

    static java.util.Map<String, Object> toMap(Portal portal) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", portal.id());
        map.put("name", portal.name());
        map.put("dashboardId", portal.dashboardId());
        map.put("token", portal.token());
        map.put("autoRefreshSeconds", portal.autoRefreshSeconds());
        map.put("expiresAt", portal.expiresAt());
        map.put("revoked", portal.revoked());
        map.put("updatedAt", portal.updatedAt());
        return map;
    }

    static Portal fromMap(java.util.Map<String, Object> map) {
        Object refresh = map.get("autoRefreshSeconds");
        int seconds = refresh instanceof Number number ? number.intValue() : 0;
        return new Portal(text(map.get("id")), text(map.get("name")), text(map.get("dashboardId")),
                text(map.get("token")), seconds, text(map.get("updatedAt")),
                text(map.get("expiresAt")), Boolean.TRUE.equals(map.get("revoked")));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<Portal> load() {
        List<Portal> portals = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return portals;
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            JsonNode array = root.path("portals");
            if (!array.isArray()) {
                return portals;
            }
            for (JsonNode node : array) {
                try {
                    portals.add(new Portal(node.path("id").asString(""),
                            node.path("name").asString(""),
                            node.path("dashboardId").asString(""),
                            node.path("token").asString(""),
                            node.path("autoRefreshSeconds").asInt(0),
                            node.path("updatedAt").asString(""),
                            node.path("expiresAt").asString(""),
                            node.path("revoked").asBoolean(false)));
                } catch (RuntimeException ignored) {
                    // 单条坏定义跳过
                }
            }
        } catch (Exception ignored) {
            // 坏文件当空
        }
        return portals;
    }

    private void write(List<Portal> portals) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", 1);
        ArrayNode array = root.putArray("portals");
        for (Portal portal : portals) {
            ObjectNode node = array.addObject();
            node.put("id", portal.id());
            node.put("name", portal.name());
            node.put("dashboardId", portal.dashboardId());
            node.put("token", portal.token());
            node.put("autoRefreshSeconds", portal.autoRefreshSeconds());
            node.put("expiresAt", portal.expiresAt());
            node.put("revoked", portal.revoked());
            node.put("updatedAt", portal.updatedAt());
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
}
