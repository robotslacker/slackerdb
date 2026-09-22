package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.response.BackendKeyData;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.server.DBSession;
import org.slackerdb.common.utils.Utils;

import java.time.LocalDateTime;
import java.util.Arrays;

public class CancelRequest  extends PostgresRequest {
    /**
     * CancelRequest 作为**新连接上的第一个报文**发送时，没有消息类型字节，
     * 其固定首 8 字节为：Int32(16) + Int32(80877102)。
     *
     * <p>这与 {@link SSLRequest#SSLRequestHeader} 处于同一个协议位置（前导报文）。
     * 真实客户端（psql 的 Ctrl+C、pgjdbc 的 {@code Statement.cancel()}）走的都是这种形态：
     * 新建一条 TCP 连接 → 只发这 16 字节 → 立即关闭，服务端**不应回任何响应**。</p>
     */
    public static final byte[] CancelRequestHeader =
            {0x00, 0x00, 0x00, 0x10, 0x04, (byte)0xD2, 0x16, 0x2E};

    public int processId;
    public int secretKey;

    public CancelRequest(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    /**
     * 解析取消前导报文的**尾 8 字节**（pid + secret）。
     *
     * <p>解码器已经消费掉首 8 字节的长度与取消码，因此这里不再做任何偏移跳跃。
     * 与之相对的 {@link #decode(byte[])} 保留给"带类型字节 'F'"的兼容形态，
     * 两者的偏移假设不同，<b>不要</b>合并成一个方法——混用正是历史上取消功能
     * 一直不通的根因。</p>
     */
    public void decodeTail(byte[] tail) {
        processId = Utils.bytesToInt32(Arrays.copyOfRange(tail, 0, 4));
        secretKey = Utils.bytesToInt32(Arrays.copyOfRange(tail, 4, 8));
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
        try {
            if (secretKey != BackendKeyData.FIXED_SECRET)
            {
                return;
            }

            // 记录发起取消的这条临时会话的状态。
            // 必须做空值判断：会话可能在报文到达前就被摘除（例如刚被 KILL），
            // 直接解引用会抛 NPE 并被 channelRead 吞成一条 ERROR 日志。
            DBSession selfSession = this.dbInstance.getSession(getCurrentSessionId(ctx));
            if (selfSession != null) {
                selfSession.executingFunction = this.getClass().getSimpleName();
                selfSession.executingTime = LocalDateTime.now();
            }

            // 取消目标会话正在执行的语句。
            // 注意：这是**跨线程**操作（取消请求走的是新建连接，与目标会话不在同一个线程上），
            // 所以不能直接遍历目标会话的 parsedStatements —— 统一交给 DBSession
            // 提供的方法，由它保证并发安全。
            // 目标不存在（已结束/已清理）属于正常时序，静默忽略。
            DBSession targetSession = this.dbInstance.getSession(processId);
            if (targetSession != null) {
                targetSession.cancelRunningStatements();
            }

            if (selfSession != null) {
                selfSession.executingFunction = "";
                selfSession.executingTime = null;
            }
        }
        finally {
            // PG 协议规定：服务端处理 CancelRequest 时不发送任何响应，直接关闭连接。
            ctx.close();
        }
    }
}
