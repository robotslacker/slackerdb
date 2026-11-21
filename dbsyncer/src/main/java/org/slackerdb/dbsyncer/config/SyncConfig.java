package org.slackerdb.dbsyncer.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * dbsyncer.yml 的完整模型。
 *
 * <p>这里刻意使用 public 字段 + 手写绑定器：YAML 的键就是对外契约，绑定器可以做到
 * "未知键报错 + 带路径的错误信息"，比反射/注解绑定更可控。</p>
 */
public final class SyncConfig {

    /** 当前只接受 1。 */
    public int version = 1;

    public Defaults defaults = new Defaults();

    public Map<String, ConnectionConfig> connections = new LinkedHashMap<>();

    public Map<String, TargetConfig> targets = new LinkedHashMap<>();

    public List<JobConfig> jobs = new ArrayList<>();

    /** 全局默认。 */
    public static final class Defaults {
        public BatchConfig batch = new BatchConfig();
        public ReconnectConfig reconnect = new ReconnectConfig();
        public SnapshotConfig snapshot = new SnapshotConfig();
    }

    public static final class BatchConfig {
        public int maxRows = 2000;
        public long flushIntervalMs = 10_000;
        public int maxBufferedEvents = 100_000;
        public long maxBufferedBytes = 64L * 1024 * 1024;
        /** 单批写入目标的最大尝试次数；用尽后暂停作业且不推进检查点。 */
        public int maxAttempts = 3;
        /** 两次尝试之间的间隔。 */
        public long retryIntervalMs = 1000;
    }

    public static final class ReconnectConfig {
        public int maxAttempts = 10;
        public long intervalMs = 3000;
    }

    public static final class SnapshotConfig {
        public SnapshotMode mode = SnapshotMode.REPLACE;
        public int fetchSize = 10_000;

        public SnapshotConfig copy() {
            SnapshotConfig copy = new SnapshotConfig();
            copy.mode = mode;
            copy.fetchSize = fetchSize;
            return copy;
        }
    }

    public static final class TargetConfig {
        /** 必填、无默认值。 */
        public String schema;
    }

    public static final class ConnectionConfig {
        public SourceType type;
        public String url;
        public String username;
        public String password;
        public Map<String, String> properties = new LinkedHashMap<>();
        public ReplicationConfig replication = new ReplicationConfig();
    }

    /** PG 复制参数；connection 级给默认，job 级覆盖 publication/slot。 */
    public static final class ReplicationConfig {
        public final java.util.Set<String> specified = new java.util.LinkedHashSet<>();
        public String publication;
        public String slot;
        public long statusIntervalMs = 10_000;
    }

    public static final class TablesConfig {
        public List<TableRule> include = new ArrayList<>();
        public List<String> exclude = new ArrayList<>();
        public boolean caseSensitive = false;
    }

    public static final class TableRule {
        public String match;
        public String target;
        public List<String> primaryKey = new ArrayList<>();
        public ColumnRule columns = new ColumnRule();
    }

    public static final class ColumnRule {
        public List<String> include = new ArrayList<>();
        public List<String> exclude = new ArrayList<>();
        public Map<String, String> rename = new LinkedHashMap<>();
        public Map<String, String> constants = new LinkedHashMap<>();
        public Map<String, String> types = new LinkedHashMap<>();
    }

    public static final class JobConfig {
        public String name;
        public String description;
        public String source;
        public String target;
        public JobMode mode = JobMode.SNAPSHOT_CDC;
        public boolean autoStart = false;
        public ReplicationConfig replication = new ReplicationConfig();
        public TablesConfig tables = new TablesConfig();
        /** null = 继承 defaults。 */
        public SnapshotConfig snapshot;
    }

    // ------------------------------------------------------------------ enums

    public enum SourceType {
        POSTGRES, MYSQL;

        public static SourceType from(String value) {
            if (value == null) {
                throw new IllegalArgumentException("missing source type");
            }
            return switch (value.trim().toLowerCase()) {
                case "postgres", "postgresql" -> POSTGRES;
                case "mysql" -> MYSQL;
                default -> throw new IllegalArgumentException("unsupported source type: " + value);
            };
        }

    }

    public enum JobMode {
        SNAPSHOT_CDC("snapshot+cdc"), SNAPSHOT("snapshot"), CDC("cdc");

        public final String yaml;

        JobMode(String yaml) {
            this.yaml = yaml;
        }

        public static JobMode from(String value) {
            if (value == null) {
                return SNAPSHOT_CDC;
            }
            return switch (value.trim().toLowerCase()) {
                case "snapshot+cdc" -> SNAPSHOT_CDC;
                case "snapshot" -> SNAPSHOT;
                case "cdc" -> CDC;
                default -> throw new IllegalArgumentException("mode must be snapshot+cdc|snapshot|cdc: " + value);
            };
        }

        public boolean includesSnapshot() {
            return this != CDC;
        }

        public boolean includesCdc() {
            return this != SNAPSHOT;
        }
    }

    public enum SnapshotMode {
        REPLACE, APPEND, SKIP_IF_EXISTS;

        public static SnapshotMode from(String value) {
            if (value == null) {
                return REPLACE;
            }
            return switch (value.trim().toLowerCase()) {
                case "replace" -> REPLACE;
                case "append" -> APPEND;
                case "skipifexists", "skip-if-exists" -> SKIP_IF_EXISTS;
                default -> throw new IllegalArgumentException(
                        "snapshot.mode must be replace|append|skipIfExists: " + value);
            };
        }
    }


}
