package org.slackerdb.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.message.PostgresMessage;
import org.slackerdb.server.DBInstance;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class CommandComplete extends PostgresMessage {
    //  CommandComplete (B)
    //    Byte1('C')
    //      Identifies the message as a command-completed response.
    //    Int32
    //      Length of message contents in bytes, including self.
    //    String
    //      The command tag. This is usually a single word that identifies which SQL command was completed.
    //      For an INSERT command, the tag is INSERT oid rows, where rows is the number of rows inserted. oid used to be the object ID of the inserted row if rows was 1 and the target table had OIDs, but OIDs system columns are not supported anymore; therefore oid is always 0.
    //      For a DELETE command, the tag is DELETE rows where rows is the number of rows deleted.
    //      For an UPDATE command, the tag is UPDATE rows where rows is the number of rows updated.
    //      For a MERGE command, the tag is MERGE rows where rows is the number of rows inserted, updated, or deleted.
    //      For a SELECT or CREATE TABLE AS command, the tag is SELECT rows where rows is the number of rows retrieved.
    //      For a MOVE command, the tag is MOVE rows where rows is the number of rows the cursor's position has been changed by.
    //      For a FETCH command, the tag is FETCH rows where rows is the number of rows that have been retrieved from the cursor.
    //      For a COPY command, the tag is COPY rows where rows is the number of rows copied. (Note: the row count appears only in PostgreSQL 8.2 and later.)

    private String commandResult = "";

    public CommandComplete(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    public void setCommandResult(String commandResult)
    {
        this.commandResult = commandResult;
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) throws IOException {
        out.write((byte)'C');

        String result = this.commandResult;
        byte[] resultByte = result.getBytes(StandardCharsets.UTF_8);
        out.write(Utils.int32ToBytes(4 + resultByte.length + 1));
        out.write(resultByte);
        out.write((byte)0);
    }
}
