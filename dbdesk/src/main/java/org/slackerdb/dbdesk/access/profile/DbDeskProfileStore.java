package org.slackerdb.dbdesk.access.profile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DbDesk 连接历史存储。
 *
 * <p>路径保持 {@code ~/.slackerdb/profiles.json}。v1 里的 {@code managementPath} 字段
 * 已废弃：读取时直接忽略，由连接探测按“实例 / dbproxy”自动推导管理 URL。</p>
 */
public final class DbDeskProfileStore {

    private static final int VERSION = 2;

    private final Path file;
    private final ObjectMapper json = new ObjectMapper();

    public DbDeskProfileStore(Path file) {
        this.file = file;
    }

    /** 默认路径：{@code ~/.slackerdb/profiles.json}；可用 {@code -Ddbdesk.profiles.file} 覆盖。 */
    public static DbDeskProfileStore defaultStore() {
        String override = System.getProperty("dbdesk.profiles.file");
        Path path = override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".slackerdb", "profiles.json");
        return new DbDeskProfileStore(path);
    }

    public Path file() {
        return file;
    }

    public Snapshot load() {
        if (!Files.isRegularFile(file)) {
            return new Snapshot(null, List.of());
        }
        try {
            JsonNode root = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
            String lastAlias = text(root, "lastAlias");
            List<DbDeskProfile> profiles = new ArrayList<>();
            JsonNode array = root.path("profiles");
            if (array.isArray()) {
                for (JsonNode item : array) {
                    try {
                        Integer databasePort = item.path("databasePort").isNumber()
                                ? item.path("databasePort").asInt() : null;
                        Integer managementPort = item.path("managementPort").isNumber()
                                ? item.path("managementPort").asInt() : null;
                        profiles.add(new DbDeskProfile(
                                text(item, "alias"),
                                text(item, "host"),
                                databasePort,
                                managementPort,
                                text(item, "database")));
                    } catch (RuntimeException ignored) {
                        // 单条坏记录跳过
                    }
                }
            }
            return new Snapshot(lastAlias, List.copyOf(profiles));
        } catch (IOException | RuntimeException e) {
            return new Snapshot(null, List.of());
        }
    }

    /**
     * 连接成功后记录历史：同名更新并移到最前，同时把它设为 lastAlias。
     * 连接失败不会调用本方法。
     */
    public void recordSuccessful(DbDeskProfile profile) throws IOException {
        Snapshot snapshot = load();
        List<DbDeskProfile> profiles = new ArrayList<>();
        profiles.add(profile);
        for (DbDeskProfile existing : snapshot.profiles()) {
            if (!existing.alias().equals(profile.alias())) {
                profiles.add(existing);
            }
        }
        save(new Snapshot(profile.alias(), profiles));
    }

    public void save(Snapshot snapshot) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        if (snapshot.lastAlias() != null && !snapshot.lastAlias().isBlank()) {
            root.put("lastAlias", snapshot.lastAlias());
        }
        List<Map<String, Object>> profiles = new ArrayList<>();
        for (DbDeskProfile profile : snapshot.profiles()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("alias", profile.alias());
            item.put("host", profile.host());
            if (profile.databasePort() != null) {
                item.put("databasePort", profile.databasePort());
            }
            if (profile.managementPort() != null) {
                item.put("managementPort", profile.managementPort());
            }
            if (profile.database() != null) {
                item.put("database", profile.database());
            }
            profiles.add(item);
        }
        root.put("profiles", profiles);

        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, json.writeValueAsString(root), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /** 一次读取的结果。 */
    public record Snapshot(String lastAlias, List<DbDeskProfile> profiles) {
        public Snapshot {
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
        }
    }
}
