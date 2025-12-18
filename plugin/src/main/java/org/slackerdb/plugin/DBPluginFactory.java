package org.slackerdb.plugin;

import org.pf4j.DefaultPluginFactory;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

/**
 * 自定义插件工厂，用于创建插件实例并注入上下文。
 *
 * <p>继承自 PF4J 的 DefaultPluginFactory，在创建插件后，
 * 如果插件实现了 {@link IDBPluginContext} 接口，则自动注入 {@link DBPluginContext}。</p>
 *
 * @see DBPluginManager
 * @see IDBPluginContext
 */
public class DBPluginFactory extends DefaultPluginFactory {
    /** 插件管理器引用，用于获取上下文对象 */
    private final DBPluginManager manager;

    /**
     * 构造函数。
     *
     * @param manager 插件管理器实例
     */
    public DBPluginFactory(DBPluginManager manager) {
        this.manager = manager;
    }

    /**
     * 创建插件实例并注入上下文。
     *
     * <p>首先调用父类方法创建插件实例，然后检查插件是否实现了 {@link IDBPluginContext} 接口，
     * 如果是，则调用 {@link IDBPluginContext#setDBPluginContext(DBPluginContext)} 注入上下文。</p>
     *
     * @param wrapper 插件包装器
     * @return 创建并注入上下文后的插件实例
     */
    @Override
    public Plugin create(PluginWrapper wrapper) {
        Plugin plugin = super.create(wrapper);

        if (plugin instanceof IDBPluginContext) {
            ((IDBPluginContext) plugin)
                    .setDBPluginContext(manager.getContext());
        }
        return plugin;
    }
}
