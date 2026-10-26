package org.slackerdb.dbdesk.access.ai;

/**
 * AI Chat WebSocket 事件。
 *
 * @param type    connected / response / error / info
 * @param content 文本内容
 */
public record AiChatMessage(String type, String content) {
}
