package org.slackerdb.dbproxy.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbproxy.message.PostgresRequest;
import org.slackerdb.dbproxy.server.ProxyInstance;

import java.io.IOException;
import java.util.Arrays;

/**
 * 标准 CancelRequest：**新连接上的第一个（也是唯一一个）报文**，没有消息类型字节。
 *
 * <pre>
 *   Int32(16) | Int32(80877102) | Int32(pid) | Int32(secret)
 * </pre>
 *
 * <p>代理在这里**只做解析**：真正的转发（按 pid 查 {@code CancelRouter} 并回送到上游）由
 * {@code PostgresProxyServerHandler} 完成。按 PG 规范，服务端处理取消时不应发送任何响应字节，
 * 因此 {@link #process} 只负责关闭连接。</p>
 */
public class CancelRequest extends PostgresRequest {
    /**
     * 取消前导报文的固定首 8 字节：Int32(16) + Int32(80877102)。
     * 与 dbserver 侧 {@code org.slackerdb.dbserver.message.request.CancelRequest.CancelRequestHeader}
     * 是同一份协议常量。
     */
    public static final byte[] CancelRequestHeader =
            {0x00, 0x00, 0x00, 0x10, 0x04, (byte) 0xD2, 0x16, 0x2E};

    public int processId;
    public int secretKey;

    public CancelRequest(ProxyInstance pProxyInstance) {
        super(pProxyInstance);
    }

    /**
     * 解析取消前导报文的**尾 8 字节**（pid + secret）。
     * 解码器已经消费掉首 8 字节的长度与取消码，这里不再做任何偏移跳跃。
     */
    public void decodeTail(byte[] tail) {
        processId = Utils.bytesToInt32(Arrays.copyOfRange(tail, 0, 4));
        secretKey = Utils.bytesToInt32(Arrays.copyOfRange(tail, 4, 8));
    }

    /**
     * 拼装一个完整的 16 字节取消报文，用于转发给上游。
     */
    public static byte[] buildFrame(int processId, int secretKey) {
        byte[] frame = new byte[16];
        System.arraycopy(CancelRequestHeader, 0, frame, 0, 8);
        System.arraycopy(Utils.int32ToBytes(processId), 0, frame, 8, 4);
        System.arraycopy(Utils.int32ToBytes(secretKey), 0, frame, 12, 4);
        return frame;
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        // 不发送任何响应字节，直接关闭取消连接。
        // 转发动作在 PostgresProxyServerHandler 中完成（见 handleCancelRequest）。
        ctx.close();
    }
}
