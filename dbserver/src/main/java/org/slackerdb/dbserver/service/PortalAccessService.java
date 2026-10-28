package org.slackerdb.dbserver.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slackerdb.dbserver.server.DBInstance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 门户访问日志（M6）：记录"谁在什么时候打开了哪个分享链接"。
 *
 * <p>门户本身是**公开只读**的，所以日志接口也不做鉴权 —— 它只写"访问事实"，不写敏感数据。
 * 真要做访问控制，是在网关/反向代理层加，而不是在这里。</p>
 *
 * <p>表与定义表一样用全限定名 {@code <库名>.sysaux.portal_access}，
 * 避免落到连接池默认的 memory 目录（M5 踩过这个坑）。</p>
 */
public class PortalAccessService {

    /** 单次查询上限：日志是用来"看趋势"的，不是审计导出。 */
    private static final int MAX_LIMIT = 500;

    private final DBInstance dbInstance;
    private final Logger logger;
    private final String qualifiedTable;

    public PortalAccessService(DBInstance dbInstance, Javalin managementApp, Logger logger) {
        this.dbInstance = dbInstance;
        this.logger = logger;
        // 库名统一取 sysauxSchema()：没有 --data 时是 memory，且带引号（实例名允许 -）。
        this.qualifiedTable = dbInstance.sysauxSchema() + ".portal_access";
        ensureTable();
        registerRoutes(managementApp);
    }

    private void ensureTable() {
        try (Connection connection = dbInstance.dbDataSourcePool.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("create schema if not exists " + dbInstance.sysauxSchema());
            statement.execute("create table if not exists " + qualifiedTable + " ("
                    + "id varchar not null, "
                    + "portal_id varchar not null, "
                    + "token varchar not null, "
                    + "path varchar not null, "
                    + "accessed_at varchar not null)");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to prepare sysaux.portal_access: " + e.getMessage(), e);
        }
    }

    private void registerRoutes(Javalin managementApp) {
        managementApp.unsafe.routes.post("/portal-access", ctx -> {
            JSONObject body;
            try {
                body = JSON.parseObject(ctx.body());
            } catch (RuntimeException e) {
                ctx.status(400).json(Map.of("retCode", 400, "retMsg", "Invalid JSON", "errorClass", "VALIDATION"));
                return;
            }
            String portalId = body.getString("portalId");
            if (portalId == null || portalId.isBlank()) {
                ctx.status(400).json(Map.of("retCode", 400, "retMsg", "portalId is required",
                        "errorClass", "VALIDATION"));
                return;
            }
            try (Connection connection = dbInstance.dbDataSourcePool.getConnection();
                 PreparedStatement insert = connection.prepareStatement(
                         "insert into " + qualifiedTable + " (id, portal_id, token, path, accessed_at) values (?, ?, ?, ?, ?)")) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, portalId);
                insert.setString(3, body.getString("token") == null ? "" : body.getString("token"));
                insert.setString(4, body.getString("path") == null ? "" : body.getString("path"));
                insert.setString(5, Instant.now().toString());
                insert.executeUpdate();
            } catch (SQLException e) {
                logger.warn("[SERVER][PORTAL-ACCESS] record failed: {}", e.getMessage());
                ctx.status(500).json(Map.of("retCode", 500, "retMsg", e.getMessage(),
                        "errorClass", "INTERNAL"));
                return;
            }
            ctx.status(204);
        });

        managementApp.unsafe.routes.get("/portal-access", ctx -> {
            String portalId = ctx.queryParam("portalId");
            int limit = clampLimit(ctx.queryParam("limit"));
            StringBuilder sql = new StringBuilder("select portal_id, token, path, accessed_at from ")
                    .append(qualifiedTable);
            if (portalId != null && !portalId.isBlank()) {
                sql.append(" where portal_id = ?");
            }
            sql.append(" order by accessed_at desc limit ").append(limit);

            JSONArray items = new JSONArray();
            try (Connection connection = dbInstance.dbDataSourcePool.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                if (portalId != null && !portalId.isBlank()) {
                    statement.setString(1, portalId);
                }
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        JSONObject item = new JSONObject();
                        item.put("portalId", rs.getString(1));
                        item.put("token", rs.getString(2));
                        item.put("path", rs.getString(3));
                        item.put("at", rs.getString(4));
                        items.add(item);
                    }
                }
            } catch (SQLException e) {
                logger.error("[SERVER][PORTAL-ACCESS] query failed: {}", e.getMessage(), e);
                ctx.status(500).json(Map.of("retCode", 500, "retMsg", e.getMessage(),
                        "errorClass", "INTERNAL"));
                return;
            }
            ctx.json(Map.of("items", items));
        });
    }

    private static int clampLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return 50;
        }
        try {
            return Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException ignored) {
            return 50;
        }
    }
}
