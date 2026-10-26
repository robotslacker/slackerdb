package org.slackerdb.dbdesk.access.definitions;

import org.slackerdb.dbdesk.access.http.ManagementClient;


/**
 * 后端选择（M5）：默认 {@code auto} —— 管理口声明了 {@code features.definitions} 就用服务端，
 * 否则退回本地文件。
 *
 * <p>为什么要有 {@code -Ddbdesk.definitions.backend=file|server|auto}：
 * 排查"到底是页面问题还是服务端问题"时，能一条开关把后端切回去，不用改代码。</p>
 *
 * <p>探测失败（服务端不可达/老版本）一律**退回文件**：门户/看板不能因为服务端没升级就打不开。</p>
 */
public final class DefinitionBackends {

    private static volatile DefinitionBackend current;

    private DefinitionBackends() {
    }

    /** 当前生效的后端（进程内单例，第一次调用时按配置解析）。 */
    public static DefinitionBackend current() {
        DefinitionBackend backend = current;
        if (backend == null) {
            synchronized (DefinitionBackends.class) {
                backend = current;
                if (backend == null) {
                    backend = resolve();
                    current = backend;
                }
            }
        }
        return backend;
    }

    /** 显式指定（测试/排查用）。传 null 表示重新按配置解析。 */
    public static void override(DefinitionBackend backend) {
        current = backend;
    }

    private static DefinitionBackend resolve() {
        String mode = System.getProperty("dbdesk.definitions.backend", "auto").trim().toLowerCase();
        if ("file".equals(mode)) {
            return FileDefinitionBackend.defaultBackend();
        }
        String managementUrl = managementUrl();
        if (managementUrl == null) {
            return FileDefinitionBackend.defaultBackend();
        }
        if ("server".equals(mode)) {
            return new ServerDefinitionBackend(managementUrl);
        }
        // auto：探一下能力（失败一律退回文件：服务端没升级时页面不能打不开）
        try (ManagementClient client = new ManagementClient(managementUrl)) {
            return client.capabilities().features().definitions()
                    ? new ServerDefinitionBackend(managementUrl)
                    : FileDefinitionBackend.defaultBackend();
        } catch (Exception notReachable) {
            return FileDefinitionBackend.defaultBackend();
        }
    }

    private static String managementUrl() {
        String explicit = System.getProperty("dbdesk.management.url");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return null;
    }

    /** 状态描述（页面/日志用）。 */
    public static String describe() {
        return current().name();
    }
}
