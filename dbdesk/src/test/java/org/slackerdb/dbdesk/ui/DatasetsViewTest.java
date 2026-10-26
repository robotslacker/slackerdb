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

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4-1：数据集管理页的浏览器验收 —— 新建 → 试跑（出预览）→ 保存 → 刷新后仍在 → 删除。
 *
 * <pre>
 *   mvn -Pdbdesk -pl dbdesk test "-Ddbdesk.ui.url=http://127.0.0.1:8090/" "-Dtest=DatasetsViewTest"
 * </pre>
 *
 * <p>测试用的存储文件由启动参数指定（{@code -Ddbdesk.datasets.file=…}），避免污染真实用户目录。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.ui.url", matches = ".+")
class DatasetsViewTest {

    private static final String BASE = System.getProperty("dbdesk.ui.url").replaceAll("/+$", "");
    /** 每次跑用唯一名字：存储是真实文件，重名会被"同名保护"拒绝（这正是要验的行为之一）。 */
    private static final String DATASET_NAME = "UI 数据集 " + System.currentTimeMillis();

    private Playwright playwright;
    private Browser browser;
    private Page page;
    private final CopyOnWriteArrayList<String> consoleErrors = new CopyOnWriteArrayList<>();

    @BeforeAll
    void launchBrowser() {
        playwright = UiTestSupport.createPlaywright();
        browser = UiTestSupport.launchBrowser(playwright);
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
        page.navigate(BASE + "/datasets");
    }

    @Test
    void createsTestsAndSavesDataset() throws Exception {
        page.waitForSelector("#dataset-name input", new Page.WaitForSelectorOptions().setTimeout(60_000));

        page.fill("#dataset-name input", DATASET_NAME);
        page.evaluate("() => { document.querySelector('#dataset-sql').sql = "
                + "'select 1 as id, cast(\\'abc\\' as varchar) as name, cast(2.5 as double) as amount'; }");

        page.click("#dataset-test");
        page.waitForFunction("() => document.querySelector('#dataset-status').textContent.includes('试跑成功')",
                null, new Page.WaitForFunctionOptions().setTimeout(60_000));
        String runStatus = page.locator("#dataset-status").textContent();
        String previewText = page.locator("#dataset-preview").textContent();
        String columnsText = page.locator("#dataset-columns").textContent();

        page.click("#dataset-save");
        page.waitForFunction("() => document.querySelector('#dataset-status').textContent.includes('已保存')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));

        // 刷新页面：列表里应当能看到（说明真的落盘了）
        page.reload();
        // Grid 单元格在 shadow DOM 里：`document.querySelectorAll`（evaluate）不穿透，
        // 必须用 Playwright 的 locator（它会穿透 shadow root）。
        page.locator("#dataset-list vaadin-grid-cell-content",
                new Page.LocatorOptions().setHasText(DATASET_NAME)).first()
                .waitFor(new Locator.WaitForOptions().setTimeout(30_000));

        Files.createDirectories(Paths.get("target"));
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(Paths.get("target", "m4-datasets.png")).setFullPage(true));

        System.out.printf("[M4] run='%s' | columns='%s' | preview='%s'%n",
                runStatus, columnsText.replace("\n", " "), previewText.replace("\n", " "));

        assertTrue(runStatus.contains("试跑成功"), "试跑应成功，实际：" + runStatus);
        assertTrue(columnsText.contains("amount"), "列应被推断出来，实际：" + columnsText);
        assertTrue(previewText.contains("abc"), "预览应显示查询结果，实际：" + previewText);

        // 收尾：删掉测试数据集（避免测试数据堆积）
        page.locator("#dataset-list vaadin-grid-cell-content", new Page.LocatorOptions().setHasText(DATASET_NAME))
                .first().click();
        page.waitForFunction("() => document.querySelector('#dataset-status').textContent.includes('已载入')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        page.click("#dataset-delete");
        page.waitForFunction("() => document.querySelector('#dataset-status').textContent.includes('已删除')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));

        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }
}
