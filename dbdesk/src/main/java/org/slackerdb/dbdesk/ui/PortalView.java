package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import org.slackerdb.dbdesk.access.chart.ChartStore;
import org.slackerdb.dbdesk.access.dashboard.Dashboard;
import org.slackerdb.dbdesk.access.dashboard.DashboardData;
import org.slackerdb.dbdesk.access.dashboard.DashboardStore;
import org.slackerdb.dbdesk.access.dataset.DatasetStore;
import org.slackerdb.dbdesk.access.portal.Portal;
import org.slackerdb.dbdesk.access.portal.PortalStore;
import org.slackerdb.dbdesk.jsbridge.ChartView;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 数据门户页（M4-4）：<b>只读</b>看板，按 token 访问。
 *
 * <p>安全边界靠"没有东西可点"来保证：这里不加载编辑器、不切连接、不写任何状态；
 * 唯一的动作是定时重绘。token 不存在时给一个明确的失效页，不抛 500。</p>
 *
 * <p>该路由**不套 MainLayout**：门户是给业务方看的，出现侧边栏（能点进 SQL 工作台）就失去了只读的意义。</p>
 *
 * <p>语言跟随浏览者当前会话（默认英文）：门户本身不提供切换入口。</p>
 */
@Route("portal/:token")
public class PortalView extends VerticalLayout implements BeforeEnterObserver {

    private final PortalStore portals = PortalStore.defaultStore();
    private final DashboardStore dashboards = DashboardStore.defaultStore();
    private final DatasetStore datasets = DatasetStore.defaultStore();
    private final ChartStore charts = ChartStore.defaultStore();

    private final H1 title = new H1();
    private final Span subtitle = new Span();
    private final Div canvas = new Div();
    private final Span status = new Span();

    private String token;

    public PortalView() {
        setSizeFull();
        setPadding(true);
        setSpacing(true);
        title.setId("portal-title");
        title.getStyle().set("margin", "0");
        subtitle.setId("portal-subtitle");
        subtitle.getStyle().set("color", "var(--lumo-secondary-text-color)");
        canvas.setId("portal-canvas");
        canvas.setWidthFull();
        canvas.getStyle()
                .set("display", "grid")
                .set("grid-template-columns", "repeat(12, 1fr)")
                .set("gap", "12px");
        status.setId("portal-status");
        add(title, subtitle, canvas, status);
        setFlexGrow(1, canvas);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        token = event.getRouteParameters().get("token").orElse("");
        render();
    }

    private void render() {
        Optional<Portal> portal = portals.findByToken(token);
        // 生命周期检查：撤销/过期都要给出明确原因，而不是笼统的"无效"
        if (portal.isPresent() && !portal.get().usableAt(java.time.Instant.now())) {
            title.setText(portal.get().revoked() ? I18n.t("share.revoked.title") : I18n.t("share.expired.title"));
            subtitle.setText(I18n.t("share.contact"));
            canvas.removeAll();
            status.setText("");
            UI.getCurrent().setPollInterval(-1);
            return;
        }
        if (portal.isEmpty()) {
            title.setText(I18n.t("share.invalid.title"));
            subtitle.setText(I18n.t("share.contact"));
            canvas.removeAll();
            status.setText("");
            return;
        }
        Dashboard dashboard = dashboards.find(portal.get().dashboardId()).orElse(null);
        if (dashboard == null) {
            title.setText(portal.get().name());
            subtitle.setText(I18n.t("share.dashboardDeleted"));
            canvas.removeAll();
            return;
        }
        title.setText(dashboard.name());
        subtitle.setText(I18n.t("share.subtitle",
                LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))));

        DashboardData.Result result = new DashboardData(datasets, charts).render(dashboard, null, 2_000);
        canvas.removeAll();
        for (DashboardData.CardResult card : result.cards()) {
            canvas.add(buildCard(card));
        }
        status.setText(I18n.t("share.stats", result.cards().size(), result.elapsedMillis()));

        UI ui = UI.getCurrent();
        if (portal.get().autoRefreshSeconds() > 0) {
            ui.setPollInterval(portal.get().autoRefreshSeconds() * 1_000);
            ui.addPollListener(event -> render());
        } else {
            ui.setPollInterval(-1);
        }
    }

    private VerticalLayout buildCard(DashboardData.CardResult card) {
        VerticalLayout box = new VerticalLayout();
        box.setPadding(false);
        box.setSpacing(false);
        box.getStyle()
                .set("grid-column", "span " + card.card().w())
                .set("border", "1px solid var(--lumo-contrast-10pct)")
                .set("border-radius", "8px")
                .set("padding", "8px");
        H4 cardTitle = new H4(card.chartName());
        cardTitle.getStyle().set("margin", "0 0 4px 0");
        box.add(cardTitle);
        if (card.error() != null) {
            box.add(new Span(I18n.t("share.card.failed", card.error())));
            return box;
        }
        ChartView view = new ChartView();
        view.setId("portal-card-" + card.card().chartId());
        view.setWidthFull();
        view.setHeight((card.card().h() * 70) + "px");
        view.setOption(card.option());
        box.add(view);
        return box;
    }
}
