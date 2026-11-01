package org.slackerdb.dbserver.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DuckLake 模式验收：{@code data_dir=:ducklake:} 的识别、必填项校验，以及挂载 SQL 的拼装。
 *
 * <p>归入 {@code live} 组：DuckLake 依赖额外的 {@code ducklake} 扩展，默认不参与常规回归。</p>
 */
@Tag("live")
class DucklakeConfigurationTest {

    @Test
    @DisplayName("Only ':ducklake:' enables DuckLake mode (case-insensitive, trimmed)")
    void recognisesDucklakeMode() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();
        assertFalse(configuration.isDucklakeEnabled());

        configuration.setData_dir("  :DUCKLAKE:  ");
        assertTrue(configuration.isDucklakeEnabled());

        configuration.setData_dir(":memory:");
        assertFalse(configuration.isDucklakeEnabled());
    }

    @Test
    @DisplayName("ducklake_meta is required when DuckLake mode is enabled")
    void requiresMeta() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setData_dir(":ducklake:");
        configuration.setData("my_lake");

        ServerException e = assertThrows(ServerException.class,
                () -> DBInstance.validateDucklakeConfiguration(configuration));
        assertTrue(e.getMessage().contains("ducklake_meta"), e.getMessage());

        // ducklake_path may stay empty; meta alone is enough.
        configuration.setDucklakeMeta("my_lake_metadata.db");
        DBInstance.validateDucklakeConfiguration(configuration);
    }

    @Test
    @DisplayName("data (the attached catalog name) is required in DuckLake mode")
    void requiresData() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setData_dir(":ducklake:");
        configuration.setDucklakeMeta("my_lake_metadata.db");

        ServerException e = assertThrows(ServerException.class,
                () -> DBInstance.validateDucklakeConfiguration(configuration));
        assertTrue(e.getMessage().contains("data"), e.getMessage());
    }

    @Test
    @DisplayName("data_encrypt is rejected in DuckLake mode instead of being silently ignored")
    void rejectsEncryption() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setData_dir(":ducklake:");
        configuration.setData("my_lake");
        configuration.setDucklakeMeta("my_lake_metadata.db");
        configuration.setDataEncrypt(true);

        ServerException e = assertThrows(ServerException.class,
                () -> DBInstance.validateDucklakeConfiguration(configuration));
        assertTrue(e.getMessage().contains("data_encrypt"), e.getMessage());
    }

    @Test
    @DisplayName("Non-DuckLake configurations are unaffected even when DuckLake parameters are empty")
    void ignoresDucklakeParametersOutsideDucklakeMode() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setData_dir(":memory:");
        DBInstance.validateDucklakeConfiguration(configuration);
    }

    @Test
    @DisplayName("Build the DuckLake ATTACH statement: DATA_PATH only when a path is configured")
    void buildsAttachSql() {
        assertEquals(
                "ATTACH 'ducklake:my_lake_metadata.db' AS \"my_lake\"",
                DBInstance.buildDucklakeAttachSql("my_lake_metadata.db", "", "my_lake", false));

        assertEquals(
                "ATTACH 'ducklake:my_lake_metadata.db' AS \"my_lake\" (DATA_PATH 's3://bucket/data/')",
                DBInstance.buildDucklakeAttachSql(
                        "my_lake_metadata.db", "s3://bucket/data/", "my_lake", false));

        assertEquals(
                "ATTACH 'ducklake:my_lake_metadata.db' AS \"my_lake\" (DATA_PATH 's3://bucket/data/', READ_ONLY)",
                DBInstance.buildDucklakeAttachSql(
                        "my_lake_metadata.db", "s3://bucket/data/", "my_lake", true));
    }

    @Test
    @DisplayName("Quotes in the metadata link and data path are escaped")
    void escapesQuotes() {
        assertEquals(
                "ATTACH 'ducklake:o''brien.db' AS \"my_lake\" (DATA_PATH 's3://bucket/o''brien/')",
                DBInstance.buildDucklakeAttachSql(
                        "o'brien.db", "s3://bucket/o'brien/", "my_lake", false));
    }

    @Test
    @DisplayName("Passwords in the metadata link are redacted in logs")
    void redactsPassword() {
        assertEquals(
                "myuser/***@dbhost:5432/lakedb",
                DBInstance.redactDucklakeMeta("myuser/secret@dbhost:5432/lakedb"));
        assertEquals(
                "my_lake_metadata.db",
                DBInstance.redactDucklakeMeta("my_lake_metadata.db"));
    }
}
