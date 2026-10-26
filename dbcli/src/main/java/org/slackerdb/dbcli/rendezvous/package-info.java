/**
 * 会合点后端：{@code _JOB TIMER <点名>}（放行）与 {@code _JOB WAIT <点名>}（等待）的落地实现。
 *
 * <ul>
 *   <li>{@link org.slackerdb.dbcli.rendezvous.RendezvousBackend}：契约（幂等放行、超时是正常结果、
 *       点名先规范化、{@code close()} 唤醒等待者）；</li>
 *   <li>{@link org.slackerdb.dbcli.rendezvous.LatchRendezvous}：进程内闸门（线程模式，零 IPC）；</li>
 *   <li>{@link org.slackerdb.dbcli.rendezvous.SqliteRendezvous}：SQLite 同步库（跨进程：{@code mode=process}）。</li>
 * </ul>
 *
 * <p>两个实现必须行为一致（同一套规范化、同样的"超时 = false"），因此测试是共享的。
 * 后端被 {@code JobManager} 在生产过程中替换时，旧后端必须唤醒其等待者。</p>
 */
package org.slackerdb.dbcli.rendezvous;
