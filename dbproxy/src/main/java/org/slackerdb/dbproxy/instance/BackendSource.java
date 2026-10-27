package org.slackerdb.dbproxy.instance;

/**
 * 后端来源。
 *
 * <p>两类后端在代理里的能力完全不同，状态里必须能一眼分开：</p>
 * <ul>
 *   <li>{@link #MANAGEMENT} —— 目录扫描到的实例（代理创建、或用户手工放在 data_dir 下）。
 *       代理拥有目录，因此可以 start/shutdown/restart/destroy；</li>
 *   <li>{@link #EXTERNAL} —— 通过组播注册进来的后端。目录不在代理手里，
 *       只做数据转发，任何生命周期操作都必须拒绝。</li>
 * </ul>
 *
 * <p>别名冲突时内部优先：外部注册会被丢弃，不会出现在状态里。</p>
 */
public enum BackendSource {
    MANAGEMENT,
    EXTERNAL;

    /** 对外 JSON/CLI 使用的名字（小写）。 */
    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
