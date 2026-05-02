/*
 * Copyright (c) 2025, SlackerDB
 *
 * Unix Domain Socket 的 SocketFactory 实现。
 * 使用 Java 16+ 的 UnixDomainSocketAddress API 创建 UDS 连接。
 *
 * 注意：在 Linux 上，SocketChannel.open(StandardProtocolFamily.UNIX).socket()
 * 会抛出 UnsupportedOperationException，因为 UDS SocketChannel 不支持返回
 * java.net.Socket 对象。因此我们使用自定义的 UdsSocket 子类来包装 SocketChannel。
 */
package org.slackerdb.jdbc;

import javax.net.SocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * 自定义 SocketFactory，用于通过 Unix Domain Socket 连接到 SlackerDB 服务器。
 * <p>
 * 支持两种构造方式：
 * <ul>
 *   <li>{@code UnixDomainSocketFactory(String socketPath)} - 指定 socket 文件路径</li>
 *   <li>{@code UnixDomainSocketFactory()} - 使用默认路径 {@code /tmp/.s.slackerdb.4309}</li>
 * </ul>
 * <p>
 * 通过 JDBC 连接参数 {@code socketFactory} 和 {@code socketFactoryArg} 指定。
 */
public class UnixDomainSocketFactory extends SocketFactory {

    private static final Logger LOGGER = Logger.getLogger(UnixDomainSocketFactory.class.getName());

    /** 默认 socket 文件路径 */
    private static final String DEFAULT_SOCKET_PATH = "/tmp/.s.slackerdb.4309";

    /** UDS socket 文件路径 */
    private final String socketPath;

    /**
     * 无参构造函数，使用默认 socket 路径。
     */
    public UnixDomainSocketFactory() {
        this(DEFAULT_SOCKET_PATH);
    }

    /**
     * 指定 socket 文件路径的构造函数。
     *
     * @param socketPath UDS socket 文件路径
     */
    public UnixDomainSocketFactory(String socketPath) {
        this.socketPath = socketPath != null ? socketPath : DEFAULT_SOCKET_PATH;
        LOGGER.fine("UnixDomainSocketFactory created with socket path: " + this.socketPath);
    }

    @Override
    public Socket createSocket() throws IOException {
        LOGGER.fine("Creating UDS socket to: " + socketPath);

        // 使用 Java 16+ 的 UnixDomainSocketAddress API
        SocketChannel socketChannel = SocketChannel.open(StandardProtocolFamily.UNIX);

        // 连接到 UDS socket 文件
        SocketAddress address = UnixDomainSocketAddress.of(Path.of(socketPath));
        socketChannel.connect(address);

        LOGGER.fine("UDS socket connected to: " + socketPath);

        // 返回自定义的 UdsSocket 包装器
        // 注意：不能使用 socketChannel.socket()，因为在 Linux 上 UDS SocketChannel
        // 不支持返回 java.net.Socket 对象（会抛出 UnsupportedOperationException）
        return new UdsSocket(socketChannel);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        // UDS 模式下忽略 host/port 参数
        return createSocket();
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
            throws IOException {
        // UDS 模式下忽略 host/port 参数
        return createSocket();
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        // UDS 模式下忽略 host/port 参数
        return createSocket();
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        // UDS 模式下忽略 host/port 参数
        return createSocket();
    }

    public Socket createSocket(InetSocketAddress address, SocketAddress localAddr, boolean stream)
            throws IOException {
        // UDS 模式下忽略 address 参数
        return createSocket();
    }

    public Socket createSocket(InetSocketAddress address, int timeout) throws IOException {
        // UDS 模式下忽略 address 参数
        return createSocket();
    }

    /**
     * 自定义 Socket 子类，包装一个已连接到 UDS 的 SocketChannel。
     * <p>
     * 在 Linux 上，SocketChannel.open(StandardProtocolFamily.UNIX) 创建的通道
     * 不支持 socket() 方法，因此我们需要手动包装。
     * <p>
     * 此类重写了所有被 PGStream 和 ConnectionFactoryImpl 使用的 Socket 方法，
     * 将调用委托给底层的 SocketChannel。
     */
    private static class UdsSocket extends Socket {

        private final SocketChannel channel;
        private final InputStream inputStream;
        private final OutputStream outputStream;
        private volatile int soTimeout;
        private volatile boolean closed;

        UdsSocket(SocketChannel channel) throws IOException {
            super(); // 调用父类无参构造，不实际创建底层 socket
            this.channel = channel;
            this.inputStream = Channels.newInputStream(channel);
            this.outputStream = Channels.newOutputStream(channel);
            this.soTimeout = 0;
            this.closed = false;
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            // 已经通过 SocketChannel.connect() 连接了，忽略后续的 connect 调用
            // PGStream.createSocket() 在调用 socketFactory.createSocket() 后，
            // 会检查 !socket.isConnected()，如果未连接则调用 socket.connect(address, timeout)
            // 这里我们已经是连接状态，所以此方法不会被调用，但为了安全保留空实现
        }

        @Override
        public boolean isConnected() {
            return channel.isOpen() && channel.isConnected();
        }

        @Override
        public boolean isClosed() {
            return closed || !channel.isOpen();
        }

        @Override
        public boolean isBound() {
            return true;
        }

        @Override
        public boolean isInputShutdown() {
            return !channel.isOpen();
        }

        @Override
        public boolean isOutputShutdown() {
            return !channel.isOpen();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            if (isClosed()) {
                throw new SocketException("Socket is closed");
            }
            return inputStream;
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            if (isClosed()) {
                throw new SocketException("Socket is closed");
            }
            return outputStream;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            channel.close();
        }

        @Override
        public synchronized void setSoTimeout(int timeout) throws SocketException {
            if (timeout < 0) {
                throw new IllegalArgumentException("Timeout cannot be negative");
            }
            this.soTimeout = timeout;
        }

        @Override
        public synchronized int getSoTimeout() throws SocketException {
            return soTimeout;
        }

        @Override
        public void setTcpNoDelay(boolean on) throws SocketException {
            // UDS 不涉及 TCP，忽略
        }

        @Override
        public boolean getTcpNoDelay() throws SocketException {
            return true; // UDS 总是"无延迟"
        }

        @Override
        public void setKeepAlive(boolean on) throws SocketException {
            // UDS 不涉及 TCP keepalive，忽略
        }

        @Override
        public boolean getKeepAlive() throws SocketException {
            return false;
        }

        @Override
        public void setReceiveBufferSize(int size) throws SocketException {
            // UDS 不涉及 TCP 缓冲区，忽略
        }

        @Override
        public int getReceiveBufferSize() throws SocketException {
            return 8192; // 默认值
        }

        @Override
        public void setSendBufferSize(int size) throws SocketException {
            // UDS 不涉及 TCP 缓冲区，忽略
        }

        @Override
        public int getSendBufferSize() throws SocketException {
            return 8192; // 默认值
        }

        @Override
        public void setReuseAddress(boolean on) throws SocketException {
            // UDS 不涉及，忽略
        }

        @Override
        public boolean getReuseAddress() throws SocketException {
            return true;
        }

        @Override
        public void shutdownInput() throws IOException {
            channel.shutdownInput();
        }

        @Override
        public void shutdownOutput() throws IOException {
            channel.shutdownOutput();
        }

        @Override
        public SocketAddress getLocalSocketAddress() {
            return null; // UDS 没有本地地址概念
        }

        @Override
        public SocketAddress getRemoteSocketAddress() {
            if (channel.isOpen()) {
                try {
                    return channel.getRemoteAddress();
                } catch (IOException e) {
                    return null;
                }
            }
            return null;
        }

        @Override
        public int getPort() {
            return 0; // UDS 没有端口概念
        }

        @Override
        public int getLocalPort() {
            return 0; // UDS 没有端口概念
        }

        @Override
        public InetAddress getInetAddress() {
            return null; // UDS 没有 InetAddress 概念
        }

        @Override
        public InetAddress getLocalAddress() {
            return null; // UDS 没有 InetAddress 概念
        }

        @Override
        public String toString() {
            return "UdsSocket{channel=" + channel + "}";
        }
    }
}
