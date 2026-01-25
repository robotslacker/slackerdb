package org.slackerdb.plugin;

import io.javalin.Javalin;
import org.slf4j.Logger;

import java.sql.Connection;

/**
 * Plugin context class for passing shared resources between plugin manager and plugins.
 * Contains database connection, logger, and Javalin web application instance.
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
}
