package org.slackerdb.protocol;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

public class DataRow extends PostgresMessage {
    //  DataRow (B)
    //    Byte1('D')
    //      Identifies the message as a data row.
    //    Int32
    //      Length of message contents in bytes, including self.
    //    Int16
    //      The number of column values that follow (possibly zero).
    //      Next, the following pair of fields appear for each column:
    //      Int32
    //        The length of the column value, in bytes (this count does not include itself).
    //        Can be zero. As a special case, -1 indicates a NULL column value.
    //        No value bytes follow in the NULL case.
    //      Byte(n)
    //        The value of the column, in the format indicated by the associated format code. n is the above length.
    private List<Column> columns;
    public void setColumns(List<Column> columns)
    {
        this.columns = columns;
    }
    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) throws IOException {
        out.write((byte)'D');

        int columnSizeSum = 0;
        for (Column column : columns)
        {
            if (column.columnLength == -1)
            {
                columnSizeSum = columnSizeSum + 4;
            }
            else
            {
                columnSizeSum = columnSizeSum + 4 + column.columnLength;
            }
        }
        out.write(Utils.int32ToBytes(4 + 2 + columnSizeSum));
        out.write(Utils.int16ToBytes((short)columns.size()));
        for (Column column : columns)
        {
            out.write(Utils.int32ToBytes(column.columnLength));
            if (column.columnLength != -1)
            {
                out.write(column.columnValue);
            }
        }

    }
}
