package org.slackerdb.dbsyncer.config;

import org.slackerdb.dbsyncer.DbSyncerConfigException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 严格、手写的 YAML→模型绑定器。
 */
final class ConfigBinder {

    SyncConfig bind(Object root) throws DbSyncerConfigException {
        Map<String, Object> object = asMap(root, "$");
        checkKeys(object, "$", "version", "defaults", "connections", "targets", "jobs");

        SyncConfig config = new SyncConfig();
        config.version = intValue(object, "version", "$", 1, 1, Integer.MAX_VALUE);

        Object defaultsObject = object.get("defaults");
        if (defaultsObject != null) {
            bindDefaults(asMap(defaultsObject, "$.defaults"), "$.defaults", config.defaults);
        }

        Object connectionsObject = object.get("connections");
        if (connectionsObject != null) {
            Map<String, Object> connections = asMap(connectionsObject, "$.connections");
            for (Map.Entry<String, Object> entry : connections.entrySet()) {
                String name = requireName(entry.getKey(), "$.connections");
                String path = "$.connections." + name;
                config.connections.put(name, bindConnection(asMap(entry.getValue(), path), path));
            }
        }

        Object targetsObject = object.get("targets");
        if (targetsObject != null) {
            Map<String, Object> targets = asMap(targetsObject, "$.targets");
            for (Map.Entry<String, Object> entry : targets.entrySet()) {
                String name = requireName(entry.getKey(), "$.targets");
                String path = "$.targets." + name;
                config.targets.put(name, bindTarget(asMap(entry.getValue(), path), path));
            }
        }

        Object jobsObject = object.get("jobs");
        if (jobsObject != null) {
            List<Object> jobs = asList(jobsObject, "$.jobs");
            for (int i = 0; i < jobs.size(); i++) {
                String path = "$.jobs[" + i + "]";
                config.jobs.add(bindJob(asMap(jobs.get(i), path), path, config.defaults.snapshot));
            }
        }

        applyInheritance(config);
        return config;
    }

    private void applyInheritance(SyncConfig config) {
        for (SyncConfig.JobConfig job : config.jobs) {
            if (job.snapshot == null) {
                job.snapshot = config.defaults.snapshot.copy();
            }
        }
    }

    // ------------------------------------------------------------------ defaults

    private void bindDefaults(Map<String, Object> map, String path, SyncConfig.Defaults defaults)
            throws DbSyncerConfigException {
        checkKeys(map, path, "batch", "reconnect", "snapshot");

        Object batch = map.get("batch");
        if (batch != null) {
            bindBatch(asMap(batch, path + ".batch"), path + ".batch", defaults.batch);
        }
        Object reconnect = map.get("reconnect");
        if (reconnect != null) {
            bindReconnect(asMap(reconnect, path + ".reconnect"), path + ".reconnect", defaults.reconnect);
        }
        Object snapshot = map.get("snapshot");
        if (snapshot != null) {
            bindSnapshot(asMap(snapshot, path + ".snapshot"), path + ".snapshot", defaults.snapshot);
        }
    }

    private void bindBatch(Map<String, Object> map, String path, SyncConfig.BatchConfig target)
            throws DbSyncerConfigException {
        checkKeys(map, path, "maxRows", "flushIntervalMs", "maxBufferedEvents", "maxBufferedBytes",
                "maxAttempts", "retryIntervalMs");
        target.maxRows = intValue(map, "maxRows", path, target.maxRows, 1, 1_000_000);
        target.flushIntervalMs = longValue(map, "flushIntervalMs", path, target.flushIntervalMs,
                100, 3_600_000);
        target.maxBufferedEvents = intValue(map, "maxBufferedEvents", path,
                target.maxBufferedEvents, 100, 10_000_000);
        target.maxBufferedBytes = longValue(map, "maxBufferedBytes", path,
                target.maxBufferedBytes, 1_048_576, 1_073_741_824);
        target.maxAttempts = intValue(map, "maxAttempts", path, target.maxAttempts, 1, 100);
        target.retryIntervalMs = longValue(map, "retryIntervalMs", path,
                target.retryIntervalMs, 0, 600_000);
    }

    private void bindReconnect(Map<String, Object> map, String path, SyncConfig.ReconnectConfig target)
            throws DbSyncerConfigException {
        checkKeys(map, path, "maxAttempts", "intervalMs");
        target.maxAttempts = intValue(map, "maxAttempts", path, target.maxAttempts, 0, 100_000);
        target.intervalMs = longValue(map, "intervalMs", path, target.intervalMs, 0, 3_600_000);
    }

    private void bindSnapshot(Map<String, Object> map, String path, SyncConfig.SnapshotConfig target)
            throws DbSyncerConfigException {
        checkKeys(map, path, "mode", "fetchSize");
        target.mode = enumValue(map, "mode", path, SyncConfig.SnapshotMode::from, target.mode);
        target.fetchSize = intValue(map, "fetchSize", path, target.fetchSize, 1, 1_000_000);
    }

    // ------------------------------------------------------------------ connections / targets

    private SyncConfig.ConnectionConfig bindConnection(Map<String, Object> map, String path)
            throws DbSyncerConfigException {
        checkKeys(map, path, "type", "url", "username", "password", "properties", "replication");
        SyncConfig.ConnectionConfig connection = new SyncConfig.ConnectionConfig();
        connection.type = enumValue(map, "type", path, SyncConfig.SourceType::from, null);
        if (connection.type == null) {
            throw new DbSyncerConfigException(path + ".type", "type is required");
        }
        connection.url = requiredString(map, "url", path);
        connection.username = requiredString(map, "username", path);
        connection.password = stringValue(map, "password", path, "");
        if (connection.password.contains("${")) {
            throw new DbSyncerConfigException(path + ".password",
                    "password only supports plaintext; variable substitution is not supported");
        }
        Object properties = map.get("properties");
        if (properties != null) {
            Map<String, Object> values = asMap(properties, path + ".properties");
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                connection.properties.put(entry.getKey(), stringScalar(entry.getValue(), path + ".properties." + entry.getKey()));
            }
        }
        Object replication = map.get("replication");
        if (replication != null) {
            bindReplication(asMap(replication, path + ".replication"), path + ".replication", connection.replication);
        }
        return connection;
    }

    private SyncConfig.TargetConfig bindTarget(Map<String, Object> map, String path)
            throws DbSyncerConfigException {
        checkKeys(map, path, "schema");
        SyncConfig.TargetConfig target = new SyncConfig.TargetConfig();
        target.schema = requiredString(map, "schema", path);
        return target;
    }

    // ------------------------------------------------------------------ jobs

    private SyncConfig.JobConfig bindJob(Map<String, Object> map, String path,
                                         SyncConfig.SnapshotConfig inheritedSnapshot)
            throws DbSyncerConfigException {
        checkKeys(map, path, "name", "description", "source", "target", "mode", "autoStart",
                "replication", "tables", "snapshot");
        SyncConfig.JobConfig job = new SyncConfig.JobConfig();
        job.name = requiredString(map, "name", path);
        job.description = stringValue(map, "description", path, "");
        job.source = requiredString(map, "source", path);
        job.target = requiredString(map, "target", path);
        job.mode = enumValue(map, "mode", path, SyncConfig.JobMode::from, job.mode);
        job.autoStart = boolValue(map, "autoStart", path, job.autoStart);
        Object replication = map.get("replication");
        if (replication != null) {
            bindReplication(asMap(replication, path + ".replication"), path + ".replication", job.replication);
        }
        Object tables = map.get("tables");
        if (tables != null) {
            bindTables(asMap(tables, path + ".tables"), path + ".tables", job.tables);
        }
        Object snapshot = map.get("snapshot");
        if (snapshot != null) {
            // 作业级只覆盖显式写出的键，没写的继续继承 defaults（否则只写 fetchSize 会把
            // mode 重置成 replace，目标表会被悄悄 TRUNCATE）。
            job.snapshot = inheritedSnapshot.copy();
            bindSnapshot(asMap(snapshot, path + ".snapshot"), path + ".snapshot", job.snapshot);
        }
        return job;
    }

    private void bindTables(Map<String, Object> map, String path, SyncConfig.TablesConfig tables)
            throws DbSyncerConfigException {
        checkKeys(map, path, "include", "exclude", "caseSensitive");
        Object includes = map.get("include");
        if (includes != null) {
            List<Object> list = asList(includes, path + ".include");
            for (int i = 0; i < list.size(); i++) {
                String itemPath = path + ".include[" + i + "]";
                Map<String, Object> item = asMap(list.get(i), itemPath);
                checkKeys(item, itemPath, "match", "target", "primaryKey", "columns");
                SyncConfig.TableRule rule = new SyncConfig.TableRule();
                rule.match = requiredString(item, "match", itemPath);
                rule.target = stringValue(item, "target", itemPath, null);
                Object primaryKey = item.get("primaryKey");
                if (primaryKey != null) {
                    rule.primaryKey = stringList(primaryKey, itemPath + ".primaryKey");
                }
                Object columns = item.get("columns");
                if (columns != null) {
                    bindColumns(asMap(columns, itemPath + ".columns"), itemPath + ".columns", rule.columns);
                }
                tables.include.add(rule);
            }
        }
        Object excludes = map.get("exclude");
        if (excludes != null) {
            tables.exclude = stringList(excludes, path + ".exclude");
        }
        tables.caseSensitive = boolValue(map, "caseSensitive", path, tables.caseSensitive);
    }

    private void bindColumns(Map<String, Object> map, String path, SyncConfig.ColumnRule columns)
            throws DbSyncerConfigException {
        checkKeys(map, path, "include", "exclude", "rename", "constants", "types");
        Object include = map.get("include");
        if (include != null) {
            columns.include = stringList(include, path + ".include");
        }
        Object exclude = map.get("exclude");
        if (exclude != null) {
            columns.exclude = stringList(exclude, path + ".exclude");
        }
        Object rename = map.get("rename");
        if (rename != null) {
            columns.rename.putAll(stringMap(rename, path + ".rename"));
        }
        Object constants = map.get("constants");
        if (constants != null) {
            columns.constants.putAll(stringMap(constants, path + ".constants"));
        }
        Object types = map.get("types");
        if (types != null) {
            columns.types.putAll(stringMap(types, path + ".types"));
        }
    }

    private void bindReplication(Map<String, Object> map, String path, SyncConfig.ReplicationConfig target)
            throws DbSyncerConfigException {
        checkKeys(map, path, "publication", "slot", "statusIntervalMs");
        if (map.containsKey("publication")) {
            target.publication = stringValue(map, "publication", path, null);
            target.specified.add("publication");
        }
        if (map.containsKey("slot")) {
            target.slot = stringValue(map, "slot", path, null);
            target.specified.add("slot");
        }
        if (map.containsKey("statusIntervalMs")) {
            target.statusIntervalMs = longValue(map, "statusIntervalMs", path, target.statusIntervalMs, 100, 3_600_000);
            target.specified.add("statusIntervalMs");
        }
    }

    // ------------------------------------------------------------------ scalar helpers

    private void checkKeys(Map<String, Object> map, String path, String... allowed)
            throws DbSyncerConfigException {
        Set<String> allowedSet = Set.of(allowed);
        for (String key : map.keySet()) {
            if (!allowedSet.contains(key)) {
                throw new DbSyncerConfigException(path, "unknown key [" + key + "]");
            }
        }
    }

    private static String requireName(String name, String path) throws DbSyncerConfigException {
        if (name == null || name.isBlank()) {
            throw new DbSyncerConfigException(path, "name must not be blank");
        }
        return name.trim();
    }

    private Map<String, Object> asMap(Object value, String path) throws DbSyncerConfigException {
        if (!(value instanceof Map<?, ?> map)) {
            throw new DbSyncerConfigException(path, "expected a map/object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new DbSyncerConfigException(path, "map key must be a string: " + entry.getKey());
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private List<Object> asList(Object value, String path) throws DbSyncerConfigException {
        if (!(value instanceof List<?> list)) {
            throw new DbSyncerConfigException(path, "expected a list");
        }
        return new ArrayList<>(list);
    }

    private String requiredString(Map<String, Object> map, String key, String path)
            throws DbSyncerConfigException {
        String value = stringValue(map, key, path, null);
        if (value == null || value.isBlank()) {
            throw new DbSyncerConfigException(path + "." + key, "is required");
        }
        return value;
    }

    private String stringValue(Map<String, Object> map, String key, String path, String fallback)
            throws DbSyncerConfigException {
        if (!map.containsKey(key) || map.get(key) == null) {
            return fallback;
        }
        return stringScalar(map.get(key), path + "." + key);
    }

    private String stringScalar(Object value, String path) throws DbSyncerConfigException {
        if (value == null) {
            return null;
        }
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        throw new DbSyncerConfigException(path, "expected a scalar string");
    }

    private Map<String, String> stringMap(Object value, String path) throws DbSyncerConfigException {
        Map<String, Object> object = asMap(value, path);
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            result.put(entry.getKey(), stringScalar(entry.getValue(), path + "." + entry.getKey()));
        }
        return result;
    }

    private List<String> stringList(Object value, String path) throws DbSyncerConfigException {
        List<Object> list = asList(value, path);
        List<String> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            result.add(stringScalar(list.get(i), path + "[" + i + "]"));
        }
        return result;
    }

    private boolean boolValue(Map<String, Object> map, String key, String path, boolean fallback)
            throws DbSyncerConfigException {
        if (!map.containsKey(key) || map.get(key) == null) {
            return fallback;
        }
        Object value = map.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            String normalized = string.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized)) {
                return false;
            }
        }
        throw new DbSyncerConfigException(path + "." + key, "expected true or false");
    }

    private int intValue(Map<String, Object> map, String key, String path, int fallback, int min, int max)
            throws DbSyncerConfigException {
        long value = longValue(map, key, path, fallback, min, max);
        return (int) value;
    }

    private long longValue(Map<String, Object> map, String key, String path, long fallback, long min, long max)
            throws DbSyncerConfigException {
        if (!map.containsKey(key) || map.get(key) == null) {
            return fallback;
        }
        Object value = map.get(key);
        long number;
        if (value instanceof Number n) {
            number = n.longValue();
        } else if (value instanceof String string) {
            try {
                number = Long.parseLong(string.trim());
            } catch (NumberFormatException e) {
                throw new DbSyncerConfigException(path + "." + key, "expected an integer", e);
            }
        } else {
            throw new DbSyncerConfigException(path + "." + key, "expected an integer");
        }
        if (number < min || number > max) {
            throw new DbSyncerConfigException(path + "." + key,
                    "must be between " + min + " and " + max + ", got " + number);
        }
        return number;
    }

    private <E> E enumValue(Map<String, Object> map, String key, String path,
                            EnumParser<E> parser, E fallback) throws DbSyncerConfigException {
        if (!map.containsKey(key) || map.get(key) == null) {
            return fallback;
        }
        Object value = map.get(key);
        if (!(value instanceof String string)) {
            throw new DbSyncerConfigException(path + "." + key, "expected a string");
        }
        try {
            E parsed = parser.parse(string);
            return parsed == null ? fallback : parsed;
        } catch (IllegalArgumentException e) {
            throw new DbSyncerConfigException(path + "." + key, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface EnumParser<E> {
        E parse(String value);
    }
}
