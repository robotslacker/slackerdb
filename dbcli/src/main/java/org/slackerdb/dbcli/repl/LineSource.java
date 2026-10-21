package org.slackerdb.dbcli.repl;

import java.io.IOException;

/**
 * 交互式控制台的**取行抽象**：控制台只认"给个提示符、还我一行"，不认终端。
 *
 * <h2>为什么把它抽出来</h2>
 * <p>控制台"必须能在无 TTY 的环境里单测"（CI / surefire 里
 * {@code System.console()} 是 null，也不该真的去等键盘）。把取行抽成接口之后：</p>
 * <ul>
 *   <li>生产实现是 {@link JLineLineSource}（行编辑、历史、多行续行提示都由 JLine 提供）；</li>
 *   <li>测试实现给一串"预置输入行"，于是"同一脚本用 {@code --execute} 跑"与
 *       "逐行粘进控制台跑"可以直接比较 stdout 与 {@code --logfile} 内容（§7.2 第 4 条）。</li>
 * </ul>
 *
 * <h2>提示符不是数据通道</h2>
 * <p>提示符由取行实现写到它自己的显示面（JLine 终端），<b>不经过</b>
 * {@code engine.ScriptOutput}——stdout 上的字节仍然只有脚本数据（§3.7）。</p>
 */
public interface LineSource extends AutoCloseable {

    /**
     * 读一行。
     *
     * @param prompt 提示符（主提示符与续行提示符由控制台决定）
     * @return 行内容（不含行尾）；<b>输入结束（EOF / Ctrl-D）返回 null</b>
     * @throws InputInterruptedException 输入被中断（Ctrl-C）：控制台会丢掉当前没写完的缓冲
     * @throws IOException               读取失败（终端不可用）：控制台会退出
     */
    String readLine(String prompt) throws IOException, InputInterruptedException;

    /**
     * 在 {@code action} 覆盖的这段时间里接管 Ctrl-C（SIGINT）：收到信号时执行 {@code action}，
     * 而不是让 JVM 直接退出。
     *
     * <p><b>为什么是取行实现的能力</b>：信号属于终端，控制台不该知道 JLine、也不该用
     * {@code sun.misc.Signal} 这类非标准 API。生产实现用 JLine 的终端注册处理器；Dumb 终端或
     * 平台不支持时返回空动作（{@code Ctrl-C} 退回默认行为），控制台无需分叉。</p>
     *
     * <p><b>只覆盖"执行期"</b>：调用方在读一行之后、执行这一行之前安装，执行完恢复。
     * 提示符处的 Ctrl-C 仍然由取行实现自己处理（JLine 抛 {@link InputInterruptedException}）。</p>
     *
     * @param action 收到 SIGINT 时要做的事（控制台传的是"取消当前语句"）
     * @return 恢复原处理器的动作；不支持时是空动作，调用方**总是**要调用它
     */
    default Runnable captureInterrupt(Runnable action) {
        return () -> {
        };
    }

    @Override
    void close() throws IOException;
}
