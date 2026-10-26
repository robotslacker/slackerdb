package org.slackerdb.dbdesk.access.sql;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slackerdb.dbdesk.access.http.ManagementClient;
import org.slackerdb.dbdesk.access.http.SessionInfo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcSqlExecutor} 的集成测试：多语句、出错即停、截断、取消/超时配置、来源打标。
 *
 * <pre>
 *   mvn -Pdbdesk -pl dbdesk test "-Ddbdesk.it.url=jdbc:postgresql://127.0.0.1:4409/m0" "-Dtest=JdbcSqlExecutorTest"
 * </pre>
 *
 * <p>依赖探针建的表（{@code m0_probe} / {@code m0_big}）。{@code -Ddbdesk.management.url} 可选：
 * 给了就顺带验证"控制台会话能被打标并被 /status 看见"。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.it.url", matches = ".+")
class JdbcSqlExecutorTest {

    private JdbcSqlExecutor executor;

    @BeforeAll
    void open() {
        executor = new JdbcSqlExecutor(System.getProperty("dbdesk.it.url"));
    }

    @AfterAll
    void close() {
        if (executor != null) {
            executor.close();
        }
    }

    @Test
    void executesSingleQuery() {
        List<StatementResult> results = executor.execute("select 42 as answer, 'x' as label", 100);

        assertEquals(1, results.size());
        StatementResult result = results.get(0);
        assertFalse(result.failed());
        assertTrue(result.query());
        assertEquals(List.of("answer", "label"), result.columns());
        assertEquals(1, result.rowCount());
        assertEquals(42, ((Number) result.rows().get(0).get(0)).intValue());
        assertEquals("x", result.rows().get(0).get(1));
        assertFalse(result.truncated());
    }

    @Test
    void executesMultipleStatementsInOrder() {
        List<StatementResult> results = executor.execute("select 1 as a; select 2 as b;", 100);

        assertEquals(2, results.size());
        assertEquals("a", results.get(0).columns().get(0));
        assertEquals("b", results.get(1).columns().get(0));
        assertEquals(1, ((Number) results.get(0).rows().get(0).get(0)).intValue());
        assertEquals(2, ((Number) results.get(1).rows().get(0).get(0)).intValue());
    }

    @Test
    void stopsAtFirstFailureButKeepsEarlierResults() {
        List<StatementResult> results = executor.execute("select 1 as ok; select * from no_such_table_xyz; select 3", 100);

        assertEquals(2, results.size(), "出错后不应继续执行第 3 条");
        assertFalse(results.get(0).failed());
        assertTrue(results.get(1).failed());
        assertNotNull(results.get(1).error());
    }

    @Test
    void truncatesOverlongCells() {
        List<StatementResult> results = executor.execute("select payload from m0_big", 10);

        StatementResult result = results.get(0);
        assertFalse(result.failed());
        assertTrue(result.truncated(), "200 万字符的单值应被标记为截断");
        String cell = (String) result.rows().get(0).get(0);
        assertTrue(cell.length() >= JdbcSqlExecutor.MAX_CELL_CHARS);
        assertTrue(cell.length() < JdbcSqlExecutor.MAX_CELL_CHARS + 64);
    }

    @Test
    void marksRowLimit() {
        List<StatementResult> results = executor.execute("select * from m0_probe", 10);

        StatementResult result = results.get(0);
        assertEquals(10, result.rowCount());
        assertTrue(result.rowsTruncated(), "达到 maxRows 时应标记 rowsTruncated");
    }

    @Test
    void queryTimeoutIsConfigurable() {
        executor.setQueryTimeoutSeconds(5);
        assertEquals(5, executor.queryTimeoutSeconds());
        executor.setQueryTimeoutSeconds(0);
        assertEquals(1, executor.queryTimeoutSeconds(), "超时至少 1 秒");
        executor.setQueryTimeoutSeconds(30);
    }

    @Test
    void consoleSessionIsMarkedWithApplicationName() throws Exception {
        String managementUrl = System.getProperty("dbdesk.management.url");
        if (managementUrl == null || managementUrl.isBlank()) {
            return; // 没给管理口就跳过这一段（分页/执行本身已经验过）
        }
        // 先跑一条，确保连接建立（打标发生在建连时）
        executor.execute("select 1", 10);

        try (ManagementClient management = new ManagementClient(managementUrl)) {
            List<SessionInfo> sessions = management.sessions();
            assertTrue(sessions.stream().anyMatch(session -> session.fromConsole()),
                    "会话列表里应出现 slackerdb-console/ 来源，实际：" + sessions.stream()
                            .map(SessionInfo::applicationName).toList());
        }
    }
}
