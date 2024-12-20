package org.slackerdb.connector.mysql;

import java.util.ArrayList;
import java.util.List;

public class TableSyncerGroup {
    private String syncerGroupName;
    public TableSyncerGroup(String groupName)
    {
        this.syncerGroupName = groupName;
    }
    public List<TableSyncer> tableSyncers = new ArrayList<>();
    public void addSyncerTask(
            String taskName,
            String sourceTableName,
            String targetTableName,
            String syncFilter,
            int    eventQueueSize)
    {
        TableSyncer tableSyncer = new TableSyncer(taskName, eventQueueSize);
        tableSyncer.sourceTableName = sourceTableName;
        tableSyncer.targetTableName = targetTableName;
        tableSyncer.syncFilter = syncFilter;
    }

    // 开始同步
    public void start()
    {
        // 从配置信息中获取上次同步的位置信息，看是否需要重新全量同步

    }
}
