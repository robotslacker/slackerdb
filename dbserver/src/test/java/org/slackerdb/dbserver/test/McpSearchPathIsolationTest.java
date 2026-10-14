package org.slackerdb.dbserver.test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.test.support.PgWireClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：<b>跨借用者</b>的会话状态隔离——MCP 借到的连接必须先把 schema 搜索路径钉回默认值。
 *
 * <p><b>被测缺陷</b>：MCP 与 pgwire、数据服务共用同一个连接池，连接是复用的，而归还时的复位
 * 只做"回滚未提交事务 + 恢复 autoCommit"，<b>不碰 {@code search_path}</b>。
 * 于是只要前一个会话在连接上留过非默认的搜索路径（客户端在启动包里要求
 * {@code currentSchema=client_sp} 就是最常见的来源），MCP 就会把它继承下来。
 * 后果不是报错，而是<b>静默读错数据</b>：{@code run_sql} 执行的是用户原始 SQL，
 * 非限定名按别人的 schema 解析，同名表在两个 schema 里都存在时直接读到另一个表。</p>
 *
 * <p><b>为什么要单独立这个用例</b>：这是两个改动交叉才显形的缺陷——"关闭即归还"让连接真的
 * 回到池里（以前 MCP 关掉的连接只是泄漏掉、不会被复用），"<code>search_path</code> 原样生效"
 * 又让客户端要求的路径留在了连接上。两者单独看都没问题，合起来才有这条泄漏路径。</p>
 *
 * <p><b>为什么要 {@code max_connections=1}</b>：池里只有一条物理连接，MCP 借到的必然是
 * 上一个 pgwire 会话用过的那一条。否则 MCP 可能借到一条全新连接，测试会假绿——
 * 这个缺陷恰恰只在"复用同一条连接"时出现。</p>
 *
 * <p>断言分三层：</p>
 * <ol>
 *   <li>{@link #mcpBorrowedConnectionKeepsDefaultSchema()} —— 未受污染时 MCP 看到的是默认 schema；</li>
 *   <li>同名表在两个 schema 里内容不同 —— 让"解析到哪个 schema"变成可断言的数字；</li>
 *   <li>{@code current_catalog()} 不得被拽回内存库 —— 规范化必须只改 schema、不改当前库。</li>
 * </ol>
 */
public class McpSearchPathIsolationTest {

    private static final String INSTANCE = "mcpsearchpath";

    /** 客户端要求的 schema：故意与默认的 {@code main} 不同，才能把"请求是否被继承"测出来。 */
    private static final String CLIENT_SCHEMA = "client_sp";

    /** 两个 schema 里同名的表：main 里 1 行，client_sp 里 2 行。 */
    private static final String TABLE = "iso_probe";

    private static final int MAIN_ROWS = 1;
    private static final int CLIENT_SCHEMA_ROWS = 2;

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static DBInstance dbInstance;
    private static int dbPort;
    private static int mcpPort;

    @BeforeAll
    static void initAll(@TempDir Path dataDir) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setPortX(0);
        cfg.setData(INSTANCE);
        cfg.setData_dir(dataDir.toString());
        cfg.setSqlHistory("OFF");
        cfg.setDataServiceHistory("OFF");
        cfg.setLog_level("INFO");
        // 只留一条物理连接：MCP 借到的必然是上一个 pgwire 会话用过的那条，
        // 否则"search_path 有没有被继承"就不是确定的（可能借到全新连接，测试假绿）。
        cfg.setMax_connections(1);
        cfg.setConnection_pool_minimum_idle(0);
        cfg.setConnection_pool_maximum_idle(1);

        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();
        mcpPort = cfg.getPortX();

        // 准备两个同名、不同内容的表（不限定名的那个落在默认 schema main 里）
        try (Connection conn = DriverManager.getConnection(instanceUrl(), "", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS " + CLIENT_SCHEMA);
            stmt.execute("CREATE TABLE IF NOT EXISTS " + TABLE + "(a INTEGER)");
            stmt.execute("CREATE TABLE IF NOT EXISTS " + CLIENT_SCHEMA + "." + TABLE + "(a INTEGER)");
            stmt.execute("INSERT INTO " + TABLE + " VALUES (1)");
            for (int i = 0; i < CLIENT_SCHEMA_ROWS; i++) {
                stmt.execute("INSERT INTO " + CLIENT_SCHEMA + "." + TABLE + " VALUES (" + (i + 1) + ")");
            }
        }
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    private static String instanceUrl() {
        return "jdbc:postgresql://127.0.0.1:" + dbPort + "/" + INSTANCE;
    }

    /**
     * 核心用例：pgwire 会话把 {@code client_sp} 留在连接上之后，MCP 借到同一条连接时
     * 必须仍然按默认 schema（{@code main}）解析非限定名。
     */
    @Test
    void mcpBorrowedConnectionKeepsDefaultSchema() throws Exception {
        // ---------- 前提 1：未受污染时，MCP 看到的就是默认 schema ----------
        assertEquals("main", mcpScalar("select current_schema()"),
                "MCP 借到的连接应当按默认 schema 解析");
        assertEquals(String.valueOf(MAIN_ROWS), mcpScalar("select count(*) from " + TABLE),
                "前提不成立：未受污染时非限定名应当解析到默认 schema 里的表");

        // ---------- 前提 2：一个明确要求了 search_path 的 pgwire 会话，把 client_sp 留在连接上 ----------
        try (PgWireClient client = new PgWireClient("127.0.0.1", dbPort, INSTANCE,
                Map.of("search_path", CLIENT_SCHEMA), 10_000)) {
            String searchPath = queryFirstText(client, "show search_path");
            assertTrue(searchPath.contains(CLIENT_SCHEMA),
                    "前提不成立：客户端在启动包里要求的 search_path 没有生效，实际 = " + searchPath);
            assertEquals(String.valueOf(CLIENT_SCHEMA_ROWS),
                    queryFirstText(client, "select count(*) from " + TABLE),
                    "前提不成立：这个会话本身应当按 client_sp 解析非限定名");
        }

        // ---------- 断言：会话结束后连接回到池里，MCP 借到同一条连接，但不得继承 client_sp ----------
        assertEquals("main", mcpScalar("select current_schema()"),
                "MCP 借到的连接继承了上一个会话的 search_path（必须显式钉回默认 schema）："
                        + "run_sql 执行的是用户原始 SQL，非限定名会按别人的 schema 解析");
        assertEquals(String.valueOf(MAIN_ROWS), mcpScalar("select count(*) from " + TABLE),
                "MCP 的非限定名解析到了上一个会话残留的 schema —— 同名表已读到另一个表的数据"
                        + "（main 里 " + MAIN_ROWS + " 行，client_sp 里 " + CLIENT_SCHEMA_ROWS + " 行）");

        // ---------- 规范化只能改 schema，不得改变当前库 ----------
        // （把 memory 之类别的 catalog 放进 search_path 第一项会切换当前库，
        //   于是非限定名建表落进内存库而不是实例库——StartupRequest 的注释里记着这个坑。）
        assertEquals(INSTANCE, mcpScalar("select current_catalog()"),
                "规范化 search_path 时必须带上当前 catalog，不能把会话拽回内存库");
    }

    /**
     * 把一个标量查询交给 MCP 的 {@code run_sql}（不带 handle，走 {@code borrow()} 那条路径），
     * 返回第一行第一列的文本。
     */
    private static String mcpScalar(String sql) throws Exception {
        JSONObject arguments = new JSONObject();
        arguments.put("sql", sql);
        JSONObject params = new JSONObject();
        params.put("name", "run_sql");
        params.put("arguments", arguments);

        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        JSONObject meta = new JSONObject();
        meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
        params.put("_meta", meta);
        request.put("params", params);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + mcpPort + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toJSONString()))
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "POST /mcp -> " + response.body());

        JSONObject envelope = JSON.parseObject(response.body());
        assertNull(envelope.get("error"), "run_sql [" + sql + "] 失败: " + envelope);
        JSONObject structured = envelope.getJSONObject("result").getJSONObject("structuredContent");
        assertNotNull(structured, "run_sql [" + sql + "] 没有返回 structuredContent: " + envelope);
        Object value = structured.getJSONArray("rows").getJSONArray(0).get(0);
        return String.valueOf(value);
    }

    /** 发一条简单查询，返回第一行第一列的文本；出错时返回 {@code ERROR: ...} 便于断言里看清楚。 */
    private static String queryFirstText(PgWireClient client, String sql) throws Exception {
        client.sendQuery(sql);
        List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();
        List<PgWireClient.Frame> rows = PgWireClient.ofType(frames, 'D');
        if (rows.isEmpty()) {
            String message = PgWireClient.errorField(frames, 'M');
            return message == null ? "(no rows: " + PgWireClient.types(frames) + ")" : "ERROR: " + message;
        }
        List<byte[]> values = PgWireClient.dataRowValues(rows.get(0));
        assertNotNull(values.get(0), "第一列不应为 NULL");
        return PgWireClient.textValue(values.get(0));
    }
}
