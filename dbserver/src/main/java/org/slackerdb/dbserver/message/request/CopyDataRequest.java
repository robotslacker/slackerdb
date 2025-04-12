package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.IOException;
import java.time.LocalDateTime;

public class CopyDataRequest extends PostgresRequest {
    //  CopyData (F & B)
    //    Byte1('d')
    //      Identifies the message as COPY data.
    //    Int32
    //      Length of message contents in bytes, including self.
    //    Byten
    //      Data that forms part of a COPY data stream.
    //      Messages sent from the backend will always correspond to single data rows,
    //      but messages sent by frontends might divide the data stream arbitrarily.

    byte[]  copyData;

    public CopyDataRequest(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    @Override
    public void decode(byte[] data) {
        copyData = data;
        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        // 记录会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = this.getClass().getSimpleName();
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = LocalDateTime.now();

        // 只是把数据复制到缓冲区，并不会处理
        this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained.write(copyData);

        // 取消会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = "";
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = null;
    }
}
