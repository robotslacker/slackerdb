package org.slackerdb.dbdesk.access.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MetadataReader} 的集成测试：表清单、列清单、系统 schema 过滤。
 *
 * <pre>
 *   mvn -Pdbdesk -pl dbdesk test "-Ddbdesk.it.url=jdbc:postgresql://127.0.0.1:4409/m0" "-Dtest=MetadataReaderTest"
 * </pre>
 */
@EnabledIfSystemProperty(named = "dbdesk.it.url", matches = ".+")
class MetadataReaderTest {

    private static final String URL = System.getProperty("dbdesk.it.url");

    @Test
    void listsUserTablesAndFiltersSystemSchemas() throws Exception {
        List<MetadataReader.TableRef> tables = MetadataReader.tables(URL);

        assertTrue(tables.stream().anyMatch(t -> t.name().equals("m0_probe")),
                "应能读到 m0_probe，实际：" + tables);
        assertTrue(tables.stream().anyMatch(t -> t.name().equals("m0_big")));
        assertFalse(tables.stream().anyMatch(t -> t.schema().equals("information_schema")),
                "系统 schema 应被过滤");
        assertFalse(tables.stream().anyMatch(t -> t.schema().equals("duck_catalog")));
        assertTrue(tables.stream().allMatch(t -> t.type() != null && !t.type().isBlank()));
    }

    @Test
    void listsColumnsInOrder() throws Exception {
        List<MetadataReader.ColumnRef> columns = MetadataReader.columns(URL, "main", "m0_probe");

        assertFalse(columns.isEmpty());
        assertTrue(columns.size() >= 50, "m0_probe 有 50 列，实际：" + columns.size());
        assertTrue(columns.get(0).name().equals("id"), "第一列应是 id，实际：" + columns.get(0));
        assertTrue(columns.get(0).type().toUpperCase().contains("BIGINT"));
        assertTrue(columns.stream().allMatch(column -> column.type() != null && !column.type().isBlank()));
    }

    @Test
    void returnsEmptyForUnknownTable() throws Exception {
        assertTrue(MetadataReader.columns(URL, "main", "no_such_table_xyz").isEmpty());
    }
}
