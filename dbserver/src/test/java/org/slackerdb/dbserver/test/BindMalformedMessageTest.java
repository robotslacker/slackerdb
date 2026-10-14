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
 * Bind 报文畸形时的行为契约。
 *
 * <p><b>被测缺陷</b>（改造前）：{@code BindRequest.process} 用 {@code formatCodes[i]}
 * 直接索引参数格式码，且只 {@code catch (SQLException)}。于是"格式码个数与参数个数不匹配"
 * （协议违规，但真实客户端偶有发出）会抛 {@code ArrayIndexOutOfBoundsException}：
 * 异常一路逃逸到 Netty，服务端<b>不产生任何应答</b>，客户端挂到空闲超时（默认 600s）——
 * 这是最难排查的一类故障。截断报文同样被 {@code Arrays.copyOfRange} 静默补 0 接受。</p>
 *
 * <p><b>契约</b>：报文畸形一律回 {@code 08P01 protocol_violation}，
 * 连接保持可用（Sync 之后照常工作），并且不产生任何半截应答。</p>
 */
public class BindMalformedMessageTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("bindmalformed");
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
        return new PgWireClient("127.0.0.1", dbPort, "bindmalformed", 10_000);
    }

    /** 格式码个数（2）与参数个数（1）不匹配：必须回 08P01，而不是挂死。 */
    @Test
    void mismatchedFormatCodeCountReturnsProtocolViolation() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendParse("mf_s", "SELECT $1::integer AS n", 23);
            client.sendBindWithFormatCodeCountMismatch("mf_p", "mf_s", new int[]{0, 0}, 1);
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("08P01", PgWireClient.errorField(frames, 'C'),
                    "格式码个数与参数个数不匹配属于协议违规，应回 08P01；实际报文序列="
                            + PgWireClient.types(frames));
            // Parse 成功 → '1'，Bind 失败 → 'E'，Sync → 'Z'；不得出现 '2'(BindComplete)
            assertEquals("1EZ", PgWireClient.types(frames));
        }
    }

    /** 参数声明长度超出报文实际长度（截断）：必须回 08P01。 */
    @Test
    void truncatedParameterReturnsProtocolViolation() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendParse("tr_s", "SELECT $1::integer AS n", 23);
            client.sendBindWithTruncatedParameter("tr_p", "tr_s", 8, new byte[]{0, 0, 1});
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals("08P01", PgWireClient.errorField(frames, 'C'),
                    "声明的参数长度超出报文长度应回 08P01；实际报文序列=" + PgWireClient.types(frames));
            assertEquals("1EZ", PgWireClient.types(frames));
        }
    }

    /** 畸形 Bind 之后连接必须仍然可用（Sync 之后照常查询）。 */
    @Test
    void connectionStaysUsableAfterMalformedBind() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendParse("um_s", "SELECT $1::integer AS n", 23);
            client.sendBindWithFormatCodeCountMismatch("um_p", "um_s", new int[]{0, 0}, 1);
            client.sendSync();
            client.readUntilReadyForQuery();

            // 同一条语句重新 Parse + Bind（这次合法：0 个参数、1 个 NULL 参数用合法个数）
            client.sendParse("ok_s", "SELECT 7::integer AS n");
            client.sendBind("ok_p", "ok_s");
            client.sendDescribe('P', "ok_p");
            client.sendExecute("ok_p", 0);
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals(0, PgWireClient.ofType(frames, 'E').size(),
                    "Sync 之后不应再有错误；实际=" + PgWireClient.types(frames));
            assertEquals("7", PgWireClient.textValue(
                    PgWireClient.dataRowValues(PgWireClient.ofType(frames, 'D').get(0)).get(0)));
        }
    }

    /** 反向约束：合法的格式码个数（广播 1 / 等于参数个数）不能被这次加固改坏。 */
    @Test
    void wellFormedFormatCodeCountsStillWork() throws Exception {
        try (PgWireClient client = newClient()) {
            // 广播：1 个格式码 + 2 个 NULL 参数
            client.sendParse("wf_s", "SELECT $1::integer AS a, $2::integer AS b", 23);
            client.sendBindWithFormatCodeCountMismatch("wf_p", "wf_s", new int[]{0}, 2);
            client.sendDescribe('P', "wf_p");
            client.sendExecute("wf_p", 0);
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            assertEquals(0, PgWireClient.ofType(frames, 'E').size(),
                    "1 个格式码广播 + 2 个参数是合法的；实际=" + PgWireClient.types(frames));
            assertEquals(2, PgWireClient.parseRowDescription(PgWireClient.ofType(frames, 'T').get(0)).size());
        }
    }
}
