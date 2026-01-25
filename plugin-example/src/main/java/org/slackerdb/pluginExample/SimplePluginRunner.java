package org.slackerdb.pluginExample;

import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slackerdb.plugin.DBPluginContext;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Simple plugin runner example.
 * Demonstrates how to directly use plugin classes without relying on PF4J complex mechanisms.
 *
 * <p>This example demonstrates through the following steps:
 * <ol>
 *   <li>Create database connection, Javalin instance, and logger</li>
 *   <li>Create DBPluginContext and inject resources</li>
 *   <li>Create a simple plugin instance (by extending DBPlugin)</li>
 *   <li>Manually call plugin lifecycle methods</li>
 * </ol>
 * </p>
 *
 * <p>Note: This approach is only suitable for testing and demonstration purposes; actual production environments should use the complete plugin manager.</p>
 */
public class SimplePluginRunner {
    
    private static final Logger LOG = LoggerFactory.getLogger(SimplePluginRunner.class);
    
    public static void main(String[] args) throws Exception {
        LOG.info("Starting simple plugin runner example...");
        
        // 1. Create database connection (using DuckDB in-memory database)
        Connection conn = createDuckDBConnection();
        LOG.info("Database connection created successfully");
        
        // 2. Create Javalin application instance
        Javalin javalin = Javalin.create();
        LOG.info("Javalin application instance created successfully");
        
        // 3. Create logger
        Logger pluginLogger = LoggerFactory.getLogger(PluginExample.class);
        
        // 4. Create DBPluginContext and inject resources
        DBPluginContext ctx = new DBPluginContext();
        ctx.setDbBackendConn(conn);
        ctx.setLogger(pluginLogger);
        ctx.setJavalin(javalin);
        LOG.info("DBPluginContext created and configured");
        
        // 5. Create plugin instance (using standalone support)
        PluginExample plugin = PluginExample.standAloneInstance();
        
        // 6. Inject context
        plugin.setDBPluginContext(ctx);
        
        // 7. Directly call plugin's onStart method (indirectly via start() method)
        LOG.info("Calling plugin start() method...");
        plugin.start();
        LOG.info("Plugin start() method execution completed");
        
        // 8. Wait for a while then stop the plugin
        Thread.sleep(1000);
        
        LOG.info("Calling plugin stop() method...");
        plugin.stop();
        LOG.info("Plugin stop() method execution completed");
        
        // 9. Clean up resources
        conn.close();
        LOG.info("Database connection closed");
        
        LOG.info("Simple plugin runner example execution completed");
    }
    
    /**
     * Create DuckDB in-memory database connection.
     */
    private static Connection createDuckDBConnection() throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:duckdb:memory:");
        // Simple initialization
        conn.createStatement().execute("CREATE TABLE IF NOT EXISTS demo (id INT, name VARCHAR)");
        conn.createStatement().execute("INSERT INTO demo VALUES (1, 'test data')");
        return conn;
    }
}