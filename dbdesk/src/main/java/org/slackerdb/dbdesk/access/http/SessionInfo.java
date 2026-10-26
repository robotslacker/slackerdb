package org.slackerdb.dbdesk.access.http;

/**
 * {@code GET /status} 的 {@code sessions[]} 中一项（服务端契约 S-2）。
 *
 * @param applicationName 会话来源。pgjdbc 系驱动默认报 {@code PostgreSQL JDBC Driver}，
 *                        控制台自己会显式 {@code SET application_name='slackerdb-console/<ver>'}
 * @param type            目前恒为 {@code pgwire}（WS/REPL 会话尚未纳入，见契约 11.3）
 */
public record SessionInfo(
        long id,
        String type,
        String connectedTime,
        String clientIp,
        String applicationName,
        String database,
        String status,
        String executingFunction,
        String executingSql) {

    /** 是不是本控制台自己开的会话（终止前要提示"别误杀自己"）。 */
    public boolean fromConsole() {
        return applicationName != null && applicationName.startsWith("slackerdb-console/");
    }

    /** 正在跑语句的会话（会话面板要高亮）。 */
    public boolean busy() {
        return executingSql != null && !executingSql.isBlank();
    }
}
