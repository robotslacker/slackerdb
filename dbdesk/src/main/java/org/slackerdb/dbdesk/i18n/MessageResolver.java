package org.slackerdb.dbdesk.i18n;

import java.util.Arrays;
import java.util.function.Function;

/**
 * access 层需要"在构造数据时就把文案翻好"的接缝（比如 ECharts option 里的标题）。
 *
 * <p>access 不认识 Vaadin，也不该认识；界面层启动时把 {@link org.slackerdb.dbdesk.ui.I18n#text}
 * 装进来，access 在生成展示数据时调用 {@link #render(Localized)}。没装之前（单元测试）
 * 退回成 {@code key + args}，既不会抛异常，也能让断言看到参数。</p>
 *
 * <p>全局单例是刻意的：这里替代的只是"取当前语言"这一步，DbDesk 是单进程本机应用，
 * 语言切换会整页重载，不存在并发渲染两种语言的场景。</p>
 */
public final class MessageResolver {

    private static volatile Function<Localized, String> renderer = MessageResolver::fallback;

    private MessageResolver() {
    }

    public static void install(Function<Localized, String> newRenderer) {
        renderer = newRenderer == null ? MessageResolver::fallback : newRenderer;
    }

    public static String render(Localized message) {
        return message == null ? "" : renderer.apply(message);
    }

    public static String render(String key, Object... args) {
        return render(LocalizedMessage.of(key, args));
    }

    private static String fallback(Localized message) {
        Object[] args = message.messageArgs();
        return args.length == 0
                ? message.messageKey()
                : message.messageKey() + " " + Arrays.toString(args);
    }
}
