package org.slackerdb.plugin;

import org.pf4j.DefaultPluginFactory;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import org.pf4j.PluginManager;

import java.lang.reflect.Field;

public class InjectPluginFactory extends DefaultPluginFactory {

    @Override
    public Plugin create(PluginWrapper wrapper) {
        Plugin plugin = super.create(wrapper);

        // 手动注入 PluginManager
        inject(plugin, PluginManager.class, wrapper.getPluginManager());

        return plugin;
    }

    private void inject(Object plugin, Class<?> type, Object value) {
        for (Field f : plugin.getClass().getDeclaredFields()) {
            if (f.getType().isAssignableFrom(type)) {
                f.setAccessible(true);
                try {
                    f.set(plugin, value);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
