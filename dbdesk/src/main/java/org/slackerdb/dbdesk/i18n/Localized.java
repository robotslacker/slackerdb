package org.slackerdb.dbdesk.i18n;

/**
 * “这条消息是一个 i18n key + 参数”的标记接口。
 *
 * <p>为什么不用一个异常类：{@code ConnectionProbe.ProbeException} 是受检异常，
 * {@link LocalizedException} 是非受检异常，两者都要能携带 key，界面层判断时不必逐个类型列举。</p>
 */
public interface Localized {

    String messageKey();

    Object[] messageArgs();
}
