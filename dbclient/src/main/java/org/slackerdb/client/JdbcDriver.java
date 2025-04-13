package org.slackerdb.client;

import org.apache.calcite.adapter.java.ReflectiveSchema;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.Frameworks.ConfigBuilder;

import java.sql.*;
import java.util.Properties;

public class JdbcDriver extends org.apache.calcite.jdbc.Driver {

    static {
        // 注册 Driver
        try {
            DriverManager.registerDriver(new JdbcDriver());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to register driver", e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }

        Properties props = new Properties();
        props.setProperty("serialization", "PROTOBUF");

        // 简化：这里我们把连接转发给真实的 PostgreSQL 数据库
        // 实际中你可以改成转发到 Avatica 的服务端等
        String pgUrl = "jdbc:postgresql://192.168.40.129:5432/jtls_db";
        Properties pgProps = new Properties();
        pgProps.put("user", "postgres");
        pgProps.put("password", "postgres");

        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        return DriverManager.getConnection(pgUrl, pgProps);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith("jdbc:slackerdb:");
    }

    public static class SampleData {
        public final Employee[] employees = {
                new Employee(100, "Alice"),
                new Employee(200, "Bob")
        };
    }

    public static class Employee {
        public final int id;
        public final String name;

        public Employee(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
