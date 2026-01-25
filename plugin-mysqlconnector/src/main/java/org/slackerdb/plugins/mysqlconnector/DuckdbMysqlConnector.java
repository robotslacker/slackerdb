package org.slackerdb.plugins.mysqlconnector;

import com.lmax.disruptor.dsl.Disruptor;
import io.javalin.Javalin;
import org.pf4j.PluginWrapper;
import org.slackerdb.plugin.DBPlugin;
import org.slf4j.Logger;
import com.lmax.disruptor.util.DaemonThreadFactory;

import java.sql.Connection;
import java.util.Properties;

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
 * </ul>
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
public class DuckdbMysqlConnector extends DBPlugin {
    /** Database connection instance */
    private Connection conn;
    /** Logger instance */
    private Logger     logger = null;

    private SyncEngineManager manager;
    /**
     * Constructor, calls parent constructor.
     *
     * @param wrapper PF4J plugin wrapper
     */
    public DuckdbMysqlConnector(PluginWrapper wrapper) {
        super(wrapper);

        // 1. 初始化 Disruptor (这里先简写，后续根据 Day 2 详细调整)
        Disruptor<BinlogEvent> disruptor = new Disruptor<>(
                BinlogEvent::new, 1024, DaemonThreadFactory.INSTANCE);
        // 这里需要先挂载一个简单的消费逻辑，否则队列无法发布
        disruptor.handleEventsWith((event, seq, end) -> System.out.println("Queue received: " + event.getValue()));
        disruptor.start();

        // 2. 初始化引擎管理器
        manager = new SyncEngineManager(disruptor.getRingBuffer());
    }

    /**
     * Core logic when the plugin starts.
     *
     * <p>The example demonstrates how to obtain resources available to the plugin:
     * <ul>
     *   <li>{@link #getDbConnection()} - Obtain database connection</li>
     *   <li>{@link #getLogger()} - Obtain logger</li>
     *   <li>{@link #getJavalinApp()} - Obtain Javalin application instance</li>
     * </ul>
     * The current code is commented; developers can uncomment and add business logic as needed.
     * </p>
     */
    @Override
    protected void onStart() {
        Javalin    app = this.getJavalinApp();

        app.get("/api/engine/status", ctx -> {
            ctx.result(manager.isRunning() ? "RUNNING" : "STOPPED");
        });

        app.post("/api/engine/start", ctx -> {
            // 这里你可以通过 ctx.bodyAsClass(Properties.class) 或者手动读取参数
            Properties props = new Properties();
            // 简单演示：从 body 中获取 key-value
            ctx.formParamMap().forEach((k, v) -> props.put(k, v.get(0)));

            manager.startEngine(props);
            ctx.result("Engine start command issued.");
        });

        app.post("/api/engine/stop", ctx -> {
            manager.stopEngine();
            ctx.result("Engine stop command issued.");
        });

        // Example: Obtain plugin resources
        // try {
        //     this.conn = getDbConnection();
        // } catch (SQLException ignored) {}
        // this.logger = getLogger();
        // this.app = getJavalinApp();

        // Add plugin startup logic here, for example:
        // - Register Javalin routes
        // - Initialize database tables
        // - Start background tasks
        // - Register event listeners
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
    public static DuckdbMysqlConnector standAloneInstance() throws Exception {
        return DBPlugin.Standalone.createInstance(DuckdbMysqlConnector.class);
    }
}
