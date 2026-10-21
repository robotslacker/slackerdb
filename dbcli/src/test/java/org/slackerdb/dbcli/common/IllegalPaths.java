package org.slackerdb.dbcli.common;

import org.slackerdb.common.utils.OSUtil;

/**
 * 测试用：一个<b>在当前平台上确实非法</b>的路径值，以及它应当产生的那行错误的前缀。
 *
 * <h2>为什么不能直接写 {@code a*.sql}</h2>
 * <p>这条约定原先散落在几处用例里，都用字面量 {@code a*.sql}，隐含假设"{@code *} 是非法字符"。
 * 那只是 <b>Windows</b> 的规则（Win32 文件名不允许 {@code * ? < > |}）。POSIX 上 {@code *}
 * 是**合法**的文件名字符：{@code Path.of("a*.sql")} 不抛异常，于是"非法路径 → 一行可读错误"
 * 这些用例在 Linux 上根本走不到被测分支——它们退化成"文件不存在"，整组在 Linux 回归里变红。</p>
 *
 * <p>POSIX 上唯一不可能出现在路径里的字符是 <b>NUL</b>（{@code U+0000}）：
 * {@code Path.of("a\0.sql")} 在 Linux 与 Windows 上都会抛 {@link java.nio.file.InvalidPathException}
 * （两个平台的 {@code getReason()} 都是 "Nul character not allowed"）。因此这里：
 * Windows 仍用更贴近"使用者真的会敲错"的 {@code a*.sql}，其它平台用 NUL，
 * 两边都能稳定命中 {@link PathArguments} 把 JDK 异常翻译成可读消息的那条分支。</p>
 *
 * <p>注意：NUL 取自命令行/脚本时并不现实（execve 的字符串以 NUL 结尾），
 * 这些用例要守的是"<b>别让 JDK 的 InvalidPathException 冒到用户面前</b>"，用哪个字符触发并不重要。</p>
 */
public final class IllegalPaths {

    /** 本平台非法的路径值：Windows 用 {@code a*.sql}，其它平台用含 NUL 的 {@code a\0.sql}。 */
    public static final String VALUE = OSUtil.isWindows() ? "a*.sql" : "a\u0000.sql";

    /** 期望的错误消息前缀：{@code Invalid path [<VALUE>]:}。 */
    public static final String INVALID_PREFIX = "Invalid path [" + VALUE + "]:";

    private IllegalPaths() {
    }
}
