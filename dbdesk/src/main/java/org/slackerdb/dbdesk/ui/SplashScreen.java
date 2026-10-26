package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动画面：根路径显示 2 秒，然后进入带左侧菜单、右侧空白的主界面。
 *
 * <p>启动画面不挂 {@link MainLayout}，因此这两秒内不显示导航；2 秒后导航到
 * {@code /main}，由 {@link MainView} 以 MainLayout 渲染空内容。</p>
 */
@PageTitle("DbDesk")
@Route("")
public class SplashScreen extends VerticalLayout {

    private final AtomicBoolean navigated = new AtomicBoolean(false);

    public SplashScreen() {
        setSizeFull();
        setPadding(true);
        setSpacing(true);
        setAlignItems(FlexComponent.Alignment.CENTER);
        setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);
        getStyle()
                .set("background", "radial-gradient(circle at 50% 35%, #2f6f8f 0%, #173042 55%, #0d1b24 100%)")
                .set("color", "white");

        Image icon = new Image(Branding.ICON, "DbDesk");
        icon.setWidth("96px");
        icon.setHeight("96px");
        icon.getStyle().set("border-radius", "16px").set("object-fit", "cover");

        H1 title = new H1("DbDesk");
        title.setId("splash-title");
        title.getStyle()
                .set("margin", "0")
                .set("font-size", "clamp(2.5rem, 6vw, 5rem)")
                .set("letter-spacing", "0.06em");

        Span subtitle = new Span("SlackerDB Console");
        subtitle.setId("splash-subtitle");
        subtitle.getStyle()
                .set("font-size", "var(--lumo-font-size-l)")
                .set("opacity", "0.85")
                .set("letter-spacing", "0.08em");

        add(icon, title, subtitle);
    }

    @Override
    protected void onAttach(com.vaadin.flow.component.AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        Branding.installFavicon();
        UI ui = attachEvent.getUI();
        CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS).execute(() -> {
            if (!navigated.compareAndSet(false, true)) {
                return;
            }
            try {
                ui.access(() -> ui.navigate("main"));
            } catch (Exception ignored) {
                // 页面在 2 秒内被关闭时忽略；不影响主界面直接访问
            }
        });
    }
}
