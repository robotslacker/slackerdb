package org.slackerdb.dbdesk.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4-4：数据门户的浏览器验收 —— 发布 → 只读页出图 → 无编辑入口 → 删除后链接失效。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.ui.url", matches = ".+")
class PortalViewTest {

    private static final String BASE = System.getProperty("dbdesk.ui.url").replaceAll("/+$", "");
    private static final Path DATASET_FILE = Paths.get("target", "it-datasets.json");
    private static final Path CHART_FILE = Paths.get("target", "it-charts.json");
    private static final Path DASHBOARD_FILE = Paths.get("target", "it-dashboards.json");
    private static final String DASHBOARD_NAME = "门户看板 " + UUID.randomUUID().toString().substring(0, 6);
    private static final String PORTAL_NAME = "UI 门户 " + System.currentTimeMillis();

    private Playwright playwright;
    private Browser browser;
    private Page page;
    private final CopyOnWriteArrayList<String> consoleErrors = new CopyOnWriteArrayList<>();

    @BeforeAll
    void seedStores() throws Exception {
        playwright = UiTestSupport.createPlaywright();
        browser = UiTestSupport.launchBrowser(playwright);

        Files.createDirectories(DATASET_FILE.getParent());
        Files.writeString(DATASET_FILE, """
                {"version":1,"datasets":[{"id":"ds-portal","name":"门户数据集",
                "sql":"select '一月' as month, 10 as amount union all select '二月', 20",
                "jdbcUrl":"jdbc:postgresql://127.0.0.1:4409/m0","updatedAt":"2026-10-23T00:00:00Z",
                "params":[],"columns":[{"name":"month","type":"text","role":"dimension"},
                {"name":"amount","type":"number","role":"measure"}]}]}
                """, StandardCharsets.UTF_8);
        Files.writeString(CHART_FILE, """
                {"version":1,"charts":[{"id":"ch-portal","name":"门户图","datasetId":"ds-portal",
                "type":"line","x":"month","y":["amount"],"aggregate":"none",
                "updatedAt":"2026-10-23T00:00:00Z"}]}
                """, StandardCharsets.UTF_8);
        Files.writeString(DASHBOARD_FILE, """
                {"version":1,"dashboards":[{"id":"dash-portal","name":"%s","updatedAt":"2026-10-23T00:00:00Z",
                "filters":[],"cards":[{"chartId":"ch-portal","x":0,"y":0,"w":8,"h":5}]}]}
                """.formatted(DASHBOARD_NAME), StandardCharsets.UTF_8);
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
    void openPage() {
        consoleErrors.clear();
        page = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1600, 1000)).newPage();
        page.setDefaultTimeout(60_000);
        page.onConsoleMessage(message -> {
            if ("error".equalsIgnoreCase(message.type()) && !message.text().startsWith("Failed to load resource")) {
                consoleErrors.add(message.text());
            }
        });
        UiTestSupport.useChinese(page);
        page.navigate(BASE + "/portals");
    }

    @Test
    void publishesPortalAndInvalidatesLinkAfterDelete() throws Exception {
        page.waitForSelector("#portal-name input", new Page.WaitForSelectorOptions().setTimeout(60_000));

        page.fill("#portal-name input", PORTAL_NAME);
        page.click("#portal-dashboard input");
        page.locator("vaadin-combo-box-item", new Page.LocatorOptions().setHasText(DASHBOARD_NAME)).first().click();
        page.click("#portal-save");
        page.waitForFunction("() => document.querySelector('#portal-status').textContent.includes('已保存')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));

        String link = page.locator("#portal-link").textContent();
        assertTrue(link.startsWith("http://") && link.contains("/portal/"),
                "分享链接应包含主机、端口和 /portal/，实际：" + link);

        // 打开分享链接：只读页应当渲染出看板，且没有任何编辑入口
        page.navigate(link.startsWith("http") ? link : BASE + link);
        // 判空再取 textContent：元素还没渲染出来时，谓词里直接 .textContent 会抛 TypeError，
        // Playwright 会立刻失败而不是重试（这是踩过的坑）
        page.waitForFunction("() => { const el = document.querySelector('#portal-title');"
                        + " return !!el && el.textContent.length > 0; }",
                null, new Page.WaitForFunctionOptions().setTimeout(60_000));
        String title = page.locator("#portal-title").textContent();
        int canvases = ((Number) page.evaluate("() => document.querySelectorAll('#portal-canvas canvas').length"))
                .intValue();
        long editors = ((Number) page.evaluate("() => document.querySelectorAll('sql-editor').length")).longValue();
        long navItems = ((Number) page.evaluate("() => document.querySelectorAll('vaadin-side-nav-item').length"))
                .longValue();

        Files.createDirectories(Paths.get("target"));
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(Paths.get("target", "m4-portal.png")).setFullPage(true));

        System.out.printf("[M4] portal title='%s' | canvas=%d | editors=%d | navItems=%d%n",
                title, canvases, editors, navItems);

        assertEquals(DASHBOARD_NAME, title, "门户标题应是仪表板名");
        assertTrue(canvases > 0, "门户应渲染出图表，实际：" + canvases);
        assertEquals(0L, editors, "门户是只读页，不应有 SQL 编辑器");
        assertEquals(0L, navItems, "门户不应有侧边导航（否则能点进编辑器）");

        // 删除门户 → 同一个链接必须失效
        page.navigate(BASE + "/portals");
        page.locator("#portal-list vaadin-grid-cell-content", new Page.LocatorOptions().setHasText(PORTAL_NAME))
                .first().click();
        page.waitForFunction("() => document.querySelector('#portal-status').textContent.includes('已载入')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        page.click("#portal-delete");
        page.waitForFunction("() => document.querySelector('#portal-status').textContent.includes('已删除')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));

        page.navigate(link.startsWith("http") ? link : BASE + link);
        page.waitForFunction("() => { const el = document.querySelector('#portal-title');"
                        + " return !!el && el.textContent.includes('链接无效'); }",
                null, new Page.WaitForFunctionOptions().setTimeout(60_000));

        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }
}
