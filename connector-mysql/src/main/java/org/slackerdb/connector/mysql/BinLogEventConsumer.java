package org.slackerdb.connector.mysql;

import ch.qos.logback.classic.Logger;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

public class BinLogEventConsumer {
    private Logger logger;

    // 设置日志句柄
    public void setLogger(Logger logger)
    {
        this.logger = logger;
    }

    public void consumeDDLDropEvent(Connection targetDbConnection, RowEventData rowEventData) throws SQLException
    {
        try {
            java.sql.Statement statement = targetDbConnection.createStatement();
            statement.execute(rowEventData.eventSql);
            statement.close();
            logger.trace("[MYSQL-BINLOG] Consumed DDL(Drop) [{}]", rowEventData.eventSql);
        }
        catch (SQLException sqlException)
        {
            // 如果表已经不存在，则不需要报错
            if (DuckdbUtil.isTableExists(targetDbConnection, rowEventData.targetSchemaName, rowEventData.targetTableName))
            {
                throw sqlException;
            }
            logger.trace("[MYSQL-BINLOG] Consumed DDL(Drop) [{}] with ignorable errors {}.",
                    rowEventData.eventSql, sqlException.getMessage());
        }
    }

    public void consumeDDLTruncateEvent(Connection targetDbConnection, RowEventData rowEventData) throws SQLException
    {
        java.sql.Statement statement = targetDbConnection.createStatement();
        statement.execute(rowEventData.eventSql);
        statement.close();
        logger.trace("[MYSQL-BINLOG] Consumed DDL(Truncate) [{}]", rowEventData.eventSql);
    }

    public void consumeDDLCreateEvent(Connection targetDbConnection, RowEventData rowEventData) throws SQLException
    {
        java.sql.Statement statement = targetDbConnection.createStatement();
        statement.execute(rowEventData.eventSql);
        statement.close();
        logger.trace("[MYSQL-BINLOG] Consumed DDL(Create) [{}]", rowEventData.eventSql);
    }

    public void consumeDMLInsertEvent(Connection targetDbConnection, RowEventData rowEventData, TableInfo tableInfo) throws SQLException
    {
        DuckDBAppender duckDBAppender = ((DuckDBConnection) targetDbConnection).createAppender(tableInfo.targetSchemaName, tableInfo.targetTableName);
        for (Serializable[] row : rowEventData.rows)
        {
            duckDBAppender.beginRow();
            for (Serializable serializable : row) {
                if (serializable == null) {
                    duckDBAppender.append((String)null);
                } else {
                    switch (serializable.getClass().getSimpleName()) {
                        case "Integer":
                            duckDBAppender.append((Integer) serializable);
                            break;
                        case "byte[]":
                            duckDBAppender.append(new String((byte[]) serializable));
                            break;
                        case "String":
                            duckDBAppender.append((String) serializable);
                            break;
                        default:
                            throw new RuntimeException("TODO::: duckdb Appender ColumnType: " + serializable.getClass().getSimpleName());
                    }
                }
            }
            duckDBAppender.endRow();
        }
        logger.trace(
                "[MYSQL-BINLOG] Consumed DDL(Insert) [{}]. {} rows affected.",
                tableInfo.targetSchemaName + "." + tableInfo.targetTableName,
                rowEventData.rows.size());
    }

    public void consumeDMLUpdateEvent(Connection targetDbConnection, RowEventData rowEventData, TableInfo tableInfo) throws SQLException
    {
        if (tableInfo.sourceTablePrimaryKeyColumns.isEmpty())
        {
            // 无主键更新, 首先删除原有数据，随后进行插入
            // 删除原有数据
            java.sql.Statement insertStmt1 = targetDbConnection.createStatement();
            insertStmt1.execute(tableInfo.targetTemporaryTableDDL);

            // 数据插入临时表中
            DuckDBAppender duckDBAppender1 = new DuckDBAppender(
                    (DuckDBConnection) targetDbConnection, "", tableInfo.targetTableName + "_$$");
            duckDBAppender1.beginRow();
            for (Map.Entry<Serializable[],Serializable[]> row : rowEventData.updateRows)
            {
                for (int i=1; i<=row.getKey().length; i++ )
                {
                    Serializable serializable = row.getValue()[i-1];
                    if (serializable == null) {
                        duckDBAppender1.append((String)null);
                    } else {
                        switch (serializable.getClass().getSimpleName()) {
                            case "Integer":
                                duckDBAppender1.append((Integer) serializable);
                                break;
                            case "byte[]":
                                duckDBAppender1.append(new String((byte[]) serializable));
                                break;
                            case "String":
                                duckDBAppender1.append((String) serializable);
                                break;
                            default:
                                throw new RuntimeException("TODO::: unknown column type: " + serializable.getClass().getSimpleName());
                        }
                    }
                }
            }
            duckDBAppender1.endRow();
            StringBuilder deleteSQL1 =
                    new StringBuilder("DELETE FROM " + tableInfo.targetSchemaName + "." + tableInfo.targetTableName + " AS _$$1 " +
                            "WHERE EXISTS (" +
                            "SELECT 1 " +
                            "FROM " + tableInfo.targetTableName + "_$$ AS _$$2 " +
                            "WHERE  ");
            for (int i=0; i<tableInfo.sourceTableColumns.size();i++)
            {
                if (i != 0)
                {
                    deleteSQL1.append(" AND ");
                }
                deleteSQL1.append("_$$1.");
                deleteSQL1.append(tableInfo.sourceTableColumns.get(i));
                deleteSQL1.append(" = _$$2.");
                deleteSQL1.append(tableInfo.sourceTableColumns.get(i));
            }
            deleteSQL1.append(")");
            insertStmt1.execute(deleteSQL1.toString());
            insertStmt1.close();

            // 创建一个临时表
            java.sql.Statement insertStmt = targetDbConnection.createStatement();
            insertStmt.execute(tableInfo.targetTemporaryTableDDL);

            // 数据插入临时表中
            DuckDBAppender duckDBAppender = new DuckDBAppender(
                    (DuckDBConnection) targetDbConnection, "", tableInfo.targetTableName + "_$$");
            duckDBAppender.beginRow();
            for (Map.Entry<Serializable[],Serializable[]> row : rowEventData.updateRows)
            {
                for (int i=1; i<=row.getValue().length; i++ )
                {
                    Serializable serializable = row.getValue()[i-1];
                    if (serializable == null) {
                        duckDBAppender.append((String)null);
                    } else {
                        switch (serializable.getClass().getSimpleName()) {
                            case "Integer":
                                duckDBAppender.append((Integer) serializable);
                                break;
                            case "byte[]":
                                duckDBAppender.append(new String((byte[]) serializable));
                                break;
                            case "String":
                                duckDBAppender.append((String) serializable);
                                break;
                            default:
                                throw new RuntimeException("TODO::: unknown column type: " + serializable.getClass().getSimpleName());
                        }
                    }
                }
            }
            duckDBAppender.endRow();
            // 插入新的数据
            String insertSQL = "INSERT INTO " + tableInfo.targetSchemaName + "." + tableInfo.targetTableName +
                    " SELECT * FROM " + tableInfo.targetTableName + "_$$";
            insertStmt.execute(insertSQL);
            insertStmt.close();
            logger.trace(
                    "[MYSQL-BINLOG] Consumed DDL(Update) (full column) [{}]. {} rows affected.",
                    rowEventData.eventSql, rowEventData.updateRows.size());
        }
        else
        {
            // 有主键更新，用INSERT OR REPLACE来实现
            // 创建一个临时表
            java.sql.Statement insertStmt = targetDbConnection.createStatement();
            insertStmt.execute(tableInfo.targetTemporaryTableDDL);

            // 数据插入临时表中
            DuckDBAppender duckDBAppender = new DuckDBAppender(
                    (DuckDBConnection) targetDbConnection, "", tableInfo.targetTableName + "_$$");
            for (Map.Entry<Serializable[],Serializable[]> row : rowEventData.updateRows)
            {
                duckDBAppender.beginRow();
                for (int i=1; i<=row.getValue().length; i++ )
                {
                    Serializable serializable = row.getValue()[i-1];
                    if (serializable == null) {
                        duckDBAppender.append((String)null);
                    } else {
                        switch (serializable.getClass().getSimpleName()) {
                            case "Integer":
                                duckDBAppender.append((Integer) serializable);
                                break;
                            case "byte[]":
                                duckDBAppender.append(new String((byte[]) serializable));
                                break;
                            case "String":
                                duckDBAppender.append((String) serializable);
                                break;
                            default:
                                throw new RuntimeException("TODO::: unknown column type: " + serializable.getClass().getSimpleName());
                        }
                    }
                }
                duckDBAppender.endRow();
            }
            // 插入新的数据
            String insertSQL = "INSERT OR REPLACE INTO " + tableInfo.targetSchemaName + "." + tableInfo.targetTableName +
                    " SELECT * FROM " + tableInfo.targetTableName + "_$$";
            insertStmt.execute(insertSQL);
            insertStmt.close();

            logger.trace(
                    "[MYSQL-BINLOG] Consumed DDL(Update) (primary column) [{}]. {} rows affected.",
                    rowEventData.eventSql, rowEventData.updateRows.size());
        }
        logger.trace(
                "[MYSQL-BINLOG] Consumed DDL(Update) [{}]. {} rows affected.",
                tableInfo.targetSchemaName + "." + tableInfo.targetTableName,
                rowEventData.rows.size());
    }

    public void consumeDMLDeleteEvent(Connection targetDbConnection, RowEventData rowEventData, TableInfo tableInfo) throws SQLException
    {
        // 创建一个临时表
        java.sql.Statement deleteStmt = targetDbConnection.createStatement();
        deleteStmt.execute(tableInfo.targetTemporaryTableDDL);

        // 数据插入临时表中
        DuckDBAppender duckDBAppender = new DuckDBAppender(
                (DuckDBConnection) targetDbConnection, "", tableInfo.targetTableName + "_$$");
        for (Serializable[] row : rowEventData.rows)
        {
            duckDBAppender.beginRow();
            for (int i=1; i<=row.length; i++ )
            {
                Serializable serializable = row[i-1];
                if (serializable == null) {
                    duckDBAppender.append((String)null);
                } else {
                    switch (serializable.getClass().getSimpleName()) {
                        case "Integer":
                            duckDBAppender.append((Integer) serializable);
                            break;
                        case "byte[]":
                            duckDBAppender.append(new String((byte[]) serializable));
                            break;
                        case "String":
                            duckDBAppender.append((String) serializable);
                            break;
                        default:
                            throw new RuntimeException("TODO::: unknown column type: " + serializable.getClass().getSimpleName());
                    }
                }
            }
            duckDBAppender.endRow();
        }

        // 根据临时表中的数据进行字段删除
        StringBuilder deleteSQL =
                new StringBuilder("DELETE FROM " + tableInfo.targetSchemaName + "." + tableInfo.targetTableName + " AS _$$1 " +
                        "WHERE EXISTS (" +
                        "SELECT 1 " +
                        "FROM " + tableInfo.targetTableName + "_$$ AS _$$2 " +
                        "WHERE  ");
        for (int i=0; i<tableInfo.sourceTablePrimaryKeyColumns.size();i++)
        {
            if (i != 0)
            {
                deleteSQL.append(" AND ");
            }
            deleteSQL.append("_$$1.");
            deleteSQL.append(tableInfo.sourceTablePrimaryKeyColumns.get(i));
            deleteSQL.append(" = _$$2.");
            deleteSQL.append(tableInfo.sourceTablePrimaryKeyColumns.get(i));
        }
        deleteSQL.append(")");
        deleteStmt.execute(deleteSQL.toString());
        deleteStmt.close();

        logger.trace(
                "[MYSQL-BINLOG] Consumed DDL(Delete) [{}]. {} rows affected.",
                tableInfo.targetSchemaName + "." + tableInfo.targetTableName, rowEventData.rows.size());
    }
}
