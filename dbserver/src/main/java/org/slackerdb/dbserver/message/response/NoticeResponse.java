package org.slackerdb.dbserver.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.common.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * NoticeResponse（报文类型 {@code 'N'}）：服务端给客户端的非致命提示。
 *
 * <p>注意与 {@code SSLRequest}/{@code NoticeMessage} 的区别：后者是为了回答 SSLRequest 而
 * 直接写一个裸字节 {@code 'N'}，<b>不是</b>协议里的 NoticeResponse。本类才是规范报文
 * （字段结构与 {@link ErrorResponse} 相同，severity = {@code NOTICE}），
 * 客户端会把它当提示展示（psql 打印 {@code NOTICE: ...}，驱动进 warning 链）。</p>
 */
public class NoticeResponse extends PostgresMessage {

    private String noticeCode = "01000";
    private String noticeMessage;
    private String noticeSeverity = "NOTICE";

    public NoticeResponse(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    public void setNotice(String message) {
        this.noticeMessage = message;
    }

    public void setNotice(String code, String message) {
        this.noticeCode = code;
        this.noticeMessage = message;
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) throws IOException {
        byte[] bytesSeverity = noticeSeverity.getBytes(StandardCharsets.UTF_8);
        byte[] bytesMessage = noticeMessage.getBytes(StandardCharsets.UTF_8);
        byte[] bytesCode = noticeCode.getBytes(StandardCharsets.UTF_8);

        out.write((byte) 'N');
        out.write(Utils.int32ToBytes(4
                + bytesSeverity.length + 2
                + bytesSeverity.length + 2
                + bytesCode.length + 2
                + bytesMessage.length + 2
                + 1));
        out.write((byte) 'S');                       // severity
        out.write(bytesSeverity);
        out.write((byte) 0);
        out.write((byte) 'V');                       // severity（非本地化）
        out.write(bytesSeverity);
        out.write((byte) 0);
        out.write((byte) 'C');                       // SQLSTATE
        out.write(bytesCode);
        out.write((byte) 0);
        out.write((byte) 'M');                       // message
        out.write(bytesMessage);
        out.write((byte) 0);
        out.write((byte) 0);                         // 字段终止符
    }
}
