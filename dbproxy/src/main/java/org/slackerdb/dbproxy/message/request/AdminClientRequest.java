package org.slackerdb.dbproxy.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbproxy.message.PostgresMessage;
import org.slackerdb.dbproxy.message.PostgresRequest;
import org.slackerdb.dbproxy.message.response.AdminClientResp;
import org.slackerdb.dbproxy.server.ProxyInstance;
import org.slackerdb.dbproxy.server.ProxySession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

public class AdminClientRequest extends PostgresRequest {
    public static final byte[] AdminClientRequestHeader = {0x00, 0x00, 0x00, 0x08, 0x01, 0x01, 0x01, 0x01};

    public String clientRequestCommand;

    public AdminClientRequest(ProxyInstance pProxyInstance) {
        super(pProxyInstance);
    }

    @Override
    public void decode(byte[] data) {
        clientRequestCommand = new String(data, StandardCharsets.UTF_8);
        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        System.out.println(clientRequestCommand);
        StringBuilder feedBackMsg = new StringBuilder();
        if (clientRequestCommand.trim().toUpperCase().startsWith("STOP"))
        {
            // 关闭代理服务
            try {
                this.proxyInstance.stop();
            } catch (ServerException e) {
                this.proxyInstance.logger.error("Error closing backend proxy", e);
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
            feedBackMsg.append("SERVER STATUS: ").append(this.proxyInstance.instanceState).append("\n");
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
            feedBackMsg.append(String.format("%-20s", "  Max_Workers:")).append(this.proxyInstance.serverConfiguration.getMax_Workers()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Log:")).append(this.proxyInstance.serverConfiguration.getLog()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Log_Level:")).append(this.proxyInstance.serverConfiguration.getLog_level().levelStr).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Locale:")).append(this.proxyInstance.serverConfiguration.getLocale()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Client_TimeOut:")).append(this.proxyInstance.serverConfiguration.getClient_timeout()).append("\n");

            // 显示当前的代理会话情况
            feedBackMsg.append("SERVER SESSIONS: \n");
            feedBackMsg.append("  Total ").append(this.proxyInstance.proxySessions.size()).append(" clients connected.\n");
            for (Integer sessionId : this.proxyInstance.proxySessions.keySet())
            {
                ProxySession proxySession = this.proxyInstance.getSession(sessionId);
                feedBackMsg.append("    ").append("Session ID: ").append(sessionId).append("\n");
                feedBackMsg.append("    ").append(" Connected: ").append(proxySession.connectedTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
                feedBackMsg.append("    ").append(" Client IP: ").append(proxySession.clientAddress).append("\n");
                feedBackMsg.append("    ").append("    Status: ").append(proxySession.status).append("\n");
                if (proxySession.executingTime == null) {
                    feedBackMsg.append("    ").append("Executing Time      : N/A").append("\n");
                }
                else
                {
                    feedBackMsg.append("    ").append("Executing Time      : ")
                            .append(proxySession.executingTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    // 计算时间差
                    feedBackMsg.append("  (").append(Duration.between(proxySession.executingTime, currentTime).getSeconds()).append(" seconds)\n");

                }
                feedBackMsg.append("\n");
            }
        }
        else if (clientRequestCommand.trim().toUpperCase().startsWith("REGISTER"))
        {
            Map<String, String> appOptions = new HashMap<>();
            String paramName = null;
            String paramValue;
            clientRequestCommand = clientRequestCommand.trim();
            clientRequestCommand = clientRequestCommand.substring("REGISTER".length() + 1);
            clientRequestCommand = clientRequestCommand.trim();

            boolean parameterParseOK = true;
            for (String arg : clientRequestCommand.split(" ")) {
                if (arg.startsWith("--")) {
                    paramName = arg.substring(2).toLowerCase();
                }
                else
                {
                    if (paramName == null)
                    {
                        parameterParseOK = false;
                        feedBackMsg.append("[ADMIN CMD] Unexpected command parameter [").append(clientRequestCommand).append("].");
                        break;
                    }
                    paramValue = arg;
                    appOptions.put(paramName, paramValue);
                    paramName = null;
                }
            }
            if (parameterParseOK && (!appOptions.containsKey("database") || !appOptions.containsKey("target")))
            {
                feedBackMsg.append("[ADMIN CMD] Register need target database info and local alias name [ --database <database name> --target <remote db url>].");
            }
            else {
                // 服务端开始代理
                try {
                    proxyInstance.createAlias(appOptions.get("database"), false);
                    proxyInstance.addAliasTarget(appOptions.get("database"), appOptions.get("target"), 200);
                    feedBackMsg.append("[ADMIN CMD] Register proxy successful!\n");
                } catch (ServerException se) {
                    feedBackMsg.append("[ADMIN CMD] Register proxy failed!\n");
                    feedBackMsg.append("[ADMIN CMD] ").append(se.getMessage());
                }
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
