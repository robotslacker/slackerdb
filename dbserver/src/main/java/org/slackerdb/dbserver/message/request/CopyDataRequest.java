package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.server.DBSession;
import org.slackerdb.dbserver.sql.CopyProtocolHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;

/**
 * CopyData ('d') —— COPY IN 期间客户端发来的数据段。
 *
 * <pre>
 *   Byte1('d')
 *   Int32   长度（含自身）
 *   Byten   数据（客户端可以任意切分，与服务端的行边界无关）
 * </pre>
 *
 * <p><b>本项目不做流式摄取</b>：这里只是把数据段堆进 {@link DBSession#copyLastRemained}，
 * 真正的解析发生在 CopyDone。因此必须有一个硬上限（{@link CopyProtocolHandler#maxPayloadBytes}，
 * 2 GiB），超过就<b>立刻判定本次 COPY 失败</b>：丢弃已缓冲数据、回滚服务端自开的 COPY 事务、
 * 回 ErrorResponse + ReadyForQuery，而不是让 JVM 去分配一个注定失败的数组
 * </p>
 *
 * <p>失败之后的 CopyData 按 PG 的"报错后丢弃到同步点"语义一律忽略 —— 既不再占内存，
 * 也不会被当成下一次 COPY 的数据。</p>
 */
public class CopyDataRequest extends PostgresRequest {

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
        DBSession session = this.dbInstance.getSession(getCurrentSessionId(ctx));

        // 记录会话的开始时间，以及业务类型
        session.executingFunction = this.getClass().getSimpleName();
        session.executingTime = LocalDateTime.now();

        if (session.copyAborted) {
            // 本次 COPY 已经判失败（错误与 ReadyForQuery 都发过了），后续数据丢弃
            clearExecutionStamp(session);
            return;
        }

        long buffered = session.copyLastRemained.size();
        long incoming = copyData == null ? 0 : copyData.length;
        if (buffered + incoming > CopyProtocolHandler.maxPayloadBytes) {
            String message = "COPY from stdin failed: data exceeds the in-memory buffer limit ("
                    + (CopyProtocolHandler.maxPayloadBytes / (1024 * 1024)) + "MB)"
                    + " of this server (received " + (buffered + incoming) + " bytes)"
                    + "; the COPY has been aborted and no rows were written";
            CopyProtocolHandler.abortCopyIn(session, this.dbInstance, ctx, "54000", message);
            this.dbInstance.logger.warn("[SERVER][COPY       ] {}", message);
            clearExecutionStamp(session);
            return;
        }

        // 只是把数据复制到缓冲区，并不会处理
        if (incoming > 0) {
            try {
                session.copyLastRemained.write(copyData);
            }
            catch (OutOfMemoryError outOfMemory) {
                // 缓冲扩容失败。**不能**让 OutOfMemoryError 逃逸到 Netty：客户端既拿不到错误，
                // 会话（乃至整个连接）也一起废掉，而且 OOM 之前已经把服务端堆吃光了。
                // 这里先把这个"占着大头"的缓冲整个换掉（立刻释放掉最大的那个数组），
                // 再按 COPY 失败收尾 —— 于是"太大就拒绝"在堆小于 2GiB 上限的机器上同样成立。
                session.copyLastRemained = new ByteArrayOutputStream();
                String message = "COPY from stdin failed: cannot buffer this COPY in memory"
                        + " (in-memory buffer limit is " + (CopyProtocolHandler.maxPayloadBytes / (1024 * 1024))
                        + "MB, received " + Math.max(buffered, incoming) + " bytes)"
                        + "; the COPY has been aborted and no rows were written";
                CopyProtocolHandler.abortCopyIn(session, this.dbInstance, ctx, "53200", message);
                this.dbInstance.logger.warn("[SERVER][COPY       ] {} (原因: {})", message, outOfMemory);
                clearExecutionStamp(session);
                return;
            }
        }

        clearExecutionStamp(session);
    }

    private static void clearExecutionStamp(DBSession session) {
        // 取消会话的开始时间，以及业务类型
        session.executingFunction = "";
        session.executingTime = null;
    }
}
