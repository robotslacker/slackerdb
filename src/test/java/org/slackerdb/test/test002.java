package org.slackerdb.test;

import org.slackerdb.utils.Sleeper;

import java.sql.*;

public class test002 {

    public static void main(String[] args) throws SQLException, ClassNotFoundException {
        Connection conn = DriverManager.getConnection("jdbc:duckdb:");

        String sql = "CREATE TABLE variousDataTypeSelect ( " +
                "id INTEGER PRIMARY KEY," +
                "name VARCHAR(100)," +
                "birth_date DATE," +
                "is_active BOOLEAN," +
                "salary DECIMAL(10, 2)," +
                "binary_data BLOB," +
                "float_value FLOAT," +
                "double_value DOUBLE," +
                "numeric_value NUMERIC," +
                "timestamp_value TIMESTAMP" +
                ")";
        conn.createStatement().execute(sql);

        PreparedStatement preparedStatement = conn.prepareStatement(sql);

        ResultSetMetaData resultSetMetaData = preparedStatement.getMetaData();
        for (int i = 1; i <= resultSetMetaData.getColumnCount(); i++) {
            System.out.println(resultSetMetaData.getColumnName(i));
            System.out.println(resultSetMetaData.getColumnTypeName(i));
            System.out.println(resultSetMetaData.getScale(i));
        }
    }
}

