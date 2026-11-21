package org.slackerdb.dbcli.job;

import java.nio.file.Path;

/**
 * 真实子进程探针：给 {@link JobStoreTimerTest} 用——跨进程会合必须是**两个真进程**才算验证过。
 *
 * <p>用法：{@code JobStoreProbe <job-db> <point> <timeoutMillis|none>}</p>
 * <p>输出（stdout，逐行）：{@code arrived} → {@code released} 或 {@code timeout}。</p>
 * <p>退出码：{@code 0} 已放行；{@code 3} 超时；{@code 4} 用法错误。</p>
 */
public final class JobStoreProbe {

    private JobStoreProbe() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length < 3) {
            System.err.println("usage: JobStoreProbe <job-db> <point> <timeoutMillis|none>");
            System.exit(4);
            return;
        }
        Path database = Path.of(args[0]);
        String point = args[1];
        long timeoutMillis = "none".equalsIgnoreCase(args[2])
                ? SqliteJobStore.NO_TIMEOUT : Long.parseLong(args[2]);
        try (SqliteJobStore store = new SqliteJobStore(database, "probe", false)) {
            store.arrive(point, "CHILD");
            System.out.println("arrived");
            System.out.flush();
            boolean released = store.await(point, timeoutMillis);
            System.out.println(released ? "released" : "timeout");
            System.out.flush();
            System.exit(released ? 0 : 3);
        }
    }
}
