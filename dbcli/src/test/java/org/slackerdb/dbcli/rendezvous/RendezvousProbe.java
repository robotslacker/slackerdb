package org.slackerdb.dbcli.rendezvous;

import java.nio.file.Path;

/**
 * 真实子进程探针：给 {@code SqliteRendezvousTest} 用——跨进程会合必须是**两个真进程**才算验证过。
 *
 * <p>用法：{@code RendezvousProbe <同步库> <运行代号> <点名> <超时毫秒|none>}</p>
 * <p>输出（stdout，逐行）：{@code arrived} → {@code released} 或 {@code timeout}。</p>
 * <p>退出码：{@code 0} 已放行；{@code 3} 超时；{@code 4} 用法错误。</p>
 * <p>{@code none} 表示没有时限（{@link RendezvousBackend#NO_TIMEOUT}）——脚本里 {@code _JOB WAIT}
 * 不写 {@code timeout=} 时走的就是这条语义。</p>
 */
public final class RendezvousProbe {

    private RendezvousProbe() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length < 4) {
            System.err.println("usage: RendezvousProbe <sync-db> <generation> <point> <timeoutMillis|none>");
            System.exit(4);
            return;
        }
        Path database = Path.of(args[0]);
        String generation = args[1];
        String point = args[2];
        long timeoutMillis = "none".equalsIgnoreCase(args[3])
                ? RendezvousBackend.NO_TIMEOUT : Long.parseLong(args[3]);
        try (SqliteRendezvous rendezvous = SqliteRendezvous.open(database, generation)) {
            rendezvous.arrive(point, "CHILD");
            System.out.println("arrived");
            System.out.flush();
            boolean released = rendezvous.await(point, timeoutMillis);
            System.out.println(released ? "released" : "timeout");
            System.out.flush();
            System.exit(released ? 0 : 3);
        }
    }
}
