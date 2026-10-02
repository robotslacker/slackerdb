package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.server.DBInstance;

/**
 * Flush ('H') —— 客户端要求把服务端已缓冲的输出立刻推出。
 *
 * <pre>
 *   Byte1('H')
 *   Int32(4)
 * </pre>
 *
 * <p><b>协议语义</b>：这是一个<b>没有响应</b>的报文。服务端只需要把出站缓冲刷出去。</p>
 *
 * <p><b>注意</b>：本报文不参与协议阶段推进，因此<b>不能</b>修改
 */
public class FlushRequest extends PostgresRequest {

    public FlushRequest(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) {
        // 唯一动作：把已排队但未刷出的数据推出去。不产生任何响应报文。
        ctx.flush();
    }
}
