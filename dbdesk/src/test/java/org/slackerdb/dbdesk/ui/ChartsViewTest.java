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
 * M4-2：图表编辑器的浏览器验收 —— 选数据集 → 预览（真的渲染出 canvas）→ 保存 → 刷新仍在。
 *
 * <p>前置数据：测试直接把一个数据集写进控制台用的存储文件（{@code target/it-datasets.json}，
 * 与控制台启动参数 {@code -Ddbdesk.datasets.file} 对应），比"先用数据集页建一个"更快也更稳。</p>
 *
 * <pre>
 *   mvn -Pdbdesk -pl dbdesk test "-Ddbdesk.ui.url=http://127.0.0.1:8090/" "-Dtest=ChartsViewTest"
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.ui.url", matches = ".+")
class ChartsViewTest {

    private static final String BASE = System.getProperty("dbdesk.ui.url").replaceAll("/+$", "");
    private static final Path DATASET_FILE = Paths.get("target", "it-datasets.json");
    private static final String DATASET_NAME = "图表用数据集 " + UUID.randomUUID().toString().substring(0, 8);
    private static final String CHART_NAME = "UI 图表 " + System.currentTimeMillis();

    private Playwright playwright;
    private Browser browser;
    private Page page;
    private final CopyOnWriteArrayList<String> consoleErrors = new CopyOnWriteArrayList<>();

    @BeforeAll
    void seedDataset() throws Exception {
        playwright = UiTestSupport.createPlaywright();
        browser = UiTestSupport.launchBrowser(playwright);

        String json = """
                {"version":1,"datasets":[{"id":"ds-chart-test","name":"%s",
                "sql":"select '一月' as month, 10 as amount union all select '二月', 20",
                "jdbcUrl":"jdbc:postgresql://127.0.0.1:4409/m0","updatedAt":"2026-10-23T00:00:00Z",
                "params":[],"columns":[{"name":"month","type":"text","role":"dimension"},
                {"name":"amount","type":"number","role":"measure"}]}]}
                """.formatted(DATASET_NAME);
        Files.createDirectories(DATASET_FILE.getParent());
        Files.writeString(DATASET_FILE, json, StandardCharsets.UTF_8);
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
        page.navigate(BASE + "/charts");
    }

    @Test
    void createsPreviewAndSavesChart() throws Exception {
        page.waitForSelector("#chart-name input", new Page.WaitForSelectorOptions().setTimeout(60_000));

        page.fill("#chart-name input", CHART_NAME);
        page.click("#chart-dataset input");
        page.locator("vaadin-combo-box-item", new Page.LocatorOptions().setHasText(DATASET_NAME))
                .first().click();
        page.waitForTimeout(800);

        page.click("#chart-render");
        page.waitForFunction("() => document.querySelector('#chart-status').textContent.includes('预览成功')",
                null, new Page.WaitForFunctionOptions().setTimeout(60_000));

        int canvases = ((Number) page.evaluate("() => document.querySelectorAll('#chart-preview canvas').length")).intValue();
        String optionJson = (String) page.evaluate("() => document.querySelector('#chart-preview').optionJson");
        String status = page.locator("#chart-status").textContent();

        page.click("#chart-save");
        page.waitForFunction("() => document.querySelector('#chart-status').textContent.includes('已保存')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));

        page.reload();
        page.locator("#chart-list vaadin-grid-cell-content", new Page.LocatorOptions().setHasText(CHART_NAME))
                .first().waitFor(new Locator.WaitForOptions().setTimeout(30_000));

        Files.createDirectories(Paths.get("target"));
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(Paths.get("target", "m4-charts.png")).setFullPage(true));

        System.out.printf("[M4] preview status='%s' | canvas=%d | option has series=%s%n",
                status, canvases, optionJson.contains("series"));

        assertTrue(status.contains("预览成功"), "预览应成功，实际：" + status);
        assertTrue(canvases > 0, "ECharts 应真的画出 canvas，实际 canvas 数：" + canvases);
        assertTrue(optionJson.contains("\"series\""), "option 里应有 series：" + optionJson);

        // 收尾：删掉图表
        page.locator("#chart-list vaadin-grid-cell-content", new Page.LocatorOptions().setHasText(CHART_NAME))
                .first().click();
        page.waitForFunction("() => document.querySelector('#chart-status').textContent.includes('已载入')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        page.click("#chart-delete");
        page.waitForFunction("() => document.querySelector('#chart-status').textContent.includes('已删除')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));

        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }
}
