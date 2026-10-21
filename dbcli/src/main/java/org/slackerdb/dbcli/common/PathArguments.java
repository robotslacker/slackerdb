package org.slackerdb.dbcli.common;

import java.nio.file.Path;

/**
 * 用户写下的**路径参数**的唯一解析入口：{@code --execute}/{@code --profile}/{@code --logfile}/
 * {@code --reference}/{@code --report-dir}/{@code --pidfile}、{@code _start}、{@code _spool}、
 * {@code _ssh sftp get/put} 的本地路径、作业的 {@code script=/logfile=/...} 都走这里。
 *
 * <h2>为什么统一</h2>
 * <p>以前这些位置直接 {@code Path.of(...)}：Windows 上的 {@code * ? < > |} 之类非法字符会让
 * {@link java.nio.file.InvalidPathException}（RuntimeException）一路冒到 {@code Main}，
 * 使用者看到 {@code ERROR: uncaught exception: java.nio.file.InvalidPathException ...} 加一整段
 * Java 栈、退出码 255——那是"实现缺陷"的信号，而这里只是路径写错了。</p>
 *
 * <p>现在统一成一行可读错误（与数值参数的 {@code Invalid <参数名> [<值>]: ...} 同一种风格）：
 * {@code Invalid path [a*.sql]: Illegal char <*>.}。</p>
 */
public final class PathArguments {

    /** 路径参数不合法：{@code getMessage()} 就是可以直接打印给使用者的那一行。 */
    public static final class InvalidPathException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private final String value;

        private InvalidPathException(String message, String value) {
            super(message);
            this.value = value;
        }

        /** 使用者写的那一串原文。 */
        public String value() {
            return value;
        }
    }

    private PathArguments() {
    }

    /**
     * 只**校验**并解析成 {@link Path}（不拼接、不绝对化）：给 {@code _ssh sftp get/put} 的本地路径用，
     * 语义与以前一致（相对进程当前目录，由驱动去解析）。
     *
     * @throws InvalidPathException 空值或含有本平台不允许的字符
     */
    public static Path parse(String value) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) {
            throw new InvalidPathException("Invalid path []: the path is empty.", text);
        }
        try {
            return Path.of(text);
        } catch (java.nio.file.InvalidPathException invalid) {
            String reason = invalid.getReason();
            throw new InvalidPathException("Invalid path [" + text + "]: "
                    + (reason == null || reason.isBlank() ? "not a valid path" : reason) + ".", text);
        }
    }

    /**
     * 解析一个路径参数：相对路径按 {@code base} 解析，绝对路径原样规范化。
     *
     * @throws InvalidPathException 空值或含有本平台不允许的字符
     */
    public static Path resolve(Path base, String value) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) {
            throw new InvalidPathException("Invalid path []: the path is empty.", text);
        }
        try {
            Path path = Path.of(text);
            return path.isAbsolute() ? path.normalize() : base.resolve(path).normalize();
        } catch (java.nio.file.InvalidPathException invalid) {
            String reason = invalid.getReason();
            throw new InvalidPathException("Invalid path [" + text + "]: "
                    + (reason == null || reason.isBlank() ? "not a valid path" : reason) + ".", text);
        }
    }
}
