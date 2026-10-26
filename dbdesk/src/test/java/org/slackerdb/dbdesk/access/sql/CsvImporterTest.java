package org.slackerdb.dbdesk.access.sql;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSV 导入的集成测试（对着真实 dbserver）：
 * 转义/换行/NULL/中文、列映射、错误策略、取消。
 *
 * <pre>
 *   mvn -Pdbdesk -pl dbdesk test "-Ddbdesk.it.url=jdbc:postgresql://127.0.0.1:4409/m0" "-Dtest=CsvImporterTest"
 * </pre>
 */
@EnabledIfSystemProperty(named = "dbdesk.it.url", matches = ".+")
class CsvImporterTest {

    private static final String URL = System.getProperty("dbdesk.it.url");
    private static final String TABLE = "dbdesk_import_test";

    @TempDir
    Path tempDir;

    @BeforeAll
    static void createTable() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "", "");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE);
            statement.execute("CREATE TABLE " + TABLE + " ("
                    + "id BIGINT, name VARCHAR, amount DECIMAL(10,2), note VARCHAR)");
        }
    }

    @AfterAll
    static void dropTable() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "", "");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE);
        }
    }

    private Path csv(String content) throws Exception {
        Path file = tempDir.resolve("data-" + System.nanoTime() + ".csv");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("引号/逗号/字段内换行/中文/NULL 都能正确落库")
    void importsTrickyValues() throws Exception {
        // 用普通字符串拼接：字段里本身含 """，写成 text block 会把块提前闭合
        String content = "id,name,amount,note\n"
                + "1,\"Alice, A.\",12.34,\"line1\nline2\"\n"
                + "2,Bob,0.00,\n"
                + "3,张三,99.99,\"say \"\"hi\"\"\"\n";
        Path file = csv(content);

        CsvImporter.ImportResult result = CsvImporter.importInto(URL, TABLE,
                List.of("id", "name", "amount", "note"), file, CsvImporter.Options.defaults(), () -> false);

        assertEquals(3, result.imported());
        assertEquals(0, result.failed());

        try (Connection connection = DriverManager.getConnection(URL, "", "");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT id, name, amount, note FROM " + TABLE + " ORDER BY id")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getLong("id"));
            assertEquals("Alice, A.", rs.getString("name"));
            assertTrue(rs.getString("note").contains("\n"), "字段内的换行应保留");
            assertTrue(rs.next());
            assertEquals("Bob", rs.getString("name"));
            assertNull(rs.getString("note"), "空字段按 NULL 导入");
            assertTrue(rs.next());
            assertEquals("张三", rs.getString("name"));
            assertEquals("say \"hi\"", rs.getString("note"), "双引号转义应还原");
        }
    }

    @Test
    @DisplayName("列映射：可以跳过 CSV 里的列")
    void respectsColumnMapping() throws Exception {
        Path file = csv("""
                id,ignore_me,amount,note
                10,whatever,1.50,hello
                """);

        CsvImporter.ImportResult result = CsvImporter.importInto(URL, TABLE,
                java.util.Arrays.asList("id", null, "amount", "note"), file, CsvImporter.Options.defaults(), () -> false);

        assertEquals(1, result.imported());
        try (Connection connection = DriverManager.getConnection(URL, "", "");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT name, note FROM " + TABLE + " WHERE id = 10")) {
            assertTrue(rs.next());
            assertNull(rs.getString("name"), "被跳过的列导入 NULL");
            assertEquals("hello", rs.getString("note"));
        }
    }

    @Test
    @DisplayName("stopOnError=true：坏行让整批回滚，一行都不留")
    void stopOnErrorRollsBackEverything() throws Exception {
        String marker = "stop-" + System.nanoTime();
        Path file = csv("id,name,amount,note\n"
                + "20," + marker + ",1.00,ok\n"
                + "not-a-number,broken,1.00,bad\n");

        assertThrows(Exception.class, () -> CsvImporter.importInto(URL, TABLE,
                List.of("id", "name", "amount", "note"), file, CsvImporter.Options.defaults(), () -> false));

        try (Connection connection = DriverManager.getConnection(URL, "", "");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM " + TABLE + " WHERE name = '" + marker + "'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "stopOnError 时整批应回滚");
        }
    }

    @Test
    @DisplayName("stopOnError=false：跳过坏行继续，错误带回行号")
    void skipModeKeepsGoing() throws Exception {
        Path file = csv("id,name,amount,note\n"
                + "30,good,1.00,ok\n"
                + "bad-id,broken,1.00,bad\n"
                + "31,good2,2.00,ok\n");

        CsvImporter.Options options = new CsvImporter.Options(',', true, "", 10, false, StandardCharsets.UTF_8);
        CsvImporter.ImportResult result = CsvImporter.importInto(URL, TABLE,
                List.of("id", "name", "amount", "note"), file, options, () -> false);

        assertEquals(2, result.imported());
        assertEquals(1, result.failed());
        assertEquals(3, result.errors().get(0).line(), "错误应带 CSV 行号（含表头）");
    }

    @Test
    @DisplayName("取消：已提交的批次保留，结果标记 cancelled")
    void cancellationStopsLoop() throws Exception {
        Path file = csv("id,name,amount,note\n1,a,1,ok\n2,b,1,ok\n3,c,1,ok\n");
        CsvImporter.Options options = new CsvImporter.Options(',', true, "", 1, false, StandardCharsets.UTF_8);

        CsvImporter.ImportResult result = CsvImporter.importInto(URL, TABLE,
                List.of("id", "name", "amount", "note"), file, options, () -> true);

        assertTrue(result.cancelled(), "应标记为已取消");
        assertEquals(0, result.imported(), "取消发生在一开始，不应写入任何行");
    }

    @Test
    @DisplayName("COPY 路径：坏行落在块中间时，跳过模式仍能继续并报出行号")
    void copyPathSkipsBadRowInsideChunk() throws Exception {
        Path file = csv("id,name,amount,note\n"
                + "40,good,1.00,ok\n"
                + "bad-id,broken,1.00,bad\n"
                + "41,good2,2.00,ok\n");

        CsvImporter.Options options = new CsvImporter.Options(',', true, "", 10, false,
                StandardCharsets.UTF_8, true, 100);
        CsvImporter.ImportResult result = CsvImporter.importInto(URL, TABLE,
                List.of("id", "name", "amount", "note"), file, options, () -> false);

        assertEquals(2, result.imported(), "COPY 块失败后应回落逐行，跳过坏行继续");
        assertEquals(1, result.failed());
        assertEquals(3, result.errors().get(0).line(), "错误行号必须是 CSV 行号（含表头）");
    }

    @Test
    @DisplayName("COPY 路径：5 万行要远快于逐行（回归：别把极速路径改没了）")
    void copyPathIsFastForLargeFile() throws Exception {
        StringBuilder text = new StringBuilder("id,name,amount,note\n");
        for (int i = 0; i < 50_000; i++) {
            text.append(50_000 + i).append(",user-").append(i).append(',').append(i % 1000)
                    .append(".50,row-").append(i).append('\n');
        }
        Path file = csv(text.toString());

        CsvImporter.Options options = new CsvImporter.Options(',', true, "", 1_000, true,
                StandardCharsets.UTF_8, true, 10_000);
        CsvImporter.ImportResult result = CsvImporter.importInto(URL, TABLE,
                List.of("id", "name", "amount", "note"), file, options, () -> false);

        assertEquals(50_000, result.imported());
        // 逐行路径在这台机器上约 776 行/秒（5 万行 ≈ 64 s）；COPY 是毫秒级，留足余量
        assertTrue(result.elapsedMillis() < 15_000,
                "5 万行走 COPY 应该远快于逐行，实际耗时 " + result.elapsedMillis() + " ms");
    }

    @Test
    @DisplayName("预览：只读前 N 行，不改库")
    void previewReturnsRows() throws Exception {
        Path file = csv("id,name\n1,a\n2,b\n3,c\n");

        List<List<String>> rows = CsvImporter.preview(file, CsvImporter.Options.defaults(), 2);

        assertEquals(2, rows.size());
        assertEquals(List.of("id", "name"), rows.get(0));
        assertEquals(List.of("1", "a"), rows.get(1));
    }
}
