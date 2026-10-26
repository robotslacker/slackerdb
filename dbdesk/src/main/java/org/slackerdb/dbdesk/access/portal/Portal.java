package org.slackerdb.dbdesk.access.portal;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * 数据门户：某个仪表板的**只读分享**。
 *
 * <p>门户 = 仪表板 + 随机 token + 自动刷新间隔 + 撤销标记。门户链接一律长期有效，
 * 不引入过期时间；需要失效时直接撤销。</p>
 *
 * <p>为什么用 token 而不是账号：内网场景下"拿到链接就能看"已经够用，引入账号体系会把 M6 拖成大工程。</p>
 *
 * <p>{@code revoked=true} = 立即失效但保留记录（删记录会让"这个链接曾经存在过"这件事无从追溯）。</p>
 */
public record Portal(String id, String name, String dashboardId, String token,
                     int autoRefreshSeconds, String updatedAt,
                     String expiresAt, boolean revoked) {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 兼容旧调用：不过期、未撤销。 */
    public Portal(String id, String name, String dashboardId, String token,
                  int autoRefreshSeconds, String updatedAt) {
        this(id, name, dashboardId, token, autoRefreshSeconds, updatedAt, null, false);
    }

    public Portal {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("portal name must not be blank");
        }
        if (dashboardId == null || dashboardId.isBlank()) {
            throw new IllegalArgumentException("portal must reference a dashboard");
        }
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.strip();
        name = name.strip();
        dashboardId = dashboardId.strip();
        token = token == null || token.isBlank() ? newToken() : token.strip().toLowerCase(Locale.ROOT);
        autoRefreshSeconds = Math.max(0, Math.min(3_600, autoRefreshSeconds));
        updatedAt = updatedAt == null || updatedAt.isBlank() ? Instant.now().toString() : updatedAt;
        expiresAt = expiresAt == null || expiresAt.isBlank() ? null : expiresAt.strip();
    }

    /** 新的分享 token（32 位十六进制）。 */
    public static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 链接当前是否可用（只看撤销标记；门户一律长期有效）。 */
    public boolean usableAt(Instant now) {
        return !revoked;
    }

    /** 状态文案（管理页展示）。 */
    public String statusAt(Instant now) {
        return revoked ? "已撤销" : "有效（长期）";
    }

    public Portal withAutoRefresh(int seconds) {
        return new Portal(id, name, dashboardId, token, seconds, Instant.now().toString(), expiresAt, revoked);
    }

    public Portal withLifecycle(String newExpiresAt, boolean newRevoked) {
        return new Portal(id, name, dashboardId, token, autoRefreshSeconds,
                Instant.now().toString(), newExpiresAt, newRevoked);
    }
}
