package org.slackerdb.configuration;

import com.sun.management.OperatingSystemMXBean;
import org.slackerdb.exceptions.ServerException;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.util.Properties;
import ch.qos.logback.classic.Level;

public class ServerConfiguration extends Throwable {
    private static final Properties appProperties = new Properties();

    private static String   data = "slackerdb";
    private static String   data_dir = ":memory:";
    private static String   temp_dir = System.getProperty("java.io.tmpdir");
    private static String   extension_dir = "";
    private static String   log = "console";
    private static Level    log_level = Level.INFO;
    private static int      port = 4309;
    private static String   bind = "0.0.0.0";
    private static String   currentSchema;
    private static String   memory_limit = null;
    private static int      threads = (int)(Runtime.getRuntime().availableProcessors() * 0.5);
    private static String   access_mode = "READ_WRITE";
    private static int      max_workers = Runtime.getRuntime().availableProcessors();
    private static int      clientTimeout = 600;

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

    public static void LoadDefaultConfiguration()
    {
        // 数据库名称
        data = readOption("data", data);
        // 数据目录位置，默认存放在内存中
        data_dir = readOption("data_dir", data_dir);
        // 日志目录，默认为console，即不记录日志文件
        log = readOption("log", log);
        // 默认打印到INFO级别
        log_level = Level.valueOf(readOption("log_level", log_level.levelStr));
        // 默认开放全部地址访问
        bind = readOption("bind", bind);
        port = readOption("port", port);
        // 最大容许的内存数量
        if (memory_limit != null) {
            memory_limit = readOption("memory_limit", memory_limit);
        }
        else
        {
            OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            String defaultPhysicalMemorySize = (int) (osBean.getTotalPhysicalMemorySize() / 1024 / 1024 / 1024 * 0.6) + "GB";
            memory_limit = readOption("memory_limit", defaultPhysicalMemorySize);
        }
        // 默认使用主机内核数量的80%
        threads = readOption("threads", threads);
        // 数据库最大工作线程
        max_workers = readOption("max_workers", max_workers);
        // 客户端最大超时时间, 默认为10分钟
        clientTimeout = readOption("client_timeout", clientTimeout);
        // 客户端读写模式
        access_mode = readOption("access_mode", access_mode);
        // 默认用户Schema
        currentSchema = readOption("current_schema", "");
        // 数据库临时文件目录，默认和data_dir相同
        temp_dir = readOption("temp_dir", temp_dir);
        // 扩展文件目录， 默认不配置
        extension_dir = readOption("extension_dir", extension_dir);
    }

    // 读取参数配置文件
    public static void LoadConfigurationFile(String configurationFileName) throws ServerException {
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
        LoadDefaultConfiguration();
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
    public static int getMax_Workers()
    {
        return max_workers;
    }

    public static String getTemp_dir()
    {
        return temp_dir;
    }

    public static String getExtension_dir()
    {
        return extension_dir;
    }
    public static void setTemp_dir(String pTemp_dir)
    {
        temp_dir = pTemp_dir;
    }
    public static void setExtension_dir(String pExtension_dir)
    {
        extension_dir = pExtension_dir;
    }
    public static void setData(String pData)
    {
        data = pData;
    }
    public static void setData_dir(String pData_dir)
    {
        data_dir = pData_dir;
    }
    public static void setMax_workers(int pMax_workers)
    {
        max_workers = pMax_workers;
    }

    public static void setThreads(int pThreads)
    {
        threads = pThreads;
    }
    public static void setMemory_limit(String pMemory_limit)
    {
        memory_limit = pMemory_limit;
    }
}
