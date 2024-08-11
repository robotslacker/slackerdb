package org.slackerdb.protocol.postgres.server;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ChannelAttributeManager {
    // 使用 Map 存储每个 Channel 的属性集合
    private final Map<Channel, Map<String, Object>> channelAttributes = new HashMap<>();

    // 设置属性并记录到属性集合中
    public void setAttribute(Channel channel, String key, Object value) {
        channel.attr(AttributeKey.valueOf(key)).set(value);

        // 获取或创建该 Channel 的属性集合
        Map<String, Object> attributes = channelAttributes.computeIfAbsent(channel, k -> new HashMap<>());
        attributes.put(key, value);
    }

    // 获取某个 Channel 的所有属性键集合
    public Set<String> listAttributes(Channel channel) {
        Map<String, Object> attributes = channelAttributes.get(channel);
        return attributes != null ? attributes.keySet() : null;
    }

    // 获取某个属性的值
    public Object getAttribute(Channel channel, String key) {
        return channel.attr(AttributeKey.valueOf(key)).get();
    }

    public void clear(Channel channel)
    {
        channelAttributes.get(channel).clear();
        channelAttributes.remove(channel);
    }
}
