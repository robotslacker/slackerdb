package org.slackerdb.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.protocol.context.NetworkProtoContext;
import org.slackerdb.protocol.context.ProtoContext;
import org.slackerdb.protocol.descriptor.NetworkProtoDescriptor;
import org.slackerdb.protocol.events.BytesEvent;
import org.slackerdb.utils.Sleeper;
import org.slackerdb.utils.Utils;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;


public class TcpServerHandler extends ChannelInboundHandlerAdapter {

    // Create the execution context
    public static NetworkProtoDescriptor protoDescriptor;
    private NetworkProtoContext context;

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) {
        // 获取远端的 IP 地址和端口号
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        AppLogger.logger.trace("[SERVER] Accepted connection from {}", remoteAddress.toString());

        // Create the execution context
        context = (NetworkProtoContext) protoDescriptor.buildContext(null);

    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuffer) {
            ByteBuffer byteBuffer = (ByteBuffer) msg;

            if (byteBuffer.remaining() > 0) {
                //If there is something
                byte[] byteArray = new byte[byteBuffer.remaining()];
                byteBuffer.get(byteArray);

                var bb = context.buildBuffer();
                context.setUseCallDurationTimes(false);
                bb.write(byteArray);
                BytesEvent bytesEvent = new BytesEvent(context, null, bb);

                //Generate a BytesEvent and send it
                context.send(bytesEvent, ctx);
            }
            byteBuffer.clear();
        } else {
            // 如果不是 ByteBuf 类型的消息，可以在这里进行相应的处理逻辑
            // 例如：处理其他类型的消息
            System.out.println("Received message of unknown type: " + msg);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 获取远端的 IP 地址和端口号
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        AppLogger.logger.trace("[SERVER] Connection {} disconnected.", remoteAddress.toString());

        // 释放资源
        super.channelInactive(ctx);
        ctx.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // 获取远端的 IP 地址和端口号
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        AppLogger.logger.trace("[SERVER] Connection {} error.", remoteAddress.toString(), cause);

        // 关闭连接
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == IdleState.READER_IDLE) {
                AppLogger.logger.trace("[SERVER] Connection {} error. Read timeout. ", ctx.channel().remoteAddress());
                ctx.close();
            } else if (event.state() == IdleState.WRITER_IDLE) {
                AppLogger.logger.trace("[SERVER] Connection {} error. Write timeout. ", ctx.channel().remoteAddress());
                System.out.println("Write timeout, closing connection: " + ctx.channel().remoteAddress());
                ctx.close();
            } else if (event.state() == IdleState.ALL_IDLE) {
                AppLogger.logger.trace("[SERVER] Connection {} error. all timeout. ", ctx.channel().remoteAddress());
                ctx.close();
            }
        }
    }
}
