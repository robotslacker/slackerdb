package org.slackerdb.dbdesk.i18n;

import java.util.Objects;

/**
 * 一条待渲染的本地化消息（key + 参数）。
 *
 * <p>用于“先拿到、晚点再按用户当前语言渲染”的场景，比如连接探测产出的 warning：
 * 探测发生在 access 层（不认识 Vaadin），渲染发生在顶栏。</p>
 */
public record LocalizedMessage(String messageKey, Object[] messageArgs) implements Localized {

    public LocalizedMessage {
        Objects.requireNonNull(messageKey, "messageKey");
        messageArgs = messageArgs == null ? new Object[0] : messageArgs.clone();
    }

    public static LocalizedMessage of(String messageKey, Object... args) {
        return new LocalizedMessage(messageKey, args);
    }

    @Override
    public Object[] messageArgs() {
        return messageArgs.clone();
    }
}
