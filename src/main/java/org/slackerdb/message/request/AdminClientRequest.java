package org.slackerdb.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.exceptions.ServerException;
import org.slackerdb.message.PostgresMessage;
import org.slackerdb.message.PostgresRequest;
import org.slackerdb.message.response.AdminClientResp;
import org.slackerdb.server.DBInstance;
import org.slackerdb.server.DBSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class AdminClientRequest  extends PostgresRequest {
    public static final byte[] AdminClientRequestHeader = {0x00, 0x00, 0x00, 0x08, 0x01, 0x01, 0x01, 0x01};

    public String clientRequestCommand;

    public AdminClientRequest(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    @Override
    public void decode(byte[] data) {
        clientRequestCommand = new String(data, StandardCharsets.UTF_8);
        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        // 记录会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = this.getClass().getSimpleName();
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL = clientRequestCommand;
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = LocalDateTime.now();

        StringBuilder feedBackMsg = new StringBuilder();
        if (clientRequestCommand.trim().equalsIgnoreCase("STOP"))
        {
            // 关闭数据库
            try {
                this.dbInstance.stop();
            } catch (ServerException e) {
                this.dbInstance.logger.error("Error closing backend connection", e);
            }
            feedBackMsg.append("Server stop successful.");
        }
        else if (clientRequestCommand.trim().equalsIgnoreCase("STATUS"))
        {
            LocalDateTime currentTime = LocalDateTime.now();

            // 显示当前服务状态
            feedBackMsg.append("SERVER STATUS: ").append(this.dbInstance.instanceState).append("\n");
            feedBackMsg.append("   : ").append(ProcessHandle.current().pid()).append("\n");
            feedBackMsg.append("  PID : ").append(ProcessHandle.current().pid()).append("\n");
            feedBackMsg.append("  Now : ").append(currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
            if (this.dbInstance.bootTime != null) {
                feedBackMsg.append("  Boot: ").append(this.dbInstance.bootTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");

                // 计算时间差
                Duration duration = Duration.between(this.dbInstance.bootTime, currentTime);
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
            feedBackMsg.append(String.format("%-20s", "  Bind_Host:")).append(this.dbInstance.serverConfiguration.getBindHost()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Port:")).append(this.dbInstance.serverConfiguration.getPort()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Data:")).append(this.dbInstance.serverConfiguration.getData()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Data_Dir:")).append(this.dbInstance.serverConfiguration.getData_Dir()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Temp_Dir:")).append(this.dbInstance.serverConfiguration.getTemp_dir()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  SQLHistory_Dir:")).append(this.dbInstance.serverConfiguration.getSqlHistoryDir()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  SQLHistory:")).append(this.dbInstance.serverConfiguration.getSqlHistory()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  PLSQL_Func_Dir:")).append(this.dbInstance.serverConfiguration.getPlsql_func_dir()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Extension_Dir:")).append(this.dbInstance.serverConfiguration.getExtension_dir()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Threads:")).append(this.dbInstance.serverConfiguration.getThreads()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Memory_Limit:")).append(this.dbInstance.serverConfiguration.getMemory_limit()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Max_Workers:")).append(this.dbInstance.serverConfiguration.getMax_Workers()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Access_mode:")).append(this.dbInstance.serverConfiguration.getAccess_mode()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Log:")).append(this.dbInstance.serverConfiguration.getLog()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Log_Level:")).append(this.dbInstance.serverConfiguration.getLog_level().levelStr).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Locale:")).append(this.dbInstance.serverConfiguration.getLocale()).append("\n");
            feedBackMsg.append(String.format("%-20s", "  Client_TimeOut:")).append(this.dbInstance.serverConfiguration.getClient_timeout()).append("\n");

            // 显示数据库基本信息
            feedBackMsg.append("SERVER USAGE: \n");
            feedBackMsg.append("  Max Connections(High water mark): ").append(this.dbInstance.connectionPool.size()).append("\n");
            feedBackMsg.append("  Active Sessions: ").append(this.dbInstance.activeSessions).append("\n");

            // 显示当前的数据库会话情况
            feedBackMsg.append("SERVER SESSIONS: \n");
            feedBackMsg.append("  Total ").append(this.dbInstance.dbSessions.size()).append(" clients connected.\n");
            for (Integer sessionId : this.dbInstance.dbSessions.keySet())
            {
                DBSession dbSession = this.dbInstance.getSession(sessionId);
                feedBackMsg.append("    ").append("Session ID: ").append(sessionId).append("\n");
                feedBackMsg.append("    ").append(" Connected: ").append(dbSession.connectedTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
                feedBackMsg.append("    ").append(" Client IP: ").append(dbSession.clientAddress).append("\n");
                feedBackMsg.append("    ").append("    Status: ").append(dbSession.status).append("\n");
                feedBackMsg.append("    ").append("Executing Function  : ").append(dbSession.executingFunction).append("\n");
                String[] executingSQLasList = dbSession.executingSQL.split("\n");
                for (int i=0;i<executingSQLasList.length;i++)
                {
                    if (i==0)
                    {
                        feedBackMsg.append("    ").append("Executing SQL       : ").append(executingSQLasList[i]).append("\n");
                    }
                    else
                    {
                        feedBackMsg.append("    ").append("                     " ).append(executingSQLasList[i]).append("\n");
                    }
                }

                if (dbSession.executingTime == null) {
                    feedBackMsg.append("    ").append("Executing Time      : N/A").append("\n");
                }
                else
                {
                    feedBackMsg.append("    ").append("Executing Time      : ")
                            .append(dbSession.executingTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    // 计算时间差
                    feedBackMsg.append("  (").append(Duration.between(dbSession.executingTime, currentTime).getSeconds()).append(" seconds)\n");

                }
                feedBackMsg.append("\n");
            }
        }
        else if (clientRequestCommand.trim().toUpperCase().startsWith("KILL"))
        {
            String targetSessionIdStr = clientRequestCommand.trim().toUpperCase().replace("KILL", "").trim();
            int targetSessionId = 0;
            try
            {
                targetSessionId = Integer.parseInt(targetSessionIdStr);
            }
            catch (NumberFormatException ignored) {}
            if (this.dbInstance.dbSessions.containsKey(targetSessionId))
            {
                this.dbInstance.logger.info("[KILL SESSION] Will kill session [{}] ...", targetSessionIdStr);
                feedBackMsg.append("[KILL SESSION] Will kill session [").append(targetSessionIdStr).append("] ...");
                try
                {
                    if (this.dbInstance.dbSessions.get(targetSessionId).executingPreparedStatement != null) {
                        this.dbInstance.dbSessions.get(targetSessionId).executingPreparedStatement.cancel();
                    }
                }
                catch (SQLException ignored) {}
                try
                {
                    this.dbInstance.dbSessions.get(targetSessionId).abortSession();
                }
                catch (SQLException ignored) {}
                this.dbInstance.logger.info("[KILL SESSION] Session [{}] has been killed.", targetSessionIdStr);
                feedBackMsg.append("[KILL SESSION] Session [").append(targetSessionIdStr).append("] has been killed.");
            }
            else
            {
                feedBackMsg.append("[KILL SESSION] Unknown sessionID [").append(targetSessionIdStr).append("].");
            }
        }
        else
        {
            feedBackMsg.append("[ADMIN CMD] Unknown command [").append(clientRequestCommand).append("].");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        AdminClientResp adminClientResp = new AdminClientResp(this.dbInstance);
        adminClientResp.setReturnMsg(feedBackMsg.toString());
        adminClientResp.process(ctx, request, out);

        // 发送并刷新返回消息
        PostgresMessage.writeAndFlush(ctx, AdminClientResp.class.getSimpleName(), out, this.dbInstance.logger);
        out.close();

        // 取消会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = "";
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL = "";
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = null;
    }
}
