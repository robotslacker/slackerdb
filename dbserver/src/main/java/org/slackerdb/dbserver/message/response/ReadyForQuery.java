package org.slackerdb.dbserver.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.common.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class ReadyForQuery extends PostgresMessage {
    public ReadyForQuery(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) throws IOException {
        //  ReadyForQuery (B)
        //    Byte1('Z')
        //      Identifies the message type.
        //      ReadyForQuery is sent whenever the backend is ready for a new query cycle.
        //    Int32(5)
        //      Length of message contents in bytes, including self.
        //    Byte1
        //      Current backend transaction status indicator.
        //      Possible values are 'I' if idle (not in a transaction block);
        //      'T' if in a transaction block;
        //      or 'E' if in a failed transaction block (queries will be rejected until block is ended).
        out.write((byte) 'Z');
        out.write(Utils.int32ToBytes(5));

        // 事务状态字节直接取自会话的事务状态机（'I' / 'T' / 'E'）。
        // 修复前这里只会回 'T' 或 'I'，事务块内出错后仍报 'T'，与 PG 语义不符（BUG-15）：
        // 客户端会以为事务还能继续，却在下一条语句上收到莫名其妙的失败。
        out.write(this.dbInstance.getSession(getCurrentSessionId(ctx)).getTransactionState().getStatusByte());
    }
}
