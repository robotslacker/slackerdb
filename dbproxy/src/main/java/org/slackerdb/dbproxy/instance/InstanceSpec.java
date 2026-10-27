package org.slackerdb.dbproxy.instance;

import java.util.Map;

/**
 * 创建实例的请求。
 *
 * @param alias       目录名/路由键（会被规范化为小写）
 * @param name        dbserver 的 {@code --data} 库名；为空时等于 alias
 * @param start       创建后是否立即启动（默认 true）
 * @param overrides   需要写进 instance.conf 的额外键（受管键不允许覆盖）
 */
public record InstanceSpec(String alias, String name, boolean start, Map<String, String> overrides) {

    public InstanceSpec {
        alias = InstancePaths.normalizeAlias(alias);
        name = (name == null || name.isBlank()) ? alias : name.trim();
        overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
    }

    public static InstanceSpec of(String alias) {
        return new InstanceSpec(alias, null, true, Map.of());
    }
}
