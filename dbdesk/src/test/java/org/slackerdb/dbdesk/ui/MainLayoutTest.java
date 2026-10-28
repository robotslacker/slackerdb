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
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 应用壳：默认未连接、菜单全部禁用、Connect/断开流程。
 *
 * <pre>
 *   mvn -Pdbdesk -pl dbdesk test "-Ddbdesk.ui.url=http://127.0.0.1:8090/" "-Dtest=MainLayoutTest"
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.ui.url", matches = ".+")
class MainLayoutTest {

    private static final String BASE = System.getProperty("dbdesk.ui.url").replaceAll("/+$", "");
    private static final String JDBC_URL = System.getProperty("dbdesk.it.url", "jdbc:postgresql://127.0.0.1:4409/m0");
    private static final String MANAGEMENT_URL = System.getProperty("dbdesk.management.url", "http://127.0.0.1:8409");

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
        page.setDefaultTimeout(30_000);
        page.onConsoleMessage(message -> {
            if ("error".equalsIgnoreCase(message.type()) && !message.text().startsWith("Failed to load resource")) {
                consoleErrors.add(message.text());
            }
        });
        UiTestSupport.useChinese(page);
        page.navigate(BASE + "/main");
    }

    @Test
    void startsDisconnectedWithAllNavigationDisabled() {
        page.waitForSelector("#main-nav", new Page.WaitForSelectorOptions().setTimeout(30_000));
        page.waitForSelector("#connection-status", new Page.WaitForSelectorOptions().setTimeout(30_000));

        assertTrue(page.locator("#connection-status").textContent().contains("未连接"));
        assertTrue(page.locator("#connect-server").isVisible());
        assertTrue(page.locator("#main-nav").textContent().contains("SQL 控制台"));
        assertTrue(!page.locator("#main-nav").textContent().contains("连接配置"),
                "连接配置菜单应已移除");
        assertTrue(!page.locator("#main-nav").textContent().contains("门户访问日志"),
                "门户访问日志菜单应已移除");
        assertTrue(!page.locator("#main-nav").textContent().contains("定义存储"),
                "定义存储菜单应已移除");
        assertTrue(navDisabled("sql"), "未连接时 SQL 控制台应禁用");
        assertTrue(navDisabled("instances"), "未连接时实例页应禁用");
        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }

    @Test
    void languageSwitchChangesShellLabels() {
        page.waitForSelector("#main-nav");
        assertTrue(page.locator("#main-nav").textContent().contains("SQL 控制台"));
        page.click("#language-switch");
        page.waitForFunction("() => document.querySelector('#main-nav')"
                + " && document.querySelector('#main-nav').textContent.includes('SQL Console')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        assertTrue(page.locator("#language-switch").textContent().contains("中文"));
        page.click("#language-switch");
        page.waitForFunction("() => document.querySelector('#main-nav')"
                + " && document.querySelector('#main-nav').textContent.includes('SQL 控制台')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
    }

    @Test
    void connectButtonOpensDialog() {
        page.waitForSelector("#connect-server");
        page.click("#connect-server");
        page.waitForSelector("#connect-alias", new Page.WaitForSelectorOptions().setTimeout(30_000));
        assertTrue(page.locator("#connect-db-port").isVisible());
        assertTrue(page.locator("#connect-database").isVisible());
        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }

    @Test
    void connectThenDisconnectEnablesAndDisablesNavigation() {
        page.waitForSelector("#connect-server", new Page.WaitForSelectorOptions().setTimeout(30_000));
        page.click("#connect-server");
        page.waitForSelector("#connect-alias", new Page.WaitForSelectorOptions().setTimeout(30_000));

        Endpoint endpoint = endpoint();
        page.fill("#connect-alias input", "ui-connect-test");
        page.fill("#connect-host input", endpoint.host());
        page.fill("#connect-db-port input", String.valueOf(endpoint.databasePort()));
        page.fill("#connect-mgmt-port input", String.valueOf(endpoint.managementPort()));
        page.fill("#connect-database input", endpoint.database());
        page.click("#connect-submit");

        page.waitForFunction("() => document.querySelector('#connection-status')"
                        + " && document.querySelector('#connection-status').textContent.includes('ui-connect-test')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        assertTrue(!navDisabled("sql"), "连接后 SQL 控制台应启用");
        assertTrue(!navDisabled("instances"), "连接后实例页应启用");
        assertEquals("切换", page.locator("#connection-menu-button").textContent().trim());
        assertEquals("断开", page.locator("#connection-disconnect").textContent().trim());
        assertTrue(page.locator("#connection-status").textContent().contains("ui-connect-test"));
        assertTrue(!page.locator("#connection-menu-button").textContent().contains("ui-connect-test"),
                "别名只应出现在状态区，不应再出现在按钮上");

        page.click("#connection-disconnect");
        page.waitForFunction("() => document.querySelector('#connection-status')"
                        + " && document.querySelector('#connection-status').textContent.includes('未连接')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        assertTrue(navDisabled("sql"), "断开后 SQL 控制台应禁用");
        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }

    @Test
    void sqlOnlyWhenManagementPortIsBlank() {
        Endpoint endpoint = endpoint();
        openConnect();
        page.fill("#connect-alias input", "ui-sql-only");
        page.fill("#connect-host input", endpoint.host());
        page.fill("#connect-db-port input", String.valueOf(endpoint.databasePort()));
        page.fill("#connect-database input", endpoint.database());
        page.click("#connect-submit");

        page.waitForFunction("() => document.querySelector('#connection-status')"
                        + " && document.querySelector('#connection-status').textContent.includes('SQL only')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        assertTrue(!navDisabled("sql"), "SQL only 时 SQL 控制台应启用");
        assertTrue(navDisabled("instances"), "SQL only 时实例页应禁用");
    }

    @Test
    void failedManagementDoesNotWriteHistory() throws Exception {
        Endpoint endpoint = endpoint();
        String alias = "ui-fail-" + System.nanoTime();
        Path history = Path.of(System.getProperty("dbdesk.profiles.file", "target/ui-profiles.json"));
        String before = Files.exists(history) ? Files.readString(history) : "";

        openConnect();
        page.fill("#connect-alias input", alias);
        page.fill("#connect-host input", endpoint.host());
        page.fill("#connect-db-port input", String.valueOf(endpoint.databasePort()));
        page.fill("#connect-mgmt-port input", "1");
        page.fill("#connect-database input", endpoint.database());
        page.click("#connect-submit");

        page.waitForFunction("() => document.querySelector('#connect-status')"
                        + " && document.querySelector('#connect-status').textContent.includes('管理口不可达')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        assertTrue(page.locator("#connection-status").textContent().contains("未连接"));
        String after = Files.exists(history) ? Files.readString(history) : "";
        assertTrue(!after.contains(alias), "连接失败不应写入历史");
        assertTrue(before.length() == after.length() || !after.contains(alias));
    }

    private void openConnect() {
        page.waitForSelector("#connect-server", new Page.WaitForSelectorOptions().setTimeout(30_000));
        page.click("#connect-server");
        page.waitForSelector("#connect-alias", new Page.WaitForSelectorOptions().setTimeout(30_000));
    }
    private boolean navDisabled(String path) {
        Object value = page.locator("#main-nav vaadin-side-nav-item[path='" + path + "']")
                .evaluate("e => e.hasAttribute('disabled') || e.getAttribute('aria-disabled') === 'true'");
        return Boolean.TRUE.equals(value);
    }

    private static Endpoint endpoint() {
        String jdbc = JDBC_URL;
        String rest = jdbc.substring("jdbc:postgresql://".length());
        int query = rest.indexOf('?');
        if (query >= 0) {
            rest = rest.substring(0, query);
        }
        int slash = rest.indexOf('/');
        String hostPort = slash < 0 ? rest : rest.substring(0, slash);
        String database = slash < 0 ? "m0" : rest.substring(slash + 1);
        int colon = hostPort.lastIndexOf(':');
        String host = colon < 0 ? hostPort : hostPort.substring(0, colon);
        int port = colon < 0 ? 5432 : Integer.parseInt(hostPort.substring(colon + 1));

        String management = MANAGEMENT_URL;
        int managementPort;
        int lastColon = management.lastIndexOf(':');
        assertNotNull(management);
        managementPort = Integer.parseInt(management.substring(lastColon + 1).replaceAll("/.*$", ""));
        return new Endpoint(host, port, managementPort, database);
    }

    private record Endpoint(String host, int databasePort, int managementPort, String database) {
    }
}
