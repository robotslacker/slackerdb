package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbserver.configuration.ServerConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dbserver 的 DuckLake 参数验收：默认值为空、配置文件读取、裁剪，以及 DuckLake 模式下
 * 临时目录回落到系统临时目录。
 *
 * <p>归入 {@code live} 组：DuckLake 依赖额外的 {@code ducklake} 扩展，默认不参与常规回归。</p>
 */
@Tag("live")
class DucklakeConfigFileTest {

    @Test
    @DisplayName("Both DuckLake parameters default to empty")
    void emptyByDefault() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();

        assertEquals("", configuration.getDucklakeMeta());
        assertEquals("", configuration.getDucklakePath());
        assertFalse(configuration.isDucklakeEnabled());
    }

    @Test
    @DisplayName("Values are trimmed when set programmatically")
    void trimsValues() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();

        configuration.setDucklakeMeta("  my_lake_metadata.db  ");
        configuration.setDucklakePath("  s3://bucket/data/  ");

        assertEquals("my_lake_metadata.db", configuration.getDucklakeMeta());
        assertEquals("s3://bucket/data/", configuration.getDucklakePath());

        configuration.setDucklakeMeta(null);
        configuration.setDucklakePath(null);
        assertEquals("", configuration.getDucklakeMeta());
        assertEquals("", configuration.getDucklakePath());
    }

    @Test
    @DisplayName("ducklake_meta / ducklake_path are read from the configuration file")
    void loadsFromConfigurationFile(@TempDir Path tempDir) throws Exception {
        Path conf = tempDir.resolve("dbserver.conf");
        Files.writeString(conf, "data_dir=:ducklake:\n"
                + "ducklake_meta=myuser/secret@dbhost:5432/lakedb\n"
                + "ducklake_path=s3://bucket/data/\n");

        ServerConfiguration configuration = new ServerConfiguration();
        configuration.loadConfigurationFile(conf.toString());

        assertTrue(configuration.isDucklakeEnabled(), "data_dir=:ducklake: enables DuckLake mode");
        assertEquals("myuser/secret@dbhost:5432/lakedb", configuration.getDucklakeMeta());
        assertEquals("s3://bucket/data/", configuration.getDucklakePath());
        assertEquals(":ducklake:", configuration.getData_Dir());
    }

    @Test
    @DisplayName("DuckLake mode falls back to the system temp directory, not ':ducklake:'")
    void ducklakeUsesSystemTempDir() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setData_dir(":ducklake:");

        assertEquals(System.getProperty("java.io.tmpdir"), configuration.getTemp_dir());
    }
}
