package org.slackerdb.dbdesk.i18n;

import java.util.Arrays;

/**
 * 带 i18n key 的异常：access 层只管抛 key 与参数，界面层用当前语言渲染。
 *
 * <p>放在 {@code i18n} 包里、不依赖 Vaadin/Javalin/Spring，所以 {@code access} 层可以安全使用；
 * 也不要用 {@link Throwable#getMessage()} 直接展示给用户——那是 key，留给日志检索。</p>
 *
 * <p>约定：只有<b>我们自己写的</b>用户可见消息用本异常；服务端返回的 {@code retMsg}、
 * JDBC 驱动消息、SQL 错误保持原文，不翻译。</p>
 */
public class LocalizedException extends RuntimeException implements Localized {

    private final String messageKey;
    private final Object[] messageArgs;

    public LocalizedException(String messageKey, Object... messageArgs) {
        super(messageKey);
        this.messageKey = messageKey;
        this.messageArgs = messageArgs == null ? new Object[0] : messageArgs.clone();
    }

    public LocalizedException(Throwable cause, String messageKey, Object... messageArgs) {
        super(messageKey, cause);
        this.messageKey = messageKey;
        this.messageArgs = messageArgs == null ? new Object[0] : messageArgs.clone();
    }

    public String messageKey() {
        return messageKey;
    }

    public Object[] messageArgs() {
        return messageArgs.clone();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + messageKey
                + (messageArgs.length == 0 ? "" : " " + Arrays.toString(messageArgs)) + "]";
    }
}
