package org.slackerdb.common.utils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DBUtil {
    public static Map<String, String> parseJdbcUrl(String jdbcUrl) {
        Map<String, String> result = new HashMap<>();

        // 正则表达式匹配 JDBC URL
        String regex = "((?<user>[^?]+(?=/|$))?/?(?<pass>[^?]+)?@)?jdbc:(?<protocol>[^:]+)://(?<host>[^:/]+)(:(?<port>\\d+))?/?(?<database>[^?]+)?(\\?(?<params>.*))?";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(jdbcUrl);
        if (matcher.matches()) {
            // 提取子协议、主机、端口、数据库名称
            result.put("user", matcher.group("user"));
            result.put("pass", matcher.group("pass"));
            result.put("protocol", matcher.group("protocol"));
            result.put("host", matcher.group("host"));
            result.put("port", matcher.group("port"));  // 可能为 null
            result.put("database", matcher.group("database"));

            // 解析 URL 参数（如用户名和密码）
            String params = matcher.group("params");
            if (params != null) {
                for (String param : params.split("&")) {
                    String[] keyValue = param.split("=");
                    if (keyValue.length == 2) {
                        result.put("param:" + keyValue[0], keyValue[1]);
                    }
                }
            }

        } else {
            throw new IllegalArgumentException("Invalid JDBC URL: " + jdbcUrl);
        }

        return result;
    }

    public static Connection getJdbcConnection(String jdbcUrl) throws SQLException
    {
        Map<String, String> result = parseJdbcUrl(jdbcUrl);

        String jdbcConnectUserName = result.getOrDefault("user", "");
        String jdbcConnectPassword = result.getOrDefault("pass", "");
        Properties jdbcConnectProperties = new Properties();
        StringBuilder jdbcConnectUrl = new StringBuilder();
        jdbcConnectUrl.append("jdbc:")
                .append(result.getOrDefault("protocol", ""))
                .append("://")
                .append(result.getOrDefault("host", ""));
        if (result.get("port") != null)
        {
            jdbcConnectUrl.append(":").append(result.get("port"));
        }
        if (result.get("database") != null)
        {
            jdbcConnectUrl.append("/").append(result.get("database"));
        }
        for (String keyName : result.keySet())
        {
            if (keyName.startsWith("param:"))
            {
                jdbcConnectProperties.put(keyName.replace("param:", ""), result.get(keyName));
            }
        }
        if (!jdbcConnectUserName.isEmpty()) {
            jdbcConnectProperties.put("user", jdbcConnectUserName);
        }
        if (!jdbcConnectPassword.isEmpty()) {
            jdbcConnectProperties.put("password", jdbcConnectPassword);
        }
        return DriverManager.getConnection(jdbcConnectUrl.toString(), jdbcConnectProperties);
    }
}
