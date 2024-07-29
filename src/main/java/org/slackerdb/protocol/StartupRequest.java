package org.slackerdb.protocol;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class StartupRequest  extends PostgresRequest {
    @Override
    public void decode(byte[] data) {
        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 总是回复认证成功
        AuthenticationOk authenticationOk = new AuthenticationOk();
        authenticationOk.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, AuthenticationOk.class.getSimpleName(), out);

        // 返回一些参数信息
        ParameterStatus parameterStatus = new ParameterStatus();
        parameterStatus.setKeyValue("server_version", "15");
        parameterStatus.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ParameterStatus.class.getSimpleName(), out);

        parameterStatus.setKeyValue("server_type", "JANUS");
        parameterStatus.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ParameterStatus.class.getSimpleName(), out);

        parameterStatus.setKeyValue("client_encoding", "UTF8");
        parameterStatus.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ParameterStatus.class.getSimpleName(), out);

        parameterStatus.setKeyValue("DateStyle", "ISO, YMD");
        parameterStatus.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ParameterStatus.class.getSimpleName(), out);

        parameterStatus.setKeyValue("TimeZone", Utils.getZoneId());
        parameterStatus.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ParameterStatus.class.getSimpleName(), out);

        parameterStatus.setKeyValue("is_superuser", "on");
        parameterStatus.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ParameterStatus.class.getSimpleName(), out);

        // 返回 BackendKeyData
        BackendKeyData backendKeyData = new BackendKeyData();
        backendKeyData.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, BackendKeyData.class.getSimpleName(), out);

        // 做好准备，可以查询
        ReadyForQuery readyForQuery = new ReadyForQuery();
        readyForQuery.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out);
    }
}
