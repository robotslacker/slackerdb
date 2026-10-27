package org.slackerdb.dbdesk.access.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiChatClientTest {

    @Test
    void convertsHttpManagementUrlToWebSocketEndpoint() {
        assertEquals("ws://127.0.0.1:8409/aichat",
                AiChatClient.websocketUrlFor("http://127.0.0.1:8409"));
        // 经 dbProxy 时管理口 base 带 /<别名>，WS 地址必须保留这段路径
        assertEquals("ws://127.0.0.1:8409/sales/aichat",
                AiChatClient.websocketUrlFor("http://127.0.0.1:8409/sales"));
        assertEquals("wss://example.com/aichat",
                AiChatClient.websocketUrlFor("https://example.com/"));
        assertEquals("ws://example.com/aichat",
                AiChatClient.websocketUrlFor("example.com"));
    }
}
