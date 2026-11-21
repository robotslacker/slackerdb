package org.slackerdb.dbcli.job;

import org.slackerdb.dbcli.common.DbcliPaths;

import java.nio.file.Path;

/**
 * 运行启动时的共享 SQLite 库引导：{@code WORK/dbcli_log.db}。
 *
 * <p>这个文件不只在 {@code _JOB} 使用时存在：每次真正执行脚本/控制台/嵌入运行，启动阶段都会
 * 打开一次并建好 schema。JobManager 之后复用它；正常关闭不会删除它，下一次 root 清空 WORK
 * 时回收。未来其它运行期功能也可以使用同一文件。</p>
 */
public final class JobDatabase {

    private JobDatabase() {
    }

    /** 确保 {@code WORK/dbcli_log.db} 已创建并具备当前 schema；不删除文件。 */
    public static void ensure(DbcliPaths paths) {
        Path database = paths.runDatabase();
        SqliteJobStore store;
        try {
            store = new SqliteJobStore(database, JobStoreLocator.ROOT_SCOPE, false);
        } catch (RuntimeException failed) {
            throw new JobException("Cannot initialize the run database [" + database + "]: "
                    + (failed.getMessage() == null ? failed.getClass().getSimpleName()
                            : failed.getMessage()), failed);
        }
        store.close();
    }
}
