package org.slackerdb.connector.mysql;

import ch.qos.logback.classic.Logger;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class TableFullSyncScheduler extends Thread {
    public final Map<String, TableFullSyncWorker> tableFullSyncWorkers = new HashMap<>();
    private Logger logger;
    public void setLogger(Logger logger)
    {
        this.logger = logger;
    }

    @SuppressWarnings("BusyWait")
    @Override
    public void run()
    {
        while (!isInterrupted()) {
            for (Map.Entry<String, TableFullSyncWorker> tableFullSyncWorkerMap : tableFullSyncWorkers.entrySet()) {
                TableFullSyncWorker tableFullSyncWorker = tableFullSyncWorkerMap.getValue();
                if (tableFullSyncWorker.syncCompleted) {
                    try {
                        String tableName = tableFullSyncWorkerMap.getKey();
                        tableFullSyncWorker.consumeEventQueue();
                        tableFullSyncWorker.targetDbConnection.commit();
                        tableFullSyncWorker.tableInfo.fullSyncStatus = false;
                        tableFullSyncWorker.saveCheckPoint();
                        tableFullSyncWorker.targetDbConnection.close();
                        tableFullSyncWorkers.remove(tableName);
                    } catch (SQLException sqlException) {
                        logger.error("[MYSQL-BINLOG] Consume Event Queue failed.", sqlException);
                    }
                }
            }
            try
            {
                Thread.sleep(1000L);
            }
            catch (InterruptedException ignored)
            {
                break;
            }
        }
    }

    public void addWorker(String tableName, TableFullSyncWorker tableFullSyncWorker)
    {
        tableFullSyncWorker.start();
        tableFullSyncWorkers.put(tableName, tableFullSyncWorker);
    }
}
