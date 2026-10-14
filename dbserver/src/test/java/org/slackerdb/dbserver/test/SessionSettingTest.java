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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PG 特有的会话设置/复位语句契约（{@code SET} / {@code RESET} / {@code RESET ALL} / {@code DISCARD ALL}）。
 *
 * <p><b>被测缺陷</b>（改造前）：这些语句被直接透传给 DuckDB，于是：</p>
 * <ul>
 *   <li>{@code SET statement_timeout = 0} → {@code 3F000 unrecognized configuration parameter}；</li>
 *   <li>{@code SET LOCAL x = y} → {@code 0A000 SET LOCAL is not implemented}；</li>
 *   <li>{@code RESET ALL} → {@code 0A000 Can only SET or RESET a variable}；</li>
 *   <li>{@code DISCARD ALL} → {@code 42601 syntax error at or near "DISCARD"}。</li>
 * </ul>
 * <p>而连接池 / ORM / IDE 在建立连接时会例行发送它们。</p>
 *
 * <p><b>契约</b>：接受的语句必须回成功，并<b>用 NOTICE 明确说明不会生效</b>（不静默假装）；
 * DuckDB 自己认识的参数必须继续透传；未知参数继续报错；{@code DISCARD ALL} 在事务块里回 {@code 25001}。</p>
 */
public class SessionSettingTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("sessionsetting");
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
        return new PgWireClient("127.0.0.1", dbPort, "sessionsetting", 10_000);
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

    private static String noticeText(List<PgWireClient.Frame> frames) {
        List<PgWireClient.Frame> notices = PgWireClient.ofType(frames, 'N');
        return notices.isEmpty() ? null : notices.get(0).text();
    }

    @Test
    void setPgOnlyParameterIsAcceptedWithNotice() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("SET statement_timeout = 0");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("NCZ", PgWireClient.types(frames),
                    "应为 Notice + CommandComplete + ReadyForQuery（旧行为是 3F000），实际="
                            + PgWireClient.types(frames));
            assertEquals("SET", PgWireClient.ofType(frames, 'C').get(0).text());
            assertTrue(noticeText(frames).contains("accepted but has no effect"),
                    "必须用 NOTICE 明确说明不会生效，而不是静默假装；实际=" + noticeText(frames));
            assertEquals("0", activeSession().acceptedSettings().get("statement_timeout"));
        }
    }

    @Test
    void duckdbOwnParameterStillPassesThroughWithoutNotice() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("SET threads = 2");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("CZ", PgWireClient.types(frames),
                    "DuckDB 自己认识的参数必须继续透传（不得被接管，也不该多出 NOTICE），实际="
                            + PgWireClient.types(frames));
            assertFalse(activeSession().acceptedSettings().containsKey("threads"));
        }
    }

    @Test
    void unknownParameterStillFailsHonestly() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("SET definitely_not_a_parameter = 1");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("EZ", PgWireClient.types(frames),
                    "未知参数必须继续报错（不接受、不假装），实际=" + PgWireClient.types(frames));
            assertFalse(activeSession().acceptedSettings().containsKey("definitely_not_a_parameter"));
        }
    }

    @Test
    void setLocalIsAcceptedWithExplicitNotice() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("SET LOCAL statement_timeout = 5000");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("NCZ", PgWireClient.types(frames),
                    "SET LOCAL 应被接受（旧行为是 0A000），实际=" + PgWireClient.types(frames));
            assertTrue(noticeText(frames).contains("SET LOCAL is not supported"),
                    "必须明确说明事务级 SET 不受支持；实际=" + noticeText(frames));
        }
    }

    @Test
    void resetAllAndResetOneClearAcceptedSettings() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("SET statement_timeout = 100");
            client.readUntilReadyForQuery();
            client.sendQuery("SET lock_timeout = 200");
            client.readUntilReadyForQuery();
            assertEquals(2, activeSession().acceptedSettings().size());

            client.sendQuery("RESET statement_timeout");
            List<PgWireClient.Frame> resetOne = client.readUntilReadyForQuery();
            assertEquals("CZ", PgWireClient.types(resetOne),
                    "RESET <name> 应成功（旧行为是 3F000），实际=" + PgWireClient.types(resetOne));
            assertEquals("RESET", PgWireClient.ofType(resetOne, 'C').get(0).text());
            assertEquals(1, activeSession().acceptedSettings().size());

            client.sendQuery("RESET ALL");
            List<PgWireClient.Frame> resetAll = client.readUntilReadyForQuery();
            assertEquals("CZ", PgWireClient.types(resetAll),
                    "RESET ALL 应成功（旧行为是 0A000），实际=" + PgWireClient.types(resetAll));
            assertEquals("RESET", PgWireClient.ofType(resetAll, 'C').get(0).text());
            assertEquals(0, activeSession().acceptedSettings().size());
        }
    }

    @Test
    void discardAllResetsSessionState() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendParse("d1", "SELECT 1");
            client.sendSync();
            client.readUntilReadyForQuery();
            client.sendQuery("SET statement_timeout = 100");
            client.readUntilReadyForQuery();
            assertEquals(1, activeSession().preparedStatementCount());
            assertEquals(1, activeSession().acceptedSettings().size());

            client.sendQuery("DISCARD ALL");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("CZ", PgWireClient.types(frames),
                    "DISCARD ALL 应成功（旧行为是 42601），实际=" + PgWireClient.types(frames));
            assertEquals("DISCARD ALL", PgWireClient.ofType(frames, 'C').get(0).text());
            assertEquals(0, activeSession().preparedStatementCount(), "DISCARD ALL 应清空协议级语句缓存");
            assertEquals(0, activeSession().acceptedSettings().size(), "DISCARD ALL 应清空会话设置");
        }
    }

    @Test
    void discardAllInsideTransactionReturns25001() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery("BEGIN");
            client.readUntilReadyForQuery();

            client.sendQuery("DISCARD ALL");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("EZ", PgWireClient.types(frames),
                    "事务块里的 DISCARD ALL 必须报错（PG 语义 25001），实际=" + PgWireClient.types(frames));
            assertEquals("25001", PgWireClient.errorField(frames, 'C'));

            client.sendQuery("ROLLBACK");
            client.readUntilReadyForQuery();
        }
    }

    @Test
    void extendedProtocolPathAlsoAcceptsTheseStatements() throws Exception {
        try (PgWireClient client = newPgClient()) {
            client.sendParse("e_set", "SET statement_timeout = 42");
            client.sendBind("e_set_p", "e_set");
            client.sendExecute("e_set_p", 0);
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("12NCZ", PgWireClient.types(frames),
                    "扩展协议下的 SET 也必须成功并带 NOTICE，实际=" + PgWireClient.types(frames));
            assertEquals("SET", PgWireClient.ofType(frames, 'C').get(0).text());
            assertEquals("42", activeSession().acceptedSettings().get("statement_timeout"));
        }
    }

    // ================================================================
    // SHOW 覆盖（DuckDB 不认识这些 PG 参数名，会把参数名当表名 → 42P01）
    // ================================================================

    @Test
    void showPgOnlyParameterReturnsSynthesizedRow() throws Exception {
        try (PgWireClient client = newPgClient()) {
            client.sendQuery("SHOW statement_timeout");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("TDCZ", PgWireClient.types(frames),
                    "SHOW 应回 行描述+数据行+CommandComplete+ReadyForQuery（旧行为是 42P01），实际="
                            + PgWireClient.types(frames));
            List<PgWireClient.FieldDesc> fields =
                    PgWireClient.parseRowDescription(PgWireClient.ofType(frames, 'T').get(0));
            assertEquals(1, fields.size());
            assertEquals("statement_timeout", fields.get(0).name);
            assertEquals(25, fields.get(0).typeOid, "SHOW 的列应为 text");
            assertEquals(0, fields.get(0).formatCode);
            assertEquals("0", PgWireClient.textValue(
                    PgWireClient.dataRowValues(PgWireClient.ofType(frames, 'D').get(0)).get(0)));
            assertEquals("SHOW", PgWireClient.ofType(frames, 'C').get(0).text());
        }
    }

    @Test
    void showReflectsEarlierSetAndServerVersion() throws Exception {
        try (PgWireClient client = newPgClient()) {
            client.sendQuery("SET statement_timeout = 1234");
            client.readUntilReadyForQuery();

            client.sendQuery("SHOW statement_timeout");
            List<PgWireClient.Frame> showSet = client.readUntilReadyForQuery();
            assertEquals("1234", PgWireClient.textValue(
                    PgWireClient.dataRowValues(PgWireClient.ofType(showSet, 'D').get(0)).get(0)),
                    "SHOW 必须反映本会话 SET 过的值");

            client.sendQuery("SHOW server_version");
            List<PgWireClient.Frame> showVersion = client.readUntilReadyForQuery();
            assertEquals("15.0", PgWireClient.textValue(
                    PgWireClient.dataRowValues(PgWireClient.ofType(showVersion, 'D').get(0)).get(0)),
                    "SHOW server_version 必须与握手期下发的 server_version 一致");
        }
    }

    /** 扩展协议：Describe('P') 报出的行描述必须与 Execute 发出的数据行一致。 */
    @Test
    void showOnExtendedPathUsesDescribeMetadata() throws Exception {
        try (PgWireClient client = newPgClient()) {
            client.sendParse("sh_s", "SHOW statement_timeout");
            client.sendBind("sh_p", "sh_s");
            client.sendDescribe('P', "sh_p");
            client.sendExecute("sh_p", 0);
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("12TDCZ", PgWireClient.types(frames),
                    "扩展协议下 SHOW 的应答序列不符合预期，实际=" + PgWireClient.types(frames));
            List<PgWireClient.FieldDesc> fields =
                    PgWireClient.parseRowDescription(PgWireClient.ofType(frames, 'T').get(0));
            assertEquals("statement_timeout", fields.get(0).name);
            assertEquals(0, fields.get(0).formatCode);
            assertEquals("0", PgWireClient.textValue(
                    PgWireClient.dataRowValues(PgWireClient.ofType(frames, 'D').get(0)).get(0)));
            assertEquals("SHOW", PgWireClient.ofType(frames, 'C').get(0).text());
        }
    }

    /** 反向约束：本服务端不回答的参数不许编答案，继续按 DuckDB 的行为报错。 */
    @Test
    void showOfUnknownParameterStillFailsHonestly() throws Exception {
        try (PgWireClient client = newPgClient()) {
            client.sendQuery("SHOW definitely_not_a_parameter");
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("EZ", PgWireClient.types(frames),
                    "不能给不认识的参数编答案，实际=" + PgWireClient.types(frames));
        }
    }

    private static PgWireClient newPgClient() throws Exception {
        return new PgWireClient("127.0.0.1", dbPort, "sessionsetting", 10_000);
    }
}
