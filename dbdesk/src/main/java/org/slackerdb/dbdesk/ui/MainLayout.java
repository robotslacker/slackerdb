package org.slackerdb.dbdesk.ui;

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
 * 始终创建但全部禁用；连接成功后按状态与 capability 启用。连接后管理口失联不做
 * 自动降级，页面操作按原样报错。</p>
 */
public class MainLayout extends AppLayout {

    private final SideNav nav = new SideNav();
    private final Map<String, SideNavItem> navItems = new LinkedHashMap<>();
    private final ConnectionManager connection = ConnectionManager.current();
    private final ConnectionBar connectionBar = new ConnectionBar(connection);
    private final Runnable connectionListener = this::applyConnectionState;

    public MainLayout() {
        setPrimarySection(Section.DRAWER);

        addToDrawer(Branding.brand("DbDesk"), nav);

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
        Button language = new Button(I18n.isEnglish() ? "中文" : "EN");
        language.setId("language-switch");
        language.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        language.addClickListener(event -> {
            I18n.setLocale(I18n.isEnglish() ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH);
            getUI().ifPresent(ui -> ui.getPage().reload());
        });
        return language;
    }

    private void buildNav() {
        nav.removeAll();
        navItems.clear();
        addItem("sql", "nav.sql", SqlWorkbenchView.class, VaadinIcon.CODE);
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
            return;
        }

        boolean sqlOnly = connection.isSqlOnly();
        Capabilities capabilities = connection.capabilities();
        boolean connected = connection.state() == ConnectionManager.State.CONNECTED;

        navItems.get("sql").setEnabled(true);
        navItems.get("instances").setEnabled(connected);
        navItems.get("backups").setEnabled(connected);
        navItems.get("logs").setEnabled(connected);
        navItems.get("data-services").setEnabled(connected && capability(capabilities, "dataService"));
        navItems.get("mcp").setEnabled(connected && capability(capabilities, "mcp"));
        navItems.get("ai-chat").setEnabled(connected && capability(capabilities, "aiChat"));
        navItems.get("import").setEnabled(connected);
        navItems.get("datasets").setEnabled(connected);
        navItems.get("charts").setEnabled(connected);
        navItems.get("dashboards").setEnabled(connected);
        navItems.get("portals").setEnabled(connected);
        navItems.get("scheduler").setEnabled(connected && capabilities != null && capabilities.hasEtl());

        if (sqlOnly) {
            // SQL_ONLY 时上述 connected 均为 false，只留下 SQL 控制台。
            navItems.get("sql").setEnabled(true);
        }
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
