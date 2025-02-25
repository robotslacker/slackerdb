package org.slackerdb.connector.postgres;

import org.slackerdb.common.utils.DBUtil;
import org.slackerdb.common.utils.Sleeper;

import java.sql.Connection;
import java.sql.SQLException;

public class WALSyncWorker extends Thread{
    private ConnectorTask connectorTask;
    private Connection sourceConnection;
    public WALSyncWorker(ConnectorTask connectorTask)
    {
        this.connectorTask = connectorTask;
    }

    @Override
    public void run()
    {
        // 连接源数据库
        try {
            sourceConnection = DBUtil.getJdbcConnection(this.connectorTask.getConnector().getConnectorURL());
        }
        catch (SQLException sqlException)
        {
            throw new RuntimeException(sqlException);
        }

        System.out.println("WALSyncWorker");
        try {
            Sleeper.sleep(30000);
        }
        catch (InterruptedException ignored) {}
    }
}
