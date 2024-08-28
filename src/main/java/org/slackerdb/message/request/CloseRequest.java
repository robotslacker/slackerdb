package org.slackerdb.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.entity.ParsedStatement;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.message.PostgresRequest;
import org.slackerdb.server.DBInstance;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;


public class CloseRequest extends PostgresRequest {
    //  Close (F)
    //    Byte1('C')
    //      Identifies the message as a Close command.
    //    Int32
    //      Length of message contents in bytes, including self.
    //    Byte1
    //      'S' to close a prepared statement; or 'P' to close a portal.
    //    String
    //      The name of the prepared statement or portal to close
    //      (an empty string selects the unnamed prepared statement or portal).

    public char closeType;
    public String portalName;

    @Override
    public void decode(byte[] data) {
        portalName = new String(data, StandardCharsets.UTF_8);
        closeType = portalName.charAt(0);
        portalName = portalName.substring(1);

        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) {
        String closePortal;
        if (closeType == 'S') {
            closePortal = "PreparedStatement" + "-" + portalName;
        }
        else
        {
            // (closeType == 'P')
            closePortal = "Portal" + "-" + portalName;
        }
        try {
            ParsedStatement parsedPreparedStatement =
                    DBInstance.getSession(getCurrentSessionId(ctx)).getParsedStatement(closePortal);
            if (parsedPreparedStatement != null && parsedPreparedStatement.preparedStatement != null) {
                parsedPreparedStatement.preparedStatement.close();
            }
            DBInstance.getSession(getCurrentSessionId(ctx)).clearParsedStatement(closePortal);
        }
        catch(SQLException e) {
            AppLogger.logger.error("Failed to close prepared statement", e);
        }
    }
}