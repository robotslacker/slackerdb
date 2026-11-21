package org.slackerdb.dbsyncer.target;

import org.junit.jupiter.api.Test;
import org.slackerdb.dbsyncer.model.TargetTable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckDbDdlGeneratorTest {

    @Test
    void comparesTargetSchemaColumnsAndTypes() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA sync");
                statement.execute("CREATE TABLE sync.t (id BIGINT, name VARCHAR)");
            }
            TargetTable expected = new TargetTable("memory", "sync", "t");
            expected.add(new TargetTable.TargetColumn("id", "BIGINT", "id", null));
            expected.add(new TargetTable.TargetColumn("name", "VARCHAR", "name", null));
            assertTrue(DuckDbDdlGenerator.schemaMismatch(connection, expected).isEmpty());

            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE sync.t ADD COLUMN extra INTEGER");
            }
            List<String> extra = DuckDbDdlGenerator.schemaMismatch(connection, expected);
            assertEquals(1, extra.size());
            assertTrue(extra.get(0).contains("extra column"), extra.toString());

            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE sync.t DROP COLUMN name");
            }
            List<String> missing = DuckDbDdlGenerator.schemaMismatch(connection, expected);
            assertTrue(missing.stream().anyMatch(value -> value.contains("missing column name")),
                    missing.toString());

            TargetTable absent = new TargetTable("memory", "sync", "absent");
            assertEquals(List.of("table missing"), DuckDbDdlGenerator.schemaMismatch(connection, absent));
        }
    }

    @Test
    void detectsPrimaryKeyMismatch() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA sync");
                statement.execute("CREATE TABLE sync.t (id BIGINT, code VARCHAR, PRIMARY KEY (id))");
                statement.execute("CREATE TABLE sync.u (id BIGINT)");
            }
            TargetTable withPk = new TargetTable("memory", "sync", "t");
            withPk.add(new TargetTable.TargetColumn("id", "BIGINT", "id", null));
            withPk.add(new TargetTable.TargetColumn("code", "VARCHAR", "code", null));
            withPk.primaryKey.add("id");
            assertEquals(List.of(), DuckDbDdlGenerator.primaryKeyMismatch(connection, withPk));
            assertEquals(List.of("id"), DuckDbDdlGenerator.primaryKeyColumns(connection, withPk));

            // 配置里的主键和表上的不一致：写入定位会失效，必须报差异
            TargetTable wrongPk = new TargetTable("memory", "sync", "t");
            wrongPk.add(new TargetTable.TargetColumn("id", "BIGINT", "id", null));
            wrongPk.add(new TargetTable.TargetColumn("code", "VARCHAR", "code", null));
            wrongPk.primaryKey.add("code");
            assertEquals(1, DuckDbDdlGenerator.primaryKeyMismatch(connection, wrongPk).size());

            // 表上没主键、配置也没主键：一致
            TargetTable noPk = new TargetTable("memory", "sync", "u");
            noPk.add(new TargetTable.TargetColumn("id", "BIGINT", "id", null));
            assertEquals(List.of(), DuckDbDdlGenerator.primaryKeyMismatch(connection, noPk));

            // 表上没主键、配置有主键：不一致（否则 upsert 会退化成 append）
            TargetTable expectsPk = new TargetTable("memory", "sync", "u");
            expectsPk.add(new TargetTable.TargetColumn("id", "BIGINT", "id", null));
            expectsPk.primaryKey.add("id");
            assertEquals(1, DuckDbDdlGenerator.primaryKeyMismatch(connection, expectsPk).size());
        }
    }

    /**
     * TypeMapper 能输出的每种 DuckDB 类型都必须与 information_schema 的写法对齐；
     * 否则作业每次启动都会判定"结构不一致"并放弃该表。
     */
    @Test
    void acceptsEveryTypeEmittedByTypeMapper() throws Exception {
        List<String> types = List.of(
                "BOOLEAN", "TINYINT", "SMALLINT", "INTEGER", "BIGINT",
                "DECIMAL(38,9)", "DECIMAL(20,0)", "DECIMAL(19,2)", "FLOAT", "DOUBLE",
                "VARCHAR", "DATE", "TIME", "TIMESTAMP", "TIMESTAMP WITH TIME ZONE",
                "BLOB", "JSON", "UUID");
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA sync");
            }
            TargetTable expected = new TargetTable("memory", "sync", "all_types");
            for (int i = 0; i < types.size(); i++) {
                String name = "c" + i;
                expected.add(new TargetTable.TargetColumn(name, types.get(i), name, null));
            }
            DuckDbDdlGenerator.createTable(connection, expected);

            assertEquals(List.of(), DuckDbDdlGenerator.schemaMismatch(connection, expected));
        }
    }
}
