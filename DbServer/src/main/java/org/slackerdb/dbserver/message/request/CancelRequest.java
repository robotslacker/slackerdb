package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.entity.ParsedStatement;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.response.BackendKeyData;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.server.DBSession;
import org.slackerdb.common.utils.Utils;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;

public class CancelRequest  extends PostgresRequest {
    public int processId;
    public int secretKey;

    public CancelRequest(DBInstance pDbInstance) {
        super(pDbInstance);
    }

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
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = this.getClass().getSimpleName();
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = LocalDateTime.now();

        if (this.dbInstance.getSession(processId) != null)
        {
            DBSession dbSession = this.dbInstance.getSession(processId);
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
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = "";
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = null;
    }
}
