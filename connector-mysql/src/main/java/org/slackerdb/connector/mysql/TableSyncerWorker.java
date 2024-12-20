package org.slackerdb.connector.mysql;

import java.time.LocalDateTime;

public class TableSyncerWorker extends Thread{
    // 需要同步的目标表名
    public String targetTableName;
    // 已经同步的行数
    public long affectedRows;
    // 同步开始时间
    public LocalDateTime startTime;
    // 最后一次同步的时间
    public LocalDateTime lastSyncTime;

    @Override
    public void run()
    {
        System.out.println("OK");
    }

}
