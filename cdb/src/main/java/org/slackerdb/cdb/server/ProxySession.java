package org.slackerdb.cdb.server;

import java.time.LocalDateTime;
import java.util.Map;

public class ProxySession {
    // 客户端连接建立时间
    public LocalDateTime connectedTime;

    // 客户端连接时候的选项
    public Map<String, String> startupOptions;

    // 当前会话状态  connected, dbConnected
    public String status = "N/A";

    // 客户端的IP地址
    public String clientAddress = "";

    // 当前调用的开始时间
    public LocalDateTime executingTime = null;
}
