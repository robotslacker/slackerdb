package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.server.SlackerCatalog;
import org.slackerdb.dbserver.test.support.PgWireClient;

import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 客户端统计面板依赖的 {@code pg_stat_get_*} 函数（DuckDB 一个都没有）。
 *
 * <p><b>被测缺陷</b>（改造前）：客户端展示索引/表的统计信息时会直接调</p>
 * [source,sql]
 * ----
 * SELECT ... pg_catalog.pg_relation_size(i.indexrelid) AS index_rel_size,
 *            pg_catalog.pg_stat_get_numscans(i.indexrelid) AS index_num_scans
 * FROM pg_catalog.pg_index i ...
 * ----
 * <p>DuckDB 的 {@code pg_catalog} 下没有任何 {@code pg_stat_get_*} 函数，也不存在
 * {@code pg_stat_*} 视图，于是整条元数据查询报
 * {@code Catalog Error: Scalar Function with name pg_stat_get_numscans does not exist}，
 * 面板直接空白（缺的不是一列数据，而是整条查询）。</p>
 *
 * <p><b>契约</b>：{@code pg_catalog.pg_stat_get_*(oid)} 一律可用，按 PG 的真实返回类型
 * 回答 <b>NULL</b>（= 未知；DuckDB 不记录运行期统计）。特别地<b>不能回答 0</b>：
 * 0 在 PG 里表示"确实一次都没发生过"，会被读成"这个索引从未被使用"。</p>
 */
public class PgStatFunctionCompatTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("pgstatcompat");
        cfg.setSqlHistory("OFF");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();

        try (PgWireClient client = newClient()) {
            client.sendQuery("CREATE OR REPLACE TABLE stat_t AS SELECT range AS id, 'v' || range AS v FROM range(10)");
            client.readUntilReadyForQuery();
            client.sendQuery("CREATE INDEX stat_t_v_idx ON stat_t(v)");
            client.readUntilReadyForQuery();
        }
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    private static PgWireClient newClient() throws Exception {
        return new PgWireClient("127.0.0.1", dbPort, "pgstatcompat", 10_000);
    }

    /** 客户端索引面板的真实查询形态：必须整体可用，统计列是 NULL。 */
    @Test
    void clientIndexStatisticsQueryWorks() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("SELECT i.indexrelid AS index_oid, "
                    + "pg_catalog.pg_relation_size(i.indexrelid) AS index_rel_size, "
                    + "pg_catalog.pg_stat_get_numscans(i.indexrelid) AS index_num_scans, "
                    + "pg_catalog.pg_stat_get_tuples_returned(i.indexrelid) AS index_tuples_read "
                    + "FROM pg_catalog.pg_index i");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("TDCZ", PgWireClient.types(frames),
                    "索引统计查询必须可用（旧行为是 pg_stat_get_numscans 不存在），实际="
                            + PgWireClient.types(frames) + " / "
                            + PgWireClient.errorField(frames, 'M'));

            List<PgWireClient.FieldDesc> fields = PgWireClient.parseRowDescription(
                    PgWireClient.ofType(frames, 'T').get(0));
            assertEquals(4, fields.size(), "应回 4 列");
            for (int i = 2; i < 4; i++) {
                assertEquals(20, fields.get(i).typeOid,
                        fields.get(i).name + " 必须声明成 int8(20)，与 PG 的 bigint 一致");
            }

            List<byte[]> row = PgWireClient.dataRowValues(PgWireClient.ofType(frames, 'D').get(0));
            assertNull(row.get(2), "index_num_scans 必须是 NULL（未知），不能编造 0");
            assertNull(row.get(3), "index_tuples_read 必须是 NULL（未知）");
        }
        // 查询失败过一次也不能影响后续语句（协议不能错位）
        try (PgWireClient client = newClient()) {
            client.sendQuery("SELECT 1");
            assertEquals("TDCZ", PgWireClient.types(client.readUntilReadyForQuery()));
        }
    }

    /** 所有空壳函数都按 PG 的类型回答 NULL：计数类 bigint(20)、时间类 timestamptz(1184)。 */
    @Test
    void everyStatisticFunctionAnswersTypedNull() throws Exception {
        try (PgWireClient client = newClient()) {
            for (String statistic : SlackerCatalog.PG_STAT_COUNTER_FUNCTIONS) {
                assertTypedNull(client, "pg_catalog.pg_stat_get_" + statistic + "(1)", 20);
            }
            for (String statistic : SlackerCatalog.PG_STAT_TIME_FUNCTIONS) {
                assertTypedNull(client, "pg_catalog.pg_stat_get_" + statistic + "(1)", 1184);
            }
        }
    }

    /** 参数既可以是 oid、也可以是表名（客户端两种写法都有）。 */
    @Test
    void statisticFunctionsAcceptTableNameArgument() throws Exception {
        try (PgWireClient client = newClient()) {
            assertTypedNull(client, "pg_catalog.pg_stat_get_numscans('stat_t')", 20);
            assertTypedNull(client, "pg_catalog.pg_stat_get_live_tuples('main.stat_t')", 20);
        }
    }

    /**
     * 回归（同一次报错里出现的 WARN）：{@code select null as x} 的列类型名是 "NULL"。
     *
     * <p>以前这种列落进未识别类型分支，每执行一次就刷一条
     * {@code Not implemented column type: NULL}。值本身仍然正确（NULL），影响的是日志，
     * 但客户端元数据查询里 {@code select null as ...} 很常见，足以把日志刷满。
     * 现在按文本列声明（PG 对裸 NULL 列也是文本化），且不再告警。</p>
     */
    @Test
    void bareNullLiteralColumnIsTextNull() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("SELECT null AS empty_col");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();
            assertEquals("TDCZ", PgWireClient.types(frames));

            PgWireClient.FieldDesc field = PgWireClient.parseRowDescription(
                    PgWireClient.ofType(frames, 'T').get(0)).get(0);
            assertEquals(1043, field.typeOid, "裸 NULL 列应按文本列声明");
            assertNull(PgWireClient.dataRowValues(PgWireClient.ofType(frames, 'D').get(0)).get(0));
        }
    }

    private static void assertTypedNull(PgWireClient client, String call, int expectedTypeOid)
            throws Exception {
        client.sendQuery("SELECT " + call + " AS v");
        List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();
        assertEquals("TDCZ", PgWireClient.types(frames),
                call + " 执行失败: " + PgWireClient.errorField(frames, 'M'));

        PgWireClient.FieldDesc field = PgWireClient.parseRowDescription(
                PgWireClient.ofType(frames, 'T').get(0)).get(0);
        assertEquals(expectedTypeOid, field.typeOid, call + " 的返回类型必须与 PG 一致");
        assertNull(PgWireClient.dataRowValues(PgWireClient.ofType(frames, 'D').get(0)).get(0),
                call + " 必须回答 NULL（未知），不能编造 0 / 空串");
    }
}
