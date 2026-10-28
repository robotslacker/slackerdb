package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
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
    private final TextField name = new TextField(I18n.t("portal.name"));
    private final ComboBox<String> dashboard = new ComboBox<>(I18n.t("portal.dashboard"));
    private final IntegerField refreshSeconds = new IntegerField(I18n.t("portal.refresh"));
    private final com.vaadin.flow.component.checkbox.Checkbox revoked =
            new com.vaadin.flow.component.checkbox.Checkbox(I18n.t("portal.revoked"));
    private final Span link = new Span();
    private final Span status = new Span(I18n.t("portal.status.ready"));

    private final Button create = new Button(I18n.t("common.create"));
    private final Button save = new Button(I18n.t("common.save"));
    private final Button remove = new Button(I18n.t("common.delete"));
    private final Button copy = new Button(I18n.t("portal.copy"));

    private String editingId;
    private String currentToken;
    private List<Dashboard> dashboardItems = List.of();

    public PortalsView() {
        setSizeFull();
        setPadding(true);
        setSpacing(true);
        I18n.setTitle("nav.portals");

        VerticalLayout left = new VerticalLayout();
        left.setWidth("340px");
        left.setHeightFull();
        left.setPadding(false);
        H2 title = new H2(I18n.t("portal.title"));
        title.getStyle().set("margin", "0");
        create.setId("portal-new");
        create.addClickListener(event -> newPortal());
        list.setId("portal-list");
        list.setWidthFull();
        list.setHeightFull();
        list.addColumn(Portal::name).setHeader(I18n.t("portal.column.name")).setAutoWidth(true);
        list.addColumn(portal -> dashboardName(portal.dashboardId()))
                .setHeader(I18n.t("portal.column.dashboard")).setAutoWidth(true);
        list.addColumn(portal -> I18n.text(portal.statusAt(java.time.Instant.now())))
                .setHeader(I18n.t("portal.column.status")).setAutoWidth(true);
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
                status.setText(I18n.t("portal.link.saveFirst"));
                return;
            }
            String url = absoluteUrl(currentToken);
            UI.getCurrent().getPage().executeJs("navigator.clipboard && navigator.clipboard.writeText($0)", url);
            status.setText(I18n.t("portal.link.copied", url));
        });

        right.add(new HorizontalLayout(name, dashboard, refreshSeconds),
                new HorizontalLayout(revoked),
                new HorizontalLayout(save, remove, copy, status),
                new H3(I18n.t("portal.link.title")), link,
                new H3(I18n.t("portal.notes.title")));
        right.add(new Span(I18n.t("portal.notes.body")));
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
                .map(Dashboard::name).findFirst().orElse(I18n.t("portal.deletedDashboard"));
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
        link.setText(I18n.t("portal.link.pending"));
        status.setText(I18n.t("portal.newStatus"));
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
        status.setText(I18n.t("portal.loaded", portal.name()));
    }

    private void saveCurrent() {
        try {
            Dashboard target = dashboardItems.stream()
                    .filter(item -> item.name().equals(dashboard.getValue()))
                    .findFirst()
                    .orElseThrow(() -> new org.slackerdb.dbdesk.i18n.LocalizedException("portal.dashboard.required"));
            Portal portal = new Portal(editingId, name.getValue() == null || name.getValue().isBlank()
                    ? I18n.t("portal.unnamed") : name.getValue(), target.id(), currentToken,
                    refreshSeconds.getValue() == null ? 0 : refreshSeconds.getValue(), null,
                    null, revoked.getValue());
            portals.save(portal);
            editingId = portal.id();
            currentToken = portal.token();
            link.setText(absoluteUrl(portal.token()));
            refreshList();
            status.setText(I18n.t("portal.saved", portal.name()));
        } catch (Exception e) {
            status.setText(I18n.t("portal.saveFailed", I18n.message(e)));
        }
    }

    private void deleteCurrent() {
        if (editingId == null) {
            status.setText(I18n.t("portal.none"));
            return;
        }
        try {
            String deleted = name.getValue();
            portals.delete(editingId);
            refreshList();
            newPortal();
            status.setText(I18n.t("portal.deleted", deleted));
        } catch (Exception e) {
            status.setText(I18n.t("portal.deleteFailed", I18n.message(e)));
        }
    }

    /** 测试用。 */
    public String linkText() {
        return link.getText();
    }
}
