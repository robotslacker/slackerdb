package org.slackerdb.protocol;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class TerminateRequest extends PostgresRequest {
    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        PreparedStatement preparedStatement = (PreparedStatement) ctx.channel().attr(AttributeKey.valueOf("PreparedStatement")).get();
        if (preparedStatement != null) {
            try {
                if (!preparedStatement.isClosed())
                {
                    preparedStatement.close();
                }
            } catch (SQLException ignored) {}
        }
        Connection conn = (Connection) ctx.channel().attr(AttributeKey.valueOf("Connection")).get();
        try {
            if (!conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException ignored) {}
    }
}
