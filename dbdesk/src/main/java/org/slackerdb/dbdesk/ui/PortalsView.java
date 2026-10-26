package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinRequest;
import org.slackerdb.dbdesk.access.dashboard.Dashboard;
import org.slackerdb.dbdesk.access.dashboard.DashboardStore;
import org.slackerdb.dbdesk.access.portal.Portal;
import org.slackerdb.dbdesk.access.portal.PortalStore;

import java.net.InetAddress;
import java.util.List;

/**
 * 门户管理（M4-4）：把一个仪表板发布成"只读分享链接"。
 *
 * <p>链接形如 {@code /portal/{token}}，token 128 位随机、不可枚举；门户页不加载任何编辑入口。
 * 失效只需删掉这条门户记录。</p>
 */
@Route(value = "portals", layout = MainLayout.class)
public class PortalsView extends HorizontalLayout {

    private final PortalStore portals = PortalStore.defaultStore();
    private final DashboardStore dashboards = DashboardStore.defaultStore();

    private final Grid<Portal> list = new Grid<>(Portal.class, false);
    private final TextField name = new TextField("门户名");
    private final ComboBox<String> dashboard = new ComboBox<>("仪表板");
    private final IntegerField refreshSeconds = new IntegerField("自动刷新（秒，0=不刷新）");
    private final com.vaadin.flow.component.checkbox.Checkbox revoked =
            new com.vaadin.flow.component.checkbox.Checkbox("已撤销（链接立即失效）");
    private final Span link = new Span();
    private final Span status = new Span("就绪");

    private final Button create = new Button("新建");
    private final Button save = new Button("保存");
    private final Button remove = new Button("删除");
    private final Button copy = new Button("复制链接");

    private String editingId;
    private String currentToken;
    private List<Dashboard> dashboardItems = List.of();

    public PortalsView() {
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        VerticalLayout left = new VerticalLayout();
        left.setWidth("340px");
        left.setHeightFull();
        left.setPadding(false);
        H2 title = new H2("数据门户");
        title.getStyle().set("margin", "0");
        create.setId("portal-new");
        create.addClickListener(event -> newPortal());
        list.setId("portal-list");
        list.setWidthFull();
        list.setHeightFull();
        list.addColumn(Portal::name).setHeader("名称").setAutoWidth(true);
        list.addColumn(portal -> dashboardName(portal.dashboardId())).setHeader("仪表板").setAutoWidth(true);
        list.addColumn(portal -> portal.statusAt(java.time.Instant.now()))
                .setHeader("状态").setAutoWidth(true);
        list.addSelectionListener(event -> event.getFirstSelectedItem().ifPresent(this::load));
        left.add(title, new HorizontalLayout(create), list);
        left.setFlexGrow(1, list);

        VerticalLayout right = new VerticalLayout();
        right.setPadding(false);
        name.setId("portal-name");
        name.setWidth("260px");
        dashboard.setId("portal-dashboard");
        dashboard.setWidth("260px");
        refreshSeconds.setId("portal-refresh");
        refreshSeconds.setValue(60);
        refreshSeconds.setWidth("220px");
        revoked.setId("portal-revoked");
        link.setId("portal-link");
        status.setId("portal-status");

        save.setId("portal-save");
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        save.addClickListener(event -> saveCurrent());
        remove.setId("portal-delete");
        remove.addClickListener(event -> deleteCurrent());
        copy.setId("portal-copy");
        copy.addClickListener(event -> {
            if (currentToken == null) {
                status.setText("先保存门户");
                return;
            }
            String url = absoluteUrl(currentToken);
            UI.getCurrent().getPage().executeJs("navigator.clipboard && navigator.clipboard.writeText($0)", url);
            status.setText("链接已复制：" + url);
        });

        right.add(new HorizontalLayout(name, dashboard, refreshSeconds),
                new HorizontalLayout(revoked),
                new HorizontalLayout(save, remove, copy, status),
                new H3("分享链接"), link,
                new H3("说明"));
        right.add(new Span("门户是只读页面：没有 SQL 编辑器、没有连接切换，只有看板本身与自动刷新。"));
        add(left, right);
        setFlexGrow(1, right);

        refreshDashboards();
        refreshList();
        newPortal();
    }

    private void refreshDashboards() {
        dashboardItems = dashboards.all();
        dashboard.setItems(dashboardItems.stream().map(Dashboard::name).toList());
    }

    private void refreshList() {
        list.setItems(portals.all());
    }

    private String dashboardName(String id) {
        return dashboardItems.stream().filter(item -> item.id().equals(id))
                .map(Dashboard::name).findFirst().orElse("(已删除)");
    }

    private String absoluteUrl(String token) {
        String host = null;
        Integer port = null;
        String scheme = "http";
        VaadinRequest request = VaadinRequest.getCurrent();
        if (request != null) {
            String hostHeader = request.getHeader("Host");
            if (hostHeader != null && !hostHeader.isBlank()) {
                int colon = hostHeader.lastIndexOf(':');
                if (colon > 0) {
                    host = hostHeader.substring(0, colon);
                    try {
                        port = Integer.parseInt(hostHeader.substring(colon + 1));
                    } catch (NumberFormatException ignored) {
                        // 端口写坏就退回默认
                    }
                } else {
                    host = hostHeader;
                }
            }
            String forwardedProto = request.getHeader("X-Forwarded-Proto");
            if (forwardedProto != null && !forwardedProto.isBlank()) {
                scheme = forwardedProto.strip().toLowerCase(java.util.Locale.ROOT);
            }
        }
        if (host == null || host.isBlank() || "127.0.0.1".equals(host)
                || "localhost".equalsIgnoreCase(host) || "0.0.0.0".equals(host)) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "localhost";
            }
        }
        if (port == null) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        boolean defaultPort = ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        String authority = defaultPort ? host : host + ":" + port;
        return scheme + "://" + authority + "/portal/" + token;
    }

    private void newPortal() {
        editingId = null;
        currentToken = null;
        name.setValue("");
        refreshSeconds.setValue(60);
        revoked.setValue(false);
        link.setText("（保存后生成）");
        status.setText("新建门户");
        list.deselectAll();
        if (dashboard.getValue() == null && !dashboardItems.isEmpty()) {
            dashboard.setValue(dashboardItems.get(0).name());
        }
    }

    private void load(Portal portal) {
        editingId = portal.id();
        currentToken = portal.token();
        name.setValue(portal.name());
        refreshSeconds.setValue(portal.autoRefreshSeconds());
        revoked.setValue(portal.revoked());
        dashboard.setValue(dashboardName(portal.dashboardId()));
        link.setText(absoluteUrl(portal.token()));
        status.setText("已载入：" + portal.name());
    }

    private void saveCurrent() {
        try {
            Dashboard target = dashboardItems.stream()
                    .filter(item -> item.name().equals(dashboard.getValue()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("先选一个仪表板"));
            Portal portal = new Portal(editingId, name.getValue() == null || name.getValue().isBlank()
                    ? "未命名门户" : name.getValue(), target.id(), currentToken,
                    refreshSeconds.getValue() == null ? 0 : refreshSeconds.getValue(), null,
                    null, revoked.getValue());
            portals.save(portal);
            editingId = portal.id();
            currentToken = portal.token();
            link.setText(absoluteUrl(portal.token()));
            refreshList();
            status.setText("已保存：" + portal.name());
        } catch (Exception e) {
            status.setText("保存失败：" + message(e));
        }
    }

    private void deleteCurrent() {
        if (editingId == null) {
            status.setText("当前没有已保存的门户");
            return;
        }
        try {
            String deleted = name.getValue();
            portals.delete(editingId);
            refreshList();
            newPortal();
            status.setText("已删除：" + deleted + "（对应链接立即失效）");
        } catch (Exception e) {
            status.setText("删除失败：" + message(e));
        }
    }

    private static String message(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    /** 测试用。 */
    public String linkText() {
        return link.getText();
    }
}
