package org.slackerdb.plugin;

import org.pf4j.*;
import java.nio.file.Path;

/**
 * Custom plugin manager, extending PF4J's DefaultPluginManager.
 *
 * <p>Responsible for managing Slackerdb plugin loading, starting, stopping, and unloading.
 * Overrides {@link #createPluginFactory()} method to use custom {@link DBPluginFactory},
 * ensuring automatic injection of {@link DBPluginContext} when plugins are created.</p>
 *
 * @see DBPluginFactory
 * @see DBPluginContext
 */
public class DBPluginManager extends DefaultPluginManager {
    /** Plugin context object to be injected into each plugin */
    private final DBPluginContext ctx;

    /**
     * Constructor.
     *
     * @param pluginsRoot Plugin root directory path
     * @param ctx Plugin context object
     */
    public DBPluginManager(Path pluginsRoot, DBPluginContext ctx) {
        super(pluginsRoot);
        this.ctx = ctx;
    }

    /**
     * Constructor.
     *
     * @param ctx Plugin context object
     */
    public DBPluginManager(DBPluginContext ctx) {
        super();
        this.ctx = ctx;
    }

    /**
     * Get plugin context (package-visible, used by {@link DBPluginFactory}).
     *
     * @return Plugin context object
     */
    DBPluginContext getContext() {
        return ctx;
    }

    /**
     * Create custom plugin factory.
     *
     * @return Configured {@link DBPluginFactory} instance
     */
    @Override
    protected PluginFactory createPluginFactory() {
        return new DBPluginFactory(this);
    }
}
