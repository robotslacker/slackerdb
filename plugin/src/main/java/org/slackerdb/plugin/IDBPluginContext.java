package org.slackerdb.plugin;

/**
 * 插件上下文接口，用于标记需要接收 {@link DBPluginContext} 的插件。
 *
 * <p>任何需要访问数据库连接、日志记录器或 Javalin 应用的插件都应实现此接口。
 * 插件管理器会在创建插件实例后调用 {@link #setDBPluginContext(DBPluginContext)} 注入上下文。</p>
 *
 * @see DBPluginContext
 * @see DBPluginFactory
 */
public interface IDBPluginContext {
    /**
     * 设置插件上下文。
     *
     * @param ctx 插件上下文对象
     */
    void setDBPluginContext(DBPluginContext ctx);
}
