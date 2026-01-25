package org.slackerdb.plugin;

/**
 * Plugin context interface for marking plugins that need to receive {@link DBPluginContext}.
 *
 * <p>Any plugin that needs access to database connections, loggers, or Javalin applications should implement this interface.
 * The plugin manager will call {@link #setDBPluginContext(DBPluginContext)} to inject context after creating the plugin instance.</p>
 *
 * @see DBPluginContext
 * @see DBPluginFactory
 */
public interface IDBPluginContext {
    /**
     * Set plugin context.
     *
     * @param ctx Plugin context object
     */
    void setDBPluginContext(DBPluginContext ctx);
}
