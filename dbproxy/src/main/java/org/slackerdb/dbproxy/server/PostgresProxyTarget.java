package org.slackerdb.dbproxy.server;

import java.time.LocalDateTime;

public class PostgresProxyTarget {
    // 创建时间
    public LocalDateTime createdDateTime;

    // 最后更新时间
    public LocalDateTime lastActivatedTIme;

    // 远程数据库的主机地址
    public String  host;

    // 远程数据库的端口
    public int     port;

    // 远程数据库管理端口
    public int     portX;

    // 远程数据库的数据库名称
    public String  database;

    /**
     * 租约到期时刻（{@code System.currentTimeMillis()} 基准）。
     *
     * <p>{@code 0} 表示<b>无租约、不参与过期清理</b>。组播发现的每个条目都会带上
     * {@code now + 15s} 的租约（见 {@code ProxyInstance.applyAnnouncement}）；这个"0 = 无租约"
     * 的约定是留给直接写入 {@code proxyTarget} 的调用方的，避免它们放进去的目标被过期扫描
     * 静默摘掉。</p>
     */
    public long    leaseExpireAtMs;
}
