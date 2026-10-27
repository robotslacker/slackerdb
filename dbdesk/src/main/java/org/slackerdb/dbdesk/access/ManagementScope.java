package org.slackerdb.dbdesk.access;

/**
 * 连接成功后的管理范围。
 *
 * <p>刻意与“有没有连上”分开：SQL 连接和管理连接是两个通道，菜单/页面按这个范围启停。</p>
 */
public enum ManagementScope {
    /** 没有管理口，只有 SQL。 */
    NONE,
    /** 管理口指向 dbserver 实例（直连，或 dbproxy 的 {@code /<database>} 转发）。 */
    INSTANCE,
    /** 管理口指向 dbproxy 本身，只有这种范围有集群“实例管理”。 */
    CLUSTER
}
