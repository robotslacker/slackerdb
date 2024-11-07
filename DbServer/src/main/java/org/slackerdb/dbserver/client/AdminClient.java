package org.slackerdb.dbserver.client;

/*
    AdminClient

    用来接受客户端的管理命令
    目前实现的有：
    1： 停止
    2： 查看服务器运行状态
    3： 杀掉会话进程
 */

import ch.qos.logback.classic.Logger;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.buffer.Unpooled;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.message.request.AdminClientRequest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class AdminClient {
    // Encoder to convert byte[] to ByteBuf
    static class ByteArrayEncoder extends MessageToByteEncoder<byte[]> {
        @Override
        protected void encode(ChannelHandlerContext channelHandlerContext, byte[] msg, ByteBuf out)  {
            out.writeBytes(msg);
        }
    }

    // Decoder to convert ByteBuf to byte[]
    static class ByteArrayDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            while (in.readableBytes() > 0) {
                if (in.readableBytes() < 5) {
                    // 等待发送完毕，前5个字节为标识符和消息体长度
                    return;
                }

                // 首字节为消息体的长度
                byte[] data = new byte[5];
                in.readBytes(data);
                ByteBuffer byteBuffer = ByteBuffer.wrap(data);
                byteBuffer.get();  // 忽略传递的首位标志
                int messageLen = byteBuffer.getInt();

                // 等待消息体发送结束, 4字节的字节长度也是消息体长度的一部分
                if (in.readableBytes() < (messageLen - 4)) {
                    in.readerIndex(in.readerIndex() - 5); // 重置读取位置
                    return;
                }
                data = new byte[messageLen - 4];
                in.readBytes(data);

                // 处理消息，并回显
                System.out.println(new String(data, StandardCharsets.UTF_8));

                // 关闭连接，每次只处理一个请求
                ctx.close();
            }
        }
    }

    public static void doCommand(Logger logger, ServerConfiguration serverConfiguration, String command) {
        // 启动Netty客户端
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap client = new Bootstrap();
            client.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ChannelInitializer<NioSocketChannel>() {
                                 @Override
                                 protected void initChannel(NioSocketChannel ch) {
                                     // 定义消息处理
                                     ch.pipeline().addLast(new ByteArrayEncoder());
                                     ch.pipeline().addLast(new ByteArrayDecoder());
                                 }
                             }
                    );

            // 连接服务器
            ChannelFuture future =
                    client.connect(serverConfiguration.getBindHost(), serverConfiguration.getPort()).sync();

            // 发送消息头，并等待回应标志
            ByteBuf buffer = Unpooled.wrappedBuffer(AdminClientRequest.AdminClientRequestHeader);
            future.channel().writeAndFlush(buffer).sync();

            // 发送消息正文
            byte[] msg = command.getBytes(StandardCharsets.UTF_8);
            buffer = Unpooled.buffer().capacity(5+msg.length);
            buffer.writeByte('!');
            buffer.writeInt(4+command.length());
            buffer.writeBytes(msg);
            future.channel().writeAndFlush(buffer).sync();

            // Wait until the connection is closed.
            future.channel().closeFuture().sync();
        }
        catch (Exception e) {
            logger.error("Error connecting to server", e);
        }
        finally {
            group.shutdownGracefully();
        }
    }
}
