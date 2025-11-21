package org.slackerdb.plugin;

import org.pf4j.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class DBPluginManager extends DefaultPluginManager {

    private final Map<String, Object> context = new HashMap<>();

    public DBPluginManager(Path pluginsRoot) {
        super(pluginsRoot);
    }

    public void setContextValue(String key, Object value) {
        context.put(key, value);
    }

    public Object getContextValue(String key) {
        return context.get(key);
    }

    @Override
    protected PluginFactory createPluginFactory() {
        return new InjectPluginFactory();
    }
}
