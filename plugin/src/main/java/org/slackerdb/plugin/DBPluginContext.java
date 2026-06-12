package org.slackerdb.plugin;

import io.javalin.Javalin;
import org.slf4j.Logger;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

/**
 * Plugin context class for passing shared resources between plugin manager and plugins.
 * Contains database connection, logger, Javalin web application instance, and plugin properties.
 *
 * <p>This class is created and populated by the plugin manager, then injected into each plugin instance
 * via {@link IDBPluginContext#setDBPluginContext(DBPluginContext)}.</p>
 */
public class DBPluginContext {
    /** Backend database connection */
    private Connection dbBackendConn;
    /** SLF4J logger */
    private Logger logger;
    /** Javalin web application instance */
    private Javalin javalin;
    /** Plugin custom properties (from dbserver configuration, format: <pluginId>.<paramName>) */
    private Map<String, String> pluginProperties = new HashMap<>();

    /**
     * Set backend database connection.
     *
     * @param dbBackendConn Database connection object
     */
    public void setDbBackendConn(Connection dbBackendConn)
    {
        this.dbBackendConn = dbBackendConn;
    }

    /**
     * Get backend database connection.
     *
     * @return Database connection object
     */
    public Connection getDbBackendConn()
    {
        return this.dbBackendConn;
    }

    /**
     * Set logger.
     *
     * @param logger SLF4J logger
     */
    public void setLogger(Logger logger)
    {
        this.logger = logger;
    }

    /**
     * Get logger.
     *
     * @return Configured logger
     */
    public Logger getLogger()
    {
        return this.logger;
    }

    /**
     * Set Javalin web application instance.
     *
     * @param javalin Javalin application instance
     */
    public void setJavalin(Javalin javalin)
    {
        this.javalin = javalin;
    }

    /**
     * Get Javalin web application instance.
     *
     * @return Javalin application instance
     */
    public Javalin getJavalin() {
        return javalin;
    }

    /**
     * Set plugin custom properties.
     *
     * @param pluginProperties Map of plugin properties, key format: "<pluginId>.<paramName>"
     */
    public void setPluginProperties(Map<String, String> pluginProperties)
    {
        this.pluginProperties = pluginProperties;
    }

    /**
     * Get all plugin custom properties.
     *
     * @return Map of plugin properties, key format: "<pluginId>.<paramName>"
     */
    public Map<String, String> getPluginProperties()
    {
        return this.pluginProperties;
    }

    /**
     * Get properties for a specific plugin.
     *
     * @param pluginId Plugin ID
     * @return Map of properties for the specified plugin, key is paramName, value is paramValue
     */
    public Map<String, String> getPluginProperties(String pluginId)
    {
        Map<String, String> result = new HashMap<>();
        String prefix = pluginId + ".";
        for (Map.Entry<String, String> entry : pluginProperties.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return result;
    }

    /**
     * Get a single property value for a specific plugin.
     *
     * @param pluginId Plugin ID
     * @param paramName Parameter name
     * @return Parameter value, or null if not found
     */
    public String getPluginProperty(String pluginId, String paramName)
    {
        return pluginProperties.get(pluginId + "." + paramName);
    }
}
