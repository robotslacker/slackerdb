package org.slackerdb.common.profiles;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 契约 C-4 的 {@code profiles.json} 读写（{@code ~/.slackerdb/profiles.json}）。
 *
 * <p>为什么放在 {@code common}：{@code dbcli} 与控制台要读**同一份**文件，
 * 规则（默认 profile、原子写、坏文件降级、口令不落盘）只写一次，两边就不可能漂移。</p>
 *
 * <p>与旧版控制台的 {@code connections.json}（只有 name/jdbcUrl/managementUrl）兼容：
 * 读的时候如果发现旧格式，会按 URL 反解出 host/port/database，并在下次保存时升级成新格式。</p>
 */
public final class ProfileStore {

    /** 契约版本；破坏性变更才递增。 */
    public static final int VERSION = 1;

    private final Path file;

    public ProfileStore(Path file) {
        this.file = file;
    }

    /** 默认位置：{@code ~/.slackerdb/profiles.json}（可用 {@code -Dslackerdb.profiles.file} 覆盖）。 */
    public static ProfileStore defaultStore() {
        String override = System.getProperty("slackerdb.profiles.file");
        Path path = override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".slackerdb", "profiles.json");
        return new ProfileStore(path);
    }

    public Path file() {
        return file;
    }

    /** 载入全部 profile；文件不存在返回空列表（由调用方决定是否塞默认值）。 */
    public List<ConnectionProfile> profiles() {
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return new ArrayList<>(); // 坏文件不该让 CLI/控制台起不来
        }
    }

    /** 默认 profile 名；没有显式设置时取第一条，空列表返回 null。 */
    public String defaultProfileName() {
        List<ConnectionProfile> all = profiles();
        if (all.isEmpty()) {
            return null;
        }
        try {
            JSONObject root = JSON.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            String name = root.getString("defaultProfile");
            if (name != null && all.stream().anyMatch(p -> p.name().equals(name))) {
                return name;
            }
        } catch (Exception ignored) {
            // 落到"第一条"
        }
        return all.get(0).name();
    }

    /** 按名字取；没有返回 null。 */
    public ConnectionProfile find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (ConnectionProfile profile : profiles()) {
            if (profile.name().equals(name)) {
                return profile;
            }
        }
        return null;
    }

    /** 默认 profile（显式 defaultProfile 优先，否则第一条）。 */
    public ConnectionProfile defaultProfile() {
        return find(defaultProfileName());
    }

    /** 原子写入（写 .tmp 再 ATOMIC_MOVE），同时设置 defaultProfile。 */
    public void save(List<ConnectionProfile> profiles, String defaultProfile) throws IOException {
        JSONObject root = new JSONObject();
        root.put("version", VERSION);
        if (defaultProfile != null && !defaultProfile.isBlank()) {
            root.put("defaultProfile", defaultProfile);
        }
        JSONArray array = new JSONArray();
        for (ConnectionProfile profile : profiles) {
            JSONObject node = new JSONObject();
            node.put("name", profile.name());
            node.put("kind", profile.kind());
            node.put("host", profile.host());
            node.put("port", profile.port());
            node.put("portX", profile.portX());
            node.put("database", profile.database());
            node.put("user", profile.user());
            if (!profile.options().isEmpty()) {
                node.put("options", new JSONObject(new LinkedHashMap<>(profile.options())));
            }
            array.add(node);
        }
        root.put("profiles", array);

        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, JSON.toJSONString(root, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat),
                StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** upsert（按 name）：返回新的完整列表。 */
    public List<ConnectionProfile> upsert(ConnectionProfile profile, String defaultProfile) throws IOException {
        List<ConnectionProfile> all = profiles();
        List<ConnectionProfile> updated = new ArrayList<>(all.size() + 1);
        boolean replaced = false;
        for (ConnectionProfile existing : all) {
            if (existing.name().equals(profile.name())) {
                updated.add(profile);
                replaced = true;
            } else {
                updated.add(existing);
            }
        }
        if (!replaced) {
            updated.add(profile);
        }
        String effectiveDefault = defaultProfile == null || defaultProfile.isBlank()
                ? defaultProfileName() : defaultProfile;
        save(updated, effectiveDefault);
        return List.copyOf(updated);
    }

    /** 按 name 删除；返回新的完整列表。 */
    public List<ConnectionProfile> remove(String name) throws IOException {
        List<ConnectionProfile> remaining = profiles().stream()
                .filter(profile -> !profile.name().equals(name))
                .toList();
        String currentDefault = defaultProfileName();
        save(remaining, name.equals(currentDefault) ? null : currentDefault);
        return remaining;
    }

    // ========== 解析（含旧格式兼容） ==========

    private static List<ConnectionProfile> parse(String text) {
        JSONObject root = JSON.parseObject(text);
        List<ConnectionProfile> result = new ArrayList<>();
        JSONArray array = root.getJSONArray("profiles");
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                ConnectionProfile profile = fromObject(array.getJSONObject(i));
                if (profile != null) {
                    result.add(profile);
                }
            }
            return result;
        }
        // 旧格式（控制台的 connections.json）：只有 name / jdbcUrl / managementUrl
        JSONArray legacy = root.getJSONArray("connections");
        if (legacy != null) {
            for (int i = 0; i < legacy.size(); i++) {
                ConnectionProfile profile = fromLegacy(legacy.getJSONObject(i));
                if (profile != null) {
                    result.add(profile);
                }
            }
        }
        return result;
    }

    private static ConnectionProfile fromObject(JSONObject node) {
        try {
            String name = node.getString("name");
            String host = node.getString("host");
            String database = node.getString("database");
            if (name == null || host == null || database == null) {
                return null;
            }
            Map<String, String> options = new LinkedHashMap<>();
            JSONObject rawOptions = node.getJSONObject("options");
            if (rawOptions != null) {
                for (String key : rawOptions.keySet()) {
                    options.put(key, String.valueOf(rawOptions.get(key)));
                }
            }
            return new ConnectionProfile(name, node.getString("kind"), host,
                    node.getIntValue("port", 0), node.getIntValue("portX", 0),
                    database, node.getString("user"), options);
        } catch (RuntimeException e) {
            return null; // 坏条目跳过，不影响其它 profile
        }
    }

    /** 旧格式：{ name, jdbcUrl, managementUrl } → 反解 host/port/database。 */
    private static ConnectionProfile fromLegacy(JSONObject node) {
        try {
            String name = node.getString("name");
            String jdbcUrl = node.getString("jdbcUrl");
            if (name == null || jdbcUrl == null) {
                return null;
            }
            String rest = jdbcUrl.substring(jdbcUrl.indexOf("//") + 2);
            int slash = rest.indexOf('/');
            String hostPort = slash < 0 ? rest : rest.substring(0, slash);
            String database = slash < 0 ? "" : rest.substring(slash + 1);
            int query = database.indexOf('?');
            if (query >= 0) {
                database = database.substring(0, query);
            }
            int colon = hostPort.lastIndexOf(':');
            String host = colon < 0 ? hostPort : hostPort.substring(0, colon);
            int port = colon < 0 ? 0 : Integer.parseInt(hostPort.substring(colon + 1));
            int portX = 0;
            String managementUrl = node.getString("managementUrl");
            if (managementUrl != null && !managementUrl.isBlank()) {
                int lastColon = managementUrl.lastIndexOf(':');
                if (lastColon > 0) {
                    try {
                        portX = Integer.parseInt(managementUrl.substring(lastColon + 1).replaceAll("/.*$", ""));
                    } catch (NumberFormatException ignored) {
                        portX = 0;
                    }
                }
            }
            return new ConnectionProfile(name, ConnectionProfile.KIND_SERVER, host, port, portX, database, "", Map.of());
        } catch (RuntimeException e) {
            return null;
        }
    }

}
