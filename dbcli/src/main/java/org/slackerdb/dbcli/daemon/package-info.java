/**
 * 守护运行：{@code --daemon true} 把脚本放到后台副本，父进程立即返回并报告 pid。
 *
 * <ul>
 *   <li>{@link org.slackerdb.dbcli.daemon.CliDaemon}：意图判定、拉起副本、等 pidfile、冻结文案；</li>
 *   <li>{@link org.slackerdb.dbcli.daemon.PidFile}：pidfile 的读写（写 = 当前进程的 pid）。</li>
 * </ul>
 *
 * <p>没有子命令，也没有 {@code start}/{@code stop}/{@code status}：{@code --daemon} 就说清了"要后台"。
 * 父进程报的 pid 来自**副本自己写的** pidfile（不是中间 shell 的 pid）。</p>
 */
package org.slackerdb.dbcli.daemon;
