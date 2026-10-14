package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.test.support.PgWireClient;

import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQL {@code ANALYZE <外部表>} 会发的远端语句契约（B4）。
 *
 * <p><b>被测缺陷</b>（改造前）：{@code postgres_fdw} 的 ANALYZE 先发
 * （见 {@code deparse.c} 的 {@code deparseAnalyzeSizeSql} / {@code deparseAnalyzeInfoSql}）</p>
 *
 * [source,sql]
 * ----
 * SELECT pg_catalog.pg_relation_size('main.t'::pg_catalog.regclass) / 8192
 * SELECT reltuples, relkind FROM pg_catalog.pg_class WHERE oid = 'main.t'::pg_catalog.regclass
 * ----
 *
 * <p>而 DuckDB 没有 {@code regclass} 类型，旧行为直接报
 * {@code ERROR: Catalog Error: Type with name regclass does not exist!}，ANALYZE 因此完全不可用。</p>
 *
 * <p><b>契约</b>：{@code 'schema.table'::pg_catalog.regclass} 被翻译成**真正的 oid 查询**
 * （不是删掉转换留下裸名字），于是后面的 {@code pg_relation_size(oid)} 与
 * {@code pg_class WHERE oid = …} 都能正常回答；表名形式（带或不带 schema 前缀）与 oid 形式
 * 都要能用。</p>
 */
public class AnalyzeForeignTableTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("analyzefdw");
        cfg.setSqlHistory("OFF");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();

        try (PgWireClient client = newClient()) {
            client.sendQuery("CREATE OR REPLACE TABLE an_t AS SELECT range AS id, 'v' || range AS v FROM range(200)");
            client.readUntilReadyForQuery();
        }
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    private static PgWireClient newClient() throws Exception {
        return new PgWireClient("127.0.0.1", dbPort, "analyzefdw", 10_000);
    }

    private static String firstValue(List<PgWireClient.Frame> frames) {
        return PgWireClient.textValue(PgWireClient.dataRowValues(PgWireClient.ofType(frames, 'D').get(0)).get(0));
    }

    /** ANALYZE 第一步：表大小（页数）。 */
    @Test
    void analyzeSizeQueryWorks() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("SELECT pg_catalog.pg_relation_size('main.an_t'::pg_catalog.regclass) / 8192");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("TDCZ", PgWireClient.types(frames),
                    "ANALYZE 的取大小语句必须可用（旧行为是 regclass 类型不存在），实际="
                            + PgWireClient.types(frames) + " / " + PgWireClient.errorField(frames, (char) 77));
            String pages = firstValue(frames);
            assertFalse(pages == null || pages.isBlank(), "页数不应为空");
            // 注意：DuckDB 的 `/` 是浮点除法（PG 里 bigint/int 是整除），所以这里是小数值。
            // postgres_fdw 对远端结果用 atoi 截断（atoi("1234.5") == 1234，与整除同值），
            // 因此小数页数不影响 fdw 得到的页数；这里断言非负即可。
            assertTrue(Double.parseDouble(pages) >= 0, "页数应 >= 0，实际=" + pages);
        }
    }

    /** ANALYZE 第二步：从 pg_class 取 reltuples / relkind。 */
    @Test
    void analyzeInfoQueryWorks() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("SELECT reltuples, relkind FROM pg_catalog.pg_class "
                    + "WHERE oid = 'main.an_t'::pg_catalog.regclass");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("TDCZ", PgWireClient.types(frames),
                    "ANALYZE 的取 reltuples/relkind 语句必须可用，实际=" + PgWireClient.types(frames)
                            + " / " + PgWireClient.errorField(frames, (char) 77));
            List<byte[]> row = PgWireClient.dataRowValues(PgWireClient.ofType(frames, 'D').get(0));
            assertEquals(2, row.size(), "应回 reltuples 与 relkind 两列");
            assertFalse(row.get(1) == null, "relkind 不应为 NULL（外部表分析需要它）");
        }
    }

    /** regclass 转换被翻译成真正的 oid 查询（两种形态都可用）。 */
    @Test
    void regclassCastResolvesToRealOid() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("select 'main.an_t'::pg_catalog.regclass");
            List<PgWireClient.Frame> byCast = client.readUntilReadyForQuery();
            assertEquals("TDCZ", PgWireClient.types(byCast));
            long oid = Long.parseLong(firstValue(byCast));
            assertTrue(oid > 0, "应解析出真实 oid，实际=" + oid);

            // oid 形式与表名形式都要能取到大小（宏对两种入参都支持）
            client.sendQuery("select pg_catalog.pg_relation_size(" + oid + ")");
            List<PgWireClient.Frame> byOid = client.readUntilReadyForQuery();
            assertEquals("TDCZ", PgWireClient.types(byOid), "按 oid 取大小应可用");

            client.sendQuery("select pg_catalog.pg_relation_size('an_t')");
            List<PgWireClient.Frame> byName = client.readUntilReadyForQuery();
            assertEquals("TDCZ", PgWireClient.types(byName), "按表名取大小应可用");

            assertEquals(firstValue(byOid), firstValue(byName),
                    "按 oid 与按表名应得到同一个估算大小");
        }
    }

    /** ANALYZE 的第三步是采样：走游标（B1），这里确认 fdw 的采样查询形状可用。 */
    @Test
    void analyzeSampleQueryShapeWorks() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("DECLARE an_c CURSOR FOR SELECT id, v FROM main.an_t");
            assertEquals("CZ", PgWireClient.types(client.readUntilReadyForQuery()));

            client.sendQuery("FETCH 100 FROM an_c");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();
            assertEquals(100, PgWireClient.ofType(frames, 'D').size(), "采样应按批返回行");
            assertEquals("FETCH 100", PgWireClient.ofType(frames, 'C').get(0).text());

            client.sendQuery("CLOSE an_c");
            client.readUntilReadyForQuery();
        }
    }
}
