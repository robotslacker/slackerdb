package org.slackerdb.dbserver.server;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;

public class ConnectionWrapper {
    private boolean poolConnection = false;

    public void setPoolConnection(boolean poolConnection) {
        this.poolConnection = poolConnection;
    }

    public Connection wrap(Connection originalConnection) {
        return (Connection) Proxy.newProxyInstance(
                originalConnection.getClass().getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        System.out.println("Custom behavior before close");
                        if (poolConnection) {
                            System.out.println("Connection is in pool");
                            return method.invoke(originalConnection, args);
                        } else {
                            System.out.println("Connection close skipped");
                            return null;
                        }
                    }
                    // 其他方法原样调用
                    return method.invoke(originalConnection, args);
                }
        );
    }

    public static void main(String[] args) throws Exception {
        Connection realConnection = DriverManager.getConnection(
                "jdbc:duckdb::memory:", "", "");
        ConnectionWrapper wrapper = new ConnectionWrapper(); // 默认禁止关闭
        Connection wrappedConnection = wrapper.wrap(realConnection);

        wrappedConnection.close(); // 输出：Connection close skipped

        wrapper.setPoolConnection(false); // 动态允许关闭
        wrappedConnection.close(); // 输出：Connection is allowed to close
    }
}
