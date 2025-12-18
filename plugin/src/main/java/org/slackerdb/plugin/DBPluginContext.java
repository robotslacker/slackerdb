package org.slackerdb.plugin;

import io.javalin.Javalin;
import org.slf4j.Logger;

import java.sql.Connection;

/**
 * 插件上下文类，用于在插件管理器与插件之间传递共享资源。
 * 包含数据库连接、日志记录器和 Javalin Web 应用实例。
 *
 * <p>该类由插件管理器创建并填充，然后通过 {@link IDBPluginContext#setDBPluginContext(DBPluginContext)}
 * 注入到每个插件实例中。</p>
 */
public class DBPluginContext {
    /** 后端数据库连接 */
    private Connection dbBackendConn;
    /** SLF4J 日志记录器 */
    private Logger logger;
    /** Javalin Web 应用实例 */
    private Javalin javalin;

    /**
     * 设置后端数据库连接。
     *
     * @param dbBackendConn 数据库连接对象
     */
    public void setDbBackendConn(Connection dbBackendConn)
    {
        this.dbBackendConn = dbBackendConn;
    }

    /**
     * 获取后端数据库连接。
     *
     * @return 数据库连接对象
     */
    public Connection getDbBackendConn()
    {
        return this.dbBackendConn;
    }

    /**
     * 设置日志记录器。
     *
     * @param logger SLF4J 日志记录器
     */
    public void setLogger(Logger logger)
    {
        this.logger = logger;
    }

    /**
     * 获取日志记录器。
     *
     * @return 配置好的日志记录器
     */
    public Logger getLogger()
    {
        return this.logger;
    }

    /**
     * 设置 Javalin Web 应用实例。
     *
     * @param javalin Javalin 应用实例
     */
    public void setJavalin(Javalin javalin)
    {
        this.javalin = javalin;
    }

    /**
     * 获取 Javalin Web 应用实例。
     *
     * @return Javalin 应用实例
     */
    public Javalin getJavalin() {
        return javalin;
    }
}
