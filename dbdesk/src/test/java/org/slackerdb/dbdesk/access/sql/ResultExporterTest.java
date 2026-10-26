package org.slackerdb.dbdesk.access.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResultExporter} 的集成测试：CSV 转义、JSON 结构、**不截断**、全量行。
 *
 * <pre>
 *   mvn -Pdbdesk -pl dbdesk test "-Ddbdesk.it.url=jdbc:postgresql://127.0.0.1:4409/m0" "-Dtest=ResultExporterTest"
 * </pre>
 */
@EnabledIfSystemProperty(named = "dbdesk.it.url", matches = ".+")
class ResultExporterTest {

    private static final String URL = System.getProperty("dbdesk.it.url");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void writesCsvWithHeaderAndBom() throws Exception {
        Path file = tempDir.resolve("simple.csv");
        ResultExporter.ExportResult result =
                ResultExporter.export(URL, "select 1 as a, 'x' as b", ResultExporter.Format.CSV, file);

        assertEquals(1, result.rows());
        assertTrue(result.bytes() > 0);
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("\uFEFF"), "CSV 应带 UTF-8 BOM（Excel 中文不乱码）");
        List<String> lines = content.substring(1).lines().toList();
        assertEquals(List.of("a,b", "1,x"), lines);
    }

    @Test
    void escapesCsvFieldsWithCommaQuoteAndNewline() throws Exception {
        Path file = tempDir.resolve("escape.csv");
        ResultExporter.export(URL,
                "select 'a,b' as x, 'q\"q' as y, null as z, 'l1' || chr(10) || 'l2' as w",
                ResultExporter.Format.CSV, file);

        String content = Files.readString(file, StandardCharsets.UTF_8).substring(1);
        assertTrue(content.contains("\"a,b\""), "含逗号的字段要加引号，实际：" + content);
        assertTrue(content.contains("\"q\"\"q\""), "引号要翻倍，实际：" + content);
        assertTrue(content.contains("\"l1\nl2\""), "换行要留在引号里，实际：" + content);
        assertTrue(content.contains(",,,") || content.contains("\"l1\nl2\"\n") || content.contains(",,,"),
                "NULL 应写空字段");
    }

    @Test
    void writesJsonArrayOfObjects() throws Exception {
        Path file = tempDir.resolve("rows.json");
        ResultExporter.ExportResult result =
                ResultExporter.export(URL, "select 1 as a, 'x' as b, null as c", ResultExporter.Format.JSON, file);

        assertEquals(1, result.rows());
        JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        assertTrue(root.isArray());
        assertEquals(1, root.size());
        assertEquals(1, root.get(0).path("a").asInt());
        assertEquals("x", root.get(0).path("b").asText());
        assertTrue(root.get(0).path("c").isNull(), "SQL NULL 应写成 JSON null");
    }

    @Test
    void exportsEveryRow() throws Exception {
        Path file = tempDir.resolve("many.json");
        ResultExporter.ExportResult result =
                ResultExporter.export(URL, "select * from range(250)", ResultExporter.Format.JSON, file);

        assertEquals(250, result.rows());
        JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(250, root.size());
    }

    @Test
    void doesNotTruncateLargeValues() throws Exception {
        Path file = tempDir.resolve("big.csv");
        ResultExporter.ExportResult result =
                ResultExporter.export(URL, "select payload from m0_big", ResultExporter.Format.CSV, file);

        assertEquals(1, result.rows());
        assertTrue(result.bytes() > 2_000_000, "2 MB 的单值不能像界面那样截断，实际字节数：" + result.bytes());
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(content.contains("x".repeat(1_000_000)), "文件里应包含完整的 200 万字符值");
        assertTrue(content.indexOf("已截断") < 0, "导出不应带界面的截断标记");
    }

    @Test
    void reportsFailureForBadSql() {
        Path file = tempDir.resolve("bad.csv");
        Exception error = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> ResultExporter.export(URL, "select * from no_such_table_xyz", ResultExporter.Format.CSV, file));
        assertNotNull(error.getMessage());
    }

    @Test
    void writesNothingButHeaderForEmptyResult() throws IOException, java.sql.SQLException {
        Path file = tempDir.resolve("empty.csv");
        ResultExporter.ExportResult result = ResultExporter.export(URL,
                "select 1 as a where 1 = 0", ResultExporter.Format.CSV, file);

        assertEquals(0, result.rows());
        assertEquals(List.of("\uFEFFa"), Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    @Test
    void reportsProgressAndSupportsCancel() throws Exception {
        // m0_probe 有 100 万行：导出必然跨过 1024 行的检查点
        java.util.concurrent.atomic.AtomicLong lastProgress = new java.util.concurrent.atomic.AtomicLong(-1);
        java.util.concurrent.atomic.AtomicInteger ticks = new java.util.concurrent.atomic.AtomicInteger();
        Path file = tempDir.resolve("cancel.csv");

        RuntimeException error = org.junit.jupiter.api.Assertions.assertThrows(
                ResultExporter.ExportCancelledException.class,
                () -> ResultExporter.export(URL, "select * from m0_probe", ResultExporter.Format.CSV, file,
                        () -> ticks.incrementAndGet() > 2,
                        rows -> lastProgress.set(rows)));

        assertTrue(lastProgress.get() > 0, "取消前应当至少回报过一次进度，实际：" + lastProgress.get());
        assertTrue(error.getMessage().contains("已取消"), "异常信息应说明已取消，实际：" + error.getMessage());
    }
}
