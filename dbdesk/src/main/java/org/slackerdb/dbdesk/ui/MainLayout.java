package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import org.slackerdb.dbdesk.access.http.Capabilities;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 应用壳：左侧导航 + 顶栏连接状态。
 *
 * <p>启动永远处于未连接状态：不读 profile、不探测能力、不发健康请求。左侧菜单
 * 始终创建但未连接时全部禁用；"实例管理"只在“只连 dbproxy 管理端口”的集群连接下显示。
 * 连接后管理口失联不做自动降级，页面操作按原样报错。</p>
 */
public class MainLayout extends AppLayout {

    private final SideNav nav = new SideNav();
    private final Map<String, SideNavItem> navItems = new LinkedHashMap<>();
    private final ConnectionManager connection = ConnectionManager.current();
    private final ConnectionBar connectionBar = new ConnectionBar(connection);
    private final Runnable connectionListener = this::applyConnectionState;

    public MainLayout() {
        setPrimarySection(Section.DRAWER);
        I18n.applyPageMeta(UI.getCurrent(), null);

        addToDrawer(Branding.brand(I18n.t("app.title")), nav);

        HorizontalLayout topBar = new HorizontalLayout(connectionBar, languageButton());
        topBar.setWidthFull();
        topBar.setJustifyContentMode(FlexComponent.JustifyContentMode.END);
        topBar.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
        topBar.setSpacing(true);
        addToNavbar(topBar);

        nav.setId("main-nav");
        buildNav();
        applyConnectionState();
    }

    private Button languageButton() {
        // 按钮显示“将切换到的语言”，用该语言自己的写法
        boolean english = I18n.isEnglish();
        Button language = new Button(english ? I18n.t("language.zh") : I18n.t("language.en"));
        language.setId("language-switch");
        language.setTooltipText(I18n.t("language.switch.tooltip"));
        language.getElement().setAttribute("aria-label", I18n.t("language.switch.tooltip"));
        language.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        language.addClickListener(event -> {
            I18n.setLocale(english ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH);
            getUI().ifPresent(ui -> ui.getPage().reload());
        });
        return language;
    }

    private void buildNav() {
        nav.removeAll();
        navItems.clear();
        addItem("sql", "nav.sql", SqlWorkbenchView.class, VaadinIcon.CODE);
        addItem("cluster", "nav.cluster", ClusterView.class, VaadinIcon.CLUSTER);
        addItem("instances", "nav.instances", InstancesView.class, VaadinIcon.DASHBOARD);
        addItem("backups", "nav.backups", BackupsView.class, VaadinIcon.DOWNLOAD);
        addItem("logs", "nav.logs", LogsView.class, VaadinIcon.FILE_TEXT);
        addItem("data-services", "nav.dataServices", DataServicesView.class, VaadinIcon.CLOUD);
        addItem("mcp", "nav.mcp", McpView.class, VaadinIcon.PLUG);
        addItem("ai-chat", "nav.aiChat", AiChatView.class, VaadinIcon.COMMENT);
        addItem("import", "nav.import", ImportView.class, VaadinIcon.UPLOAD);
        addItem("datasets", "nav.datasets", DatasetsView.class, VaadinIcon.TABLE);
        addItem("charts", "nav.charts", ChartsView.class, VaadinIcon.CHART);
        addItem("dashboards", "nav.dashboards", DashboardsView.class, VaadinIcon.GRID);
        addItem("portals", "nav.portals", PortalsView.class, VaadinIcon.SHARE);
        addItem("scheduler", "nav.scheduler", SchedulerView.class, VaadinIcon.CALENDAR);
    }

    private void addItem(String key, String labelKey, Class<? extends com.vaadin.flow.component.Component> route,
                         VaadinIcon icon) {
        SideNavItem item = new SideNavItem(I18n.t(labelKey), route, icon.create());
        navItems.put(key, item);
        nav.addItem(item);
    }

    private void applyConnectionState() {
        boolean disconnected = connection.state() == ConnectionManager.State.DISCONNECTED
                || connection.state() == ConnectionManager.State.CONNECTING;
        if (disconnected) {
            navItems.values().forEach(item -> item.setEnabled(false));
            navItems.get("cluster").setVisible(false);
            return;
        }

        boolean instance = connection.hasInstanceManagement();
        boolean cluster = connection.hasClusterManagement();
        boolean database = connection.hasDatabase();
        Capabilities capabilities = connection.capabilities();

        navItems.get("sql").setEnabled(database);
        navItems.get("cluster").setVisible(cluster);
        navItems.get("cluster").setEnabled(cluster);
        navItems.get("instances").setEnabled(instance);
        navItems.get("backups").setEnabled(instance);
        navItems.get("logs").setEnabled(instance);
        navItems.get("data-services").setEnabled(instance && capability(capabilities, "dataService"));
        navItems.get("mcp").setEnabled(instance && capability(capabilities, "mcp"));
        navItems.get("ai-chat").setEnabled(instance && capability(capabilities, "aiChat"));
        navItems.get("import").setEnabled(instance);
        navItems.get("datasets").setEnabled(instance);
        navItems.get("charts").setEnabled(instance);
        navItems.get("dashboards").setEnabled(instance);
        navItems.get("portals").setEnabled(instance);
        navItems.get("scheduler").setEnabled(instance && capabilities != null && capabilities.hasEtl());
    }

    private static boolean capability(Capabilities capabilities, String name) {
        if (capabilities == null) {
            return false;
        }
        return switch (name) {
            case "dataService" -> capabilities.features().dataService();
            case "mcp" -> capabilities.features().mcp();
            case "aiChat" -> capabilities.features().aiChat();
            default -> false;
        };
    }

    @Override
    protected void onAttach(com.vaadin.flow.component.AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        connection.addListener(connectionListener);
        Branding.installFavicon();
        applyConnectionState();
    }

    @Override
    protected void onDetach(com.vaadin.flow.component.DetachEvent detachEvent) {
        connection.removeListener(connectionListener);
        super.onDetach(detachEvent);
    }

    /** 测试用：当前状态下的菜单是否全部禁用。 */
    public boolean allNavigationDisabled() {
        return navItems.values().stream().noneMatch(SideNavItem::isEnabled);
    }
}
