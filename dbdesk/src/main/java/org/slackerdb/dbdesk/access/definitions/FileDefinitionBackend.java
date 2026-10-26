package org.slackerdb.dbdesk.access.definitions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 本地文件后端（默认）：{@code ~/.dbdesk/<kind>.json}，原子写、坏条目跳过。
 *
 * <p>这就是 M4 四个 Store 用的文件格式（{@code {"version":1,"<kind>":[...]}}），
 * 所以"M4 的本地定义"与"M5 的服务端定义"是同一段 JSON，迁移只是搬字节。</p>
 */
public final class FileDefinitionBackend implements DefinitionBackend {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path baseDir;

    public FileDefinitionBackend(Path baseDir) {
        this.baseDir = baseDir;
    }

    public static FileDefinitionBackend defaultBackend() {
        return new FileDefinitionBackend(Path.of(System.getProperty("user.home"), ".dbdesk"));
    }

    @Override
    public String name() {
        return "file";
    }

    @Override
    public List<Map<String, Object>> list(String kind) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode node : readArray(kind)) {
            items.add(JSON.convertValue(node, Map.class));
        }
        return items;
    }

    @Override
    public Optional<Map<String, Object>> find(String kind, String id) {
        for (JsonNode node : readArray(kind)) {
            if (id.equals(node.path("id").asString(""))) {
                return Optional.of(JSON.convertValue(node, Map.class));
            }
        }
        return Optional.empty();
    }

    @Override
    public Map<String, Object> save(String kind, Map<String, Object> definition) {
        Map<String, Object> stored = new LinkedHashMap<>(definition);
        String id = asString(stored.get("id"));
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        stored.put("id", id);
        stored.put("updatedAt", Instant.now().toString());

        List<JsonNode> nodes = readArray(kind);
        List<JsonNode> updated = new ArrayList<>();
        boolean replaced = false;
        for (JsonNode node : nodes) {
            if (id.equals(node.path("id").asString(""))) {
                updated.add(JSON.valueToTree(stored));
                replaced = true;
            } else {
                updated.add(node);
            }
        }
        if (!replaced) {
            updated.add(JSON.valueToTree(stored));
        }
        writeArray(kind, updated);
        return stored;
    }

    @Override
    public boolean delete(String kind, String id) {
        List<JsonNode> nodes = readArray(kind);
        List<JsonNode> updated = new ArrayList<>();
        boolean removed = false;
        for (JsonNode node : nodes) {
            if (id.equals(node.path("id").asString(""))) {
                removed = true;
            } else {
                updated.add(node);
            }
        }
        if (removed) {
            writeArray(kind, updated);
        }
        return removed;
    }

    /** 文件路径：优先 {@code -Ddbdesk.<kind>.file=…}，否则 {@code <baseDir>/<kind>.json}。 */
    public Path fileFor(String kind) {
        String override = System.getProperty("dbdesk." + kind + ".file");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return baseDir.resolve(kind + ".json");
    }

    private List<JsonNode> readArray(String kind) {
        Path file = fileFor(kind);
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            JsonNode array = root.path(kind);
            List<JsonNode> nodes = new ArrayList<>();
            if (array.isArray()) {
                array.forEach(nodes::add);
            }
            return nodes;
        } catch (Exception e) {
            // 坏文件当空：控制台先能打开
            return new ArrayList<>();
        }
    }

    private void writeArray(String kind, List<JsonNode> nodes) {
        Path file = fileFor(kind);
        ObjectNode root = JSON.createObjectNode();
        root.put("version", 1);
        ArrayNode array = root.putArray(kind);
        nodes.forEach(array::add);
        try {
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
        } catch (IOException e) {
            throw new IllegalStateException("failed to write " + file + ": " + e.getMessage(), e);
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
