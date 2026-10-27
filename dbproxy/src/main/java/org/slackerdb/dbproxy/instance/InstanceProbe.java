package org.slackerdb.dbproxy.instance;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 单实例探测：只读，不启动、不停止任何东西。
 *
 * <p>判定顺序是刻意的：<b>{@code /health} &gt; 进程存活 &gt; 记忆</b>。
 * 只要管理口说 UP，实例就是活的——哪怕 pid 文件丢了（用户手工启动时常见）。</p>
 */
public final class InstanceProbe {

    public static final String HEALTH_UP = "UP";
    public static final String HEALTH_DOWN = "DOWN";
    public static final String HEALTH_TIMEOUT = "TIMEOUT";
    public static final String HEALTH_UNKNOWN = "UNKNOWN";

    private static final String LOCAL_HOST = "127.0.0.1";

    private final HttpClient http;
    private final Duration timeout;

    public InstanceProbe() {
        this(Duration.ofSeconds(1));
    }

    public InstanceProbe(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * 探测一个实例目录。
     *
     * @param alias 目录名（= 别名）
     * @param dir   实例目录
     */
    public ObservedInstance probe(String alias, Path dir) {
        InstancePaths paths = new InstancePaths(alias, dir);

        InstanceConf conf;
        try {
            conf = InstanceConf.load(paths.conf());
        } catch (IOException ioe) {
            return new ObservedInstance(alias, dir, InstanceState.UNKNOWN, "", null, -1, -1,
                    HEALTH_UNKNOWN, null, "Cannot read " + InstancePaths.CONF_FILE + ": " + ioe.getMessage(), null);
        }

        List<String> problems = conf.problems();
        if (!problems.isEmpty()) {
            return new ObservedInstance(alias, dir, InstanceState.UNKNOWN, conf.dataName(), null,
                    conf.port(), conf.portX(), HEALTH_UNKNOWN, null, String.join("; ", problems), conf);
        }

        Integer pidFromFile = readPid(paths.pid());
        ProcessHandle handle = pidFromFile == null ? null : ProcessHandle.of(pidFromFile).orElse(null);
        boolean alive = handle != null && handle.isAlive();
        boolean verified = alive && isOwnProcess(paths, handle);

        String health = probeHealth(conf.portX());
        Long uptime = alive
                ? handle.info().startInstant()
                    .map(start -> Math.max(0L, Duration.between(start, Instant.now()).getSeconds()))
                    .orElse(null)
                : null;

        InstanceState state;
        String error = null;
        if (HEALTH_UP.equals(health)) {
            // 管理口是权威信号：即使 pid 文件缺失/过期也认定在运行
            state = InstanceState.RUNNING;
        } else if (alive && verified) {
            state = HEALTH_UNKNOWN.equals(health) ? InstanceState.RUNNING : InstanceState.DEGRADED;
        } else if (alive) {
            // pid 存活但命令行不匹配：多半是 pid 被系统复用，绝不能据此发信号
            state = InstanceState.UNKNOWN;
            error = "pid " + pidFromFile + " is alive but its command line does not reference "
                    + paths.conf().toAbsolutePath();
        } else {
            state = InstanceState.STOPPED;
            if (pidFromFile != null) {
                error = "stale pid file (process " + pidFromFile + " is gone)";
            }
        }

        Integer reportedPid = verified ? pidFromFile : null;

        // 停止的实例端口本来就是关的，把"连接被拒绝"显示成 DOWN 只会误导运维。
        if (state == InstanceState.STOPPED) {
            health = HEALTH_UNKNOWN;
        }

        return new ObservedInstance(alias, dir, state, conf.dataName(), reportedPid,
                conf.port(), conf.portX(), health, uptime, error, conf);
    }

    /** 读 pid 文件；不存在/非法/非正数时返回 {@code null}。 */
    public static Integer readPid(Path pidFile) {
        if (pidFile == null || !Files.isRegularFile(pidFile)) {
            return null;
        }
        try {
            String text = Files.readString(pidFile, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                return null;
            }
            long value = Long.parseLong(text);
            return value > 0 && value <= Integer.MAX_VALUE ? (int) value : null;
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    /**
     * 命令行校验：进程必须确实是"本实例"。
     *
     * <p>这一步是 {@code shutdown}/{@code destroy} 的安全底线——PID 会被系统复用，
     * 只按数字发信号可能杀掉毫不相干的进程。取不到命令行时按"是"处理：
     * 那种情况下健康检查仍然是主信号，而发信号前还会再确认一次。</p>
     */
    public static boolean isOwnProcess(InstancePaths paths, ProcessHandle handle) {
        if (handle == null) {
            return false;
        }
        String commandLine = handle.info().commandLine().orElse("");
        if (commandLine.isEmpty()) {
            return true;
        }
        String confPath = paths.conf().toAbsolutePath().toString();
        return normalize(commandLine).contains(normalize(confPath));
    }

    public static boolean isPidAlive(Integer pid) {
        if (pid == null || pid <= 0) {
            return false;
        }
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        return handle.isPresent() && handle.get().isAlive();
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace('\\', '/');
    }

    private String probeHealth(int port) {
        if (port < 1 || port > 65535) {
            return HEALTH_UNKNOWN;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + LOCAL_HOST + ":" + port + "/health"))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return HEALTH_DOWN;
            }
            JSONObject body = JSON.parseObject(response.body());
            String status = body == null ? null : body.getString("status");
            return "UP".equalsIgnoreCase(status) ? HEALTH_UP : HEALTH_DOWN;
        } catch (HttpTimeoutException timeoutException) {
            return HEALTH_TIMEOUT;
        } catch (Exception e) {
            return HEALTH_DOWN;
        }
    }
}
