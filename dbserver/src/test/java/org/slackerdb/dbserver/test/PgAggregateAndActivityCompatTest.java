package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.test.support.PgWireClient;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code pg_aggregate} 与 {@code pg_stat_activity} 两张 PG 目录关系。
 *
 * <p><b>被测缺陷</b>（同一批客户端元数据查询里实测到）：</p>
 * <ul>
 *   <li>{@code pg_catalog.pg_aggregate} 不存在 —— 客户端枚举聚合函数时会写
 *       {@code pg_proc p JOIN pg_aggregate a ON a.aggfnoid = p.oid}，整条查询报
 *       {@code Table with name pg_aggregate does not exist}。</li>
 *   <li>{@code pg_catalog.pg_stat_activity} 不存在 —— 会话面板写
 *       {@code SELECT sa.* FROM pg_catalog.pg_stat_activity sa}，同样整条报错。</li>
 * </ul>
 *
 * <p><b>契约（刻意的取舍）</b>：两张关系都只保证<b>存在、列与 PG 对齐、零行</b>。
 * 会话的观测与终止<b>不</b>通过 PG 目录伪造 —— SlackDB 自己的管理面才是权威入口
 * （{@code GET /status} 的 sessions、{@code POST /sessions/{id}/kill}、SQL 的
 * {@code KILL <sessionId>}）。因此客户端的会话面板显示"没有会话"，而不是报错，
 * 也不会出现"看起来杀掉了、其实没杀"的假成功。</p>
 */
public class PgAggregateAndActivityCompatTest {

    private static final String DATABASE = "pgactcompat";

    /** PG 16 {@code pg_stat_activity} 的列，顺序必须一致（客户端 {@code sa.*} 按它展开）。 */
    private static final List<String> PG_STAT_ACTIVITY_COLUMNS = List.of(
            "datid", "datname", "pid", "leader_pid", "usesysid", "usename",
            "application_name", "client_addr", "client_hostname", "client_port",
            "backend_start", "xact_start", "query_start", "state_change",
            "wait_event_type", "wait_event", "state", "backend_xid", "backend_xmin",
            "query_id", "query", "backend_type");

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData(DATABASE);
        cfg.setSqlHistory("OFF");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    private static PgWireClient newClient() throws Exception {
        return new PgWireClient("127.0.0.1", dbPort, DATABASE, 10_000);
    }

    // ------------------------------------------------------------------
    // pg_aggregate
    // ------------------------------------------------------------------

    /** 客户端枚举聚合函数的真实形态：{@code pg_proc JOIN pg_aggregate} 必须能跑（结果为空）。 */
    @Test
    void pgAggregateExistsAsEmptyRelation() throws Exception {
        try (PgWireClient client = newClient()) {
            List<String> count = firstColumn(client,
                    "SELECT count(*) FROM pg_catalog.pg_aggregate");
            assertEquals(List.of("0"), count, "pg_aggregate 目前必须是空关系（与 pg_proc 同口径）");

            List<String> joined = firstColumn(client,
                    "SELECT p.proname FROM pg_catalog.pg_proc p "
                            + "JOIN pg_catalog.pg_aggregate a ON a.aggfnoid = p.oid");
            assertEquals(0, joined.size(),
                    "pg_proc JOIN pg_aggregate 应返回空结果，而不是报 'pg_aggregate does not exist'");
        }
    }

    // ------------------------------------------------------------------
    // pg_stat_activity
    // ------------------------------------------------------------------

    /** 客户端原始查询形态 {@code SELECT sa.* FROM pg_catalog.pg_stat_activity sa} 必须能跑（零行）。 */
    @Test
    void statActivityExistsAsEmptyRelationWithPgShape() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("SELECT sa.* FROM pg_catalog.pg_stat_activity sa");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();
            assertEquals("TCZ", PgWireClient.types(frames),
                    "会话查询必须可用且返回零行（旧行为是 pg_stat_activity 不存在），实际="
                            + PgWireClient.types(frames) + " / " + PgWireClient.errorField(frames, 'M'));

            List<PgWireClient.FieldDesc> fields = PgWireClient.parseRowDescription(
                    PgWireClient.ofType(frames, 'T').get(0));
            List<String> names = new ArrayList<>();
            for (PgWireClient.FieldDesc field : fields) {
                names.add(field.name);
            }
            assertEquals(PG_STAT_ACTIVITY_COLUMNS, names,
                    "sa.* 必须按 PG 16 的列展开（客户端按列名/列序取值）");

            // 抽查类型：pid 是 int4，backend_start 是 timestamptz，backend_type 是文本
            // （DuckDB 的 TEXT 就是 VARCHAR，声明成 1043；PG 那边是 25，两者都是字符串，客户端等价）
            assertEquals(23, fields.get(2).typeOid, "pid 必须是 int4(23)");
            assertEquals(1184, fields.get(10).typeOid, "backend_start 必须是 timestamptz(1184)");
            assertEquals(1043, fields.get(21).typeOid, "backend_type 必须是文本");
        }
    }

    /** 零行的另一面：{@code count(*)} 为 0，且没有编造出来的行。 */
    @Test
    void statActivityHasNoFabricatedRows() throws Exception {
        try (PgWireClient client = newClient()) {
            assertEquals(List.of("0"), firstColumn(client,
                    "SELECT count(*) FROM pg_catalog.pg_stat_activity"));
            // 与会话管理相关的列也必须是空的（这里不伪造"在线会话"）
            assertEquals(0, firstColumn(client,
                    "SELECT pid FROM pg_catalog.pg_stat_activity WHERE state = 'active'").size());
        }
    }

    // ------------------------------------------------------------------ 工具

    private static List<String> firstColumn(PgWireClient client, String sql) throws Exception {
        client.sendQuery(sql);
        List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();
        String types = PgWireClient.types(frames);
        assertTrue(!types.contains("E"),
                sql + " 失败: " + PgWireClient.errorField(frames, 'M'));
        assertTrue(types.endsWith("CZ"), sql + " 缺 ReadyForQuery（报文=" + types + "）");

        List<String> values = new ArrayList<>();
        for (PgWireClient.Frame row : PgWireClient.ofType(frames, 'D')) {
            values.add(PgWireClient.textValue(PgWireClient.dataRowValues(row).get(0)));
        }
        return values;
    }
}
