package org.slackerdb.dbproxy.message.request;

import com.sun.management.OperatingSystemMXBean;
import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbproxy.message.PostgresMessage;
import org.slackerdb.dbproxy.message.response.AdminClientResp;
import org.slackerdb.dbproxy.server.PostgresProxyTarget;
import org.slackerdb.dbproxy.server.ProxyInstance;
import org.slackerdb.dbproxy.message.PostgresRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Properties;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AdminClientRequest extends PostgresRequest {
    public static final byte[] AdminClientRequestHeader = {0x00, 0x00, 0x00, 0x08, 0x01, 0x01, 0x01, 0x01};

    public AdminClientRequest(ProxyInstance pProxyInstance) {
        super(pProxyInstance);
    }

    public String clientRequestCommand;

    @Override
    public void decode(byte[] data) {
        clientRequestCommand = new String(data, StandardCharsets.UTF_8);
        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        StringBuilder feedBackMsg = new StringBuilder();
        if (clientRequestCommand.trim().toUpperCase().startsWith("STOP"))
        {
            // 关闭数据库
            try {
                this.proxyInstance.stop();
            } catch (ServerException e) {
                this.proxyInstance.logger.error("Error closing backend connection", e);
            }
            feedBackMsg.append("Server stop successful.");
        }
        else if (clientRequestCommand.trim().toUpperCase().startsWith("STATUS"))
        {
            LocalDateTime currentTime = LocalDateTime.now();

            // 从资源信息中读取系统的版本号
            String version, localBuildDate;
            try {
                InputStream inputStream = this.getClass().getResourceAsStream("/version.properties");
                Properties properties = new Properties();
                properties.load(inputStream);
                version = properties.getProperty("version", "{project.version}");
                String buildTimestamp = properties.getProperty("build.timestamp", "${build.timestamp}");

                // 转换编译的时间格式
                try {
                    ZonedDateTime zdt = ZonedDateTime.parse(buildTimestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
                    LocalDateTime localDateTime = LocalDateTime.ofInstant(zdt.toInstant(), ZoneId.systemDefault());
                    localBuildDate =
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").format(localDateTime) + " " +
                                    TimeZone.getTimeZone(ZoneId.systemDefault()).getID();
                }
                catch (DateTimeParseException ex)
                {
                    localBuildDate = buildTimestamp;
                }
            }
            catch (IOException ioe)
            {
                version = "{project.version}";
                localBuildDate = "${build.timestamp}";
            }

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
                    .append("Target URL")
                    .append("\n");
            for (String aliasName : this.proxyInstance.proxyTarget.keySet()) {
                PostgresProxyTarget postgresProxyTarget = this.proxyInstance.proxyTarget.get(aliasName);
                feedBackMsg.append("    ")
                        .append(String.format("%-10s", aliasName))
                        .append(String.format("%-25s", postgresProxyTarget.createdDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))))
                        .append(String.format("%-25s", postgresProxyTarget.lastActivatedTIme.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))))
                        .append(postgresProxyTarget.host).append(":").append(postgresProxyTarget.port)
                        .append("/").append(postgresProxyTarget.database)
                        .append("\n");
            }
            feedBackMsg.append("\n");
        }
        else if (clientRequestCommand.trim().toUpperCase().startsWith("REGISTER"))
        {
            Pattern pattern = Pattern.compile(
                    "REGISTER\\s+(\\S+):(\\d+),(\\d+)/(\\S+)\\s+AS\\s+(\\S+)"
            );
            Matcher matcher = pattern.matcher(clientRequestCommand);

            if (matcher.find()) {
                String host = matcher.group(1);
                int port = Integer.parseInt(matcher.group(2));
                int portX = Integer.parseInt(matcher.group(3));
                String service = matcher.group(4);
                String alias = matcher.group(5);
                PostgresProxyTarget postgresProxyTarget = new PostgresProxyTarget();
                postgresProxyTarget.createdDateTime = LocalDateTime.now();
                postgresProxyTarget.lastActivatedTIme = LocalDateTime.now();
                postgresProxyTarget.host = host;
                postgresProxyTarget.port = port;
                postgresProxyTarget.portX = portX;
                postgresProxyTarget.database = service;
                this.proxyInstance.proxyTarget.put(alias, postgresProxyTarget);
                feedBackMsg.append("Register " +
                        "[").append(host).append(":").append(port).append(",").append(portX)
                        .append("/").append(service).append("] " +
                        "as [").append(alias).append("] successful. ");
                // 处理PortX的转发请求
                if (this.proxyInstance.proxyInstanceX != null) {
                    this.proxyInstance.proxyInstanceX.forwarderPathMappings
                            .put("/" + alias, "http://" + postgresProxyTarget.host + ":" + postgresProxyTarget.portX);
                }
            } else {
                feedBackMsg.append("WRONG REGISTER COMMAND FORMAT. ").append(clientRequestCommand);
            }
        }
        else if (clientRequestCommand.trim().toUpperCase().startsWith("UNREGISTER"))
        {
            Pattern pattern = Pattern.compile(
                    "UNREGISTER\\s+(\\S+)"
            );
            Matcher matcher = pattern.matcher(clientRequestCommand);

            if (matcher.find()) {
                String alias = matcher.group(1);
                // 处理PORTX的转发请求
                if (this.proxyInstance.proxyInstanceX != null) {
                    this.proxyInstance.proxyInstanceX.forwarderPathMappings
                            .remove("/" + alias);
                }
                this.proxyInstance.proxyTarget.remove(alias);
                feedBackMsg.append("Unregister " + "[").append(alias).append("] successful. ");
            } else {
                feedBackMsg.append("WRONG UNREGISTER COMMAND FORMAT. ").append(clientRequestCommand);
            }
        }
        else
        {
            feedBackMsg.append("[ADMIN CMD] Unknown command [").append(clientRequestCommand).append("].");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AdminClientResp adminClientResp = new AdminClientResp(this.proxyInstance);
        adminClientResp.setReturnMsg(feedBackMsg.toString());
        adminClientResp.process(ctx, request, out);

        // 发送并刷新返回消息
        PostgresMessage.writeAndFlush(ctx, AdminClientResp.class.getSimpleName(), out, this.proxyInstance.logger);
        out.close();

        // 如果是独占模式, 则停止命令将同时停止程序运行
        if (clientRequestCommand.trim().toUpperCase().startsWith("STOP") && this.proxyInstance.isExclusiveMode())
        {
            System.exit(0);
        }
    }
}
