package org.slackerdb.dbcli.engine.command;

import org.slackerdb.dbcli.engine.ScriptContext;
import org.slackerdb.dbcli.engine.ScriptStoppedException;

import java.nio.file.Path;
import java.util.List;

/**
 * 命令执行体可以回调的宿主能力（由 {@code ScriptRunner} 实现）。
 *
 * <p>命令只通过这个接口碰引擎，不直接依赖 {@code ScriptRunner} 的私有实现：
 * 这样"一个命令一个类"才是可测的——测试可以给一个最小的宿主，不必竖起整个执行引擎。</p>
 */
public interface CommandHost {

    /** 等待类命令（{@code _SLEEP}/{@code _HOST}）的轮询分片（毫秒）：停止请求最多晚这么久被看见。 */
    long STOP_POLL_MILLIS = 50L;

    /** 这次运行的会话状态。 */
    ScriptContext context();

    /** 是否已经请求停止（作业被 abort/shutdown/管理器关闭）。 */
    boolean stopRequested();

    /**
     * 停止请求的唯一判据点：已请求停止就抛 {@link ScriptStoppedException}。
     *
     * <p>父脚本与控制台的信号恒为 false，因此对它们这条判断永远不生效。</p>
     */
    void checkStopRequested();

    /**
     * 跑一个子脚本（{@code _START}）：共享会话状态，返回子脚本自己的有效退出码。
     */
    int runChildScript(Path scriptFile, List<String> argv);

    /** 报一次错误（打印 + 计数 + {@code _WHENEVER} 判定）。 */
    void reportError(String message);

    /** 一条"既是状态行又是失败"的消息（打印一次 + 计数 + {@code _WHENEVER} 判定）。 */
    void countFailure(String message);
}
