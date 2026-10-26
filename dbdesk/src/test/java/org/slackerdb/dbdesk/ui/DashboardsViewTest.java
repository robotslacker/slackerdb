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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4-3：仪表板页的浏览器验收 —— 选图表加卡片 → 刷新出图 → 保存 → 刷新页面仍在。
 *
 * <p>前置数据直接写进控制台用的存储文件（target/it-datasets.json / it-charts.json）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.ui.url", matches = ".+")
class DashboardsViewTest {

    private static final String BASE = System.getProperty("dbdesk.ui.url").replaceAll("/+$", "");
    private static final Path DATASET_FILE = Paths.get("target", "it-datasets.json");
    private static final Path CHART_FILE = Paths.get("target", "it-charts.json");
    private static final String CHART_NAME = "看板卡片图 " + UUID.randomUUID().toString().substring(0, 6);
    private static final String DASHBOARD_NAME = "UI 看板 " + System.currentTimeMillis();

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
                {"version":1,"datasets":[{"id":"ds-dash","name":"看板数据集",
                "sql":"select '一月' as month, 10 as amount union all select '二月', 20",
                "jdbcUrl":"jdbc:postgresql://127.0.0.1:4409/m0","updatedAt":"2026-10-23T00:00:00Z",
                "params":[],"columns":[{"name":"month","type":"text","role":"dimension"},
                {"name":"amount","type":"number","role":"measure"}]}]}
                """, StandardCharsets.UTF_8);
        Files.writeString(CHART_FILE, """
                {"version":1,"charts":[{"id":"ch-dash","name":"%s","datasetId":"ds-dash",
                "type":"bar","x":"month","y":["amount"],"aggregate":"none",
                "updatedAt":"2026-10-23T00:00:00Z"}]}
                """.formatted(CHART_NAME), StandardCharsets.UTF_8);
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
        page.navigate(BASE + "/dashboards");
    }

    @Test
    void buildsRendersAndSavesDashboard() throws Exception {
        page.waitForSelector("#dashboard-name input", new Page.WaitForSelectorOptions().setTimeout(60_000));

        page.fill("#dashboard-name input", DASHBOARD_NAME);
        page.click("#dashboard-chart-to-add input");
        page.locator("vaadin-combo-box-item", new Page.LocatorOptions().setHasText(CHART_NAME)).first().click();
        page.click("#dashboard-add-card");
        page.waitForTimeout(500);
        page.click("#dashboard-refresh");
        page.waitForFunction("() => document.querySelector('#dashboard-status').textContent.includes('刷新成功')",
                null, new Page.WaitForFunctionOptions().setTimeout(60_000));

        int canvases = ((Number) page.evaluate("() => document.querySelectorAll('#dashboard-canvas canvas').length"))
                .intValue();
        String status = page.locator("#dashboard-status").textContent();

        page.click("#dashboard-save");
        page.waitForFunction("() => document.querySelector('#dashboard-status').textContent.includes('已保存')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));

        page.reload();
        page.locator("#dashboard-list vaadin-grid-cell-content", new Page.LocatorOptions().setHasText(DASHBOARD_NAME))
                .first().waitFor(new Locator.WaitForOptions().setTimeout(30_000));

        Files.createDirectories(Paths.get("target"));
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(Paths.get("target", "m4-dashboards.png")).setFullPage(true));

        System.out.printf("[M4] dashboard status='%s' | canvas=%d%n", status, canvases);

        assertTrue(status.contains("刷新成功"), "刷新应成功，实际：" + status);
        assertTrue(canvases > 0, "看板应渲染出 canvas，实际：" + canvases);

        // 收尾：删除看板
        page.locator("#dashboard-list vaadin-grid-cell-content", new Page.LocatorOptions().setHasText(DASHBOARD_NAME))
                .first().click();
        page.waitForFunction("() => document.querySelector('#dashboard-status').textContent.includes('已载入')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        page.click("#dashboard-delete");
        page.waitForFunction("() => document.querySelector('#dashboard-status').textContent.includes('已删除')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));

        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }
}
