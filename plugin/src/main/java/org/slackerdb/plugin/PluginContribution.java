package org.slackerdb.plugin;

import java.util.Map;

/**
 * 插件向平台声明的能力。
 *
 * <p><b>为什么需要它</b>：平台（控制台）要知道"这个插件提供了什么、它的接口在哪、契约是第几版"，
 * 过去只能靠探测端点（例如去调 {@code /scheduler/status}）来推断，既不可靠也无法表达版本差异。
 * 让插件自己声明之后，客户端按声明决定菜单与按钮的可用性，插件缺席或版本较老时自动降级。</p>
 *
 * @param routePrefix     路由前缀（如 {@code /scheduler}），用于客户端拼接与文档展示
 * @param contractVersion 插件对外契约版本；客户端可据此决定是否启用某项功能
 * @param capabilities    能力开关（如 {@code {"etl": true, "templates": false}}），
 *                        客户端只用它做显隐，不做业务判断
 */
public record PluginContribution(String routePrefix, int contractVersion, Map<String, Object> capabilities) {

    /** 便捷构造：只声明路由前缀与契约版本。 */
    public static PluginContribution of(String routePrefix, int contractVersion) {
        return new PluginContribution(routePrefix, contractVersion, Map.of());
    }
}
