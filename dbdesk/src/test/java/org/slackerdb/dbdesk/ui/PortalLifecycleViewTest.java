package org.slackerdb.dbdesk.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6：门户生命周期在页面上的表现 —— 历史过期时间不再生效，撤销仍立即失效。
 *
 * <p>直接写 portals 文件（与其它 M4/M5 用例同一手法），不依赖管理页操作。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.ui.url", matches = ".+")
class PortalLifecycleViewTest {

    private static final String BASE = System.getProperty("dbdesk.ui.url").replaceAll("/+$", "");
    private static final Path PORTAL_FILE = Paths.get("target", "it-portals.json");
    private static final Path DASHBOARD_FILE = Paths.get("target", "it-dashboards.json");
    private static final Path CHART_FILE = Paths.get("target", "it-charts.json");
    private static final Path DATASET_FILE = Paths.get("target", "it-datasets.json");
    private static final String EXPIRED_TOKEN = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    private static final String REVOKED_TOKEN = "rrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrr";

    private Playwright playwright;
    private Browser browser;
    private Page page;
    private final CopyOnWriteArrayList<String> consoleErrors = new CopyOnWriteArrayList<>();

    @BeforeAll
    void seedStores() throws Exception {
        playwright = UiTestSupport.createPlaywright();
        browser = UiTestSupport.launchBrowser(playwright);

        Files.createDirectories(PORTAL_FILE.getParent());
        Files.writeString(DATASET_FILE, """
                {"version":1,"datasets":[{"id":"ds-lc","name":"生命周期数据集",
                "sql":"select '一月' as month, 10 as amount","jdbcUrl":"jdbc:postgresql://127.0.0.1:4409/m0",
                "updatedAt":"2026-10-23T00:00:00Z","params":[],
                "columns":[{"name":"month","type":"text","role":"dimension"},
                {"name":"amount","type":"number","role":"measure"}]}]}
                """, StandardCharsets.UTF_8);
        Files.writeString(CHART_FILE, """
                {"version":1,"charts":[{"id":"ch-lc","name":"生命周期图","datasetId":"ds-lc",
                "type":"bar","x":"month","y":["amount"],"aggregate":"none",
                "updatedAt":"2026-10-23T00:00:00Z"}]}
                """, StandardCharsets.UTF_8);
        Files.writeString(DASHBOARD_FILE, """
                {"version":1,"dashboards":[{"id":"dash-lc","name":"生命周期看板",
                "updatedAt":"2026-10-23T00:00:00Z","filters":[],
                "cards":[{"chartId":"ch-lc","x":0,"y":0,"w":8,"h":5}]}]}
                """, StandardCharsets.UTF_8);
        Files.writeString(PORTAL_FILE, """
                {"version":1,"portals":[
                  {"id":"p-expired","name":"过期门户","dashboardId":"dash-lc","token":"%s",
                   "autoRefreshSeconds":0,"updatedAt":"2026-10-23T00:00:00Z",
                   "expiresAt":"2020-01-01T00:00:00Z","revoked":false},
                  {"id":"p-revoked","name":"撤销门户","dashboardId":"dash-lc","token":"%s",
                   "autoRefreshSeconds":0,"updatedAt":"2026-10-23T00:00:00Z",
                   "expiresAt":null,"revoked":true}
                ]}
                """.formatted(EXPIRED_TOKEN, REVOKED_TOKEN), StandardCharsets.UTF_8);
    }

    @AfterAll
    void closeBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void newPage() {
        consoleErrors.clear();
        page = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1400, 900)).newPage();
        page.setDefaultTimeout(60_000);
        page.onConsoleMessage(message -> {
            if ("error".equalsIgnoreCase(message.type()) && !message.text().startsWith("Failed to load resource")) {
                consoleErrors.add(message.text());
            }
        });
    }

    private String openAndReadTitle(String token) {
        UiTestSupport.useChinese(page);
        page.navigate(BASE + "/portal/" + token);
        page.waitForFunction("() => { const el = document.querySelector('#portal-title');"
                        + " return !!el && el.textContent.length > 0; }",
                null, new Page.WaitForFunctionOptions().setTimeout(60_000));
        return page.locator("#portal-title").textContent();
    }

    @Test
    void longTermLinkStillWorksAndRevokedLinkFails() {
        String longTerm = openAndReadTitle(EXPIRED_TOKEN);
        int canvasesLongTerm = ((Number) page.evaluate(
                "() => document.querySelectorAll('#portal-canvas canvas').length")).intValue();

        String revoked = openAndReadTitle(REVOKED_TOKEN);
        int canvasesRevoked = ((Number) page.evaluate(
                "() => document.querySelectorAll('#portal-canvas canvas').length")).intValue();

        System.out.printf("[M6] longTerm='%s' (canvas=%d) | revoked='%s' (canvas=%d)%n",
                longTerm, canvasesLongTerm, revoked, canvasesRevoked);

        assertTrue(longTerm.contains("生命周期看板"), "历史过期时间不应再让链接失效，实际：" + longTerm);
        assertTrue(revoked.contains("撤销"), "撤销链接应明确提示撤销，实际：" + revoked);
        assertTrue(canvasesLongTerm > 0, "长期有效链接应正常渲染图表");
        assertTrue(canvasesRevoked == 0, "撤销后的链接不应渲染任何图表");
        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }
}
