package org.slackerdb.dbsyncer.config;

import org.slackerdb.dbsyncer.DbSyncerConfigException;
import org.slackerdb.dbsyncer.util.TablePattern;
import org.slackerdb.dbsyncer.util.ReplicationNames;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 跨对象校验：唯一性、引用、命名、类型与策略冲突。
 */
public final class ConfigValidator {

    private static final Pattern JOB_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern PG_OBJECT = Pattern.compile("[a-z0-9_]{1,63}");
    private static final Pattern TARGET_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,127}");

    private ConfigValidator() {
    }

    public static void validate(SyncConfig config) throws DbSyncerConfigException {
        if (config.version != 1) {
            throw new DbSyncerConfigException("$.version", "only version 1 is supported, got " + config.version);
        }
        if (config.targets.isEmpty()) {
            throw new DbSyncerConfigException("$.targets", "at least one target is required");
        }
        if (config.jobs.isEmpty()) {
            throw new DbSyncerConfigException("$.jobs", "at least one job is required");
        }

        Set<String> jobNames = new HashSet<>();
        for (int i = 0; i < config.jobs.size(); i++) {
            SyncConfig.JobConfig job = config.jobs.get(i);
            String path = "$.jobs[" + i + "]";
            if (!JOB_NAME.matcher(job.name).matches()) {
                throw new DbSyncerConfigException(path + ".name",
                        "must match [A-Za-z0-9_-]{1,64}: " + job.name);
            }
            if (!jobNames.add(job.name)) {
                throw new DbSyncerConfigException(path + ".name", "duplicate job name [" + job.name + "]");
            }
            if (!config.connections.containsKey(job.source)) {
                throw new DbSyncerConfigException(path + ".source", "unknown connection [" + job.source + "]");
            }
            if (!config.targets.containsKey(job.target)) {
                throw new DbSyncerConfigException(path + ".target", "unknown target [" + job.target + "]");
            }
            SyncConfig.ConnectionConfig connection = config.connections.get(job.source);
            SyncConfig.TargetConfig target = config.targets.get(job.target);
            if (connection.type == SyncConfig.SourceType.MYSQL
                    && !connection.replication.specified.isEmpty()) {
                throw new DbSyncerConfigException(path + ".source",
                        "replication options only apply to PostgreSQL connections");
            }
            if (target.schema == null || target.schema.isBlank()) {
                throw new DbSyncerConfigException(path + ".target",
                        "target [" + job.target + "] must define schema");
            }
            if (!TARGET_IDENT.matcher(target.schema).matches()) {
                throw new DbSyncerConfigException("$.targets." + job.target + ".schema",
                        "invalid DuckDB identifier: " + target.schema);
            }
            if (job.tables == null || job.tables.include.isEmpty()) {
                throw new DbSyncerConfigException(path + ".tables.include", "at least one include rule is required");
            }
            if (connection.type == SyncConfig.SourceType.POSTGRES) {
                validatePgJob(job, path);
            } else {
                validateMySqlJob(job, path);
            }
            validateTableRules(job, path);
        }

        validateTargetTableOwnership(config);
        validateReplicationNames(config);
        validateConnectionReplication(config);
    }

    /** 连接级 replication 默认值同样是对外契约，必须一起校验。 */
    private static void validateConnectionReplication(SyncConfig config) throws DbSyncerConfigException {
        for (var entry : config.connections.entrySet()) {
            SyncConfig.ReplicationConfig replication = entry.getValue().replication;
            String path = "$.connections." + entry.getKey() + ".replication";
            validatePgObject(replication.publication, path + ".publication");
            validatePgObject(replication.slot, path + ".slot");
        }
    }

    /**
     * publication / 复制槽名按作业名派生，派生时会归一化大小写和标点，
     * 因此 {@code a-b} 与 {@code a_b} 会抢同一个槽。这里按"同一条连接"判重，
     * 显式写了名字的也要和派生名一起比较。
     */
    private static void validateReplicationNames(SyncConfig config) throws DbSyncerConfigException {
        Set<String> slots = new HashSet<>();
        Set<String> publications = new HashSet<>();
        for (int i = 0; i < config.jobs.size(); i++) {
            SyncConfig.JobConfig job = config.jobs.get(i);
            SyncConfig.ConnectionConfig connection = config.connections.get(job.source);
            if (connection.type != SyncConfig.SourceType.POSTGRES) {
                continue;
            }
            String path = "$.jobs[" + i + "]";
            String publication = firstNonBlank(job.replication.publication,
                    connection.replication.publication, ReplicationNames.publication(job.name));
            String slot = firstNonBlank(job.replication.slot,
                    connection.replication.slot, ReplicationNames.slot(job.name));
            String scope = connection.url;
            if (!publications.add(scope + "|" + publication)) {
                throw new DbSyncerConfigException(path + ".replication.publication",
                        "another job on connection [" + job.source + "] already uses publication ["
                                + publication + "]; set a distinct job name or replication.publication");
            }
            if (!slots.add(scope + "|" + slot)) {
                throw new DbSyncerConfigException(path + ".replication.slot",
                        "another job on connection [" + job.source + "] already uses replication slot ["
                                + slot + "]; set a distinct job name or replication.slot");
            }
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static void validatePgJob(SyncConfig.JobConfig job, String path) throws DbSyncerConfigException {
        validatePgObject(job.replication.publication, path + ".replication.publication");
        validatePgObject(job.replication.slot, path + ".replication.slot");
    }

    private static void validateMySqlJob(SyncConfig.JobConfig job, String path) throws DbSyncerConfigException {
        if (!job.replication.specified.isEmpty()) {
            throw new DbSyncerConfigException(path + ".replication",
                    "replication options only apply to PostgreSQL sources");
        }
    }

    private static void validatePgObject(String value, String path) throws DbSyncerConfigException {
        if (value != null && !value.isBlank() && !PG_OBJECT.matcher(value).matches()) {
            throw new DbSyncerConfigException(path, "must match [a-z0-9_]{1,63}: " + value);
        }
    }

    private static void validateTableRules(SyncConfig.JobConfig job, String path)
            throws DbSyncerConfigException {
        for (int i = 0; i < job.tables.include.size(); i++) {
            SyncConfig.TableRule rule = job.tables.include.get(i);
            String rulePath = path + ".tables.include[" + i + "]";
            if (rule.match == null || rule.match.isBlank()) {
                throw new DbSyncerConfigException(rulePath + ".match", "match is required");
            }
            try {
                TablePattern.compile(rule.match, job.tables.caseSensitive);
            } catch (IllegalArgumentException e) {
                throw new DbSyncerConfigException(rulePath + ".match",
                        "invalid table pattern: " + e.getMessage());
            }
            if (rule.target != null && !rule.target.isBlank()
                    && !rule.target.contains("{") && !rule.target.contains("$")
                    && !TARGET_IDENT.matcher(rule.target).matches()) {
                throw new DbSyncerConfigException(rulePath + ".target",
                        "invalid DuckDB identifier: " + rule.target);
            }
            for (String column : rule.primaryKey) {
                if (column == null || column.isBlank()) {
                    throw new DbSyncerConfigException(rulePath + ".primaryKey", "column name must not be blank");
                }
            }
            for (String column : rule.columns.exclude) {
                if (rule.primaryKey.stream().anyMatch(pk -> pk.equalsIgnoreCase(column))) {
                    throw new DbSyncerConfigException(rulePath + ".columns.exclude",
                            "primary key column [" + column + "] cannot be excluded");
                }
            }
            for (String type : rule.columns.types.values()) {
                if (type == null || type.isBlank() || !type.matches("[A-Za-z0-9_(),\\s\\[\\]<>'\"]+")) {
                    throw new DbSyncerConfigException(rulePath + ".columns.types",
                            "invalid DuckDB type override: " + type);
                }
            }
        }
        for (String exclude : job.tables.exclude) {
            if (exclude == null || exclude.isBlank()) {
                throw new DbSyncerConfigException(path + ".tables.exclude", "exclude pattern must not be blank");
            }
            try {
                TablePattern.compile(exclude, job.tables.caseSensitive);
            } catch (IllegalArgumentException e) {
                throw new DbSyncerConfigException(path + ".tables.exclude",
                        "invalid exclude pattern: " + e.getMessage());
            }
        }
    }

    private static void validateTargetTableOwnership(SyncConfig config) throws DbSyncerConfigException {
        Set<String> claimed = new HashSet<>();
        for (int i = 0; i < config.jobs.size(); i++) {
            SyncConfig.JobConfig job = config.jobs.get(i);
            SyncConfig.TargetConfig target = config.targets.get(job.target);
            for (int j = 0; j < job.tables.include.size(); j++) {
                SyncConfig.TableRule rule = job.tables.include.get(j);
                if (rule.target == null || rule.target.isBlank()
                        || rule.target.contains("{") || rule.target.contains("$")) {
                    continue;
                }
                String key = target.schema.toLowerCase(Locale.ROOT) + "." + rule.target.toLowerCase(Locale.ROOT);
                if (!claimed.add(key)) {
                    throw new DbSyncerConfigException("$.jobs[" + i + "].tables.include[" + j + "].target",
                            "target table [" + key + "] is already claimed by another job");
                }
            }
        }
    }
}
