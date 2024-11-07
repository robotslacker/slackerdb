package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.response.ReadyForQuery;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;

public class SyncRequest extends PostgresRequest {
    public SyncRequest(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    @Override
    public void decode(byte[] data) {
        super.decode(data);
    }
    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        // 记录会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = this.getClass().getSimpleName();
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = LocalDateTime.now();

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
        readyForQuery.process(ctx, request, out);

        // 发送并刷新返回消息
        PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);

        out.close();

        // 取消会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = "";
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = null;
    }
}
