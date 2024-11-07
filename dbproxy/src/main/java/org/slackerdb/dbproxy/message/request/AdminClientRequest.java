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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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
        StringBuilder feedBackMsg = new StringBuilder();
        if (clientRequestCommand.trim().equalsIgnoreCase("STOP"))
        {
            // 关闭代理服务
            try {
                this.proxyInstance.stop();
            } catch (ServerException e) {
                this.proxyInstance.logger.error("Error closing backend proxy", e);
            }
            feedBackMsg.append("Server stop successful.");
        }
        else if (clientRequestCommand.trim().equalsIgnoreCase("STATUS"))
        {
            LocalDateTime currentTime = LocalDateTime.now();

            // 显示当前服务状态
            feedBackMsg.append("SERVER STATUS: ").append(this.proxyInstance.instanceState).append("\n");
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
        if (clientRequestCommand.trim().equalsIgnoreCase("STOP") && this.proxyInstance.isExclusiveMode())
        {
            System.exit(0);
        }
    }
}
