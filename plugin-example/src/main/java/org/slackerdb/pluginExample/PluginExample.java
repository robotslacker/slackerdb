package org.slackerdb.pluginExample;

import io.javalin.Javalin;
import org.pf4j.PluginWrapper;
import org.slackerdb.plugin.DBPlugin;
import org.slf4j.Logger;

import java.sql.Connection;
import java.util.Map;

/**
 * Plugin example class demonstrating how to write a Slackerdb plugin.
 *
 * <p>This class extends {@link DBPlugin}, demonstrating the basic structure and usage of lifecycle methods.
 * Developers can refer to this example to create their own plugins.</p>
 *
 * <p>Plugin features:
 * <ul>
 *   <li>Obtain database connection, logger, and Javalin app instance on startup (example code is commented)</li>
 *   <li>Implements complete lifecycle methods (onStart, onStop, onDelete)</li>
 *   <li>Demonstrates how to override before/after hook methods</li>
 *   <li>Demonstrates how to receive plugin-specific properties from dbserver configuration</li>
 * </ul>
 * </p>
 *
 * <p>Plugin properties can be passed via dbserver configuration file or command line:
 * <ul>
 *   <li>Configuration file: <code>pluginexample.home=someDir</code></li>
 *   <li>Command line: <code>--pluginexample.home=someDir</code></li>
 * </ul>
 * The plugin can access these properties via {@link #getPluginProperties()} or {@link #getPluginProperty(String)}.
 * </p>
 *
 * <p>Usage steps:
 * <ol>
 *   <li>Uncomment the code in onStart() method to obtain resources</li>
 *   <li>Add custom logic as needed</li>
 *   <li>Package the plugin as a JAR file and place it in the plugin directory</li>
 * </ol>
 * </p>
 *
 * @see DBPlugin
 */
public class PluginExample extends DBPlugin {
    /** Database connection instance */
    private Connection conn;
    /** Javalin web application instance */
    private Javalin    app = null;
    /** Logger instance */
    private Logger     logger = null;

    /**
     * Constructor, calls parent constructor.
     *
     * @param wrapper PF4J plugin wrapper
     */
    public PluginExample(PluginWrapper wrapper) {
        super(wrapper);
    }

    /**
     * Core logic when the plugin starts.
     *
     * <p>The example demonstrates how to obtain resources available to the plugin:
     * <ul>
     *   <li>{@link #getDbConnection()} - Obtain database connection</li>
     *   <li>{@link #getLogger()} - Obtain logger</li>
     *   <li>{@link #getJavalinApp()} - Obtain Javalin application instance</li>
     *   <li>{@link #getPluginProperties()} - Obtain all plugin properties from dbserver</li>
     *   <li>{@link #getPluginProperty(String)} - Obtain a specific plugin property</li>
     * </ul>
     * </p>
     */
    @Override
    protected void onStart() {
        System.err.println("OK onStart");
        // Example: Obtain plugin resources
        // try {
        //     this.conn = getDbConnection();
        // } catch (SQLException ignored) {}
        // this.logger = getLogger();
        // this.app = getJavalinApp();

        // Example: Read plugin-specific properties from dbserver configuration
        // Properties can be set in dbserver configuration file:
        //   pluginexample.home=someDir
        // Or via command line:
        //   --pluginexample.home=someDir
        // String homeDir = getPluginProperty("home");
        // if (homeDir != null) {
        //     System.err.println("PluginExample home directory: " + homeDir);
        // }

        // Add plugin startup logic here, for example:
        // - Register Javalin routes
        // - Initialize database tables
        // - Start background tasks
        // - Register event listeners
    }

    /**
     * Get all properties for this plugin from dbserver configuration.
     * Properties are passed via dbserver configuration file or command line
     * with the format: <code><pluginId>.<paramName></code>.
     *
     * <p>This method automatically filters by this plugin's pluginId,
     * so only properties prefixed with "pluginexample." are returned,
     * with the prefix stripped. For example, if the config has
     * <code>pluginexample.home=/path</code>, this returns {"home" -> "/path"}.</p>
     *
     * @return Map of properties for this plugin, key is paramName, value is paramValue
     */
    @Override
    protected Map<String, String> getPluginProperties() {
        // Calls DBPlugin.getPluginProperties() which internally calls
        // ctx.getPluginProperties(getPluginId()) to filter by pluginId
        return super.getPluginProperties();
    }

    /**
     * Get a specific property for this plugin from dbserver configuration.
     * Properties are passed via dbserver configuration file or command line
     * with the format: <code><pluginId>.<paramName></code>.
     *
     * <p>This method automatically filters by this plugin's pluginId,
     * so only the property prefixed with "pluginexample." is matched.
     * For example, <code>getPluginProperty("home")</code> matches
     * <code>pluginexample.home</code> in the configuration.</p>
     *
     * <p>Example usage:
     * <pre>
     * // In dbserver config file:
     * pluginexample.home=/path/to/home
     * // In plugin code:
     * String home = getPluginProperty("home");
     * if (home != null) {
     *     logger.info("PluginExample home directory: {}", home);
     * }
     * </pre>
     * </p>
     *
     * @param paramName The parameter name (without plugin ID prefix)
     * @return The parameter value, or null if not found
     */
    @Override
    protected String getPluginProperty(String paramName) {
        // Calls DBPlugin.getPluginProperty(paramName) which internally calls
        // ctx.getPluginProperty(getPluginId(), paramName) to filter by pluginId
        return super.getPluginProperty(paramName);
    }

    /**
     * Logic when the plugin stops.
     * Resources can be released here, connections closed, tasks stopped, etc.
     */
    protected void onStop() {
        // Example: Clean up resources
        // if (conn != null) {
        //     try { conn.close(); } catch (SQLException ignored) {}
        // }
    }

    /**
     * Logic when the plugin is deleted.
     * Permanent cleanup operations can be performed here, such as deleting temporary files, unregistering entries, etc.
     */
    protected void onDelete() {
        // Example: Delete resources created by the plugin
    }

    // ========== Lifecycle Hook Methods ==========

    /**
     * Executed before onStart().
     * Can be used for configuration initialization, environment validation, etc.
     */
    protected void beforeStart() {
        // Example: Check if dependent services are available
    }

    /**
     * Executed after onStart().
     * Can be used for post-startup notifications, status reporting, etc.
     */
    protected void afterStart() {
        // Example: Log successful plugin startup
    }

    /**
     * Executed before onStop().
     * Can be used for saving state, preparing for shutdown, etc.
     */
    protected void beforeStop() {
        // Example: Notify related components about impending shutdown
    }

    /**
     * Executed after onStop().
     * Can be used for final cleanup, resource release confirmation, etc.
     */
    protected void afterStop() {
        // Example: Confirm all resources have been released
    }

    /**
     * Executed before onDelete().
     * Can be used for data backup, delete operation confirmation, etc.
     */
    protected void beforeDelete() {
        // Example: Backup plugin data
    }

    /**
     * Executed after onDelete().
     * Can be used for post-deletion cleanup work.
     */
    protected void afterDelete() {
        // Example: Delete backup temporary files
    }

    // ========== Standalone Running Support ==========

    /**
     * Create standalone plugin instance.
     * This method returns a plugin instance configured with a simulated PluginWrapper, suitable for testing and standalone execution.
     * Users need to manually set DBPluginContext and call start() method.
     *
     * @return Standalone plugin instance
     * @throws Exception if creation fails
     */
    public static PluginExample standAloneInstance() throws Exception {
        return DBPlugin.Standalone.createInstance(PluginExample.class);
    }
}
