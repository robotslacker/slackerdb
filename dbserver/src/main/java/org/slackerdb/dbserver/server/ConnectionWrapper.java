package org.slackerdb.dbserver.server;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.sql.SQLException;
import java.sql.DriverManager;
import java.util.Properties;

public class ConnectionWrapper implements InvocationHandler {
    private final Connection originalConnection;
    private final Map<String, String> clientInfoMap = new HashMap<>();

    public ConnectionWrapper(Connection originalConnection) {
        this.originalConnection = originalConnection;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();

        // Custom handling for setClientInfo
        if ("setClientInfo".equals(methodName)) {
            if (args.length == 2 && args[0] instanceof String && args[1] instanceof String) {
                clientInfoMap.put((String) args[0], (String) args[1]);
                return null; // Indicate successful execution
            }
            else if (args.length == 1 && args[0] instanceof Properties properties)
            {
                for (String key : properties.stringPropertyNames()) {
                    clientInfoMap.put(key, properties.getProperty(key));
                }
                return null;
            }
        }

        // Custom handling for getClientInfo
        if ("getClientInfo".equals(methodName)) {
            if (args == null || args.length == 0) {
                Properties properties = new Properties();
                properties.putAll(clientInfoMap);
                return properties;
            } else if (args.length == 1 && args[0] instanceof String) {
                return clientInfoMap.get(args[0]);
            }
        }

        // For other methods, delegate to the original connection
        return method.invoke(originalConnection, args);
    }

    public static Connection createProxy(Connection originalConnection) {
        return (Connection) Proxy.newProxyInstance(
                originalConnection.getClass().getClassLoader(),
                new Class<?>[]{Connection.class},
                new ConnectionWrapper(originalConnection)
        );
    }

    public static void main(String[] args) throws SQLException {
        Connection originalConnection = DriverManager.getConnection("jdbc:duckdb::memory:");
        Connection wrappedConnection = ConnectionWrapper.createProxy(originalConnection);

        wrappedConnection.setClientInfo("key1", "value1");
        wrappedConnection.setClientInfo("key2", "value2");

        System.out.println(wrappedConnection.getClientInfo("key1")); // 输出: value1
        System.out.println(wrappedConnection.getClientInfo());       // 输出: {key1=value1, key2=value2}

        wrappedConnection.close();
    }

}
