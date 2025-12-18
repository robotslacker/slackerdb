package org.slackerdb.plugin;

import io.javalin.Javalin;
import org.duckdb.DuckDBConnection;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 抽象数据库插件基类，继承自 PF4J 的 Plugin 类并实现 IDBPluginContext 接口。
 * 为 Slackerdb 插件提供统一的数据库连接、日志和 Web 框架访问能力。
 *
 * <p>插件生命周期方法（start/stop/delete）被 final 修饰以确保正确的上下文注入和顺序执行，
 * 子类应重写相应的钩子方法（onStart, onStop, onDelete）来实现具体逻辑。</p>
 *
 * <p>插件可以通过 {@link #getDbConnection()} 获取到后端数据库连接，
 * 通过 {@link #getLogger()} 获取日志记录器，
 * 通过 {@link #getJavalinApp()} 获取 Javalin Web 应用实例。</p>
 *
 * @see IDBPluginContext
 * @see DBPluginContext
 */
public abstract class DBPlugin extends Plugin
    implements IDBPluginContext
{
    /** 插件上下文对象，包含数据库连接、日志记录器和 Javalin 应用实例 */
    private DBPluginContext ctx;

    /**
     * 获取数据库连接。
     * 返回一个与后端数据库的新连接（通过 DuckDBConnection.duplicate() 创建）。
     *
     * @return 数据库连接对象
     * @throws SQLException 如果获取连接时发生错误
     */
    protected Connection getDbConnection() throws SQLException
    {
        return ((DuckDBConnection)(ctx.getDbBackendConn())).duplicate();
    }

    /**
     * 获取日志记录器。
     *
     * @return 配置好的 SLF4J 日志记录器
     */
    protected Logger getLogger()
    {
        return ctx.getLogger();
    }

    /**
     * 获取 Javalin Web 应用实例。
     * 插件可以通过此实例注册路由、中间件等。
     *
     * @return Javalin 应用实例
     */
    protected Javalin getJavalinApp()
    {
        return ctx.getJavalin();
    }

    /**
     * 构造函数，调用父类 Plugin 的构造函数。
     *
     * @param wrapper PF4J 插件包装器
     */
    @SuppressWarnings("deprecation")
    protected DBPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    /**
     * 设置插件上下文（由插件管理器调用）。
     * 此方法在插件实例化后立即被调用，用于注入必需的上下文对象。
     *
     * @param ctx 插件上下文对象
     */
    @Override
    public final void setDBPluginContext(DBPluginContext ctx) {
        this.ctx = ctx;
    }

    /**
     * 启动插件（final 方法）。
     * 执行顺序：beforeStart() → onStart() → afterStart()。
     * 如果上下文未注入，抛出 IllegalStateException。
     * 如果任何步骤抛出异常，包装为 RuntimeException 抛出。
     */
    @Override
    public final void start() {
        if (ctx == null) {
            throw new IllegalStateException("DBPluginContext not injected");
        }
        try {
            beforeStart();
            onStart();
            afterStart();
        } catch (Exception e) {
            throw new RuntimeException("Plugin start failed", e);
        }
    }

    /**
     * 停止插件（final 方法）。
     * 执行顺序：beforeStop() → onStop() → afterStop()。
     * 忽略停止过程中发生的任何异常。
     */
    @Override
    public final void stop() {
        try {
            beforeStop();
            onStop();
            afterStop();
        } catch (Exception ignored) {}
    }

    /**
     * 删除插件（final 方法）。
     * 执行顺序：beforeDelete() → onDelete() → afterDelete()。
     * 忽略删除过程中发生的任何异常。
     */
    @Override
    public final void delete() {
        try {
            beforeDelete();
            onDelete();
            afterDelete();
        } catch (Exception ignored) {}
    }

    // ========== 抽象方法 ==========

    /**
     * 插件启动时的核心逻辑，子类必须实现。
     */
    protected abstract void onStart();

    /**
     * 插件停止时的逻辑，子类可选择重写。
     */
    protected void onStop() {}

    /**
     * 插件删除时的逻辑，子类可选择重写。
     */
    protected void onDelete() {}

    // ========== 生命周期钩子方法 ==========

    /**
     * 在 onStart() 之前执行，子类可选择重写。
     */
    protected void beforeStart() {}

    /**
     * 在 onStart() 之后执行，子类可选择重写。
     */
    protected void afterStart() {}

    /**
     * 在 onStop() 之前执行，子类可选择重写。
     */
    protected void beforeStop() {}

    /**
     * 在 onStop() 之后执行，子类可选择重写。
     */
    protected void afterStop() {}

    /**
     * 在 onDelete() 之前执行，子类可选择重写。
     */
    protected void beforeDelete() {}

    /**
     * 在 onDelete() 之后执行，子类可选择重写。
     */
    protected void afterDelete() {}
}
