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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 定义存储（M5）：数据集 / 图表 / 仪表板 / 门户四类定义的服务端读写。
 *
 * <p><b>服务端不解析 payload 的业务字段</b>：只认 {@code kind / id / name / updated_at}。
 * 定义的结构由控制台演进，服务端跟着改会变成"每加一个字段就要发一次服务端"。</p>
 *
 * <p>并发用 {@code updatedAt} 做乐观锁：PUT 带 {@code If-Match}，与库里不一致就 409，
 * 让客户端重载。比"最后写入者赢"安全，也不需要引入版本号字段。</p>
 */
public class DefinitionService {

    /** 允许的定义类型。新增类型只改这里。 */
    private static final List<String> KINDS = List.of("datasets", "charts", "dashboards", "portals");

    /** id 进 URL，限制字符集避免转义问题。 */
    private static final String ID_PATTERN = "[A-Za-z0-9_-]{1,64}";

    /** paylaod 上限 1 MB：定义是配置，不是数据。 */
    private static final int MAX_PAYLOAD_BYTES = 1024 * 1024;

    private final DBInstance dbInstance;
    private final Logger logger;
    /**
     * 全限定表名：连接池里的连接默认不在实例库上（内部服务都要自己 set search_path），
     * 所以这里用 {@code <库名>.sysaux.definitions} 写死，避免落到 memory 目录去。
     * 库名统一取 {@link DBInstance#sysauxSchema()}（没有 {@code --data} 时是 memory，且带引号）。
     */
    private final String qualifiedTable;

    public DefinitionService(DBInstance dbInstance, Javalin managementApp, Logger logger) {
        this.dbInstance = dbInstance;
        this.logger = logger;
        this.qualifiedTable = dbInstance.sysauxSchema() + ".definitions";
        ensureTable();
        registerRoutes(managementApp);
    }

    private void ensureTable() {
        // sysaux 是服务端自己的 schema：由服务端保证存在（插件也这么干）。
        // 表名用 <库名>.sysaux.definitions 全限定：池里的连接默认不在实例库上，不限定会落到 memory。
        // 注意库名必须走 sysauxSchema()：直接拼 getData() 在没有 --data 时会得到 ".sysaux"，
        // DuckDB 报 Parser Error: syntax error at or near "."，管理接口整个起不来。
        try (Connection connection = dbInstance.dbDataSourcePool.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("create schema if not exists " + dbInstance.sysauxSchema());
            statement.execute("create table if not exists " + qualifiedTable + " ("
                    + "kind varchar not null, "
                    + "id varchar not null, "
                    + "name varchar not null, "
                    + "payload varchar not null, "
                    + "updated_at varchar not null, "
                    + "primary key (kind, id))");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to prepare sysaux.definitions: " + e.getMessage(), e);
        }
    }

    private void registerRoutes(Javalin managementApp) {
        managementApp.unsafe.routes.get("/definitions/{kind}", ctx -> {
            String kind = ctx.pathParam("kind");
            if (!isKind(kind)) {
                badRequest(ctx, "Unknown definition kind [" + kind + "]");
                return;
            }
            JSONArray items = new JSONArray();
            try (Connection connection = dbInstance.dbDataSourcePool.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "select payload from " + qualifiedTable + " where kind = ? order by lower(name)")) {
                statement.setString(1, kind);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        items.add(JSON.parseObject(rs.getString(1)));
                    }
                }
            } catch (SQLException e) {
                serverError(ctx, e);
                return;
            }
            ctx.json(Map.of("items", items));
        });

        managementApp.unsafe.routes.get("/definitions/{kind}/{id}", ctx -> {
            String kind = ctx.pathParam("kind");
            String id = ctx.pathParam("id");
            if (!isKind(kind) || !isValidId(id)) {
                badRequest(ctx, "Unknown definition kind or invalid id");
                return;
            }
            JSONObject stored = read(kind, id);
            if (stored == null) {
                notFound(ctx, "Definition [" + kind + "/" + id + "] not found");
                return;
            }
            ctx.json(stored);
        });

        managementApp.unsafe.routes.post("/definitions/{kind}", ctx -> {
            String kind = ctx.pathParam("kind");
            if (!isKind(kind)) {
                badRequest(ctx, "Unknown definition kind [" + kind + "]");
                return;
            }
            JSONObject body = parseBody(ctx);
            if (body == null) {
                return;
            }
            String proposedId = body.getString("id");
            String id = proposedId == null || proposedId.isBlank() ? UUID.randomUUID().toString() : proposedId;
            if (!isValidId(id)) {
                badRequest(ctx, "Invalid id [" + id + "]");
                return;
            }
            if (read(kind, id) != null) {
                conflict(ctx, "Definition [" + kind + "/" + id + "] already exists");
                return;
            }
            writeDefinition(ctx, kind, id, body, true);
        });

        managementApp.unsafe.routes.put("/definitions/{kind}/{id}", ctx -> {
            String kind = ctx.pathParam("kind");
            String id = ctx.pathParam("id");
            if (!isKind(kind) || !isValidId(id)) {
                badRequest(ctx, "Unknown definition kind or invalid id");
                return;
            }
            JSONObject body = parseBody(ctx);
            if (body == null) {
                return;
            }
            JSONObject stored = read(kind, id);
            if (stored == null) {
                notFound(ctx, "Definition [" + kind + "/" + id + "] not found");
                return;
            }
            String ifMatch = ctx.header("If-Match");
            if (ifMatch != null && !ifMatch.isBlank()
                    && !ifMatch.equals(stored.getString("updatedAt"))) {
                ctx.status(409).json(Map.of(
                        "retCode", 409,
                        "retMsg", "Definition was modified concurrently; reload and retry.",
                        "errorClass", "CONFLICT"));
                return;
            }
            writeDefinition(ctx, kind, id, body, false);
        });

        managementApp.unsafe.routes.delete("/definitions/{kind}/{id}", ctx -> {
            String kind = ctx.pathParam("kind");
            String id = ctx.pathParam("id");
            if (!isKind(kind) || !isValidId(id)) {
                badRequest(ctx, "Unknown definition kind or invalid id");
                return;
            }
            try (Connection connection = dbInstance.dbDataSourcePool.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "delete from " + qualifiedTable + " where kind = ? and id = ?")) {
                statement.setString(1, kind);
                statement.setString(2, id);
                if (statement.executeUpdate() == 0) {
                    notFound(ctx, "Definition [" + kind + "/" + id + "] not found");
                    return;
                }
            } catch (SQLException e) {
                serverError(ctx, e);
                return;
            }
            ctx.status(204);
        });
    }

    private void writeDefinition(io.javalin.http.Context ctx, String kind, String id,
                                 JSONObject body, boolean create) {
        String name = body.getString("name");
        if (name == null || name.isBlank() || name.length() > 200) {
            badRequest(ctx, "Definition name must be 1..200 characters");
            return;
        }
        String updatedAt = Instant.now().toString();
        JSONObject payload = new JSONObject();
        payload.putAll(body);
        payload.put("id", id);
        payload.put("name", name);
        payload.put("updatedAt", updatedAt);

        try (Connection connection = dbInstance.dbDataSourcePool.getConnection()) {
            if (nameTaken(connection, kind, name, id)) {
                conflict(ctx, "Definition name [" + name + "] already exists in " + kind);
                return;
            }
            if (create) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "insert into " + qualifiedTable + " (kind, id, name, payload, updated_at) values (?, ?, ?, ?, ?)")) {
                    insert.setString(1, kind);
                    insert.setString(2, id);
                    insert.setString(3, name);
                    insert.setString(4, payload.toJSONString());
                    insert.setString(5, updatedAt);
                    insert.executeUpdate();
                }
            } else {
                try (PreparedStatement update = connection.prepareStatement(
                        "update " + qualifiedTable + " set name = ?, payload = ?, updated_at = ? where kind = ? and id = ?")) {
                    update.setString(1, name);
                    update.setString(2, payload.toJSONString());
                    update.setString(3, updatedAt);
                    update.setString(4, kind);
                    update.setString(5, id);
                    update.executeUpdate();
                }
            }
        } catch (SQLException e) {
            serverError(ctx, e);
            return;
        }
        ctx.status(create ? 201 : 200).json(payload);
    }

    private boolean nameTaken(Connection connection, String kind, String name, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from " + qualifiedTable + " where kind = ? and lower(name) = lower(?)")) {
            statement.setString(1, kind);
            statement.setString(2, name);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    if (!rs.getString(1).equals(id)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private JSONObject read(String kind, String id) {
        try (Connection connection = dbInstance.dbDataSourcePool.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select payload from " + qualifiedTable + " where kind = ? and id = ?")) {
            statement.setString(1, kind);
            statement.setString(2, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? JSON.parseObject(rs.getString(1)) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read definition: " + e.getMessage(), e);
        }
    }

    private JSONObject parseBody(io.javalin.http.Context ctx) {
        String raw = ctx.body();
        if (raw == null || raw.isBlank()) {
            badRequest(ctx, "Request body must be a definition JSON");
            return null;
        }
        if (raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            ctx.status(413).json(Map.of(
                    "retCode", 413,
                    "retMsg", "Definition payload exceeds 1 MB",
                    "errorClass", "PAYLOAD_TOO_LARGE"));
            return null;
        }
        try {
            return JSON.parseObject(raw);
        } catch (RuntimeException e) {
            badRequest(ctx, "Request body is not valid JSON: " + e.getMessage());
            return null;
        }
    }

    private static boolean isKind(String kind) {
        return KINDS.contains(kind);
    }

    private static boolean isValidId(String id) {
        return id != null && id.matches(ID_PATTERN);
    }

    private static void badRequest(io.javalin.http.Context ctx, String message) {
        ctx.status(400).json(Map.of("retCode", 400, "retMsg", message, "errorClass", "VALIDATION"));
    }

    private static void notFound(io.javalin.http.Context ctx, String message) {
        ctx.status(404).json(Map.of("retCode", 404, "retMsg", message, "errorClass", "NOT_FOUND"));
    }

    private static void conflict(io.javalin.http.Context ctx, String message) {
        ctx.status(409).json(Map.of("retCode", 409, "retMsg", message, "errorClass", "CONFLICT"));
    }

    private void serverError(io.javalin.http.Context ctx, Exception e) {
        logger.error("[SERVER][DEFINITIONS] {} failed: {}", ctx.path(), e.getMessage(), e);
        ctx.status(500).json(Map.of("retCode", 500,
                "retMsg", e.getMessage() == null ? e.toString() : e.getMessage(),
                "errorClass", "INTERNAL"));
    }
}
