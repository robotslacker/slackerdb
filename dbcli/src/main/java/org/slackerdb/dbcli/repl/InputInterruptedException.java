package org.slackerdb.dbcli.repl;

/**
 * 读取用户输入的一行（{@code readLine}）被中断（Ctrl-C）。
 *
 * <p>单独做一种异常而不是复用 {@code IOException}：控制台对两者的处理完全不同——
 * 中断只取消**当前这条输入**（丢掉没写完的缓冲，回到主提示符），
 * 读失败（终端坏了、流关了）则必须退出控制台。</p>
 */
public class InputInterruptedException extends Exception {

    private static final long serialVersionUID = 1L;

    public InputInterruptedException(String message) {
        super(message);
    }

    public InputInterruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
