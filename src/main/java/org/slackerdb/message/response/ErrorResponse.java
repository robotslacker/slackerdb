package org.slackerdb.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.message.PostgresMessage;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class ErrorResponse extends PostgresMessage {
    private String errorCode;
    private String errorMessage;

    public void setErrorResponse(String errorCode, String errorMessage)
    {
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) throws IOException {
        String severity = "ERROR";
        byte[] bytesSeverity = severity.getBytes(StandardCharsets.UTF_8);
        byte[] bytesErrorMessage = errorMessage.getBytes(StandardCharsets.UTF_8);
        byte[] bytesErrorCode = errorCode.getBytes(StandardCharsets.UTF_8);
        out.write((byte)'E');
        out.write(Utils.int32ToBytes(4 +
                bytesSeverity.length + 2 +
                bytesSeverity.length + 2 +
                bytesErrorCode.length + 2 +
                bytesErrorMessage.length + 2 + 1)) ;
        out.write((byte) 'S'); // severity
        out.write(bytesSeverity);
        out.write((byte)0);
        out.write((byte) 'V'); // text
        out.write(bytesSeverity);
        out.write((byte)0);
        out.write((byte) 'C'); // errorCode
        out.write(bytesErrorCode);
        out.write((byte)0);
        out.write((byte) 'M'); // message
        out.write(bytesErrorMessage);
        out.write((byte)0);

        // 最后还需要一个终止符号
        out.write((byte)0);
    }
}
