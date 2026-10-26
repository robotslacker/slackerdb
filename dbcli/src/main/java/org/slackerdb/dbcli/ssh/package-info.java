/**
 * 远程执行（{@code _SSH}）：连接、远端命令、SFTP 动作与会话注册表。
 *
 * <h2>阅读顺序</h2>
 * <ol>
 *   <li>{@link org.slackerdb.dbcli.ssh.SshCommandDispatcher}：IR → 动作（参数校验、冻结文案）；</li>
 *   <li>{@link org.slackerdb.dbcli.ssh.SshOperations}：动作契约（{@link org.slackerdb.dbcli.ssh.SshClient} 实现）；</li>
 *   <li>{@link org.slackerdb.dbcli.ssh.SshSessionRegistry}：名字（引用语义）→ 连接；</li>
 *   <li>{@link org.slackerdb.dbcli.ssh.SshConnectOptions}：连接参数（{@code describe()} 不含口令）。</li>
 * </ol>
 *
 * <h2>关键约束</h2>
 * <ul>
 *   <li>作业被中止时，**另一条线程**会 {@code close()} 连接来打断阻塞的远端读：
 *       {@code SshClient} 的连接字段是 {@code volatile}、{@code close()} 幂等且可从任意线程调用；</li>
 *   <li>同名槽位重新 connect 必须关掉旧连接（否则 fd/JSch 线程泄漏，对端 MaxSessions 打满）；</li>
 *   <li>首次连接不做 known_hosts 校验是**刻意的取舍**（本客户端不管理凭据与主机指纹）。</li>
 * </ul>
 */
package org.slackerdb.dbcli.ssh;
