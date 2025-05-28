package org.slackerdb.cdb.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.cdb.message.PostgresMessage;
import org.slackerdb.cdb.server.CDBInstance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class ErrorResponse extends PostgresMessage {
    private String errorCode;
    private String errorMessage;
    private String errorSeverity = "ERROR";
    private String errorFile = "n/a";
    private String errorLine = "0";

    public ErrorResponse(CDBInstance pCDBInstance) {
        super(pCDBInstance);
    }

    public void setErrorResponse(String errorCode, String errorMessage)
    {
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public void setErrorSeverity(String errorSeverity)
    {
        this.errorSeverity = errorSeverity;
    }


    public void setErrorFile(String errorFile)
    {
        this.errorFile = errorFile;
    }

    public void setErrorLine(String errorLine)
    {
        this.errorLine = errorLine;
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) throws IOException {
        byte[] bytesSeverity = errorSeverity.getBytes(StandardCharsets.UTF_8);
        byte[] bytesErrorMessage = errorMessage.getBytes(StandardCharsets.UTF_8);
        byte[] bytesErrorCode = errorCode.getBytes(StandardCharsets.UTF_8);
        byte[] bytesErrorFile = errorFile.getBytes(StandardCharsets.UTF_8);
        byte[] bytesErrorLine = errorLine.getBytes(StandardCharsets.UTF_8);
        out.write((byte)'E');
        out.write(Utils.int32ToBytes(4 +
                bytesSeverity.length + 2 +
                bytesSeverity.length + 2 +
                bytesErrorCode.length + 2 +
                bytesErrorMessage.length + 2 +
                bytesErrorFile.length + 2 +
                bytesErrorLine.length + 2 +
                1)) ;
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
        out.write((byte) 'F'); // file
        out.write(bytesErrorFile);
        out.write((byte)0);
        out.write((byte) 'L'); // line
        out.write(bytesErrorLine);
        out.write((byte)0);

        // 最后还需要一个终止符号
        out.write((byte)0);
    }
}
