package org.slackerdb.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.configuration.ServerConfiguration;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.message.PostgresMessage;
import org.slackerdb.message.PostgresRequest;
import org.slackerdb.message.response.AdminClientResp;
import org.slackerdb.server.DBInstance;
import org.slackerdb.server.DBSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class AdminClientRequest  extends PostgresRequest {
    public static final byte[] AdminClientRequestHeader = {0x00, 0x00, 0x00, 0x08, 0x01, 0x01, 0x01, 0x01};

    public String clientRequestCommand;

    @Override
    public void decode(byte[] data) {
        clientRequestCommand = new String(data, StandardCharsets.UTF_8);
        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        // 记录会话的开始时间，以及业务类型
        DBInstance.getSession(getCurrentSessionId(ctx)).executingFunction = this.getClass().getSimpleName();
        DBInstance.getSession(getCurrentSessionId(ctx)).executingSQL = clientRequestCommand;
        DBInstance.getSession(getCurrentSessionId(ctx)).executingTime = LocalDateTime.now();

        StringBuilder feedBackMsg;
        if (clientRequestCommand.trim().equalsIgnoreCase("STOP"))
        {
            // 停止网络服务
            DBInstance.protocolServer.stop(ctx.channel().remoteAddress().toString());
            // 数据库强制进行检查点操作
            DBInstance.forceCheckPoint();
            // 关闭数据库
            try {
                // 关闭所有的连接
                for (Connection conn : DBInstance.connectionPool)
                {
                    if (!conn.isClosed()) {
                        conn.close();
                    }
                }
                DBInstance.connectionPool.clear();
                // 关闭BackendSysConnection
                DBInstance.backendSysConnection.close();
            } catch (SQLException e) {
                AppLogger.logger.error("Error closing backend connection", e);
            }
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

            // 打印服务器的运行参数
            feedBackMsg.append("SERVER PARAMETER: \n");
            feedBackMsg.append(String.format("%-20s", "  Bind_Host:")).append(ServerConfiguration.getBindHost()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Port:")).append(ServerConfiguration.getPort()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Data:")).append(ServerConfiguration.getData()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Data_Dir:")).append(ServerConfiguration.getData_Dir()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Temp_Dir:")).append(ServerConfiguration.getTemp_dir()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Extension_Dir:")).append(ServerConfiguration.getExtension_dir()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Threads:")).append(ServerConfiguration.getThreads()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Memory_Limit:")).append(ServerConfiguration.getMemory_limit()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Max_Workers:")).append(ServerConfiguration.getMax_Workers()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Access_mode:")).append(ServerConfiguration.getAccess_mode()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Log:")).append(ServerConfiguration.getLog()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Log_Level:")).append(ServerConfiguration.getLog_level().levelStr).append("\n");

            // 显示数据库基本信息
            feedBackMsg.append("SERVER USAGE: \n");
            feedBackMsg.append("  Max Connections(High water mark): ").append(DBInstance.connectionPool.size()).append("\n");
            feedBackMsg.append("  Active Sessions: ").append(DBInstance.activeSessions).append("\n");

            // 显示当前的数据库会话情况
            feedBackMsg.append("SERVER SESSIONS: \n");
            feedBackMsg.append("  Total ").append(DBInstance.dbSessions.size()).append(" clients connected.\n");
            for (Integer sessionId : DBInstance.dbSessions.keySet())
            {
                DBSession dbSession = DBInstance.getSession(sessionId);
                feedBackMsg.append("    ").append("Session ID: ").append(sessionId).append("\n");
                feedBackMsg.append("    ").append(" Connected: ").append(dbSession.connectedTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
                feedBackMsg.append("    ").append(" Client IP: ").append(dbSession.clientAddress).append("\n");
                feedBackMsg.append("    ").append("    Status: ").append(dbSession.status).append("\n");
                feedBackMsg.append("    ").append("Executing Function  :").append(dbSession.executingFunction).append("\n");
                feedBackMsg.append("    ").append("Executing SQL       :").append(dbSession.executingSQL).append("\n");
                if (dbSession.executingTime == null) {
                    feedBackMsg.append("    ").append("Executing Time      : N/A").append("\n");
                }
                else
                {
                    feedBackMsg.append("    ").append("Executing Time      :").append(dbSession.executingTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
                }
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


        // 取消会话的开始时间，以及业务类型
        DBInstance.getSession(getCurrentSessionId(ctx)).executingFunction = "";
        DBInstance.getSession(getCurrentSessionId(ctx)).executingSQL = "";
        DBInstance.getSession(getCurrentSessionId(ctx)).executingTime = null;
    }
}
