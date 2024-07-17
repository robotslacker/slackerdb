package org.slackerdb.server;

import org.slackerdb.exceptions.ServerException;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;
import ch.qos.logback.classic.Level;

public class ServerConfiguration extends Throwable {
    private static String   data;
    private static String   log;
    private static Level    log_level;
    private static int      port;
    private static String   bind;
    private static String   currentSchema;
    private static String   memory_limit;
    private static int      threads;
    private static String   access_mode;
    private static int      max_connections;
    private static int      clientTimeout;
    private static boolean  loaded = false;

    public static boolean isLoaded()
    {
        return loaded;
    }

    private static void LoadConfiguration(Properties appProperties)
    {
        String propValue;

        // 数据目录
        propValue = appProperties.getProperty("data", "");
        if (propValue.trim().isEmpty())
        {
            data = "";
        }
        else
        {
            data = propValue;
        }

        // 日志目录，默认为console，即不记录日志文件
        propValue = appProperties.getProperty("log", "");
        if (propValue.trim().isEmpty())
        {
            log = "console";
        }
        else
        {
            log = propValue;
        }

        // 默认打印到INFO级别
        propValue = appProperties.getProperty("log_level", "");
        if (propValue.trim().isEmpty())
        {
            log_level = Level.INFO;
        }
        else
        {
            log_level = Level.valueOf(propValue);
        }

        // 默认开放全部地址访问
        propValue = appProperties.getProperty("bind", "");
        if (propValue.trim().isEmpty())
        {
            bind = "0.0.0.0";
        }
        else
        {
            bind = propValue;
        }
        propValue = appProperties.getProperty("port", "");
        if (propValue.trim().isEmpty())
        {
            port = 4309;
        }
        else
        {
            port = Integer.parseInt(propValue);
        }

        // 默认使用主机内存的80%
        propValue = appProperties.getProperty("memory_limit", "");
        if (propValue.trim().isEmpty())
        {
            memory_limit = "DEFAULT";
        }
        else
        {
            memory_limit = propValue;
        }

        // 默认使用主机内核数量的80%
        propValue = appProperties.getProperty("threads", "");
        if (propValue.trim().isEmpty())
        {
            // -1表示不进行设置
            threads = -1;
        }
        else
        {
            threads = Integer.parseInt(propValue);
        }

        // 数据库最大连接数
        propValue = appProperties.getProperty("max_connections", "");
        if (propValue.trim().isEmpty())
        {
            max_connections = 1000;
        }
        else
        {
            max_connections = Integer.parseInt(propValue);
        }

        // 客户端最大超时时间, 默认为10分钟
        propValue = appProperties.getProperty("clientTimeout", "");
        if (propValue.trim().isEmpty())
        {
            clientTimeout = 600;
        }
        else
        {
            clientTimeout = Integer.parseInt(propValue);
        }

        // 客户端最大超时时间, 默认为10分钟
        propValue = appProperties.getProperty("access_mode", "");
        if (propValue.trim().isEmpty())
        {
            access_mode = "READ_WRITE";
        }
        else
        {
            access_mode = propValue.trim().toUpperCase();
        }
        propValue = appProperties.getProperty("currentSchema", "");
        if (propValue.trim().isEmpty())
        {
            currentSchema = "";
        }
        else
        {
            currentSchema = propValue.trim();
        }
    }

    public static void LoadDefaultConfiguration() throws ServerException
    {
        LoadConfiguration((String) null);
    }

    // 读取参数配置文件
    public static void LoadConfiguration(String configurationFileName) throws ServerException {
        File configurationFile;
        Properties appProperties = new Properties();

        // 首先读取参数配置里头的信息
        if (configurationFileName != null)
        {
            configurationFile = new File(configurationFileName);
            try (InputStream input = new FileInputStream(configurationFile)) {
                // 加载属性文件
                appProperties.load(input);
            } catch (Exception ex) {
                throw new ServerException(99, ex.getMessage());
            }
        }
        // 加载配置信息
        LoadConfiguration(appProperties);
        loaded = true;
    }

    public static String getLog()
    {
        return log;
    }
    public static Level getLog_level()
    {
        return log_level;
    }

    public static void setPort(int pPort)
    {
        port = pPort;
    }
    public static int getPort()
    {
        return port;
    }

    public static String getBindHost()
    {
        return bind;
    }
    public static int getClientTimeout()
    {
        return clientTimeout;
    }
    public static String getAccess_mode()
    {
        return access_mode;
    }
    public static void setData(String pData)
    {
        data = pData;
    }

    public static String getData()
    {
        return data;
    }
    public static String getCurrentSchema()
    {
        return currentSchema;
    }
    public static String getMemory_limit()
    {
        return memory_limit;
    }
    public static int getThreads()
    {
        return threads;
    }
    public static int getMax_connections()
    {
        return max_connections;
    }
}
