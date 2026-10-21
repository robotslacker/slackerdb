package org.slackerdb.dbcli.ssh;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * SSH 会话注册表：保存"名字 → 连接"的映射与当前会话名。
 *
 * <h2>语义</h2>
 * <ul>
 *   <li>{@code connect} 把**当前会话**存到"当前名字"下；默认名字是 {@code NONAME}；</li>
 *   <li>{@code save <name>} 把当前会话**按引用**再挂一个名字（不复制连接！所以 save/restore
 *       拿到的是同一条活的 SSH 连接，这正是它的用途）；</li>
 *   <li>{@code restore <name>} 只做"切换当前名字"，不动连接；</li>
 *   <li>{@code disconnect} 关掉当前会话的连接。</li>
 * </ul>
 *
 * <p><b>一处刻意的严格化</b>：{@code restore} 会检查名字是否存在，
 * 于是"restore 一个不存在的名字"在**出错的那一行**立刻失败，而不是留到下一次使用时才炸，
 * 让脚本里的拼写错误当场暴露。</p>
 *
 * <p><b>线程安全</b>：{@code close()} 可能来自**另一条线程**——作业被中止时，请求停止的一方会断开
 * 作业自己的 SSH 会话，好让阻塞中的远端调用立刻失败（见 {@code ScriptJobExecutor}）。
 * 因此本类的方法都是 {@code synchronized}：注册表不会被"关"与"用"交错成中间状态。</p>
 */
public final class SshSessionRegistry implements AutoCloseable {

    /** 默认当前会话名。 */
    public static final String DEFAULT_SESSION_NAME = "NONAME";

    private final Map<String, SshOperations> sessions = new LinkedHashMap<>();
    private String currentSessionName = DEFAULT_SESSION_NAME;

    public synchronized String currentSessionName() {
        return currentSessionName;
    }

    /** 当前是否存在可用会话（即"当前名字"下挂过连接）。 */
    public synchronized boolean hasCurrent() {
        return sessions.containsKey(currentSessionName);
    }

    public synchronized SshOperations current() {
        return sessions.get(currentSessionName);
    }

    /** connect 之后调用：把连接登记到"当前名字"下。 */
    public synchronized void registerCurrent(SshOperations operations) {
        sessions.put(currentSessionName, operations);
    }

    public synchronized Set<String> sessionNames() {
        return Set.copyOf(sessions.keySet());
    }

    /**
     * {@code _SSH save <name>}：把当前会话再挂一个名字。
     *
     * @throws IllegalStateException 当前没有会话时立刻报错（不拖到下次使用）
     */
    public synchronized void save(String name) {
        SshOperations operations = sessions.get(currentSessionName);
        if (operations == null) {
            throw new IllegalStateException("SSH session not connected.");
        }
        sessions.put(name, operations);
    }

    /**
     * {@code _SSH restore <name>}：把当前会话切换到该名字。
     *
     * @throws IllegalArgumentException 名字不存在（立刻报错，见类注释）
     */
    public synchronized void restore(String name) {
        if (!sessions.containsKey(name)) {
            throw new IllegalArgumentException("SSH session [" + name + "] not found.");
        }
        currentSessionName = name;
    }

    /**
     * {@code _SSH disconnect}：关闭当前会话的连接，并**摘掉所有指向它的名字**。
     *
     * <p>为什么名字也要删：名字是引用语义，连接关掉之后 "已有会话" 就是假的了。
     * 留着它会让 {@code _ssh execute}/{@code _SSH SFTP} 走到"连接已断"的深水区
     * （报 {@code SSH session is not established} 之类的驱动异常），而不是文档承诺的
     * {@code SSH not connected.} / {@code SFTP not connected.}——状态必须反映真相，
     * 与 {@code JdbcSqlExecutor.disconnect} 摘掉所有别名是同一条规则。</p>
     */
    public synchronized void disconnectCurrent() {
        SshOperations operations = sessions.get(currentSessionName);
        if (operations == null) {
            return;
        }
        sessions.values().removeIf(candidate -> candidate == operations);
        operations.close();
    }

    /** 关闭所有连接（进程退出/脚本结束/作业被中止时调用）。 */
    @Override
    public synchronized void close() {
        for (SshOperations operations : new LinkedHashMap<>(sessions).values()) {
            operations.close();
        }
        sessions.clear();
        currentSessionName = DEFAULT_SESSION_NAME;
    }
}
