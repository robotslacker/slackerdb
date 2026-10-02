package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.response.CloseComplete;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.server.DBSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;


public class CloseRequest extends PostgresRequest {
    //  Close (F)
    //    Byte1('C')
    //      Identifies the message as a Close command.
    //    Int32
    //      Length of message contents in bytes, including self.
    //    Byte1
    //      'S' to close a prepared statement; or 'P' to close a portal.
    //    String
    //      The name of the prepared statement or portal to close
    //      (an empty string selects the unnamed prepared statement or portal).

    public char closeType;
    public String portalName;

    public CloseRequest(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    @Override
    public void decode(byte[] data) {
        // 报文体：Byte1(closeType) + String(name) + 结尾 0
        //
        // **必须剥掉结尾的 0**：它是 CString 的终止符，不属于名字。驱动（pgjdbc 及本仓库的
        // dbdriver，见 QueryExecutorImpl.sendCloseStatement/sendClosePortal）在名字后一定会写这个 0。
        // 改造前这里把整段字节（含 0）当成名字，缓存 key 于是变成
        // "PreparedStatement-s1\0"，与 Parse/Bind 存入的 key 对不上 ——
        // Close 清不掉任何东西，却仍然照常回 CloseComplete，语句/门户静默泄漏到会话结束。
        if (data == null || data.length == 0) {
            super.decode(data);
            return;
        }

        closeType = (char) (data[0] & 0xFF);

        int nameLength = data.length - 1;
        if (nameLength > 0 && data[data.length - 1] == 0) {
            nameLength--;
        }
        portalName = new String(data, 1, nameLength, StandardCharsets.UTF_8);

        // key 规则必须与写入方完全一致：
        //   * Parse/Bind 对**匿名语句**统一记作 "NONAME"（ParseRequest.decode）；
        //   * Bind 对**匿名门户**保持空串（BindRequest.decode）。
        if (closeType == 'S' && portalName.isEmpty()) {
            portalName = "NONAME";
        }

        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        DBSession session = this.dbInstance.getSession(getCurrentSessionId(ctx));

        // 记录会话的开始时间，以及业务类型
        session.executingFunction = this.getClass().getSimpleName();
        session.executingTime = LocalDateTime.now();

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            if (closeType == 'S') {
                session.clearParsedStatement("PreparedStatement" + "-" + portalName);
            }
            else {
                // (closeType == 'P')
                // 关门户只释放它的结果集，不动共享的 PreparedStatement（见 DBSession.closePortal）。
                session.closePortal("Portal" + "-" + portalName);
            }
        }
        catch (Exception e) {
            // 释放资源失败不能演变成"不回包"：客户端还在等 CloseComplete，
            // 不回就会挂到空闲超时。这里记录日志后照常应答。
            this.dbInstance.logger.error("[SERVER][PG PROTOCOL] Failed to release resource for Close [{}].",
                    closeType == 'S'
                            ? "PreparedStatement-" + portalName
                            : "Portal-" + portalName, e);
        }

        // 标记Close完成
        CloseComplete closeComplete = new CloseComplete(this.dbInstance);
        closeComplete.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, CloseComplete.class.getSimpleName(), out, this.dbInstance.logger);
        out.close();

        // 取消会话的开始时间，以及业务类型
        session.executingFunction = "";
        session.executingTime = null;
    }
}
