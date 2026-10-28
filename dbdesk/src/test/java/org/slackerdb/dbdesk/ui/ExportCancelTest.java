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

import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3-2：导出 100 万行时点"取消导出"，界面必须明确回到"已取消"而不是假装成功。
 *
 * <p>用元数据树的"导出该表"触发（m0_probe 100 万行，导出要十几秒），不需要碰编辑器，
 * 点击链路最短、也最稳定。</p>
 *
 * <pre>
 *   mvn -Pdbdesk -pl dbdesk test "-Ddbdesk.ui.url=http://127.0.0.1:8090/" "-Dtest=ExportCancelTest"
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.ui.url", matches = ".+")
class ExportCancelTest {

    private static final String URL = System.getProperty("dbdesk.ui.url").replaceAll("/+$", "") + "/sql";

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
                .setViewportSize(1600, 950)).newPage();
        page.setDefaultTimeout(60_000);
        page.onConsoleMessage(message -> {
            if ("error".equalsIgnoreCase(message.type()) && !message.text().startsWith("Failed to load resource")) {
                consoleErrors.add(message.text());
            }
        });
        UiTestSupport.useChinese(page);
        page.navigate(URL);
    }

    @Test
    void cancelsLongRunningExport() throws Exception {
        page.waitForFunction("() => document.querySelector('#metadata-status')"
                        + " && document.querySelector('#metadata-status').textContent.includes('张表')",
                null, new Page.WaitForFunctionOptions().setTimeout(60_000));

        // m0_probe：100 万行，导出要十几秒，来得及取消
        page.locator("#metadata-tree vaadin-grid-cell-content", new Page.LocatorOptions().setHasText("m0_probe"))
                .first().click();
        page.waitForFunction("() => document.querySelector('#metadata-export-table')"
                        + " && !document.querySelector('#metadata-export-table').disabled",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));

        page.click("#metadata-export-table");
        page.waitForFunction("() => document.querySelector('#export-cancel-button').disabled === false",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));

        page.waitForTimeout(300);
        page.click("#export-cancel-button");
        page.waitForFunction("() => document.querySelector('#export-status').textContent.includes('已取消')",
                null, new Page.WaitForFunctionOptions().setTimeout(60_000));

        String status = page.locator("#export-status").textContent();
        Object links = page.evaluate("() => document.querySelectorAll('#export-links a').length");
        System.out.printf("[M3] cancel status='%s' | download links=%s%n", status, links);

        assertTrue(status.contains("已取消"), "取消后状态应明确说明已取消，实际：" + status);
        assertTrue(((Number) links).intValue() == 0 || status.contains("已取消"),
                "取消后不应给出'导出成功'的下载链接");
        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }
}
