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

/**
 * {@code DEALLOCATE} 契约。
 *
 * <p><b>被测缺陷</b>（改造前）：{@code DEALLOCATE ALL} 被直接透传给 DuckDB，
 * 得到 {@code 42601 Parser Error: DEALLOCATE requires a name}。
 * 而这是 {@code postgres_fdw} <b>出错清理路径必发</b>的语句
 * （{@code connection.c} 里 {@code have_prep_stmt && have_error} 时发 {@code DEALLOCATE ALL}），
 * 于是"远端出错后清理"这一步必然再错一次。</p>
 *
 * <p>{@code DEALLOCATE <name>} 则相反：DuckDB 会静默成功，但只作用于它自己的 SQL 级
 * {@code PREPARE} 命名空间，动不了协议级缓存里的同名语句（fdw 重建预备语句前会发它）。</p>
 *
 * <p><b>契约</b>：两种形态都必须成功（无错误应答），并且都要真正释放协议级缓存。</p>
 */
public class DeallocateAllTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("deallocate");
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
        return new PgWireClient("127.0.0.1", dbPort, "deallocate", 10_000);
    }

    private static DBSession activeSession() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            DBSession found = null;
            for (DBSession session : dbInstance.dbSessions.values()) {
                if (session.channel != null && session.channel.isActive()) {
                    if (found != null) {
                        found = null;
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

    /** 简单查询路径：DEALLOCATE ALL 必须成功，并清空协议级语句缓存。 */
    @Test
    void deallocateAllOnSimpleQueryPathSucceedsAndClearsCache() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendParse("s1", "SELECT 1");
            client.sendSync();
            client.readUntilReadyForQuery();
            client.sendParse("s2", "SELECT 2");
            client.sendSync();
            client.readUntilReadyForQuery();
            assertEquals(2, activeSession().preparedStatementCount());

            client.sendQuery("DEALLOCATE ALL");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("CZ", PgWireClient.types(frames),
                    "DEALLOCATE ALL 必须成功（旧行为是 42601），实际=" + PgWireClient.types(frames));
            assertEquals("DEALLOCATE ALL", PgWireClient.ofType(frames, 'C').get(0).text());
            assertEquals(0, activeSession().preparedStatementCount(),
                    "DEALLOCATE ALL 之后协议级语句缓存必须清空");
        }
    }

    /** 扩展协议路径：Parse + Bind + Execute 一条 DEALLOCATE ALL 也必须成功并清空缓存。 */
    @Test
    void deallocateAllOnExtendedPathSucceedsAndClearsCache() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendParse("e1", "SELECT 1");
            client.sendSync();
            client.readUntilReadyForQuery();
            assertEquals(1, activeSession().preparedStatementCount());

            client.sendParse("d_all", "DEALLOCATE ALL");
            client.sendBind("d_all_p", "d_all");
            client.sendExecute("d_all_p", 0);
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("12CZ", PgWireClient.types(frames),
                    "扩展协议下的 DEALLOCATE ALL 也必须成功，实际=" + PgWireClient.types(frames));
            assertEquals("DEALLOCATE ALL", PgWireClient.ofType(frames, 'C').get(0).text());
            assertEquals(0, activeSession().preparedStatementCount());
        }
    }

    /** 带名字的形态（fdw 重建预备语句前会发）：不许报错，并释放协议级缓存里的同名条目。 */
    @Test
    void deallocateNamedStatementReleasesProtocolCacheEntry() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendParse("pgsql_fdw_prep_7", "SELECT 1");
            client.sendSync();
            client.readUntilReadyForQuery();
            assertEquals(1, activeSession().preparedStatementCount());

            client.sendQuery("DEALLOCATE pgsql_fdw_prep_7");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals(0, PgWireClient.ofType(frames, 'E').size(),
                    "DEALLOCATE <name> 不应报错，实际=" + PgWireClient.types(frames));
            assertEquals(0, activeSession().preparedStatementCount(),
                    "DEALLOCATE <name> 必须释放协议级缓存里的同名语句");
        }
    }

    /** 清理之后会话必须还能正常干活（清空缓存不能把会话搞坏）。 */
    @Test
    void sessionUsableAfterDeallocateAll() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("DEALLOCATE ALL");
            client.readUntilReadyForQuery();

            client.sendParse("after_s", "SELECT 'ok'::varchar AS s");
            client.sendBind("after_p", "after_s");
            client.sendDescribe('P', "after_p");
            client.sendExecute("after_p", 0);
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals(0, PgWireClient.ofType(frames, 'E').size(),
                    "DEALLOCATE ALL 之后必须能继续 Parse/Bind/Execute，实际=" + PgWireClient.types(frames));
            assertEquals("ok", PgWireClient.textValue(
                    PgWireClient.dataRowValues(PgWireClient.ofType(frames, 'D').get(0)).get(0)));
        }
    }
}
