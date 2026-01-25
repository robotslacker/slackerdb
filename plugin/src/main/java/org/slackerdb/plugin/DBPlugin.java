package org.slackerdb.plugin;

import io.javalin.Javalin;
import org.duckdb.DuckDBConnection;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Abstract database plugin base class, extending PF4J's Plugin class and implementing IDBPluginContext interface.
 * Provides unified database connection, logging, and web framework access capabilities for Slackerdb plugins.
 *
 * <p>Plugin lifecycle methods (start/stop/delete) are marked as final to ensure proper context injection and execution order.
 * Subclasses should override corresponding hook methods (onStart, onStop, onDelete) to implement specific logic.</p>
 *
 * <p>Plugins can obtain backend database connections via {@link #getDbConnection()},
 * obtain loggers via {@link #getLogger()},
 * and obtain Javalin web application instances via {@link #getJavalinApp()}.</p>
 *
 * @see IDBPluginContext
 * @see DBPluginContext
 */
public abstract class DBPlugin extends Plugin
    implements IDBPluginContext
{
    /** Plugin context object containing database connection, logger, and Javalin application instance */
    private DBPluginContext ctx;
    
    /** Plugin mount time (loading time) in milliseconds */
    private long mountTime = 0L;

    /**
     * Get database connection.
     * Returns a new connection to the backend database (created via DuckDBConnection.duplicate()).
     *
     * @return Database connection object
     * @throws SQLException if an error occurs while obtaining the connection
     */
    protected Connection getDbConnection() throws SQLException
    {
        return ((DuckDBConnection)(ctx.getDbBackendConn())).duplicate();
    }

    /**
     * Get logger.
     *
     * @return Configured SLF4J logger
     */
    protected Logger getLogger()
    {
        return ctx.getLogger();
    }

    /**
     * Get Javalin web application instance.
     * Plugins can register routes, middleware, etc. through this instance.
     *
     * @return Javalin application instance
     */
    protected Javalin getJavalinApp()
    {
        return ctx.getJavalin();
    }

    /**
     * Set plugin mount time.
     * This method is called by the plugin manager when the plugin is loaded.
     *
     * @param mountTime Mount time (millisecond timestamp)
     */
    public void setMountTime(long mountTime) {
        this.mountTime = mountTime;
    }

    /**
     * Get plugin mount time.
     *
     * @return Mount time (millisecond timestamp), returns 0 if not set
     */
    public long getMountTime() {
        return mountTime;
    }

    /**
     * Get formatted mount time string.
     *
     * @return Formatted mount time string (yyyy-MM-dd HH:mm:ss), returns empty string if not set
     */
    public String getMountTimeFormatted() {
        if (mountTime <= 0) {
            return "";
        }
        LocalDateTime dateTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(mountTime),
            ZoneId.systemDefault()
        );
        return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * Constructor, calls parent Plugin constructor.
     *
     * @param wrapper PF4J plugin wrapper
     */
    @SuppressWarnings("deprecation")
    protected DBPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    /**
     * Set plugin context (called by plugin manager).
     * This method is called immediately after plugin instantiation to inject required context objects.
     *
     * @param ctx Plugin context object
     */
    @Override
    public final void setDBPluginContext(DBPluginContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Start plugin (final method).
     * Execution order: beforeStart() → onStart() → afterStart().
     * Throws IllegalStateException if context is not injected.
     * If any step throws an exception, wraps it as RuntimeException and throws.
     */
    @Override
    public final void start() {
        if (ctx == null) {
            throw new IllegalStateException("DBPluginContext not injected");
        }
        try {
            beforeStart();
            onStart();
            afterStart();
        } catch (Exception e) {
            throw new RuntimeException("Plugin start failed", e);
        }
    }

    /**
     * Stop plugin (final method).
     * Execution order: beforeStop() → onStop() → afterStop().
     * Ignores any exceptions that occur during the stop process.
     */
    @Override
    public final void stop() {
        try {
            beforeStop();
            onStop();
            afterStop();
        } catch (Exception ignored) {}
    }

    /**
     * Delete plugin (final method).
     * Execution order: beforeDelete() → onDelete() → afterDelete().
     * Ignores any exceptions that occur during the delete process.
     */
    @Override
    public final void delete() {
        try {
            beforeDelete();
            onDelete();
            afterDelete();
        } catch (Exception ignored) {}
    }

    // ========== Abstract Methods ==========

    /**
     * Core logic when the plugin starts, must be implemented by subclasses.
     */
    protected abstract void onStart();

    /**
     * Logic when the plugin stops, subclasses can optionally override.
     */
    protected void onStop() {}

    /**
     * Logic when the plugin is deleted, subclasses can optionally override.
     */
    protected void onDelete() {}

    // ========== Lifecycle Hook Methods ==========

    /**
     * Executed before onStart(), subclasses can optionally override.
     */
    protected void beforeStart() {}

    /**
     * Executed after onStart(), subclasses can optionally override.
     */
    protected void afterStart() {}

    /**
     * Executed before onStop(), subclasses can optionally override.
     */
    protected void beforeStop() {}

    /**
     * Executed after onStop(), subclasses can optionally override.
     */
    protected void afterStop() {}

    /**
     * Executed before onDelete(), subclasses can optionally override.
     */
    protected void beforeDelete() {}

    /**
     * Executed after onDelete(), subclasses can optionally override.
     */
    protected void afterDelete() {}

    // ========== Standalone Running Support ==========

    /**
     * Support class for standalone plugin execution.
     * Provides methods to create plugin instances that do not depend on PF4J plugin manager.
     */
    public static class Standalone {
        /**
         * Simplified PluginWrapper implementation for standalone running environment.
         */
        private static class SimplePluginWrapper extends PluginWrapper {
            public SimplePluginWrapper() {
                super(new SimplePluginManager(),
                      new org.pf4j.DefaultPluginDescriptor("standalone-plugin", "Standalone Plugin", "1.0.0", "", "", "", ""),
                      java.nio.file.Paths.get("."),
                      DBPlugin.class.getClassLoader());
            }
        }

        /**
         * Simplified PluginManager implementation, providing only necessary methods.
         */
        private static class SimplePluginManager extends org.pf4j.DefaultPluginManager {
            // Uses default PluginDescriptorFinder
        }

        /**
         * Create standalone plugin instance.
         * This method returns a plugin instance configured with a simulated PluginWrapper, suitable for testing and standalone execution.
         *
         * @param pluginClass Class object of the plugin class
         * @param <T> Plugin type
         * @return Plugin instance
         * @throws Exception if creation fails
         */
        public static <T extends DBPlugin> T createInstance(Class<T> pluginClass) throws Exception {
            SimplePluginWrapper wrapper = new SimplePluginWrapper();
            return pluginClass.getDeclaredConstructor(PluginWrapper.class).newInstance(wrapper);
        }
    }
}
