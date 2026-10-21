package org.slackerdb.dbcli.engine.command;

import org.slackerdb.dbcli.common.PathArguments;
import org.slackerdb.dbcli.engine.ScriptContext;

import java.nio.file.Path;

/**
 * 一次命令执行的上下文：会话状态 + 宿主能力 + 几个公共小工具。
 *
 * <p>命令实现不持有引擎状态，所有与运行相关的东西都从这里拿——同一份命令实现因此可以
 * 在父脚本、作业线程、测试里用同一套代码跑。</p>
 */
public final class CommandCall {

    private final ScriptContext context;
    private final CommandHost host;

    public CommandCall(ScriptContext context, CommandHost host) {
        this.context = context;
        this.host = host;
    }

    public ScriptContext context() {
        return context;
    }

    public CommandHost host() {
        return host;
    }

    /** 相对路径的基准是**脚本的工作目录**（不是进程当前目录）。 */
    public Path resolvePath(String name) {
        // 路径参数统一入口：非法字符/空值给可读的 Invalid path [...]，不冒 InvalidPathException
        return PathArguments.resolve(context.workingDirectory(), name);
    }

    /** 报一次错误（走宿主的错误通道，带计数与 {@code _WHENEVER}）。 */
    public void reportError(String message) {
        host.reportError(message);
    }

    /** 一条"既是状态行又是失败"的消息（只打印一次）。 */
    public void countFailure(String message) {
        host.countFailure(message);
    }
}
