package org.slackerdb.dbserver.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.common.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * {@code CopyOutResponse}（开始 COPY OUT，服务端随后推数据）。
 *
 * <p>报文格式与 {@link CopyInResponse} 完全一致，只有首字节不同：</p>
 * <pre>
 *   CopyOutResponse (B)
 *     Byte1('H')
 *       Identifies the message as a Start Copy Out response. The backend will now send copy-out data.
 *     Int32
 *       Length of message contents in bytes, including self.
 *     Int8
 *       0 = 整体为文本格式（行以换行分隔，列以分隔符分隔）
 *       1 = 整体为二进制格式
 *     Int16
 *       要复制的列数（N）
 *     Int16[N]
 *       每列的格式码：0 = 文本，1 = 二进制。
 *       整体为文本格式时全部为 0。
 * </pre>
 *
 * <p>紧随其后的是 N 条 {@code CopyData('d')}、一条 {@link CopyDone}（'{@code c}'）、
 * {@code CommandComplete} 与 {@code ReadyForQuery}。客户端在这一阶段只接收、不发送，
 * 因此不需要像 COPY IN 那样新增入站报文处理。</p>
 */
public class CopyOutResponse extends PostgresMessage {

    /** 要复制的列数。 */
    public short copyColumnCount = 0;

    /** 整体格式：{@code false} = 文本（0），{@code true} = 二进制（1）。 */
    public boolean binary = false;

    public CopyOutResponse(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) throws IOException {
        out.write((byte)'H');

        out.write(Utils.int32ToBytes(4 + 1 + 2 + 2 * copyColumnCount));
        out.write((byte)(binary ? 1 : 0));
        out.write(Utils.int16ToBytes(copyColumnCount));
        // 本实现只支持"整体文本"或"整体二进制"，不支持逐列混合格式：
        // 文本模式全 0；二进制模式全 1（PG 允许逐列指定，但没有客户端会这么请求 COPY OUT）。
        for (int i = 0; i < copyColumnCount; i++) {
            out.write(Utils.int16ToBytes((short)(binary ? 1 : 0)));
        }
    }
}
