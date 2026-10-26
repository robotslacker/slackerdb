package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;

/** 应用图标 / 品牌小工具。图标文件由 META-INF/resources/robotslacker.png 提供。 */
public final class Branding {

    public static final String ICON = "robotslacker.png";

    private Branding() {
    }

    public static HorizontalLayout brand(String title) {
        Image icon = new Image(ICON, title);
        icon.setWidth("30px");
        icon.setHeight("30px");
        icon.getStyle().set("border-radius", "6px").set("object-fit", "cover");
        Span name = new Span(title);
        name.getStyle()
                .set("font-size", "var(--lumo-font-size-l)")
                .set("font-weight", "600");
        HorizontalLayout brand = new HorizontalLayout(icon, name);
        brand.setDefaultVerticalComponentAlignment(
                com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER);
        brand.setSpacing(true);
        brand.setPadding(false);
        return brand;
    }

    /** 浏览器标签页图标；在 UI 已附着后调用。 */
    public static void installFavicon() {
        UI ui = UI.getCurrent();
        if (ui == null) {
            return;
        }
        ui.getPage().executeJs("""
                const href = 'robotslacker.png';
                let link = document.querySelector('link[rel~="icon"]');
                if (!link) {
                    link = document.createElement('link');
                    link.rel = 'icon';
                    document.head.appendChild(link);
                }
                link.href = href;
                """);
    }
}
