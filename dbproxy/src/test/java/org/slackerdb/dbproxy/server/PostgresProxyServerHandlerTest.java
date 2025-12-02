package org.slackerdb.dbproxy.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbproxy.configuration.ServerConfiguration;
import org.slackerdb.dbproxy.message.request.AdminClientRequest;
import org.slackerdb.dbproxy.message.request.ProxyRequest;
import org.slackerdb.dbproxy.message.request.SSLRequest;
import org.slackerdb.dbproxy.message.request.StartupRequest;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class PostgresProxyServerHandlerTest {

    private ProxyInstance proxyInstance;
    private Logger logger;

    @BeforeEach
    void setUp() throws ServerException {
        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setPort(-1);
        configuration.setPortX(-1);

        logger = (Logger) org.slf4j.LoggerFactory.getLogger("PostgresProxyServerHandlerTest");
        logger.setLevel(Level.INFO);

        proxyInstance = new ProxyInstance(configuration);
        proxyInstance.logger = logger;
        proxyInstance.instanceState = "RUNNING";
        proxyInstance.bootTime = LocalDateTime.now().minusMinutes(1);
    }

    @Test
    void sslRequestProducesNoticeMessage() throws Exception {
        PostgresProxyServerHandler handler = new PostgresProxyServerHandler(logger, proxyInstance);
        TestEmbeddedChannel channel = new TestEmbeddedChannel(handler);
        try {
            SSLRequest sslRequest = new SSLRequest(proxyInstance);
            assertTrue(channel.writeInbound(sslRequest));

            ByteBuffer outbound = channel.readOutbound();
            assertNotNull(outbound);
            assertEquals('N', outbound.get());
            assertFalse(outbound.hasRemaining());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void startupWithoutDatabaseIsIgnored() throws Exception {
        PostgresProxyServerHandler handler = new PostgresProxyServerHandler(logger, proxyInstance);
        TestEmbeddedChannel channel = new TestEmbeddedChannel(handler);
        try {
            StartupRequest request = new StartupRequest(proxyInstance);
            channel.writeInbound(request);

            assertNull(channel.readOutbound());
            assertTrue(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void startupWithUnknownDatabaseReturnsErrorAndClosesChannel() throws Exception {
        PostgresProxyServerHandler handler = new PostgresProxyServerHandler(logger, proxyInstance);
        TestEmbeddedChannel channel = new TestEmbeddedChannel(handler);
        try {
            StartupRequest request = new StartupRequest(proxyInstance);
            request.getStartupOptions().put("database", "missing_db");

            channel.writeInbound(request);

            ByteBuffer outbound = channel.readOutbound();
            assertNotNull(outbound);
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void proxyRequestForwardsPayloadToOutboundChannel() throws Exception {
        PostgresProxyServerHandler handler = new PostgresProxyServerHandler(logger, proxyInstance);
        TestEmbeddedChannel inboundChannel = new TestEmbeddedChannel(handler);
        EmbeddedChannel outboundChannel = new EmbeddedChannel();
        setOutboundChannel(handler, outboundChannel);

        try {
            ProxyRequest proxyRequest = new ProxyRequest(proxyInstance);
            proxyRequest.setMessageType((byte) 'Q');
            byte[] payload = "select 1".getBytes(StandardCharsets.UTF_8);
            proxyRequest.decode(payload);

            inboundChannel.writeInbound(proxyRequest);

            ByteBuf forwarded = outboundChannel.readOutbound();
            assertNotNull(forwarded);
            assertEquals('Q', forwarded.readByte());
            int length = forwarded.readInt();
            assertEquals(payload.length + 4, length);
            byte[] actualPayload = new byte[payload.length];
            forwarded.readBytes(actualPayload);
            assertArrayEquals(payload, actualPayload);
            forwarded.release();
        } finally {
            inboundChannel.finishAndReleaseAll();
            outboundChannel.finishAndReleaseAll();
        }
    }

    @Test
    void adminClientStatusRequestProducesResponse() throws Exception {
        PostgresProxyServerHandler handler = new PostgresProxyServerHandler(logger, proxyInstance);
        TestEmbeddedChannel channel = new TestEmbeddedChannel(handler);
        try {
            AdminClientRequest adminClientRequest = new AdminClientRequest(proxyInstance);
            adminClientRequest.clientRequestCommand = "STATUS";

            channel.writeInbound(adminClientRequest);

            ByteBuffer outbound = channel.readOutbound();
            assertNotNull(outbound);
            assertTrue(outbound.remaining() > 0);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private void setOutboundChannel(PostgresProxyServerHandler handler, Channel outbound) throws Exception {
        Field field = PostgresProxyServerHandler.class.getDeclaredField("outboundChannel");
        field.setAccessible(true);
        field.set(handler, outbound);
    }

    private static final class TestEmbeddedChannel extends EmbeddedChannel {
        private final InetSocketAddress local = new InetSocketAddress("127.0.0.1", 4000);
        private final InetSocketAddress remote = new InetSocketAddress("127.0.0.1", 5432);

        private TestEmbeddedChannel(PostgresProxyServerHandler handler) {
            super(handler);
        }

        @Override
        public InetSocketAddress remoteAddress() {
            return remote;
        }

        @Override
        public InetSocketAddress localAddress() {
            return local;
        }
    }
}
