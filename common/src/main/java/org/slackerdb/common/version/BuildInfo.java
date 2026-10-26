package org.slackerdb.common.version;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 构建信息（构件名 / 版本号 / 构建时刻）的**唯一**读取入口。
 *
 * <p>数据来自 classpath 上的 {@code META-INF/build-info.properties}，该文件由 Maven 的
 * {@code spring-boot-maven-plugin} 的 {@code build-info} 目标在 {@code generate-resources}
 * 阶段生成到 {@code target/classes/META-INF/}，随资源打进各模块的 jar。因此：</p>
 * <ul>
 *   <li>源码树里<b>没有</b>可手工编辑的版本文件，“jar 里的版本 / POM 里的版本”不可能对不上；</li>
 *   <li>键名是 Spring Boot 的公共约定：{@code build.artifact} / {@code build.group} /
 *       {@code build.name} / {@code build.version} / {@code build.time}；</li>
 *   <li>{@code build.time} 是 <b>ISO-8601 的 UTC 瞬时</b>（{@link Instant}，带 {@code Z}），
 *       与“在哪个时区的机器上构建 / 在哪台机器上展示”无关；展示时才转成本地时间并<b>带上偏移</b>
 *       （{@code 2026-10-20 23:13:25 +08:00}），因此不存在“本地时钟 vs UTC”这类歧义。</li>
 * </ul>
 *
 * <p><b>为什么按构件名挑选，而不是取第一个</b>：classpath 上有多份同名文件是常态——Spring Boot
 * 的 fat jar 里每个依赖都可能自带 {@code META-INF/build-info.properties}。拿到别人的版本号比拿不到
 * 更糟（它会安静地印出一个错版本），所以这里按 {@code build.artifact} <b>精确匹配</b>调用方声明的
 * 构件；匹配不上就退化成可读的 {@code unknown}，绝不回显别人的值、也不回显 {@code ${...}} 占位符。</p>
 *
 * <p>读取失败（资源缺失、内容损坏、时间格式不认识）一律<b>不抛异常</b>：版本信息缺失不该让进程起不来。</p>
 *
 * <p>各模块的用法：在自己模块里放一个薄适配类（例如 {@code org.slackerdb.dbserver.BuildInfo}），
 * 在那里固定“本构件是谁”，调用点只用一行取版本号 / 构建时刻。</p>
 */
public final class BuildInfo {

    /** 生成物的 classpath 位置：由 {@code spring-boot-maven-plugin:build-info} 决定，不能改。 */
    public static final String RESOURCE = "META-INF/build-info.properties";

    /** 读不到任何信息时的可读占位值（绝不能是 {@code ${...}}，那会漏进用户看到的输出）。 */
    private static final String UNKNOWN = "unknown";

    /** 展示格式：本地时间 + 数字偏移，跨时区看图也不会误读。 */
    private static final DateTimeFormatter LOCAL_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");

    /** 按构件名缓存：版本信息整个进程只读一次。 */
    private static final Map<String, BuildInfo> CACHE = new ConcurrentHashMap<>();

    /** 什么都读不到时的实例（所有取值都是 {@code unknown}）。 */
    private static final BuildInfo EMPTY = new BuildInfo(null);

    private final String artifact;
    private final String name;
    private final String version;
    private final Instant time;

    private BuildInfo(Properties properties) {
        this.artifact = usable(properties, "build.artifact");
        this.name = firstName(properties);
        this.version = usable(properties, "build.version");
        this.time = parseTime(usable(properties, "build.time"));
    }

    /**
     * 取<b>本构件</b>的构建信息。
     *
     * @param expectedArtifact 本模块的 Maven {@code <artifactId>}，例如 {@code slackerdb-dbserver}；
     *                         只有 {@code build.artifact} 与它完全一致的条目才会被采用
     * @return 永不为 {@code null}；读不到时各取值为 {@code unknown}
     */
    public static BuildInfo of(String expectedArtifact) {
        if (expectedArtifact == null || expectedArtifact.isBlank()) {
            return EMPTY;
        }
        return CACHE.computeIfAbsent(expectedArtifact,
                key -> load(key, BuildInfo.class.getClassLoader()));
    }

    /**
     * 扫描 classpath 上所有 {@code META-INF/build-info.properties}，挑出构件名匹配的那一份。
     *
     * <p>包级可见：单元测试用自建的 {@link ClassLoader} 喂入多份候选，验证“精确匹配”与
     * “匹配不上就退化”这两条口径。</p>
     */
    static BuildInfo load(String expectedArtifact, ClassLoader classLoader) {
        List<Properties> candidates = new ArrayList<>();
        try {
            Enumeration<URL> resources = classLoader.getResources(RESOURCE);
            while (resources.hasMoreElements()) {
                Properties properties = readQuietly(resources.nextElement());
                if (properties != null) {
                    candidates.add(properties);
                }
            }
        } catch (IOException unreadableClasspath) {
            return EMPTY;
        }
        for (Properties properties : candidates) {
            if (expectedArtifact.equals(usable(properties, "build.artifact"))) {
                return new BuildInfo(properties);
            }
        }
        return EMPTY;
    }

    /** 构件名（{@code build.artifact}），例如 {@code slackerdb-dbcli}；读不到时是 {@code unknown}。 */
    public String artifact() {
        return artifact == null ? UNKNOWN : artifact;
    }

    /**
     * 给人看的名字：优先 {@code build.product}（各模块可用 {@code additionalProperties} 声明，
     * 例如 {@code dbcli (SlackerDB command line client)}），其次 {@code build.name}，再次构件名。
     */
    public String name() {
        if (name != null) {
            return name;
        }
        return artifact();
    }

    /** 版本号（{@code build.version}，即 {@code <project.version>}），例如 {@code 0.2.2}。 */
    public String version() {
        return version == null ? UNKNOWN : version;
    }

    /** 构建时刻的 {@link Instant}（UTC）；读不到或格式不认识时是 {@code null}。 */
    public Instant time() {
        return time;
    }

    /**
     * 构建时刻的展示串：本地时间 + 偏移，例如 {@code 2026-10-20 23:13:25 +08:00}；
     * 读不到时是 {@code unknown}。
     */
    public String buildTime() {
        return time == null ? UNKNOWN : LOCAL_TIME.format(time.atZone(ZoneId.systemDefault()));
    }

    /** 逐个读取候选文件；单份读坏不影响其它候选。 */
    private static Properties readQuietly(URL resource) {
        try (InputStream in = resource.openStream()) {
            Properties properties = new Properties();
            properties.load(in);
            return properties;
        } catch (IOException | IllegalArgumentException unreadable) {
            return null;
        }
    }

    /** 键缺失、为空、或残留未替换的 {@code ${...}} 占位符时都返回 {@code null}。 */
    private static String usable(Properties properties, String key) {
        if (properties == null) {
            return null;
        }
        String value = properties.getProperty(key);
        if (value == null || value.isBlank() || value.contains("${")) {
            return null;
        }
        return value.trim();
    }

    /** {@code build.product} → {@code build.name}，都没有则 {@code null}（由 {@link #name()} 兜底）。 */
    private static String firstName(Properties properties) {
        String product = usable(properties, "build.product");
        return product != null ? product : usable(properties, "build.name");
    }

    /** {@code build.time} 是 ISO-8601 的 UTC 瞬时；解析不了就当没有（展示为 {@code unknown}）。 */
    private static Instant parseTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException notAnInstant) {
            return null;
        }
    }
}
