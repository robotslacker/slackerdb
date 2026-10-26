package org.slackerdb.dbdesk.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3-2：元数据树"导出该表"的浏览器验收 —— 不写 SQL 也能直接把整张表导出并下载。
 *
 * <pre>
 *   mvn -Pdbdesk -pl dbdesk test "-Ddbdesk.ui.url=http://127.0.0.1:8090/" "-Dtest=TableExportTest"
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.ui.url", matches = ".+")
class TableExportTest {

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
        page.navigate(URL);
    }

    @Test
    void exportsWholeTableFromMetadataTree() throws Exception {
        page.waitForFunction("() => document.querySelector('#metadata-status')"
                        + " && document.querySelector('#metadata-status').textContent.includes('张表')",
                null, new Page.WaitForFunctionOptions().setTimeout(60_000));

        // 选表（m0_big：1 行，导出快，断言稳定）
        page.locator("#metadata-tree vaadin-grid-cell-content", new Page.LocatorOptions().setHasText("m0_big"))
                .first().click();
        page.waitForFunction("() => document.querySelector('#metadata-export-table')"
                        + " && !document.querySelector('#metadata-export-table').disabled",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));

        page.click("#metadata-export-table");
        page.waitForFunction("() => document.querySelector('#export-status').textContent.startsWith('已导出')",
                null, new Page.WaitForFunctionOptions().setTimeout(60_000));

        String status = page.locator("#export-status").textContent();
        String linkText = page.locator("#export-links a").first().textContent();
        Download download = page.waitForDownload(() -> page.click("#export-links a"));
        Path saved = Paths.get(download.path().toString());
        long bytes = Files.size(saved);

        Files.createDirectories(Paths.get("target"));
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(Paths.get("target", "m3-table-export.png")).setFullPage(true));

        System.out.printf("[M3] table export status='%s' | link='%s' | downloaded=%d bytes%n",
                status, linkText, bytes);

        assertTrue(status.contains("已导出 1 行"), "应导出整张表（1 行），实际：" + status);
        assertTrue(linkText.startsWith("下载"), "应有下载链接，实际：" + linkText);
        assertTrue(bytes > 0, "下载的文件不应为空");
        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }
}
