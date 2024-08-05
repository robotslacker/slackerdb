package org.slackerdb.configuration;

import org.slackerdb.exceptions.ServerException;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.util.Properties;
import ch.qos.logback.classic.Level;

public class ServerConfiguration extends Throwable {
    private static final Properties appProperties = new Properties();

    private static String   data;
    private static String   data_dir;
    private static String   log;
    private static Level    log_level;
    private static int      port;
    private static String   bind;
    private static String   currentSchema;
    private static String   memory_limit;
    private static int      threads;
    private static String   access_mode;
    private static int      max_network_workers;
    private static int      clientTimeout;

    private static int readOption(String optionName, int defaultValue)
    {
        String propValue;
        propValue = appProperties.getProperty(optionName, "");
        if (propValue.trim().isEmpty())
        {
            return defaultValue;
        }
        else
        {
            return Integer.parseInt(propValue);
        }
    }

    private static String readOption(String optionName, String defaultValue)
    {
        String propValue;
        propValue = appProperties.getProperty(optionName, "");
        if (propValue.trim().isEmpty())
        {
            return defaultValue;
        }
        else
        {
            return propValue.trim();
        }
    }

    private static void LoadConfiguration()
    {
        // 数据库名称
        data = readOption("data", "slackerdb");
        // 数据目录位置，默认存放在内存中
        data_dir = readOption("data_dir", ":memory:");
        // 日志目录，默认为console，即不记录日志文件
        log = readOption("log", "console");
        // 默认打印到INFO级别
        log_level = Level.valueOf(readOption("log_level", "INFO"));
        // 默认开放全部地址访问
        bind = readOption("bind", "0.0.0.0");
        port = readOption("port", 4309);
        // 默认使用主机内存的80%
        OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        String defaultPhysicalMemorySize = (int)(osBean.getTotalPhysicalMemorySize() / 1024 / 1024 / 1024 * 0.8) + "GB";
        memory_limit = readOption("memory_limit", defaultPhysicalMemorySize);
        // 默认使用主机内核数量的80%
        threads = readOption("threads", (int)(Runtime.getRuntime().availableProcessors()*0.8));
        // 数据库最大工作线程
        max_network_workers = readOption("max_network_workers", (int)(threads*1.5));
        // 客户端最大超时时间, 默认为10分钟
        clientTimeout = readOption("client_timeout", 600);
        // 客户端读写模式
        access_mode = readOption("access_mode", "READ_WRITE");
        // 默认用户Schema
        currentSchema = readOption("current_schema", "");
    }

    // 读取参数配置文件
    public static void LoadConfiguration(String configurationFileName) throws ServerException {
        File configurationFile;

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
        LoadConfiguration();
    }

    public static String getLog()
    {
        return log;
    }
    public static Level getLog_level()
    {
        return log_level;
    }
    public static void setLog_level(Level plog_level)
    {
        log_level = plog_level;
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
    public static String getData_Dir()
    {
        return data_dir;
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
    public static int getMax_Network_Workers()
    {
        return max_network_workers;
    }
}
