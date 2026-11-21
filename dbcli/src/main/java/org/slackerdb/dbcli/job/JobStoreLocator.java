package org.slackerdb.dbcli.job;

import org.slackerdb.dbcli.common.DbcliPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 决定"这次运行的作业库在哪儿、是不是我建的、我的 scope 是什么"。
 *
 * <h2>根进程</h2>
 * <p>没有内部作业环境变量时，库固定为 {@code WORK/dbcli_log.db}；scope 为 {@code root}，
 * owner=false（正常关闭不删文件，下一次 root 清空 WORK 时回收）。该文件由运行启动阶段
 * 无论是否使用 {@code _JOB} 都创建，JobManager 只是它的使用者之一。</p>
 *
 * <h2>作业子进程</h2>
 * <p>父进程把库路径与自己的子 scope 写进环境变量；这里只打开已有的库，owner=false。
 * 环境变量"只给了一半"或库文件不存在时明确报错，**不**静默开始一次新的运行
 * （否则屏障永远等不到）。</p>
 */
final class JobStoreLocator {

    /** 根进程的 scope。 */
    static final String ROOT_SCOPE = "root";

    /** 旧版临时目录下的子目录名（仅兼容无 paths 的旧调用）。 */
    static final String DIRECTORY_NAME = "dbcli-jobs";

    /** 无主文件的最长保留时间（毫秒）。 */
    private static final long STALE_AGE_MILLIS = Duration.ofDays(7).toMillis();

    /** "pid 已不在"的文件的等待期（毫秒）。 */
    private static final long DEAD_PID_AGE_MILLIS = Duration.ofHours(1).toMillis();

    private JobStoreLocator() {
    }

    /**
     * 打开本次运行共享的库：{@code WORK/dbcli_log.db}。
     *
     * @param paths 本次运行的目录；子进程仍读内部环境变量加入父进程的库
     */
    static SqliteJobStore open(DbcliPaths paths) {
        String database = System.getenv(SqliteJobStore.ENV_JOB_DB);
        String scope = System.getenv(SqliteJobStore.ENV_JOB_SCOPE);
        if (database != null || scope != null) {
            return joinExistingRun(database, scope);
        }
        return new SqliteJobStore(paths.runDatabase(), ROOT_SCOPE, false);
    }

    /** 兼容没有三目录的旧调用（测试/独立管理器）：仍使用系统临时目录。 */
    static SqliteJobStore open() {
        String database = System.getenv(SqliteJobStore.ENV_JOB_DB);
        String scope = System.getenv(SqliteJobStore.ENV_JOB_SCOPE);
        if (database != null || scope != null) {
            return joinExistingRun(database, scope);
        }
        return createNewRun();
    }

    private static SqliteJobStore joinExistingRun(String database, String scope) {
        if (database == null || database.isBlank() || scope == null || scope.isBlank()) {
            throw new JobException("The job environment is incomplete: both "
                    + SqliteJobStore.ENV_JOB_DB + " and " + SqliteJobStore.ENV_JOB_SCOPE
                    + " must be set when joining an existing run.");
        }
        Path path = Path.of(database);
        if (!Files.isRegularFile(path)) {
            throw new JobException("The job database [" + path + "] does not exist; "
                    + "this process was started as a job, so it cannot begin a new run.");
        }
        return new SqliteJobStore(path, scope, false);
    }

    private static SqliteJobStore createNewRun() {
        Path directory = Path.of(System.getProperty("java.io.tmpdir", "."), DIRECTORY_NAME);
        try {
            Files.createDirectories(directory);
        } catch (IOException failed) {
            throw new JobException("Cannot create the job database directory [" + directory
                    + "]: " + failed.getMessage(), failed);
        }
        removeStaleDatabases(directory);
        String runId = ProcessHandle.current().pid() + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
        Path database = directory.resolve(runId + ".db");
        return new SqliteJobStore(database, ROOT_SCOPE);
    }

    /**
     * 清理同目录下的旧库（best-effort）：被强杀的运行会留下文件，不清理会慢慢堆积。
     * 只删"确定没人用"的：pid 已不在且超过 1 小时，或超过 7 天。
     */
    private static void removeStaleDatabases(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            entries.filter(Files::isRegularFile)
                    .filter(JobStoreLocator::looksLikeJobDatabase)
                    .forEach(JobStoreLocator::deleteIfStale);
        } catch (IOException | RuntimeException ignored) {
            // 清理失败不影响本次运行
        }
    }

    private static boolean looksLikeJobDatabase(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        return name.endsWith(".db") || name.endsWith(".db-wal") || name.endsWith(".db-shm");
    }

    private static void deleteIfStale(Path file) {
        long age;
        try {
            age = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis();
        } catch (IOException unreadable) {
            return;
        }
        if (age > STALE_AGE_MILLIS) {
            deleteQuietly(file);
            deleteQuietly(Path.of(file + "-wal"));
            deleteQuietly(Path.of(file + "-shm"));
            return;
        }
        if (age > DEAD_PID_AGE_MILLIS && !ownerAlive(file)) {
            deleteQuietly(file);
            deleteQuietly(Path.of(file + "-wal"));
            deleteQuietly(Path.of(file + "-shm"));
        }
    }

    /** 从文件名开头的 pid 判断建库进程是否还活着（解析不了就当"可能活着"）。 */
    private static boolean ownerAlive(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        int dash = name.indexOf('-');
        if (dash <= 0) {
            return true;
        }
        try {
            long pid = Long.parseLong(name.substring(0, dash));
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (NumberFormatException notAPid) {
            return true;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException ignored) {
            // 尽力清理：删不掉不影响本次运行
        }
    }
}
