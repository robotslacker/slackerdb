package org.slackerdb.dbproxy.message.request;

import com.sun.management.OperatingSystemMXBean;
import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbproxy.BuildInfo;
import org.slackerdb.dbproxy.instance.InstanceController;
import org.slackerdb.dbproxy.instance.InstanceException;
import org.slackerdb.dbproxy.instance.InstanceSpec;
import org.slackerdb.dbproxy.instance.InstanceText;
import org.slackerdb.dbproxy.instance.ObservedInstance;
import org.slackerdb.dbproxy.message.PostgresMessage;
import org.slackerdb.dbproxy.message.response.AdminClientResp;
import org.slackerdb.dbproxy.server.PostgresProxyTarget;
import org.slackerdb.dbproxy.server.ProxyInstance;
import org.slackerdb.dbproxy.message.PostgresRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public class AdminClientRequest extends PostgresRequest {
    public static final byte[] AdminClientRequestHeader = {0x00, 0x00, 0x00, 0x08, 0x01, 0x01, 0x01, 0x01};

    public AdminClientRequest(ProxyInstance pProxyInstance) {
        super(pProxyInstance);
    }

    public String clientRequestCommand;

    /** 命令行的第一个词（大写），例如 {@code LIST} / {@code CREATE}。 */
    private String command = "";

    /** {@code --key value} 形式的参数（key 小写）。 */
    private Map<String, String> options = Map.of();

    @Override
    public void decode(byte[] data) {
        clientRequestCommand = new String(data, StandardCharsets.UTF_8);
        String trimmed = clientRequestCommand.trim();
        if (!trimmed.isEmpty()) {
            String[] tokens = trimmed.split("\\s+", 2);
            command = tokens[0].toUpperCase(Locale.ROOT);
        }
        options = parseOptions(trimmed);
        super.decode(data);
    }

    /**
     * 极简 {@code --key value} 解析。
     *
     * <p>绑定参数（如 {@code --conf}）由客户端原样带上，这里解析出来但不使用；
     * 含空格的取值在这个通道上本来就不受支持（历史协议限制），实例相关参数都是单 token。</p>
     */
    private static Map<String, String> parseOptions(String text) {
        Map<String, String> parsed = new LinkedHashMap<>();
        String[] tokens = text.split("\\s+");
        for (int index = 0; index < tokens.length; index++) {
            String token = tokens[index];
            if (!token.startsWith("--") || token.length() <= 2) {
                continue;
            }
            String key = token.substring(2).toLowerCase(Locale.ROOT);
            String value = "";
            if (index + 1 < tokens.length && !tokens[index + 1].startsWith("--")) {
                value = tokens[++index];
            }
            parsed.put(key, value);
        }
        return parsed;
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        StringBuilder feedBackMsg = new StringBuilder();
        if (command.equals("STOP"))
        {
            // 关闭数据库
            try {
                this.proxyInstance.stop();
            } catch (ServerException e) {
                this.proxyInstance.logger.error("Error closing backend connection", e);
            }
            feedBackMsg.append("Server stop successful.");
        }
        else if (command.equals("STATUS"))
        {
            LocalDateTime currentTime = LocalDateTime.now();

            // 版本与构建时刻来自构建期生成的 META-INF/build-info.properties
            String version = BuildInfo.version();
            String localBuildDate = BuildInfo.buildTime();

            // 显示当前服务状态
            feedBackMsg.append("PROXY STATUS: ").append(this.proxyInstance.instanceState).append("\n");
            feedBackMsg.append("  VERSION : ").append(version).append("\n");
            feedBackMsg.append("  BUILD : ").append(localBuildDate).append("\n");
            feedBackMsg.append("  PID : ").append(ProcessHandle.current().pid()).append("\n");
            feedBackMsg.append("  Now : ").append(currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
            if (this.proxyInstance.bootTime != null) {
                feedBackMsg.append("  Boot: ").append(this.proxyInstance.bootTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");

                // 计算时间差
                Duration duration = Duration.between(this.proxyInstance.bootTime, currentTime);
                long days = duration.toDays();
                long hours = duration.minusDays(days).toHours();
                long minutes = duration.minusDays(days).minusHours(hours).toMinutes();
                long seconds = duration.minusDays(days).minusHours(hours).minusMinutes(minutes).getSeconds();
                String readableTimeDifference = String.format("%d day%s, %d hour%s, %d minute%s, and %d second%s",
                        days, days == 1 ? "" : "s",
                        hours, hours == 1 ? "" : "s",
                        minutes, minutes == 1 ? "" : "s",
                        seconds, seconds == 1 ? "" : "s"
                );
                feedBackMsg.append("  Run : ").append(readableTimeDifference).append("\n");
            }

            // 打印服务器的运行参数
            feedBackMsg.append("SERVER PARAMETER: \n");
            feedBackMsg.append(String.format("%-20s", "  Bind_Host:")).append(this.proxyInstance.serverConfiguration.getBindHost()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Port:")).append(this.proxyInstance.serverConfiguration.getPort()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Port_X:")).append(this.proxyInstance.serverConfiguration.getPortX()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Max_Workers:")).append(this.proxyInstance.serverConfiguration.getMax_Workers()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Log:")).append(this.proxyInstance.serverConfiguration.getLog()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Log_Level:")).append(this.proxyInstance.serverConfiguration.getLog_level().levelStr).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Locale:")).append(this.proxyInstance.serverConfiguration.getLocale()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Client_Timeout:")).append(this.proxyInstance.serverConfiguration.getClient_timeout()).append("\n");

            OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            feedBackMsg.append(String.format("  CPU Load: %.2f%%", osBean.getProcessCpuLoad() * 100)).append("\n");
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            feedBackMsg.append("  Active Threads:   ").append(threadBean.getThreadCount()).append("\n");
            feedBackMsg.append(String.format("  Heap Memory: %s, Non-Heap Memory: %s",
                    Utils.formatBytes(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()),
                    Utils.formatBytes(ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed()))).append("\n");

            // 显示当前的数据库会话情况
            feedBackMsg.append("PROXY SESSIONS: \n");
            feedBackMsg.append("  Total ").append(this.proxyInstance.proxyTarget.size()).append(" forwarders working.\n");
            feedBackMsg.append("    ")
                    .append(String.format("%-10s", "AliasName"))
                    .append(String.format("%-25s", "Created Time"))
                    .append(String.format("%-25s", "Activated Time"))
                    .append(String.format("%-16s", "Lease"))
                    .append("Target URL")
                    .append("\n");
            for (String aliasName : this.proxyInstance.proxyTarget.keySet()) {
                PostgresProxyTarget postgresProxyTarget = this.proxyInstance.proxyTarget.get(aliasName);
                // 租约一栏是排查"发现为什么不通"的关键：正常应显示还剩多久到期
                // （组播每 5 秒续租一次，不会接近 0）；none 表示该条目没有租约、不参与过期清理
                String leaseText;
                if (postgresProxyTarget.leaseExpireAtMs <= 0) {
                    leaseText = "none";
                } else {
                    long remaining = postgresProxyTarget.leaseExpireAtMs - System.currentTimeMillis();
                    leaseText = remaining > 0 ? remaining + "ms" : "expiring";
                }
                feedBackMsg.append("    ")
                        .append(String.format("%-10s", aliasName))
                        .append(String.format("%-25s", postgresProxyTarget.createdDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))))
                        .append(String.format("%-25s", postgresProxyTarget.lastActivatedTIme.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))))
                        .append(String.format("%-16s", leaseText))
                        .append(postgresProxyTarget.host).append(":").append(postgresProxyTarget.port)
                        .append("/").append(postgresProxyTarget.database)
                        .append("\n");
            }
            feedBackMsg.append("\n");
            appendDatabases(feedBackMsg);
        }
        else if (command.equals("LIST"))
        {
            feedBackMsg.append(instanceTable());
        }
        else if (command.equals("CREATE"))
        {
            createInstance(feedBackMsg);
        }
        else if (command.equals("RESUME") || command.equals("START"))
        {
            lifecycle(feedBackMsg, "resume");
        }
        else if (command.equals("SHUTDOWN"))
        {
            lifecycle(feedBackMsg, "shutdown");
        }
        else if (command.equals("RESTART"))
        {
            lifecycle(feedBackMsg, "restart");
        }
        else if (command.equals("DESTROY"))
        {
            destroyInstance(feedBackMsg);
        }
        else
        {
            // 未知命令：一边回给调用方，一边在代理侧留一条记录。
            //
            // 这条日志在升级期间特别有用：旧版 dbserver 的注册通道（REGISTER / UNREGISTER）
            // 已被彻底移除，如果还有没升级的 dbserver 在跑，代理侧会看到 "Unknown command"，
            // 而不是让运维只看到"客户端连不上"却查不出注册根本没发生。
            feedBackMsg.append("[ADMIN CMD] Unknown command [").append(clientRequestCommand).append("].");
            this.proxyInstance.logger.warn("[PROXY][ADMIN      ] Unknown admin command [{}]. Note: the legacy "
                            + "REGISTER/UNREGISTER commands are no longer supported; backends are discovered "
                            + "through multicast (advertise_address on the dbserver, listener_address here).",
                    clientRequestCommand.trim());
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AdminClientResp adminClientResp = new AdminClientResp(this.proxyInstance);
        adminClientResp.setReturnMsg(feedBackMsg.toString());
        adminClientResp.process(ctx, request, out);

        // 发送并刷新返回消息
        PostgresMessage.writeAndFlush(ctx, AdminClientResp.class.getSimpleName(), out, this.proxyInstance.logger);
        out.close();

        // 如果是独占模式, 则停止命令将同时停止程序运行
        if (command.equals("STOP") && this.proxyInstance.isExclusiveMode())
        {
            System.exit(0);
        }
    }

    // ------------------------------------------------------------------ 实例管理命令

    private InstanceController controllerOrNull(StringBuilder feedBackMsg) {
        InstanceController controller = this.proxyInstance.getInstanceController();
        if (controller == null) {
            feedBackMsg.append("Instance management is not enabled. Set data_dir in the proxy configuration.\n");
        }
        return controller;
    }

    private String instanceTable() {
        try {
            // CLI 是"人看真相"的入口，不能拿 5 秒缓存糊弄：强制重新扫描。
            // 列表同时包含内部实例（management）与组播注册（external）。
            return InstanceText.table(this.proxyInstance.listBackends(true));
        } catch (RuntimeException runtimeException) {
            return "Failed to scan instances: " + runtimeException.getMessage() + "\n";
        }
    }

    /** 外部后端只转发、不可治理；命中时写一条原因并返回 true。 */
    private boolean rejectExternal(StringBuilder feedBackMsg, String alias) {
        if (this.proxyInstance.isExternal(alias)) {
            feedBackMsg.append("Backend [").append(alias).append("] is external (registered via multicast) and ")
                    .append("has no local instance directory. Only data forwarding is available; ")
                    .append("lifecycle operations are not supported.\n");
            return true;
        }
        return false;
    }

    private void appendDatabases(StringBuilder feedBackMsg) {
        feedBackMsg.append("DATABASES: \n");
        feedBackMsg.append(instanceTable());
    }

    private void createInstance(StringBuilder feedBackMsg) {
        InstanceController controller = controllerOrNull(feedBackMsg);
        if (controller == null) {
            return;
        }
        String alias = options.get("alias");
        if (alias == null || alias.isBlank()) {
            feedBackMsg.append("Missing --alias.\n");
            return;
        }
        boolean start = !"false".equalsIgnoreCase(options.getOrDefault("start", "true"));
        String name = options.get("name");
        try {
            ObservedInstance created = controller.create(new InstanceSpec(alias, name, start, Map.of()));
            // 内部实例优先：同名外部注册立刻让位
            this.proxyInstance.pruneShadowedExternal(created.alias());
            feedBackMsg.append("Instance [").append(created.alias()).append("] created: state=")
                    .append(created.state()).append(", pg=").append(created.pgPort())
                    .append(", mgmt=").append(created.mgmtPort()).append("\n");
            if (!start) {
                feedBackMsg.append("  (created without starting; use 'resume --alias ")
                        .append(created.alias()).append("' to start it)\n");
            }
        } catch (InstanceException instanceException) {
            feedBackMsg.append("Create failed: ").append(instanceException.getMessage()).append("\n");
        }
    }

    private void lifecycle(StringBuilder feedBackMsg, String action) {
        String alias = options.get("alias");
        if (alias == null || alias.isBlank()) {
            feedBackMsg.append("Missing --alias.\n");
            return;
        }
        if (rejectExternal(feedBackMsg, alias)) {
            return;
        }
        InstanceController controller = controllerOrNull(feedBackMsg);
        if (controller == null) {
            return;
        }
        try {
            ObservedInstance observed = switch (action) {
                case "resume" -> controller.start(alias);
                case "shutdown" -> controller.shutdown(alias);
                case "restart" -> controller.restart(alias);
                default -> throw new InstanceException(InstanceException.BAD_REQUEST,
                        "Unknown lifecycle action [" + action + "].");
            };
            feedBackMsg.append("Instance [").append(observed.alias()).append("] ").append(action)
                    .append(" OK: state=").append(observed.state())
                    .append(", pid=").append(observed.pidText()).append("\n");
        } catch (InstanceException instanceException) {
            feedBackMsg.append("Instance [").append(alias).append("] ").append(action).append(" failed: ")
                    .append(instanceException.getMessage()).append("\n");
        }
    }

    private void destroyInstance(StringBuilder feedBackMsg) {
        String alias = options.get("alias");
        if (alias == null || alias.isBlank()) {
            feedBackMsg.append("Missing --alias.\n");
            return;
        }
        if (rejectExternal(feedBackMsg, alias)) {
            return;
        }
        InstanceController controller = controllerOrNull(feedBackMsg);
        if (controller == null) {
            return;
        }
        try {
            controller.destroy(alias);
            feedBackMsg.append("Instance [").append(alias).append("] destroyed (directory removed).\n");
        } catch (InstanceException instanceException) {
            feedBackMsg.append("Destroy failed: ").append(instanceException.getMessage()).append("\n");
        }
    }
}
