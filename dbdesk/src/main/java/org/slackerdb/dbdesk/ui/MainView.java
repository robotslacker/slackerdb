package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

/**
 * 主界面内容区：左侧菜单由 {@link MainLayout} 提供，右侧保持空白。
 *
 * <p>启动画面 {@link SplashScreen} 2 秒后导航到这里。用户从菜单进入具体功能页后，
 * 内容区才由对应页面填充。</p>
 */
@PageTitle("DbDesk")
@Route(value = "main", layout = MainLayout.class)
public class MainView extends VerticalLayout {

    public MainView() {
        setId("main-content");
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        add(new Div());
    }
}
