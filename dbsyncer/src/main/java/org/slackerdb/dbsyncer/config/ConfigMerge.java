package org.slackerdb.dbsyncer.config;

/** connection 级默认 + job 级显式覆盖。 */
public final class ConfigMerge {

    private ConfigMerge() {
    }

    public static SyncConfig.ReplicationConfig replication(SyncConfig.ConnectionConfig connection,
                                                           SyncConfig.JobConfig job) {
        SyncConfig.ReplicationConfig result = new SyncConfig.ReplicationConfig();
        SyncConfig.ReplicationConfig base = connection.replication;
        result.publication = base.publication;
        result.slot = base.slot;
        result.statusIntervalMs = base.statusIntervalMs;
        result.specified.addAll(base.specified);

        SyncConfig.ReplicationConfig override = job.replication;
        if (override.specified.contains("publication")) {
            result.publication = override.publication;
        }
        if (override.specified.contains("slot")) {
            result.slot = override.slot;
        }
        if (override.specified.contains("statusIntervalMs")) {
            result.statusIntervalMs = override.statusIntervalMs;
        }
        return result;
    }

}
