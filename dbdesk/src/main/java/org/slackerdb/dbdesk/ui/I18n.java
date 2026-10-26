package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.server.VaadinSession;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * DbDesk 的轻量 i18n 入口。
 *
 * <p>语言按 Vaadin 会话保存在内存中；语言文件放在 {@code src/main/resources/i18n/messages_*.properties}。
 * 当前只覆盖应用壳与连接区，后续页面可以逐步改用 {@link #t(String)}，不需要再动框架。</p>
 */
public final class I18n {

    private static final String ATTR = I18n.class.getName() + ".locale";
    private static final String BUNDLE = "i18n.messages";

    private I18n() {
    }

    public static Locale locale() {
        VaadinSession session = VaadinSession.getCurrent();
        Object value = session == null ? null : session.getAttribute(ATTR);
        return value instanceof Locale locale ? locale : Locale.SIMPLIFIED_CHINESE;
    }

    public static void setLocale(Locale locale) {
        VaadinSession session = VaadinSession.getCurrent();
        if (session != null) {
            session.setAttribute(ATTR, locale == null ? Locale.SIMPLIFIED_CHINESE : locale);
        }
    }

    public static boolean isEnglish() {
        return Locale.ENGLISH.getLanguage().equals(locale().getLanguage());
    }

    public static String t(String key) {
        try {
            return ResourceBundle.getBundle(BUNDLE, locale()).getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }
}
