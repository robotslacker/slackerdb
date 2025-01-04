package org.slackerdb.connector.mysql;

import com.github.shyiko.mysql.binlog.event.Event;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class TableSyncer {
    public String sourceTableName;
    public String targetTableName;
    public String taskName;

    // 需要同步的目标数据库连接
    public List<TableSyncerWorker> tableSyncerWorkers = new ArrayList<>();
    // 同步的类型. APPEND_ONLY, MIX
    public String syncType;
    // 过滤条件
    public String syncFilter;

    // 事件队列，有最大容量，如果超过容量，则阻塞
    BlockingQueue<Event> eventQueue;

    // 过滤来源表，看是否属于这个同步任务来完成
    public boolean belongThisGroup(String sourceTableName)
    {
        return false;
    }

    // 构造函数
    public TableSyncer(String taskName, int eventQueueSize)
    {
        this.taskName = taskName;
        this.eventQueue = new ArrayBlockingQueue<>(eventQueueSize);
    }

    // 添加一个同步事件
    public void putEvent(Event event)
    {
        try {
            this.eventQueue.put(event);
        }
        catch (InterruptedException ignored)
        {
            Thread.currentThread().interrupt();
        }
    }
}
