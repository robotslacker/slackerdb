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
 * 控制台承接页的浏览器冒烟：状态、备份、日志、数据服务、MCP、AI Chat 都能渲染并
 * 从真实管理口拿到数据（而不是构造函数抛异常 / 页面空白）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.ui.url", matches = ".+")
class ConsoleFeatureSmokeTest {

    private static final String BASE = System.getProperty("dbdesk.ui.url").replaceAll("/+$", "");

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
    void newPage() {
        consoleErrors.clear();
        page = browser.newContext().newPage();
        page.setDefaultTimeout(30_000);
        page.onConsoleMessage(message -> {
            if ("error".equalsIgnoreCase(message.type()) && !message.text().startsWith("Failed to load resource")) {
                consoleErrors.add(message.text());
            }
        });
    }

    @Test
    void instancesBackupAndLogsRender() {
        connect();
        page.navigate(BASE + "/instances");
        page.waitForSelector("#session-grid");
        page.waitForSelector("#parameters-grid");
        assertTrue(!page.locator("#error-text").textContent().contains("失败"), "实例页不应报错");
    }

    @Test
    void dataServicesAndMcpRender() {
        connect();
        page.navigate(BASE + "/data-services");
        page.waitForSelector("#data-service-grid");
        assertTrue(!page.locator("#data-service-status").textContent().contains("失败"),
                "数据服务页不应报错：" + page.locator("#data-service-status").textContent());

        page.navigate(BASE + "/mcp");
        page.waitForSelector("#mcp-tools-grid");
        assertTrue(!page.locator("#mcp-tools-status").textContent().contains("失败"),
                "MCP 工具页不应报错：" + page.locator("#mcp-tools-status").textContent());

        page.navigate(BASE + "/backups");
        page.waitForSelector("#backup-grid");
        page.navigate(BASE + "/logs");
        page.waitForSelector("#log-content");
        page.waitForFunction("() => document.querySelector('#log-status')"
                + " && document.querySelector('#log-status').textContent.includes('已加载')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        String logText = page.locator("#log-content textarea").inputValue();
        assertTrue(!logText.isBlank(), "日志页不应显示空内容");
        System.out.println("[logs] " + logText.lines().findFirst().orElse(""));
        page.navigate(BASE + "/ai-chat");
        page.waitForSelector("#ai-chat-status");

        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }

    @Test
    void sqlConsoleHasNoLegacyConnectionControls() {
        connect();
        page.navigate(BASE + "/sql");
        page.waitForSelector("#run-button");
        assertTrue(page.locator("#jdbc-url").count() == 0, "SQL 控制台不应再显示 JDBC URL");
        assertTrue(page.locator("#connection-selector").count() == 0, "SQL 控制台不应再有历史连接下拉");
        assertTrue(page.locator("#manage-connections-button").count() == 0, "SQL 控制台不应再有管理连接按钮");
        assertTrue(page.locator("#metadata-panel").isVisible(), "SQL 控制台应使用当前连接加载元数据");
    }
    private void connect() {
        page.navigate(BASE + "/main");
        page.waitForSelector("#connect-server");
        page.click("#connect-server");
        page.waitForSelector("#connect-alias");
        page.fill("#connect-alias input", "ui-feature-test");
        page.fill("#connect-host input", "127.0.0.1");
        page.fill("#connect-db-port input", "4409");
        page.fill("#connect-mgmt-port input", "8409");
        page.fill("#connect-database input", "m0");
        page.click("#connect-submit");
        page.waitForFunction("() => document.querySelector('#connection-status')"
                        + " && document.querySelector('#connection-status').textContent.includes('ui-feature-test')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
    }
}
