package org.slackerdb.dbserver.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.common.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * ParameterDescription ('t') —— 报文/语句的参数类型描述。
 *
 * <pre>
 *   Byte1('t')
 *   Int32   长度
 *   Int16   参数个数 N
 *   Int32[N] 每个参数的 PG 类型 OID
 * </pre>
 *
 */
public class ParameterDescription extends PostgresMessage {

    private int[] parameterTypeOids = new int[0];

    public ParameterDescription(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    /** 设置参数类型 OID 列表；长度即参数个数。 */
    public void setParameterTypeOids(int[] parameterTypeOids) {
        this.parameterTypeOids = parameterTypeOids == null ? new int[0] : parameterTypeOids;
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) throws IOException {
        out.write((byte) 't');
        out.write(Utils.int32ToBytes(4 + 2 + 4 * parameterTypeOids.length));
        out.write(Utils.int16ToBytes((short) parameterTypeOids.length));
        for (int oid : parameterTypeOids) {
            out.write(Utils.int32ToBytes(oid));
        }
    }
}
