package org.slackerdb.dbproxy.instance;

import org.slackerdb.dbproxy.message.request.AdminClientRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 通过 PG 口发送管理帧（与 dbserver 自带的 {@code dbserver stop} 完全同一种报文）。
 *
 * <p>为什么不用系统信号：{@code STOP} 会走 dbserver 的 {@code DBInstance.stop()}，
 * DuckDB 是干净关闭的；而 SIGTERM 只是杀 JVM，只能靠 WAL 恢复。</p>
 */
public final class InstanceAdmin {

    private static final String LOCAL_HOST = "127.0.0.1";
    private static final int READ_TIMEOUT_MS = 800;

    private InstanceAdmin() {
    }

    /**
     * 发送 {@code STOP}，并短暂等待回应（回应可能因为进程随即退出而被截断，属正常）。
     *
     * @return {@code true} 表示收到了响应字节
     */
    public static boolean sendStop(int port, int connectTimeoutMs) throws IOException {
        byte[] command = "STOP".getBytes(StandardCharsets.UTF_8);
        byte[] header = AdminClientRequest.AdminClientRequestHeader;
        ByteBuffer frame = ByteBuffer.allocate(header.length + 5 + command.length);
        frame.put(header);
        frame.put((byte) '!');
        frame.putInt(4 + command.length);
        frame.put(command);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOCAL_HOST, port), connectTimeoutMs);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write(frame.array());
            out.flush();
            try (InputStream in = socket.getInputStream()) {
                return in.read() >= 0;
            } catch (SocketTimeoutException timeout) {
                return false;
            }
        }
    }
}
