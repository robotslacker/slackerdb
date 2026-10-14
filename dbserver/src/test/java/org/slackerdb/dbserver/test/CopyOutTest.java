package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.test.support.PgWireClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code COPY ... TO STDOUT} 的协议级验收。
 *
 * <p><b>为什么用裸协议</b>：{@code CopyOutResponse('H')} / {@code CopyData('d')} /
 * {@code CopyDone('c')} 这套报文序列只有协议层才看得见；JDBC 与 psql 都会把它折叠成
 * "结果"或"文件"，无法断言"逐行推、推完有 CopyDone"这些契约。</p>
 *
 * <p><b>为什么要往返</b>：COPY OUT 是本项目里少见的"输出必须能被自己的输入侧读回"的功能。
 * 编码（{@code CopyOutEncoder}）与解码（{@code CopyCsvReader}）一旦不互为逆运算，
 * 导出文件就是坏的 —— 而这种坏法用"输出看起来对"是测不出来的，必须真的灌回去比对。</p>
 */
public class CopyOutTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("copyout");
        cfg.setSqlHistory("OFF");
        cfg.setLog_level("INFO");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    private static PgWireClient connect() throws IOException {
        return new PgWireClient("127.0.0.1", dbPort, "copyout", 15_000);
    }

    private static List<PgWireClient.Frame> query(PgWireClient client, String sql) throws IOException {
        client.sendQuery(sql);
        return client.readUntilReadyForQuery();
    }

    /** 把 CopyData 报文的载荷按顺序拼起来（去掉每条的 'd' 与长度前缀）。 */
    private static String copyPayload(List<PgWireClient.Frame> frames) {
        StringBuilder sb = new StringBuilder();
        for (PgWireClient.Frame frame : PgWireClient.ofType(frames, 'd')) {
            sb.append(new String(frame.body, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String commandTag(List<PgWireClient.Frame> frames) {
        List<PgWireClient.Frame> completes = PgWireClient.ofType(frames, 'C');
        return completes.isEmpty() ? null : completes.get(completes.size() - 1).text();
    }

    /** 全部数据行（把多段 CopyData 拼起来再按行切），末尾空行丢弃。 */
    private static List<String> copyLines(List<PgWireClient.Frame> frames) {
        String payload = copyPayload(frames);
        List<String> lines = new ArrayList<>();
        for (String line : payload.split("\n", -1)) {
            lines.add(line);
        }
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static void seed(PgWireClient client) throws IOException {
        query(client, "drop table if exists co;");
        query(client, "create table co(id int, nm varchar, val double, note varchar);");
        query(client, "insert into co values "
                + "(1,'plain',1.5,'simple'),"
                + "(2,'has,comma',2.25,'x'),"
                + "(3,'has\"quote',3.5,'y'),"
                + "(4,NULL,NULL,NULL),"
                + "(5,'',4.5,'');");
    }

    // ================================================================
    // 报文序列
    // ================================================================

    @Test
    void copyToStdoutEmitsResponseDataDoneComplete() throws Exception {
        try (PgWireClient client = connect()) {
            seed(client);
            List<PgWireClient.Frame> frames = query(client, "copy co to stdout with (format csv);");

            String types = PgWireClient.types(frames);
            // H = CopyOutResponse；d…= CopyData；c = CopyDone；C = CommandComplete；Z = ReadyForQuery
            assertTrue(types.startsWith("H"), "应以 CopyOutResponse('H') 开头，实际=" + types);
            assertTrue(types.contains("d"), "应含 CopyData('d')，实际=" + types);
            assertTrue(types.contains("c"), "应含 CopyDone('c')，实际=" + types);
            assertEquals('Z', types.charAt(types.length() - 1), "应以 ReadyForQuery 收尾，实际=" + types);

            // CopyDone 必须紧跟在最后一条 CopyData 之后、CommandComplete 之前
            int lastData = types.lastIndexOf('d');
            int done = types.indexOf('c');
            int complete = types.indexOf('C');
            assertTrue(lastData < done && done < complete,
                    "顺序应为 …CopyData → CopyDone → CommandComplete，实际=" + types);

            assertEquals("COPY 5", commandTag(frames), "命令标签应为 COPY 5");
            assertEquals('I', PgWireClient.readyStatus(frames));
        }
    }

    /** CopyOutResponse 里带的列数必须正确（客户端靠它解析后续 CopyData）。 */
    @Test
    void copyOutResponseCarriesColumnCount() throws Exception {
        try (PgWireClient client = connect()) {
            seed(client);
            List<PgWireClient.Frame> frames = query(client, "copy co to stdout;");
            PgWireClient.Frame response = PgWireClient.ofType(frames, 'H').get(0);
            // body: int8(format) + int16(列数) + int16[N]
            assertEquals(0, response.body[0], "文本格式的格式码应为 0");
            assertEquals(4, response.int16(1), "co 表有 4 列");
        }
    }

    // ================================================================
    // 内容与转义
    // ================================================================

    @Test
    void csvOutputQuotesAndNullsAreCorrect() throws Exception {
        try (PgWireClient client = connect()) {
            seed(client);
            List<String> lines = copyLines(query(client, "copy co to stdout with (format csv);"));

            assertEquals(5, lines.size(), "应有 5 行数据");
            assertEquals("1,plain,1.5,simple", lines.get(0));
            // 含分隔符的值必须加引号
            assertEquals("2,\"has,comma\",2.25,x", lines.get(1));
            // 引号必须翻倍
            assertEquals("3,\"has\"\"quote\",3.5,y", lines.get(2));
            // NULL → 空字段（未加引号）
            assertEquals("4,,,", lines.get(3));
            // 空字符串必须加引号，否则读回来会被当成 NULL（与上一行混同）
            assertEquals("5,\"\",4.5,\"\"", lines.get(4));
        }
    }

    @Test
    void csvHeaderOptionEmitsColumnNames() throws Exception {
        try (PgWireClient client = connect()) {
            seed(client);
            List<PgWireClient.Frame> frames =
                    query(client, "copy co to stdout with (format csv, header true);");
            List<String> lines = copyLines(frames);
            assertEquals(6, lines.size(), "表头 + 5 行数据");
            assertEquals("id,nm,val,note", lines.get(0));
            // 表头不计入 COPY n 的行数
            assertEquals("COPY 5", commandTag(frames));
        }
    }

    @Test
    void textFormatEscapesSpecialCharacters() throws Exception {
        try (PgWireClient client = connect()) {
            query(client, "drop table if exists ct;");
            query(client, "create table ct(a varchar, b varchar);");
            // 值里含：分隔符(tab)、反斜杠、换行、以及字面量 "\N"（不能与 NULL 混淆）
            query(client, "insert into ct values ('x' || chr(9) || 'y', 'back\\\\slash');");
            query(client, "insert into ct values ('line' || chr(10) || 'break', NULL);");

            List<String> lines = copyLines(query(client, "copy ct to stdout;"));
            assertEquals(2, lines.size());
            // 数据库里存的是两个反斜杠（SQL 字面量 'back\\\\slash' 在标准字符串下就是 2 个）。
            // 反斜杠**无条件翻倍**，因此 2 个反斜杠导出成 4 个；读回来正好还原 2 个。
            assertEquals("x\\ty\tback\\\\\\\\slash", lines.get(0));
            // 换行 → \n；NULL → \N（原样写出，不转义）
            assertEquals("line\\nbreak\t\\N", lines.get(1));
        }
    }

    /** 子查询形态：任意查询结果都能导出（此前直接报 /dev/stdout 打不开）。 */
    @Test
    void subqueryFormExportsArbitraryQuery() throws Exception {
        try (PgWireClient client = connect()) {
            seed(client);
            List<String> lines = copyLines(query(client,
                    "copy (select id, nm from co where id <= 2 order by id) to stdout with (format csv);"));
            assertEquals(2, lines.size());
            assertEquals("1,plain", lines.get(0));
            assertEquals("2,\"has,comma\"", lines.get(1));

            // 聚合查询同样可以导出
            List<String> agg = copyLines(query(client,
                    "copy (select count(*) as n from co) to stdout with (format csv);"));
            assertEquals(List.of("5"), agg);
        }
    }

    // ================================================================
    // 往返：导出 → 灌回 → 逐值相等（编码与解码必须互为逆运算）
    // ================================================================

    @Test
    void csvRoundTripPreservesValues() throws Exception {
        try (PgWireClient client = connect()) {
            seed(client);
            String exported = copyPayload(query(client, "copy co to stdout with (format csv);"));

            query(client, "drop table if exists co2;");
            query(client, "create table co2(id int, nm varchar, val double, note varchar);");
            copyIn(client, "copy co2 from stdin with (format csv);", exported);

            assertTablesEqual(client, "co", "co2");
        }
    }

    @Test
    void textRoundTripPreservesValues() throws Exception {
        try (PgWireClient client = connect()) {
            query(client, "drop table if exists tt;");
            query(client, "create table tt(a varchar, b varchar, c int);");
            query(client, "insert into tt values "
                    + "('x' || chr(9) || 'y', 'back\\\\slash', 1),"
                    + "('line' || chr(10) || 'break', NULL, 2),"
                    + "('', '', 3),"
                    + "('quote\"and,comma', '\\N', 4);");

            String exported = copyPayload(query(client, "copy tt to stdout;"));
            query(client, "drop table if exists tt2;");
            query(client, "create table tt2(a varchar, b varchar, c int);");
            copyIn(client, "copy tt2 from stdin;", exported);

            assertTablesEqual(client, "tt", "tt2");
        }
    }

    /** BLOB 往返：bytea 的 `\x` 文本形态必须能被导入侧还原成同样的字节。 */
    @Test
    void blobRoundTripPreservesBytes() throws Exception {
        try (PgWireClient client = connect()) {
            query(client, "drop table if exists bb;");
            query(client, "create table bb(b blob);");
            query(client, "insert into bb values "
                    + "('\\xcafebabe'::blob), ('\\x00'::blob), ('\\x'::blob), (NULL);");

            String exported = copyPayload(query(client, "copy bb to stdout;"));
            query(client, "drop table if exists bb2;");
            query(client, "create table bb2(b blob);");
            copyIn(client, "copy bb2 from stdin;", exported);

            assertTablesEqual(client, "bb", "bb2");
        }
    }

    /**
     * 通过 COPY IN 子协议把载荷灌回去。
     *
     * <p><b>不能</b>在发出 COPY 语句后直接 {@code readUntilReadyForQuery()}：COPY IN 的
     * {@code ReadyForQuery} 只有在客户端发完 {@code CopyDone} 之后才会到，先等 Z 必然超时
     * （服务端在等数据、客户端在等 Z —— 死锁）。正确顺序是：
     * 读到 {@code CopyInResponse('G')} → 发 CopyData/CopyDone → 再读到 {@code ReadyForQuery}。</p>
     */
    private static void copyIn(PgWireClient client, String copySql, String payload) throws IOException {
        client.sendQuery(copySql);

        // 只读到 CopyInResponse：后面的 Z 要等我们发完数据才会来
        List<PgWireClient.Frame> started = new ArrayList<>();
        while (true) {
            PgWireClient.Frame frame = client.readFrame();
            assertNotNull(frame, "连接在 CopyInResponse 之前关闭");
            started.add(frame);
            if (frame.type == 'G' || frame.type == 'E' || frame.type == 'Z') {
                break;
            }
        }
        assertTrue(PgWireClient.types(started).startsWith("G"),
                "COPY FROM STDIN 应回 CopyInResponse('G')，实际=" + PgWireClient.types(started));

        // 载荷按 8KB 分片逐条发 CopyData，最后 CopyDone
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        int chunk = 8192;
        for (int offset = 0; offset < bytes.length; offset += chunk) {
            int len = Math.min(chunk, bytes.length - offset);
            byte[] part = new byte[len];
            System.arraycopy(bytes, offset, part, 0, len);
            client.sendCopyData(part);
        }
        client.sendCopyDone();
        List<PgWireClient.Frame> done = client.readUntilReadyForQuery();
        assertNotNull(commandTag(done), "COPY IN 应回 CommandComplete，实际=" + PgWireClient.types(done));
    }

    /** 用 SQL 逐行比较两张表是否完全一致（含 NULL 位置）。 */
    private static void assertTablesEqual(PgWireClient client, String left, String right) throws IOException {
        List<PgWireClient.Frame> diff = query(client,
                "select count(*) as n from ("
                        + "select * from " + left + " except all select * from " + right
                        + " union all "
                        + "select * from " + right + " except all select * from " + left
                        + ") d;");
        List<PgWireClient.Frame> rows = PgWireClient.ofType(diff, 'D');
        assertEquals(1, rows.size(), "差异查询应返回 1 行");
        assertEquals("0", PgWireClient.textValue(PgWireClient.dataRowValues(rows.get(0)).get(0)),
                "往返后 " + left + " 与 " + right + " 不一致");
    }

    // ================================================================
    // 错误路径
    // ================================================================

    /** 表不存在 → 42P01，且不能卡在"等数据"状态。 */
    @Test
    void missingTableReportsUndefinedTable() throws Exception {
        try (PgWireClient client = connect()) {
            List<PgWireClient.Frame> frames = query(client, "copy no_such_table to stdout;");
            assertEquals("42P01", PgWireClient.errorField(frames, 'C'));
            assertEquals('I', PgWireClient.readyStatus(frames), "出错后必须回 ReadyForQuery");
        }
    }

    /** 列不存在 → 42703。 */
    @Test
    void missingColumnReportsUndefinedColumn() throws Exception {
        try (PgWireClient client = connect()) {
            seed(client);
            List<PgWireClient.Frame> frames = query(client, "copy co(no_such_col) to stdout;");
            assertEquals("42703", PgWireClient.errorField(frames, 'C'));
        }
    }

    /** FORMAT binary 暂不支持：必须明确报 0A000，而不是发客户端解不开的字节。 */
    @Test
    void binaryFormatIsExplicitlyRejected() throws Exception {
        try (PgWireClient client = connect()) {
            seed(client);
            List<PgWireClient.Frame> frames = query(client, "copy co to stdout with (format binary);");
            assertEquals("0A000", PgWireClient.errorField(frames, 'C'));
            assertTrue(PgWireClient.types(frames).indexOf('d') < 0, "拒绝时不应发任何 CopyData");
        }
    }

    /** 非法选项 → 22023（与 COPY IN 同一套校验）。 */
    @Test
    void invalidOptionIsRejected() throws Exception {
        try (PgWireClient client = connect()) {
            seed(client);
            List<PgWireClient.Frame> frames =
                    query(client, "copy co to stdout with (format nosuchformat);");
            assertEquals("22023", PgWireClient.errorField(frames, 'C'));
        }
    }

    // ================================================================
    // 回归：服务端写文件形态必须继续透传给 DuckDB（不被我们拦下）
    // ================================================================

    @Test
    void copyToFileIsLeftToDuckDb() throws Exception {
        try (PgWireClient client = connect()) {
            seed(client);
            // 不应命中我们的 STDOUT 处理；DuckDB 自己写文件（临时目录）
            List<PgWireClient.Frame> frames = query(client, "copy co to '/tmp/copyout_probe.csv';");
            // 无论 DuckDB 成败，都不该是"我们"的 COPY OUT 报文序列
            assertTrue(PgWireClient.types(frames).indexOf('H') < 0,
                    "服务端写文件的 COPY 不应被当作 COPY TO STDOUT 处理，实际=" + PgWireClient.types(frames));
        }
    }
}
