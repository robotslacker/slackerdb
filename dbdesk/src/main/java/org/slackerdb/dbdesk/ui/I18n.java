package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slackerdb.dbdesk.access.profile.DbDeskProfileStore;
import org.slackerdb.dbdesk.i18n.LocalizedException;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DbDesk 的 i18n 入口。
 *
 * <p>语言按 Vaadin 会话保存在内存中，并持久化到 {@code ~/.slackerdb/profiles.json} 的
 * {@code language} 字段：会话优先，其次 profile，最后默认 <b>英文</b>。
 * 文案放在 {@code src/main/resources/i18n/messages*.properties}：</p>
 *
 * <ul>
 *   <li>{@code messages.properties} —— 英文，同时是所有语言的兜底；</li>
 *   <li>{@code messages_zh.properties} —— 中文。</li>
 * </ul>
 *
 * <p>带参数的句子必须用 {@link #t(String, Object...)}（MessageFormat 占位符 {@code {0}}），
 * 不要用字符串拼接，否则中英语序无法各自成立。</p>
 */
public final class I18n {

    private static final Logger log = LoggerFactory.getLogger(I18n.class);

    private static final String ATTR = I18n.class.getName() + ".locale";
    private static final String BUNDLE = "i18n.messages";
    private static final String COOKIE_NAME = "dbdesk.lang";
    private static final String LANG_EN = "en";
    private static final String LANG_ZH = "zh-CN";

    /** 没写过语言设置时使用的语言。 */
    public static final Locale DEFAULT_LOCALE = Locale.ENGLISH;

    /**
     * 关掉 "找不到请求语言就退回 JVM 默认语言" 这条规则。
     *
     * <p>少了它，在中文 Windows（{@code user.language=zh}）上请求英文会得到中文：
     * 请求 {@code en} 找不到 {@code messages_en.properties} 时，Java 会退到 JVM 默认语言
     * {@code zh}，而不是我们用 {@code messages.properties} 表达的英文默认包。
     * 用 no-fallback control 后候选只有 {@code [en, root]}，root 就是英文。</p>
     */
    private static final ResourceBundle.Control CONTROL =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    /** 已经报过缺失的 key，避免每次渲染都刷日志。 */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    static {
        // access 层生成展示数据（图表标题等）时用的渲染器；类一加载就装上，
        // 不依赖 Spring Bean 的创建时机。
        org.slackerdb.dbdesk.i18n.MessageResolver.install(I18n::text);
    }

    private I18n() {
    }

    /** 当前会话的语言；不在 Vaadin 会话里时一律英文。 */
    public static Locale locale() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null) {
            return DEFAULT_LOCALE;
        }
        Object value = session.getAttribute(ATTR);
        if (value instanceof Locale locale) {
            return locale;
        }
        // 没记录过：先看浏览器的 dbdesk.lang（UI 测试与多浏览器并存时用），再看 profile
        Locale resolved = cookieLocale();
        if (resolved == null) {
            resolved = storedLocale();
        }
        session.setAttribute(ATTR, resolved);
        return resolved;
    }

    private static Locale cookieLocale() {
        com.vaadin.flow.server.VaadinRequest request = com.vaadin.flow.server.VaadinRequest.getCurrent();
        if (request == null) {
            return null;
        }
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (jakarta.servlet.http.Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : fromCode(value);
            }
        }
        return null;
    }

    /** 切换语言：写会话、写 cookie 并持久化到 profile；页面需要重载才能重渲染。 */
    public static void setLocale(Locale locale) {
        Locale target = locale == null ? DEFAULT_LOCALE : locale;
        VaadinSession session = VaadinSession.getCurrent();
        if (session != null) {
            session.setAttribute(ATTR, target);
        }
        writeCookie(codeOf(target));
        try {
            DbDeskProfileStore.defaultStore().saveLanguage(codeOf(target));
        } catch (Exception e) {
            log.warn("Failed to save the language setting: {}", e.getMessage());
        }
    }

    private static void writeCookie(String code) {
        com.vaadin.flow.server.VaadinResponse response = com.vaadin.flow.server.VaadinResponse.getCurrent();
        if (response == null) {
            return;
        }
        jakarta.servlet.http.Cookie cookie = new jakarta.servlet.http.Cookie(COOKIE_NAME, code);
        cookie.setPath("/");
        cookie.setMaxAge(365 * 24 * 60 * 60);
        response.addCookie(cookie);
    }

    /** 当前语言的持久化代码（{@code en} / {@code zh-CN}）。 */
    public static String code() {
        return codeOf(locale());
    }

    public static String codeOf(Locale locale) {
        if (locale == null) {
            return LANG_EN;
        }
        return Locale.ENGLISH.getLanguage().equals(locale.getLanguage()) ? LANG_EN : LANG_ZH;
    }

    public static Locale fromCode(String code) {
        if (code == null) {
            return DEFAULT_LOCALE;
        }
        return switch (code.strip().toLowerCase(Locale.ROOT)) {
            case "zh", "zh-cn", "zh_cn", "cn" -> Locale.SIMPLIFIED_CHINESE;
            default -> DEFAULT_LOCALE;
        };
    }

    public static boolean isEnglish() {
        return Locale.ENGLISH.getLanguage().equals(locale().getLanguage());
    }

    public static boolean isChinese() {
        return !isEnglish();
    }

    public static String t(String key) {
        return lookup(locale(), key);
    }

    /** 带占位符的文案：{@code t("backup.status.done", file, bytes)}。 */
    public static String t(String key, Object... args) {
        return lookup(locale(), key, args);
    }

    /**
     * 按指定语言取文案；key 不存在时返回 {@code null}。
     *
     * <p>给 Vaadin 的 {@code I18NProvider} 用：它可以再退到框架自带的英文兜底。</p>
     */
    public static String lookup(Locale locale, String key, Object... args) {
        ResourceBundle bundle = bundle(locale);
        String pattern;
        try {
            pattern = bundle.getString(key);
        } catch (MissingResourceException e) {
            if (REPORTED.add(key)) {
                log.warn("Missing i18n key {} for locale {}", key, locale);
            }
            return null;
        }
        if (args == null || args.length == 0) {
            return pattern;
        }
        try {
            return new MessageFormat(pattern, locale == null ? DEFAULT_LOCALE : locale).format(args);
        } catch (IllegalArgumentException e) {
            log.warn("i18n placeholder/argument mismatch: key={} pattern={}", key, pattern);
            return pattern;
        }
    }

    /**
     * 把异常转成可展示的文案：{@link org.slackerdb.dbdesk.i18n.Localized} 按当前语言翻译，
     * 其它异常（服务端返回、JDBC 驱动、SQL 错误）原样显示，不做二次翻译。
     */
    public static String message(Throwable error) {
        if (error == null) {
            return "";
        }
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        if (current instanceof org.slackerdb.dbdesk.i18n.Localized localized) {
            return text(localized);
        }
        String text = current.getMessage();
        return text == null || text.isBlank() ? current.getClass().getSimpleName() : text;
    }

    /** 渲染一条本地化消息；参数里嵌套的 {@link org.slackerdb.dbdesk.i18n.Localized} 会先展开。 */
    public static String text(org.slackerdb.dbdesk.i18n.Localized message) {
        if (message == null) {
            return "";
        }
        Object[] args = message.messageArgs();
        Object[] rendered = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            rendered[i] = args[i] instanceof org.slackerdb.dbdesk.i18n.Localized nested ? text(nested) : args[i];
        }
        String text = lookup(locale(), message.messageKey(), rendered);
        return text == null ? message.messageKey() : text;
    }

    /** 设置页面标题（{@code DbDesk · <导航名>}）与 {@code <html lang>}。 */
    public static void applyPageMeta(UI ui, String navKey) {
        if (ui == null) {
            return;
        }
        ui.setLocale(locale());
        String title = t("app.title");
        if (navKey != null && !navKey.isBlank()) {
            title = title + " · " + t(navKey);
        }
        ui.getPage().setTitle(title);
        ui.getPage().executeJs("document.documentElement.lang = $0", isEnglish() ? "en" : "zh-CN");
    }

    /** 视图构造期设置自己的标题；{@code UI} 还没就绪时静默跳过。 */
    public static void setTitle(String navKey) {
        applyPageMeta(UI.getCurrent(), navKey);
    }

    private static Locale storedLocale() {
        try {
            return fromCode(DbDeskProfileStore.defaultStore().language());
        } catch (RuntimeException e) {
            log.warn("Failed to read the language setting; falling back to English: {}", e.getMessage());
            return DEFAULT_LOCALE;
        }
    }

    private static ResourceBundle bundle(Locale locale) {
        Locale target = locale == null ? DEFAULT_LOCALE : locale;
        try {
            return ResourceBundle.getBundle(BUNDLE, target, CONTROL);
        } catch (MissingResourceException e) {
            // 理论上默认包永远存在；真丢了也不能让整页 500
            log.error("Missing i18n bundle {}", BUNDLE, e);
            return ResourceBundle.getBundle(BUNDLE, DEFAULT_LOCALE, I18n.class.getClassLoader(), CONTROL);
        }
    }
}
