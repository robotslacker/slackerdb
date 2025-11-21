package org.slackerdb.pluginExample;

import org.pf4j.Plugin;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;
import org.slackerdb.plugin.DBPluginManager;

import java.sql.Connection;

public class PluginExample extends Plugin {
    private PluginManager pluginManager;

    @Override
    public void start() {
        DBPluginManager mgr = (DBPluginManager) pluginManager;
        Connection db = (Connection) mgr.getContextValue("Connection");

        System.out.println("[PluginExample] Using DB => " + db);
    }

    @Override
    public void stop() {
        System.out.println("PluginExample STOP");

        DBPluginManager mgr = (DBPluginManager) pluginManager;
        Connection db = (Connection) mgr.getContextValue("Connection");

        System.out.println("[PluginExample] Stop, DB => " + db);
    }
}
