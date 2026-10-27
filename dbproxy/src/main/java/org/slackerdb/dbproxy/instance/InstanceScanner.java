package org.slackerdb.dbproxy.instance;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扫描 {@code data_dir} 下的所有实例目录。
 *
 * <p>识别规则只有一条：<b>目录里有 {@code instance.conf}</b>。没有 conf 的目录一律忽略
 * （不删、不列），因此把别的东西误放进 data_dir 不会被当成数据库。</p>
 *
 * <p>扫描会跳过 {@code .tmp-*} / {@code .trash-*}（创建/删除中的中间态）与符号链接。</p>
 */
public final class InstanceScanner {

    private final InstanceProbe probe;

    public InstanceScanner(InstanceProbe probe) {
        this.probe = probe;
    }

    public Map<String, ObservedInstance> scan(Path root) {
        return scan(root, false);
    }

    /**
     * @param gcTemporaryDirectories 是否顺手清理 {@code .tmp-*}/{@code .trash-*}；
     *                               只有启动时和显式刷新需要，周期性扫描不必每次删
     */
    public Map<String, ObservedInstance> scan(Path root, boolean gcTemporaryDirectories) {
        if (root == null || !Files.isDirectory(root)) {
            return Map.of();
        }
        if (gcTemporaryDirectories) {
            gcTemporaryDirectories(root);
        }

        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child) || Files.isSymbolicLink(child)) {
                    continue;
                }
                String name = child.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                if (!Files.isRegularFile(child.resolve(InstancePaths.CONF_FILE))) {
                    continue;
                }
                candidates.add(child);
            }
        } catch (IOException ioe) {
            return Map.of();
        }

        Map<String, ObservedInstance> probed = new ConcurrentHashMap<>();
        candidates.parallelStream().forEach(dir -> {
            String alias = dir.getFileName().toString();
            probed.put(alias, probe.probe(alias, dir));
        });

        // 大小写不敏感的系统上它们是同一个名字；Linux 上允许并存，但代理路由无法区分，只能都标 UNKNOWN
        Map<String, List<String>> byLowerCase = new LinkedHashMap<>();
        for (String alias : probed.keySet()) {
            byLowerCase.computeIfAbsent(alias.toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(alias);
        }

        Map<String, ObservedInstance> result = new TreeMap<>(Comparator.naturalOrder());
        for (Map.Entry<String, List<String>> entry : byLowerCase.entrySet()) {
            List<String> aliases = entry.getValue();
            if (aliases.size() > 1) {
                String message = "Aliases collide ignoring case: " + aliases
                        + ". Rename one of the directories; routing is case-insensitive for discovered backends.";
                for (String alias : aliases) {
                    ObservedInstance original = probed.get(alias);
                    result.put(alias, new ObservedInstance(alias, original.dir(), InstanceState.UNKNOWN,
                            original.dataName(), null, original.pgPort(), original.mgmtPort(),
                            InstanceProbe.HEALTH_UNKNOWN, null, message, original.conf()));
                }
            } else {
                String alias = aliases.get(0);
                result.put(alias, probed.get(alias));
            }
        }
        return result;
    }

    /** 单个别名探测（路由未命中时用，避免等下一轮周期扫描）。 */
    public ObservedInstance probe(String alias, Path dir) {
        return probe.probe(alias, dir);
    }

    /**
     * 清理创建/删除中断留下的中间目录。
     *
     * <p>这些前缀永远不会被识别成实例，所以可以安全删除；删除失败只记在返回值里，
     * 不影响扫描。</p>
     */
    public List<String> gcTemporaryDirectories(Path root) {
        List<String> failures = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child) || Files.isSymbolicLink(child)) {
                    continue;
                }
                String name = child.getFileName().toString();
                if (!name.startsWith(InstancePaths.TEMP_PREFIX) && !name.startsWith(InstancePaths.TRASH_PREFIX)) {
                    continue;
                }
                try {
                    deleteRecursively(child);
                } catch (IOException ioe) {
                    failures.add(child + ": " + ioe.getMessage());
                }
            }
        } catch (IOException ioe) {
            failures.add(root + ": " + ioe.getMessage());
        }
        return failures;
    }

    /** 递归删除（Windows 上可能因文件句柄失败，调用方决定重试策略）。 */
    public static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path item : paths) {
                Files.deleteIfExists(item);
            }
        }
    }
}
