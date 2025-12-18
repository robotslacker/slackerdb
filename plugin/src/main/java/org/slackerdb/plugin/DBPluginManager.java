package org.slackerdb.plugin;

import org.pf4j.*;
import java.nio.file.Path;

/**
 * 自定义插件管理器，继承自 PF4J 的 DefaultPluginManager。
 *
 * <p>负责管理 Slackerdb 插件的加载、启动、停止和卸载。
 * 通过重写 {@link #createPluginFactory()} 方法使用自定义的 {@link DBPluginFactory}，
 * 确保插件创建时能够自动注入 {@link DBPluginContext}。</p>
 *
 * @see DBPluginFactory
 * @see DBPluginContext
 */
public class DBPluginManager extends DefaultPluginManager {
    /** 插件上下文对象，将被注入到每个插件中 */
    private final DBPluginContext ctx;

    /**
     * 构造函数。
     *
     * @param pluginsRoot 插件根目录路径
     * @param ctx 插件上下文对象
     */
    public DBPluginManager(Path pluginsRoot, DBPluginContext ctx) {
        super(pluginsRoot);
        this.ctx = ctx;
    }

    /**
     * 获取插件上下文（包可见，供 {@link DBPluginFactory} 使用）。
     *
     * @return 插件上下文对象
     */
    DBPluginContext getContext() {
        return ctx;
    }

    /**
     * 创建自定义插件工厂。
     *
     * @return 配置好的 {@link DBPluginFactory} 实例
     */
    @Override
    protected PluginFactory createPluginFactory() {
        return new DBPluginFactory(this);
    }
}
