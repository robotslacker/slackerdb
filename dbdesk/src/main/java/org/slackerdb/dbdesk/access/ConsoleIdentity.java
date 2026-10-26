package org.slackerdb.dbdesk.access;

/**
 * 控制台自己的身份（用于 {@code application_name} 打标）。
 *
 * <p>服务端把 {@code application_name} 填进 {@code /status.sessions[]}，会话面板据此把
 * "本机控制台自己的连接"标出来 —— 用户在终止会话时最容易误杀的就是它。</p>
 *
 * <p>约定值见契约 S-2：{@code slackerdb-console/<ver>}（dbcli 对应 {@code slackerdb-dbcli/<ver>}）。</p>
 */
public final class ConsoleIdentity {

    private static final String APPLICATION_NAME_PREFIX = "slackerdb-console/";

    private ConsoleIdentity() {
    }

    public static String applicationName() {
        return APPLICATION_NAME_PREFIX + version();
    }

    /** 优先取 jar manifest 里的实现版本；开发态（target/classes）没有，退回 {@code dev}。 */
    public static String version() {
        String version = ConsoleIdentity.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "dev" : version;
    }

    /** 判断某个 {@code applicationName} 是不是控制台自己（供会话面板用）。 */
    public static boolean isConsole(String applicationName) {
        return applicationName != null && applicationName.startsWith(APPLICATION_NAME_PREFIX);
    }
}
