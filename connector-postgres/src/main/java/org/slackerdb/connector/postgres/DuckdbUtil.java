package org.slackerdb.connector.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class DuckdbUtil {
    public static boolean isTableExists(
            Connection connection,
            String schemaName,
            String tableName) throws SQLException
    {
        boolean ret = false;
        String sql = "Select 1 From duckdb_tables() Where schema_name = ? and table_name = ? and database_name = current_database()";
        PreparedStatement preparedStatement = connection.prepareStatement(sql);
//        preparedStatement.setString(1, databaseName);
        preparedStatement.setString(1, schemaName);
        preparedStatement.setString(2, tableName);

        ResultSet rs = preparedStatement.executeQuery();
        if (rs.next())
        {
            ret = true;
        }
        rs.close();
        preparedStatement.close();
        return ret;
    }

    public static boolean isSchemaExists(
            Connection connection,
            String schemaName) throws SQLException
    {
        boolean ret = false;
        String sql = "Select 1 From duckdb_schemas() Where schema_name = ? and database_name = current_database()";
        PreparedStatement preparedStatement = connection.prepareStatement(sql);
        preparedStatement.setString(1, schemaName);

        ResultSet rs = preparedStatement.executeQuery();
        if (rs.next())
        {
            ret = true;
        }
        rs.close();
        preparedStatement.close();
        return ret;
    }

    public static void peacefulCommit(Connection connection) throws SQLException
    {
        try
        {
            connection.commit();
        }
        catch (SQLException e)
        {
            if (!e.getMessage().contains("no transaction is active"))
            {
                throw e;
            }
        }
    }
}
