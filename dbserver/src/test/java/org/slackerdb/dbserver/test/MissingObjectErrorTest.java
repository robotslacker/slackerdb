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

/**
 * Describe / Close 作用于"不存在的对象"时的协议契约。
 *
 * <p><b>被测缺陷</b>（改造前）：{@code Describe('S')} 对不存在的语句回
 * {@code ParameterDescription(0 个参数) + NoData}，{@code Describe('P')} 回 {@code NoData}，
 * {@code Close} 一律回 {@code CloseComplete}。客户端因此无法区分
 * "这个语句没有结果集 / 已经关掉了" 与 "这个语句/门户根本不存在" ——
 * 驱动与连接池会把"句柄早就没了"的状态掩盖下去。</p>
 *
 * <p><b>契约</b>（PG 协议）：</p>
 * <ul>
 *   <li>{@code Describe('S')} 不存在的语句 → {@code 26000 invalid_sql_statement_name}；</li>
 *   <li>{@code Describe('P')} 不存在的门户 → {@code 34000 invalid_cursor_name}；</li>
 *   <li>{@code Close('S')} / {@code Close('P')} 不存在的对象 → {@code 26000} / {@code 34000}；</li>
 *   <li>错误之后进入"丢弃到 Sync"，Sync 之后会话照常可用。</li>
 * </ul>
 */
public class MissingObjectErrorTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("missingobject");
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
        return new PgWireClient("127.0.0.1", dbPort, "missingobject", 10_000);
    }

    @Test
    void describeMissingStatementReturns26000() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendDescribe('S', "never_parsed_statement");
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("EZ", PgWireClient.types(frames),
                    "描述不存在的语句应回错误（而不是 ParameterDescription+NoData），实际="
                            + PgWireClient.types(frames));
            assertEquals("26000", PgWireClient.errorField(frames, 'C'),
                    "应回 26000 invalid_sql_statement_name");
        }
    }

    @Test
    void describeMissingPortalReturns34000() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendDescribe('P', "never_bound_portal");
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("EZ", PgWireClient.types(frames),
                    "描述不存在的门户应回错误（而不是 NoData），实际=" + PgWireClient.types(frames));
            assertEquals("34000", PgWireClient.errorField(frames, 'C'),
                    "应回 34000 invalid_cursor_name");
        }
    }

    @Test
    void closeMissingPortalReturns34000() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendClose('P', "never_bound_portal");
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("EZ", PgWireClient.types(frames),
                    "关闭不存在的门户应回错误，实际=" + PgWireClient.types(frames));
            assertEquals("34000", PgWireClient.errorField(frames, 'C'));
        }
    }

    /** 错误之后（Sync 同步点之后）会话必须照常可用。 */
    @Test
    void sessionUsableAfterMissingObjectErrors() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendDescribe('S', "nope_s");
            client.sendDescribe('P', "nope_p");
            client.sendClose('S', "nope_c");
            client.sendSync();
            client.readUntilReadyForQuery();

            client.sendParse("ok_s", "SELECT 'ok'::varchar AS s");
            client.sendBind("ok_p", "ok_s");
            client.sendDescribe('P', "ok_p");
            client.sendExecute("ok_p", 0);
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals(0, PgWireClient.ofType(frames, 'E').size(),
                    "Sync 之后不应再有错误；实际=" + PgWireClient.types(frames));
            assertEquals("ok", PgWireClient.textValue(
                    PgWireClient.dataRowValues(PgWireClient.ofType(frames, 'D').get(0)).get(0)));
        }
    }
}
