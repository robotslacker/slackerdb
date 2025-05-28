package org.slackerdb.cdb.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.cdb.message.PostgresRequest;
import org.slackerdb.cdb.server.CDBInstance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class StartupRequest extends PostgresRequest {
    //  StartupMessage (F)
    //    Int32
    //      Length of message contents in bytes, including self.
    //    Int32(196608)
    //      The protocol version number. The most significant 16 bits are the major version number (3 for the protocol described here).
    //      The least significant 16 bits are the minor version number (0 for the protocol described here).
    //      The protocol version number is followed by one or more pairs of parameter name and value strings. A zero byte is required as a terminator after the last name/value pair. Parameters can appear in any order. user is required, others are optional. Each parameter is specified as:
    //    String
    //      The parameter name. Currently, recognized names are:
    //      user
    //        The database username to connect as. Required; there is no default.
    //      database
    //        The database to connect to. Defaults to the username.
    //      options
    //        Command-line arguments for the backend. (This is deprecated in favor of setting individual run-time parameters.) Spaces within this string are considered to separate arguments, unless escaped with a backslash (\); write \\ to represent a literal backslash.
    //      replication
    //        Used to connect in streaming replication mode, where a small set of replication commands can be issued instead of SQL statements. Value can be true, false, or database, and the default is false. See Section 55.4 for details.
    //
    //    In addition to the above, other parameters may be listed. Parameter names beginning with _pq_. are reserved for use as protocol extensions, while others are treated as run-time parameters to be set at backend start time. Such settings will be applied during backend start (after parsing the command-line arguments if any) and will act as session defaults.
    //    String
    //      The parameter value.

    private final Map<String, String> startupOptions = new HashMap<>();
    private int version;

    public StartupRequest(CDBInstance pCDBInstance) {
        super(pCDBInstance);
    }

    public Map<String, String> getStartupOptions()
    {
        return startupOptions;
    }

    byte[] rawData;

    @Override
    public void decode(byte[] data) {
        // 前面4个字节的协议版本号.
        version = Utils.bytesToInt32(Arrays.copyOf(data, 4));
        // 目前没有对这个信息进行处理
        byte[][] result = Utils.splitByteArray(Arrays.copyOfRange(data, 4, data.length), (byte)0);
        for (int i = 0; i < result.length-1; i=i+2) {
            startupOptions.put(new String(result[i]), new String(result[i+1]));
        }
        rawData = data;
        super.decode(data);
    }

    public byte[] rebuildData() throws IOException
    {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(Utils.int32ToBytes(version));
        for (Map.Entry<String, String> entry : startupOptions.entrySet()) {
            outputStream.write(entry.getKey().getBytes());
            outputStream.write((byte) 0);
            outputStream.write(entry.getValue().getBytes());
            outputStream.write((byte) 0);
        }
        // 末尾终止符
        outputStream.write((byte) 0);
        return outputStream.toByteArray();
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
    }
}
