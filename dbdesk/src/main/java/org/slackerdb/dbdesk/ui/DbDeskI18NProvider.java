package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.i18n.I18NProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 把 Vaadin 组件（DatePicker 月份、Grid 列菜单、Upload、ConfirmDialog…）的内置文案
 * 接到我们自己的资源文件上：key 形如 {@code vaadin.xxx}，和界面文案同一份 properties。
 *
 * <p>Vaadin 组件的 key 不在我们的资源里时返回 key 本身（框架默认行为）；
 * 需要覆盖哪一条，就在两个 properties 里同时加一条 {@code vaadin.*}——
 * 具体 key 以实际运行时缺哪条为准，不要凭空猜。</p>
 */
@Component
public class DbDeskI18NProvider implements I18NProvider {

    private static final List<Locale> LOCALES = List.of(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE);

    public DbDeskI18NProvider() {
        // access 层生成展示数据（图表标题等）时用的渲染器；Bean 创建即安装。
        org.slackerdb.dbdesk.i18n.MessageResolver.install(I18n::text);
    }

    @Override
    public List<Locale> getProvidedLocales() {
        return LOCALES;
    }

    @Override
    public String getTranslation(String key, Locale locale, Object... params) {
        if (key == null) {
            return null;
        }
        String text = I18n.lookup(locale, key, params);
        return text == null ? key : text;
    }
}
