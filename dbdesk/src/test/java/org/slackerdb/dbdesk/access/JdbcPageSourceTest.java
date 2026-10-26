package org.slackerdb.dbdesk.access;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcPageSource} 的集成测试：**分页语义**是这个类的全部价值，所以逐条钉住。
 *
 * <p>开关式运行（对称 dbcli 的 {@code -Ddbcli.it.url}，默认跳过）：</p>
 * <pre>
 *   java -jar dbserver/target/slackerdb-dbserver-0.2.2-standalone.jar start --port 4409 --portX 8409 --data m0 --data_dir dbdesk/target/m0db
 *   java -cp &lt;pgjdbc&gt; dbdesk/m0/FetchProbe.java 127.0.0.1 4409 m0      # 造 m0_probe / m0_big
 *   mvn -Pdbdesk -pl dbdesk test "-Ddbdesk.it.url=jdbc:postgresql://127.0.0.1:4409/m0" "-Dtest=JdbcPageSourceTest"
 * </pre>
 *
 * <p>依赖探针建的两个表（{@code m0_probe} 100 万行 × 50 列、{@code m0_big} 单值 200 万字符）。
 * 这是 M0 的验证夹具，M1 会换成 {@code -Pembedded-it} 自包含模式。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.it.url", matches = ".+")
class JdbcPageSourceTest {

    private JdbcPageSource source;

    @BeforeAll
    void open() throws SQLException {
        source = new JdbcPageSource(System.getProperty("dbdesk.it.url"));
        source.verify();
    }

    @AfterAll
    void close() {
        if (source != null) {
            source.close();
        }
    }

    @Test
    void paginatesDeepWithStableOrder() throws SQLException {
        QueryPage page = source.fetch("select * from m0_probe", 999_000, 5);

        assertEquals(5, page.rowCount(), "深分页应正好返回 5 行");
        assertEquals(50, page.columns().size(), "m0_probe 有 50 列");
        assertEquals(page.columns().size(), page.columnTypes().size(), "列类型必须与列一一对应");
        assertTrue(page.columnTypes().stream().noneMatch(t -> t == null), "列类型不应为空");

        // 子查询包装不能破坏顺序：offset=999000 的第一行 id 必须是 999000
        assertEquals("999000", String.valueOf(page.rows().get(0).get(0)), "深分页首行 id 不对（子查询丢序？）");
        assertEquals("999004", String.valueOf(page.rows().get(4).get(0)), "深分页末行 id 不对");
    }

    @Test
    void countsWithoutLoadingRows() throws SQLException {
        assertEquals(1_000_000L, source.count("select * from m0_probe"));
        assertEquals(10L, source.count("select * from m0_probe limit 10"), "count 要把用户自己的 limit 算进去");
    }

    @Test
    void truncatesCellsOverTheConfiguredLimit() throws SQLException {
        QueryPage page = source.fetch("select payload from m0_big", 0, 1);

        assertTrue(page.truncated(), "200 万字符的单值必须被标记为截断");
        String cell = (String) page.rows().get(0).get(0);
        assertTrue(cell.length() >= JdbcPageSource.MAX_CELL_CHARS,
                "截断后至少保留 " + JdbcPageSource.MAX_CELL_CHARS + " 字符，实际 " + cell.length());
        assertTrue(cell.length() < JdbcPageSource.MAX_CELL_CHARS + 64,
                "截断标记不应超过 64 字符，实际 " + cell.length());
        assertTrue(cell.contains("已截断"), "应带可辨识的截断标记");
    }

    @Test
    void toleratesTrailingSemicolonAndUserLimit() throws SQLException {
        assertEquals(3, source.fetch("select * from m0_probe;", 0, 3).rowCount(),
                "带分号的 SQL 应能直接分页");
        assertEquals(10, source.fetch("select * from m0_probe limit 10", 0, 100).rowCount(),
                "用户自己写了 limit 时，分页不能把它放大");
        assertEquals(0, source.fetch("select * from m0_probe limit 10", 100, 10).rowCount(),
                "越过用户 limit 的页应为空");
    }

    @Test
    void keepsNullsAndColumnLabels() throws SQLException {
        QueryPage page = source.fetch("select null as nothing, 42 as answer", 0, 1);

        assertEquals(List.of("nothing", "answer"), page.columns());
        assertEquals(1, page.rowCount());
        assertEquals(null, page.rows().get(0).get(0), "SQL NULL 应保持为 null，不要变成字符串 \"null\"");
        assertEquals(42, ((Number) page.rows().get(0).get(1)).intValue());
        assertFalse(page.truncated());
        assertNotNull(page.columnTypes().get(1));
    }
}
