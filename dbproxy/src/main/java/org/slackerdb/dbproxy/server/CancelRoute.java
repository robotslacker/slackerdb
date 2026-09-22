package org.slackerdb.dbproxy.server;

import java.time.LocalDateTime;

/**
 * 一次已建立的后端会话的取消路由信息。
 *
 * <p>CancelRequest 报文里没有 database，代理无法据此选路；但报文里的 pid 就是**后端自己的会话号**
 * （dbserver 的 BackendKeyData 发送的就是 sessionId），因此代理只要在握手期把这个对应关系记下来，
 * 就能把取消精确地送回正确的上游。</p>
 *
 * <p>本对象在握手完成后被登记进 {@link CancelRouter}，并在对应的出向连接关闭时摘除。</p>
 */
public class CancelRoute {
    // 后端会话号（= BackendKeyData.pid = CancelRequest.pid）
    public int backendPid;

    // 后端密钥（原样透传给上游，代理不改写）
    public int backendSecret;

    // 上游地址
    public String upstreamHost;
    public int    upstreamPort;

    // 上游真实库名（StartupMessage 转发时被改写后的值）
    public String database;

    // 客户端请求的别名，便于日志与 STATUS 展示
    public String alias;

    // 代理侧会话号（入向连接），用于摘除时确认"仍是同一次会话"
    public long clientSessionId;

    public LocalDateTime createdTime;

    @Override
    public String toString() {
        return "pid=" + backendPid
                + " alias=" + alias
                + " upstream=" + upstreamHost + ":" + upstreamPort + "/" + database
                + " clientSession=" + clientSessionId;
    }
}
