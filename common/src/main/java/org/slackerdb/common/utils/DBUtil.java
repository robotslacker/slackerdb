package org.slackerdb.common.utils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDBC数据库连接工具类。
 * 提供JDBC URL解析和数据库连接创建功能。
 *
 * <p>该类主要用于解析标准的JDBC URL格式，提取连接参数，并创建相应的数据库连接。</p>
 *
 * <p>支持的JDBC URL格式示例：</p>
 * <ul>
 *   <li>jdbc:mysql://localhost:3306/dbname?user=root&password=123456</li>
 *   <li>jdbc:postgresql://host/dbname</li>
 *   <li>jdbc:oracle:thin:@host:1521:sid</li>
 * </ul>
 *
 * <p>注意：该工具类依赖于JDBC驱动，使用前需确保相应的JDBC驱动已加载。</p>
 */
public class DBUtil {
    /**
     * 解析JDBC URL字符串，提取各个组成部分。
     *
     * <p>该方法使用正则表达式解析JDBC URL，提取以下信息：</p>
     * <ul>
     *   <li>user - 用户名（如果URL中包含）</li>
     *   <li>pass - 密码（如果URL中包含）</li>
     *   <li>protocol - JDBC子协议（如mysql、postgresql等）</li>
     *   <li>host - 数据库服务器主机名或IP地址</li>
     *   <li>port - 数据库服务器端口号（可选）</li>
     *   <li>database - 数据库名称（可选）</li>
     *   <li>param:* - URL查询参数（如user、password等，以"param:"为前缀）</li>
     * </ul>
     *
     * <p>正则表达式解析的URL格式：<br>
     * {@code [user[/pass]@]jdbc:protocol://host[:port][/database][?params]}<br>
     * 其中方括号表示可选部分。</p>
     *
     * @param jdbcUrl 要解析的JDBC URL字符串
     * @return 包含URL各组成部分的Map，键为组件名称，值为对应的字符串值（可能为null）
     * @throws IllegalArgumentException 如果JDBC URL格式无效
     */
    public static Map<String, String> parseJdbcUrl(String jdbcUrl) {
        Map<String, String> result = new HashMap<>();

        // 正则表达式匹配 JDBC URL
        // 分组说明：
        //   (?<user>[^?]+(?=/|$))?/?(?<pass>[^?]+)?@ - 可选的用户名/密码认证部分
        //   jdbc:(?<protocol>[^:]+):// - JDBC协议部分（如mysql、postgresql）
        //   (?<host>[^:/]+) - 主机名或IP地址
        //   (:(?<port>\\d+))? - 可选的端口号
        //   /?(?<database>[^?]+)? - 可选的数据库名称
        //   (\\?(?<params>.*))? - 可选的查询参数
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

    /**
     * 根据JDBC URL创建数据库连接。
     *
     * <p>该方法首先调用{@link #parseJdbcUrl(String)}解析URL，然后构建连接字符串和连接属性，
     * 最后通过{@link DriverManager#getConnection(String, Properties)}创建连接。</p>
     *
     * <p>连接属性包括：</p>
     * <ul>
     *   <li>从URL中提取的用户名和密码（如果存在）</li>
     *   <li>URL查询参数中的所有参数</li>
     * </ul>
     *
     * <p>注意：该方法不会加载JDBC驱动，调用前需确保相应的JDBC驱动已通过{@link Class#forName(String)}
     * 或其他方式加载。</p>
     *
     * @param jdbcUrl JDBC URL字符串
     * @return 数据库连接对象
     * @throws SQLException 如果数据库连接失败或URL格式无效
     * @see #parseJdbcUrl(String)
     */
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
