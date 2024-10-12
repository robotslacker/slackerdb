package org.slackerdb.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.entity.ParsedStatement;
import org.slackerdb.message.PostgresRequest;
import org.slackerdb.message.response.BackendKeyData;
import org.slackerdb.server.DBInstance;
import org.slackerdb.server.DBSession;
import org.slackerdb.utils.Utils;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;

public class CancelRequest  extends PostgresRequest {
    public int processId;
    public int secretKey;

    @Override
    public void decode(byte[] data) {
        //  CancelRequest (F)
        //      Int32(16)
        //        Length of message contents in bytes, including self.
        //      Int32(80877102)
        //        The cancel request code. The value is chosen to contain 1234 in the most significant 16 bits, and 5678 in the least significant 16 bits. (To avoid confusion, this code must not be the same as any protocol version number.)
        //      Int32
        //         The process ID of the target backend.
        //      Int32
        //        The secret key for the target backend.
        processId = Utils.bytesToInt32(Arrays.copyOfRange(data,4,8));
        secretKey = Utils.bytesToInt32(Arrays.copyOfRange(data,8,12));

        super.decode(data);
    }


    @Override
    public void process(ChannelHandlerContext ctx, Object request) {
        if (secretKey != BackendKeyData.FIXED_SECRET)
        {
            return;
        }

        // 记录会话的开始时间，以及业务类型
        DBInstance.getSession(getCurrentSessionId(ctx)).executingFunction = this.getClass().getSimpleName();
        DBInstance.getSession(getCurrentSessionId(ctx)).executingTime = LocalDateTime.now();

        if (DBInstance.getSession(processId) != null)
        {
            DBSession dbSession = DBInstance.getSession(processId);
            for (String portalName : dbSession.parsedStatements.keySet()) {
                ParsedStatement parsedStatement = dbSession.parsedStatements.get(portalName);
                if (parsedStatement.preparedStatement != null) {
                    try {
                        if (!parsedStatement.preparedStatement.isClosed()) {
                            parsedStatement.preparedStatement.cancel();
                        }
                    }
                    catch (SQLException ignored) {}
                }
            }
        }

        // 取消会话的开始时间，以及业务类型
        DBInstance.getSession(getCurrentSessionId(ctx)).executingFunction = "";
        DBInstance.getSession(getCurrentSessionId(ctx)).executingTime = null;
    }
}
