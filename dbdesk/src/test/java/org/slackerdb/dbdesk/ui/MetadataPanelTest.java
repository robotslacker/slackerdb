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

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 元数据面板的浏览器验收：树里能看到表、选中后能看到列。
 *
 * <pre>
 *   mvn -Pdbdesk -pl dbdesk test "-Ddbdesk.ui.url=http://127.0.0.1:8090/" "-Dtest=MetadataPanelTest"
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.ui.url", matches = ".+")
class MetadataPanelTest {

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
    void showsTablesAndColumns() throws Exception {
        page.waitForFunction("() => document.querySelector('#metadata-status')"
                        + " && document.querySelector('#metadata-status').textContent.includes('张表')",
                null, new Page.WaitForFunctionOptions().setTimeout(60_000));
        String status = page.locator("#metadata-status").textContent();

        // 选表：树里点 m0_probe 那一行
        page.locator("#metadata-tree vaadin-grid-cell-content", new Page.LocatorOptions().setHasText("m0_probe"))
                .first().click();
        page.waitForFunction("() => document.querySelector('#metadata-columns')"
                        + " && document.querySelector('#metadata-columns').textContent.includes('BIGINT')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        String columnsText = page.locator("#metadata-columns").textContent();


        Files.createDirectories(Paths.get("target"));
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(Paths.get("target", "m1-metadata.png")).setFullPage(true));

        System.out.printf("[M1] metadata status='%s' | columns contain BIGINT=%s%n",
                status, columnsText.contains("BIGINT"));

        assertTrue(status.contains("张表"), "元数据状态应报告表数，实际：" + status);
        assertTrue(columnsText.contains("BIGINT"), "列面板应显示 id 的类型，实际：" + columnsText);

        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }
}
