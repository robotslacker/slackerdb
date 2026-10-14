package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.test.support.PgWireClient;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：客户端在<b>启动包</b>里要求的 {@code search_path} 不能被静默丢弃。
 *
 * <p><b>被测缺陷</b>：{@code StartupRequest} 的顺序是
 * {@code set variable current_database} → {@code set search_path} → {@code use "<库>"}。
 * 而 DuckDB 的 {@code USE} 会把 search_path <b>整个重置</b>成 {@code <catalog>.main}
 * （不是追加）。于是只要 {@code use} 执行了，前面那条 {@code set search_path} 就白设：
 * 连接照样成功、不报错、也不打日志，但客户端要求被丢掉——非限定名按 {@code <库>.main} 解析，
 * 轻则表找不到，<b>重则解析到另一个同名表、静默读到错数据</b>。</p>
 *
 * <p><b>为什么必须用裸协议测</b>：pgjdbc/dbdriver 只能通过 {@code options=-c search_path=...}
 * 表达（服务端目前不读 {@code options}），只有把 {@code search_path} 直接写进 StartupMessage
 * 的客户端（asyncpg 的 {@code server_settings}、Npgsql 的 {@code Search Path}）才会走这条路径。
 * 因此这里用 {@link PgWireClient} 自定义启动参数来复现真机形态。</p>
 *
 * <p><b>修法约定（方案 B）</b>：只修"客户端明确提了要求"这一种情况——在 {@code USE} 之后
 * 补一次 {@code set search_path}，且<b>原样下发客户端要求的值</b>。没提要求的会话保持
 * {@code <库>.main} 这个既有默认，因此下面同时钉住两种情况，避免以后有人"顺手统一"
 * 而改变默认会话语义。</p>
 *
 * <p><b>为什么补设时不能加 {@code memory.duck_catalog} 前缀</b>：DuckDB 会跟着 search_path
 * 的第一项切换当前 catalog，加上前缀会让会话回到内存库（{@code current_catalog()} 变成
 * {@code memory}、非限定名建表落进内存库），而 PG 语义里 {@code currentSchema} 只影响
 * schema 搜索路径、不改变当前库。{@code Sanity01Test#testCurrentSchema} 先抓出了这个错误实现，
 * 所以这里也把 {@code current_catalog()} 钉成断言。</p>
 */
public class StartupSearchPathTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    /** 客户端要求的搜索路径：故意取一个非默认的 schema 名，才能把"请求是否生效"测出来。 */
    private static final String CLIENT_SCHEMA = "client_sp";

    /** 磁盘模式：正是 {@code USE} 会执行、也就是缺陷会显形的部署形态。 */
    @BeforeAll
    static void initAll(@TempDir Path dataDir) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("sppath");
        cfg.setData_dir(dataDir.toString());
        cfg.setSqlHistory("OFF");
        cfg.setLog_level("INFO");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();

        // 默认 search_path 是 <库>.main，所以要用一个别名的 schema 才能区分"请求生效"与"被丢弃"
        try (Connection conn = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:" + dbPort + "/sppath", "", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS " + CLIENT_SCHEMA);
        }
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    /** 客户端在启动包里要求 search_path：会话必须真的用上它，且不改变当前库。 */
    @Test
    void clientRequestedSearchPathSurvivesUse() throws Exception {
        // 这个启动参数就是 pgjdbc 的 currentSchema 走的那条路（真机上最常见的用法）
        try (PgWireClient client = new PgWireClient("127.0.0.1", dbPort, "sppath",
                Map.of("search_path", CLIENT_SCHEMA), 10_000)) {

            String searchPath = queryFirstText(client, "SHOW search_path");
            assertTrue(searchPath.contains(CLIENT_SCHEMA),
                    "客户端在启动包里要求的 search_path 被丢弃了（DuckDB 的 USE 会重置 search_path，"
                            + "必须在 USE 之后补设），实际 search_path = " + searchPath);

            // current_schema() 跟着 search_path 的第一项走：未修复时它是 main，修复后才是 client_sp。
            // 用默认值 main 做断言区分不出"生效"与"被丢弃"，所以这里必须是这个别名 schema。
            String currentSchema = queryFirstText(client, "select current_schema()");
            assertTrue(CLIENT_SCHEMA.equalsIgnoreCase(currentSchema),
                    "客户端要求的搜索路径没有真正生效，current_schema() = " + currentSchema);

            // 补设必须是"原样"的：加了 memory.duck_catalog 前缀会把当前 catalog 拽回内存库，
            // 于是非限定名建表落进内存库而不是实例库（Sanity01Test 抓到的正是这个）。
            String currentCatalog = queryFirstText(client, "select current_catalog()");
            assertTrue("sppath".equalsIgnoreCase(currentCatalog),
                    "补设 search_path 不得改变当前库（PG 语义：currentSchema 只管 schema 搜索路径），"
                            + "实际 current_catalog() = " + currentCatalog);
        }
    }

    /** 客户端显式要求了带 catalog 的路径：同样原样生效（包括让 duck_catalog 可解析）。 */
    @Test
    void clientRequestedQualifiedSearchPathIsHonored() throws Exception {
        try (PgWireClient client = new PgWireClient("127.0.0.1", dbPort, "sppath",
                Map.of("search_path", "memory.duck_catalog"), 10_000)) {

            String searchPath = queryFirstText(client, "SHOW search_path");
            assertTrue(searchPath.contains("memory.duck_catalog"),
                    "客户端显式要求的带 catalog 路径必须原样生效，实际 = " + searchPath);

            // 此时不带 catalog 的 duck_catalog.X 应当解析得到（默认会话里它是解析不到的）
            String count = queryFirstText(client, "select count(*) from duck_catalog.pg_type");
            assertTrue(count.matches("\\d+") && Integer.parseInt(count) > 0,
                    "客户端要求的路径生效后，duck_catalog.pg_type 应当可解析，实际返回 " + count);
        }
    }

    /** 没提要求的客户端：保持既有默认（{@code <库>.main}），本次修复不得改变它。 */
    @Test
    void diskModeDefaultSearchPathIsUnchanged() throws Exception {
        try (PgWireClient client = new PgWireClient("127.0.0.1", dbPort, "sppath", 10_000)) {
            String searchPath = queryFirstText(client, "SHOW search_path");
            assertTrue(searchPath.contains("sppath"),
                    "未提要求的会话应保持 <库>.main 这个既有默认，实际 = " + searchPath);
            assertTrue(!searchPath.contains("memory.duck_catalog"),
                    "本次修复只针对提了要求的客户端，不应给默认会话也加上 duck_catalog，实际 = " + searchPath);
        }
    }

    /**
     * 不带库名时 {@code USE} 会被跳过，此时 search_path 本来就是生效的
     * （这条路径早已正确，用例用于防止以后重排语句顺序时把它改坏）。
     */
    @Test
    void clientRequestedSearchPathWorksWhenNoDatabaseGiven() throws Exception {
        try (PgWireClient client = new PgWireClient("127.0.0.1", dbPort, "",
                Map.of("search_path", "memory.main"), 10_000)) {
            String searchPath = queryFirstText(client, "SHOW search_path");
            assertTrue(searchPath.contains("memory.duck_catalog") && searchPath.contains("memory.main"),
                    "不带库名时 search_path 应同时含 fake catalog 与客户端要求，实际 = " + searchPath);
        }
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
