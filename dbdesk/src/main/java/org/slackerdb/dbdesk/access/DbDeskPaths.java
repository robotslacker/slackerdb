package org.slackerdb.dbdesk.access;

import java.nio.file.Path;

/**
 * DbDesk 本地数据目录。
 *
 * <p>启动参数 {@code --datadir <dir>}（Launcher 会转成 {@code dbdesk.datadir}）优先；
 * 其次环境变量 {@code DBDESK_DATADIR}；默认 {@code ~/.slackerdb/dbdesk-data}。
 * 上传的 CSV 先暂存在 {@code <datadir>/uploads}，再交给 CopyManager。</p>
 */
public final class DbDeskPaths {

    private DbDeskPaths() {
    }

    public static Path datadir() {
        String value = System.getProperty("dbdesk.datadir");
        if (value == null || value.isBlank()) {
            value = System.getenv("DBDESK_DATADIR");
        }
        if (value == null || value.isBlank()) {
            return Path.of(System.getProperty("user.home"), ".slackerdb", "dbdesk-data");
        }
        return Path.of(value.strip()).toAbsolutePath().normalize();
    }

    public static Path uploadsDir() {
        return datadir().resolve("uploads");
    }
}
