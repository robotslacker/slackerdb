package org.slackerdb.plugin;

import org.pf4j.DefaultPluginFactory;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

/**
 * Custom plugin factory for creating plugin instances and injecting context.
 *
 * <p>Extends PF4J's DefaultPluginFactory, after creating a plugin,
 * if the plugin implements the {@link IDBPluginContext} interface, automatically injects {@link DBPluginContext}.</p>
 *
 * @see DBPluginManager
 * @see IDBPluginContext
 */
public class DBPluginFactory extends DefaultPluginFactory {
    /** Plugin manager reference for obtaining context object */
    private final DBPluginManager manager;

    /**
     * Constructor.
     *
     * @param manager Plugin manager instance
     */
    public DBPluginFactory(DBPluginManager manager) {
        this.manager = manager;
    }

    /**
     * Create plugin instance and inject context.
     *
     * <p>First calls parent method to create plugin instance, then checks if the plugin implements {@link IDBPluginContext} interface,
     * if yes, calls {@link IDBPluginContext#setDBPluginContext(DBPluginContext)} to inject context.</p>
     *
     * @param wrapper Plugin wrapper
     * @return Created and context-injected plugin instance
     */
    @Override
    public Plugin create(PluginWrapper wrapper) {
        Plugin plugin = super.create(wrapper);

        if (plugin instanceof IDBPluginContext) {
            ((IDBPluginContext) plugin)
                    .setDBPluginContext(manager.getContext());
            
            // If it's a DBPlugin instance, set mount time
            if (plugin instanceof DBPlugin) {
                ((DBPlugin) plugin).setMountTime(System.currentTimeMillis());
            }
        }
        return plugin;
    }
}
