package org.slackerdb.dbproxy.instance;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 为新建实例挑两个空闲端口（PG + 管理口）。
 *
 * <p>没有端口范围配置：直接向系统要一个空闲端口，并排除"其他实例 conf 里已经写死的端口"。
 * 探测与实际 bind 之间有极小窗口，启动失败时由调用方报错（用户可改 conf 或重建）。</p>
 */
public final class PortAllocator {

    private PortAllocator() {
    }

    public static int[] allocate(Path root, int count, Collection<Integer> exclude) {
        Set<Integer> used = new HashSet<>();
        if (exclude != null) {
            used.addAll(exclude);
        }
        used.addAll(usedPorts(root));

        List<Integer> picked = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Integer port = null;
            for (int attempt = 0; attempt < 200; attempt++) {
                int candidate = findFreePort();
                if (candidate > 0 && !used.contains(candidate)) {
                    port = candidate;
                    break;
                }
            }
            if (port == null) {
                throw new InstanceException(InstanceException.UNAVAILABLE,
                        "Cannot allocate " + count + " free TCP port(s) for the instance. "
                                + "Close some listeners or start the instance manually with ports you choose.");
            }
            used.add(port);
            picked.add(port);
        }
        return picked.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 扫描 root 下所有实例 conf 已经占用的端口。 */
    public static Set<Integer> usedPorts(Path root) {
        Set<Integer> ports = new HashSet<>();
        if (root == null || !Files.isDirectory(root)) {
            return ports;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                Path conf = child.resolve(InstancePaths.CONF_FILE);
                if (!Files.isRegularFile(conf)) {
                    continue;
                }
                try {
                    InstanceConf parsed = InstanceConf.load(conf);
                    if (parsed.port() > 0) {
                        ports.add(parsed.port());
                    }
                    if (parsed.portX() > 0) {
                        ports.add(parsed.portX());
                    }
                } catch (IOException ignored) {
                    // 读不了的 conf 不参与端口占用计算
                }
            }
        } catch (IOException ignored) {
            return ports;
        }
        return ports;
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException ioe) {
            return -1;
        }
    }
}
