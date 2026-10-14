package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.server.DBSession;
import org.slackerdb.dbserver.test.support.PgWireClient;

import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 语句缓存生命周期与容量契约。
 *
 * <p><b>被保护的缺陷</b>：未命名语句会被下一个 Parse 覆盖，覆盖时旧句柄由
 * {@code DBSession#closeIfOrphaned} 回收 —— 这条链路已有测试覆盖。
 * 但<b>带名字</b>的语句不会被覆盖：postgres_fdw 每条 DML 都用一个全新的名字
 * （{@code pgsql_fdw_prep_<n>}）Prepare 一次，长连接上缓存只增不减，
 * 每个条目都占着一条 DuckDB 语句句柄。</p>
 *
 * <p><b>契约</b>：</p>
 * <ol>
 *   <li>反复 Parse 同一个名字不增长缓存（复用）；</li>
 *   <li>新增名字到 {@link DBSession#MAX_PREPARED_STATEMENTS} 为止都成功；</li>
 *   <li>再新增名字回 {@code 54000 program_limit_exceeded}（<b>不静默淘汰</b>）；</li>
 *   <li>超限之后复用已有名字、以及简单查询仍然正常。</li>
 * </ol>
 */
public class PreparedStatementLimitTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("stmtlimit");
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
        return new PgWireClient("127.0.0.1", dbPort, "stmtlimit", 10_000);
    }

    /** 取当前唯一活跃会话（用例串行执行，同一时刻只有一个客户端连接）。 */
    private static DBSession activeSession() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            DBSession found = null;
            for (DBSession session : dbInstance.dbSessions.values()) {
                if (session.channel != null && session.channel.isActive()) {
                    if (found != null) {
                        found = null;      // 还有多个活跃会话：等它们收敛
                        break;
                    }
                    found = session;
                }
            }
            if (found != null) {
                return found;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("没有找到唯一活跃会话");
    }

    /** 发一条 Parse + Sync 并返回报文序列。 */
    private static List<PgWireClient.Frame> parseAndSync(PgWireClient client, String name, String sql)
            throws Exception {
        client.sendParse(name, sql);
        client.sendSync();
        return client.readUntilReadyForQuery();
    }

    @Test
    void repeatedParseOfSameNameDoesNotGrowCache() throws Exception {
        try (PgWireClient client = newClient()) {
            for (int i = 0; i < 50; i++) {
                List<PgWireClient.Frame> frames = parseAndSync(client, "same_s", "SELECT " + i);
                assertEquals(0, PgWireClient.ofType(frames, 'E').size(),
                        "复用同一个语句名不应报错；第 " + i + " 次=" + PgWireClient.types(frames));
            }
            assertEquals(1, activeSession().preparedStatementCount(),
                    "反复 Parse 同一个名字必须只占一个缓存条目（复用，而不是每次新增）");
        }
    }

    @Test
    void distinctNamesGrowCacheUntilLimitThenRejectedWith54000() throws Exception {
        try (PgWireClient client = newClient()) {
            for (int i = 0; i < DBSession.MAX_PREPARED_STATEMENTS; i++) {
                List<PgWireClient.Frame> frames = parseAndSync(client, "s_" + i, "SELECT 1");
                assertEquals(0, PgWireClient.ofType(frames, 'E').size(),
                        "未到上限前必须全部成功；第 " + i + " 个=" + PgWireClient.types(frames));
            }
            assertEquals(DBSession.MAX_PREPARED_STATEMENTS, activeSession().preparedStatementCount());

            // 第 MAX+1 个新名字：必须明确报错，而不是静默淘汰正在使用的语句。
            // 注意只有 ErrorResponse + ReadyForQuery：限流发生在解析之前，所以没有 ParseComplete。
            List<PgWireClient.Frame> overflow = parseAndSync(client, "s_overflow", "SELECT 1");
            assertEquals("EZ", PgWireClient.types(overflow),
                    "超限时应只回 ErrorResponse + ReadyForQuery；实际=" + PgWireClient.types(overflow));
            assertEquals("54000", PgWireClient.errorField(overflow, 'C'),
                    "超出语句缓存上限应回 54000；实际=" + PgWireClient.types(overflow));
            assertNotNull(PgWireClient.errorField(overflow, 'M'));

            // 复用已有名字不受限流影响
            List<PgWireClient.Frame> reuse = parseAndSync(client, "s_0", "SELECT 2");
            assertEquals(0, PgWireClient.ofType(reuse, 'E').size(),
                    "复用已有语句名不应受上限影响；实际=" + PgWireClient.types(reuse));
        }
    }

    @Test
    void connectionStaysUsableAfterLimitError() throws Exception {
        try (PgWireClient client = newClient()) {
            for (int i = 0; i < DBSession.MAX_PREPARED_STATEMENTS; i++) {
                parseAndSync(client, "u_" + i, "SELECT 1");
            }
            List<PgWireClient.Frame> overflow = parseAndSync(client, "u_overflow", "SELECT 1");
            assertEquals("54000", PgWireClient.errorField(overflow, 'C'));

            // 简单查询路径不受语句缓存上限影响
            client.sendQuery("SELECT 'ok'::varchar AS s");
            List<PgWireClient.Frame> simple = client.readUntilReadyForQuery();
            assertEquals(0, PgWireClient.ofType(simple, 'E').size(),
                    "简单查询不创建语句缓存，不应受影响；实际=" + PgWireClient.types(simple));
            assertEquals("ok", PgWireClient.textValue(
                    PgWireClient.dataRowValues(PgWireClient.ofType(simple, 'D').get(0)).get(0)));
        }
    }
}
