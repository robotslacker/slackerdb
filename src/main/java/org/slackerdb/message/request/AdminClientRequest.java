package org.slackerdb.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.message.PostgresMessage;
import org.slackerdb.message.PostgresRequest;
import org.slackerdb.message.response.AdminClientResp;
import org.slackerdb.server.DBInstance;
import org.slackerdb.server.DBSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class AdminClientRequest  extends PostgresRequest {
    public static byte[] AdminClientRequestHeader = {0x00, 0x00, 0x00, 0x08, 0x01, 0x01, 0x01, 0x01};

    public String clientRequestCommand;

    @Override
    public void decode(byte[] data) {
        clientRequestCommand = new String(data, StandardCharsets.UTF_8);
        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        StringBuilder feedBackMsg;
        if (clientRequestCommand.trim().equalsIgnoreCase("STOP"))
        {
            // 停止服务
            DBInstance.protocolServer.stop(ctx.channel().remoteAddress().toString());
            feedBackMsg = new StringBuilder("Server stop successful.");
        }
        else if (clientRequestCommand.trim().equalsIgnoreCase("STATUS"))
        {
            LocalDateTime currentTime = LocalDateTime.now();

            // 显示当前服务状态
            feedBackMsg = new StringBuilder("SERVER STATUS: \n");
            feedBackMsg.append("  Now : ").append(currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
            feedBackMsg.append("  Boot: ").append(DBInstance.bootTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");

            // 计算时间差
            Duration duration = Duration.between(DBInstance.bootTime, currentTime);
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

            // 显示当前的数据库连接情况
            feedBackMsg.append("  Sessions: total ").append(DBInstance.dbSessions.size()).append(" clients connected.\n");
            for (Integer sessionId : DBInstance.dbSessions.keySet())
            {
                DBSession dbSession = DBInstance.getSession(sessionId);
                feedBackMsg.append("    ").append("Session ID: ").append(sessionId).append("\n");
                feedBackMsg.append("    ").append(" Connected: ").append(dbSession.connectedTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
                feedBackMsg.append("    ").append(" Client IP: ").append(dbSession.clientAddress).append("\n");
                feedBackMsg.append("    ").append("    Status: ").append(dbSession.status).append("\n");
                feedBackMsg.append("    ").append("ExecuteSQL: ").append(dbSession.executeSQL).append("\n");
                feedBackMsg.append("\n");
            }
        }
        else
        {
            feedBackMsg = new StringBuilder("Unknown command [" + clientRequestCommand + "].");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        AdminClientResp adminClientResp = new AdminClientResp();
        adminClientResp.setReturnMsg(feedBackMsg.toString());
        adminClientResp.process(ctx, request, out);

        // 发送并刷新返回消息
        PostgresMessage.writeAndFlush(ctx, AdminClientResp.class.getSimpleName(), out);
        out.close();
    }
}
