package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.test.support.PgWireClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 简单查询多语句批次不得被截断（BUG：以 {@code BEGIN} 开头的批次只执行第一条语句）。
 *
 * <p><b>缺陷现象</b>：{@code psql -c "begin; select 42; commit;"} 只回一个 {@code BEGIN} 标签，
 * 后面的语句被静默丢弃 —— 不报错、不提示。数据会静默丢失：实测
 * {@code begin; create table t(x int); insert into t values(1); commit;} 执行后表根本不存在。</p>
 *
 * <p><b>根因</b>：简单查询路径里有两个<b>单语句</b>拦截器（游标族、事务控制），它们只看消息开头就
 * 认定"这条语句是什么"，执行完就把整条消息收尾掉。{@code begin; ...} 的开头命中事务拦截器，
 * 于是在 BEGIN 之后 {@code break}，剩余语句再没人执行。</p>
 *
 * <p><b>为什么要用裸协议而不是 JDBC/psql 断言</b>：协议层才能看到"每条语句各回一个
 * CommandComplete"这个契约，以及 {@code ReadyForQuery} 的事务状态字节
 * （'I' 空闲 / 'T' 事务块中 / 'E' 已中止）。
 * 另外 psql <b>不会显示</b>出错之后的命令标签，用 psql 输出断言会得出错误结论。</p>
 */
public class BeginBatchTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("beginbatch");
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

    /** 发送一条简单查询并收齐到 ReadyForQuery。 */
    private static List<PgWireClient.Frame> query(PgWireClient client, String sql) throws IOException {
        client.sendQuery(sql);
        return client.readUntilReadyForQuery();
    }

    /** 按顺序取出所有 CommandComplete 的命令标签。 */
    private static List<String> commandTags(List<PgWireClient.Frame> frames) {
        List<String> tags = new ArrayList<>();
        for (PgWireClient.Frame frame : PgWireClient.ofType(frames, 'C')) {
            tags.add(frame.text());
        }
        return tags;
    }

    @Test
    void beginBatchExecutesEveryStatement() throws Exception {
        try (PgWireClient client = new PgWireClient("127.0.0.1", dbPort, "beginbatch", 10_000)) {
            List<PgWireClient.Frame> frames = query(client, "begin; select 42 as answer; commit;");

            // 三条语句 → 三个 CommandComplete，且顺序正确。
            assertEquals(List.of("BEGIN", "SELECT 1", "COMMIT"), commandTags(frames),
                    "批次里的语句被截断了，报文序列=" + PgWireClient.types(frames));

            // 结果集真的回来了（一行一列）。
            assertTrue(PgWireClient.types(frames).contains("T"),
                    "SELECT 的 RowDescription 缺失，报文序列=" + PgWireClient.types(frames));
            assertEquals(1, PgWireClient.ofType(frames, 'D').size(), "应返回 1 个 DataRow");
            assertEquals("42", PgWireClient.textValue(
                    PgWireClient.dataRowValues(PgWireClient.ofType(frames, 'D').get(0)).get(0)));

            // 批次结束后事务已提交 → 空闲。
            assertEquals('I', PgWireClient.readyStatus(frames), "COMMIT 之后应回到空闲状态");
        }
    }

    /** 核心数据丢失场景：批次里的 DDL/DML 必须真的生效。 */
    @Test
    void beginBatchActuallyAppliesDdlAndDml() throws Exception {
        try (PgWireClient client = new PgWireClient("127.0.0.1", dbPort, "beginbatch", 10_000)) {
            query(client, "drop table if exists batch_t;");
            List<PgWireClient.Frame> frames =
                    query(client, "begin; create table batch_t(x int); insert into batch_t values (9); commit;");
            assertEquals(List.of("BEGIN", "CREATE TABLE", "INSERT 0 1", "COMMIT"), commandTags(frames),
                    "DDL/DML 批次被截断，报文序列=" + PgWireClient.types(frames));

            // 独立语句确认数据真的落库了（此前是"看起来成功、其实没建表"）。
            List<PgWireClient.Frame> check = query(client, "select count(*) as n from batch_t;");
            List<PgWireClient.Frame> rows = PgWireClient.ofType(check, 'D');
            assertEquals(1, rows.size(), "count 查询应返回 1 行");
            assertEquals("1", PgWireClient.textValue(PgWireClient.dataRowValues(rows.get(0)).get(0)),
                    "批次里的 INSERT 没有生效");
        }
    }

    /** 批次内 ROLLBACK 必须真的回滚。 */
    @Test
    void rollbackInsideBatchRollsBack() throws Exception {
        try (PgWireClient client = new PgWireClient("127.0.0.1", dbPort, "beginbatch", 10_000)) {
            query(client, "drop table if exists batch_rb;");
            List<PgWireClient.Frame> frames =
                    query(client, "begin; create table batch_rb(x int); insert into batch_rb values (1); rollback;");
            assertEquals(List.of("BEGIN", "CREATE TABLE", "INSERT 0 1", "ROLLBACK"), commandTags(frames),
                    "报文序列=" + PgWireClient.types(frames));
            assertEquals('I', PgWireClient.readyStatus(frames));

            // 回滚之后表不应存在。
            List<PgWireClient.Frame> check = query(client, "select count(*) as n from batch_rb;");
            assertNotNull(PgWireClient.errorField(check, 'C'), "回滚后 batch_rb 不应存在");
        }
    }

    /** 未提交的批次必须把事务留给后续消息（ReadyForQuery = 'T'）。 */
    @Test
    void batchWithoutCommitLeavesTransactionOpen() throws Exception {
        try (PgWireClient client = new PgWireClient("127.0.0.1", dbPort, "beginbatch", 10_000)) {
            List<PgWireClient.Frame> frames = query(client, "begin; select 1 as in_txn;");
            assertEquals('T', PgWireClient.readyStatus(frames),
                    "COMMIT 未发出时事务应保持打开（'T'），实际报文=" + PgWireClient.types(frames));

            List<PgWireClient.Frame> done = query(client, "commit;");
            assertEquals('I', PgWireClient.readyStatus(done), "后续消息应能结束该事务");
        }
    }

    /**
     * 批次中某条语句出错：剩余语句仍要在事务语义下被处理
     * （后续语句以 25P02 拒绝，但 {@code COMMIT} 必须照常结束事务块）。
     *
     * <p>这一点很关键：如果错误直接冲破循环，批次里的 COMMIT 就永远执行不到，
     * 事务块会一直挂着泄漏给下一个消息。</p>
     */
    @Test
    void errorInsideBatchStillRunsCommitAndEndsTransaction() throws Exception {
        try (PgWireClient client = new PgWireClient("127.0.0.1", dbPort, "beginbatch", 10_000)) {
            List<PgWireClient.Frame> frames = query(client,
                    "begin; select * from no_such_table_at_all; select 1 as skipped; commit;");

            List<String> tags = commandTags(frames);
            assertEquals("BEGIN", tags.get(0), "报文序列=" + PgWireClient.types(frames));
            // 报错之后：后续语句被 25P02 拒绝，但 COMMIT 仍然执行
            assertTrue(tags.contains("COMMIT"),
                    "出错后 COMMIT 仍须执行，实际标签=" + tags + " 报文=" + PgWireClient.types(frames));
            assertTrue(!"SELECT 1".equals(tags.get(tags.size() - 1)),
                    "被中止事务里的语句不应执行成功，实际标签=" + tags);

            // 出错之后的语句要被 25P02 拒绝（不能继续执行）。
            // 注意：这里不能用 PgWireClient.errorField —— 它只返回**第一条** ErrorResponse，
            // 而第一条是原始的 42P01（表不存在），25P02 在它之后。
            List<String> states = new ArrayList<>();
            for (PgWireClient.Frame errorFrame : PgWireClient.ofType(frames, 'E')) {
                states.add(errorState(errorFrame));
            }
            assertTrue(states.contains("25P02"),
                    "应有一条 25P02（current transaction is aborted），实际的错误码=" + states);

            assertEquals('I', PgWireClient.readyStatus(frames), "COMMIT 之后事务块必须结束，不能泄漏");
        }
    }

    /** 取一个 ErrorResponse 报文里的 SQLSTATE（字段 'C'）。 */
    private static String errorState(PgWireClient.Frame errorFrame) {
        int offset = 0;
        while (offset < errorFrame.body.length && errorFrame.body[offset] != 0) {
            char field = (char) (errorFrame.body[offset] & 0xFF);
            String value = errorFrame.cstring(offset + 1);
            if (field == 'C') {
                return value;
            }
            offset += 1 + value.length() + 1;
        }
        return null;
    }

    /** 回归保护：原本就正常的多语句批次不能被改坏。 */
    @Test
    void plainMultiStatementBatchStillWorks() throws Exception {
        try (PgWireClient client = new PgWireClient("127.0.0.1", dbPort, "beginbatch", 10_000)) {
            List<PgWireClient.Frame> frames = query(client, "select 1 as a; select 2 as b; select 3 as c;");
            assertEquals(List.of("SELECT 1", "SELECT 1", "SELECT 1"), commandTags(frames),
                    "报文序列=" + PgWireClient.types(frames));
            assertEquals(3, PgWireClient.ofType(frames, 'D').size(), "应返回 3 个 DataRow");
            assertEquals('I', PgWireClient.readyStatus(frames));
        }
    }

    /** 回归保护：单条 BEGIN（不是批次）语义不变。 */
    @Test
    void singleBeginStatementStillOpensTransaction() throws Exception {
        try (PgWireClient client = new PgWireClient("127.0.0.1", dbPort, "beginbatch", 10_000)) {
            List<PgWireClient.Frame> frames = query(client, "begin;");
            assertEquals(List.of("BEGIN"), commandTags(frames),
                    "单条 BEGIN 应只回一个标签，报文序列=" + PgWireClient.types(frames));
            assertEquals('T', PgWireClient.readyStatus(frames));

            List<PgWireClient.Frame> done = query(client, "rollback;");
            assertEquals('I', PgWireClient.readyStatus(done));
        }
    }
}
