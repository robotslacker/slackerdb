package org.slackerdb.dbdesk.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * “实例与状态”页的插件 load/start/stop/unload 浏览器验收。
 *
 * <pre>
 *   mvn -Pdbdesk -pl dbdesk test \
 *     "-Ddbdesk.ui.url=http://127.0.0.1:8090/" \
 *     "-Ddbdesk.management.url=http://127.0.0.1:18409" \
 *     "-Ddbdesk.it.url=jdbc:postgresql://127.0.0.1:15432/mem" \
 *     "-Ddbdesk.plugin.jar=.../plugin-example/target/slackerdb-plugin-example-0.2.2.jar" \
 *     "-Dtest=PluginAdminViewTest"
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.ui.url", matches = ".+")
class PluginAdminViewTest {

    private static final String BASE = System.getProperty("dbdesk.ui.url").replaceAll("/+$", "");
    private static final String JDBC_URL = System.getProperty("dbdesk.it.url",
            "jdbc:postgresql://127.0.0.1:15432/mem");
    private static final String MANAGEMENT_URL = System.getProperty("dbdesk.management.url",
            "http://127.0.0.1:18409");
    private static final Path PLUGIN_JAR = Paths.get(System.getProperty("dbdesk.plugin.jar",
            "../plugin-example/target/slackerdb-plugin-example-0.2.2.jar"));

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
    void openPage() throws Exception {
        assertTrue(Files.isRegularFile(PLUGIN_JAR), "plugin jar not found: " + PLUGIN_JAR);
        unloadPluginQuietly();
        consoleErrors.clear();
        page = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1600, 1000)).newPage();
        page.setDefaultTimeout(30_000);
        page.onConsoleMessage(message -> {
            if ("error".equalsIgnoreCase(message.type())
                    && !message.text().startsWith("Failed to load resource")) {
                consoleErrors.add(message.text());
            }
        });
        UiTestSupport.useChinese(page);
        page.navigate(BASE + "/main");
        connect();
        page.navigate(BASE + "/instances");
        page.waitForSelector("#plugin-file", new Page.WaitForSelectorOptions().setTimeout(30_000));
    }

    @Test
    void loadStartStopAndUnloadPlugin() {
        page.fill("#plugin-file input", PLUGIN_JAR.toAbsolutePath().toString());
        page.click("#plugin-load");

        page.waitForFunction("() => document.querySelector('#plugin-status')"
                        + " && document.querySelector('#plugin-status').textContent.includes('已加载')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        page.waitForFunction("() => document.querySelector('#plugins-grid')"
                        + " && document.querySelector('#plugins-grid').textContent.includes('plugin-example')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        assertTrue(page.locator("#plugin-stop-plugin-example").isVisible(),
                "加载后应自动启动，并显示停止按钮");

        page.click("#plugin-stop-plugin-example");
        page.waitForFunction("() => document.querySelector('#plugin-status')"
                        + " && document.querySelector('#plugin-status').textContent.includes('已停止')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        page.waitForFunction("() => document.querySelector('#plugins-grid')"
                        + " && document.querySelector('#plugins-grid').textContent.includes('plugin-example')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));

        page.click("#plugin-start-plugin-example");
        page.waitForFunction("() => document.querySelector('#plugin-status')"
                        + " && document.querySelector('#plugin-status').textContent.includes('已启动')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));

        page.click("#plugin-unload-plugin-example");
        page.waitForSelector("vaadin-confirm-dialog",
                new Page.WaitForSelectorOptions().setState(WaitForSelectorState.ATTACHED)
                        .setTimeout(30_000));
        page.click("vaadin-confirm-dialog vaadin-button[slot='confirm-button']");
        page.waitForFunction("() => document.querySelector('#plugin-status')"
                        + " && document.querySelector('#plugin-status').textContent.includes('已卸载')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));

        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }

    private void connect() {
        page.waitForSelector("#connect-server", new Page.WaitForSelectorOptions().setTimeout(30_000));
        page.click("#connect-server");
        page.waitForSelector("#connect-alias", new Page.WaitForSelectorOptions().setTimeout(30_000));

        String rest = JDBC_URL.substring("jdbc:postgresql://".length());
        int slash = rest.indexOf('/');
        String hostPort = slash < 0 ? rest : rest.substring(0, slash);
        String database = slash < 0 ? "mem" : rest.substring(slash + 1);
        int query = database.indexOf('?');
        if (query >= 0) {
            database = database.substring(0, query);
        }
        int colon = hostPort.lastIndexOf(':');
        String host = colon < 0 ? hostPort : hostPort.substring(0, colon);
        int port = colon < 0 ? 5432 : Integer.parseInt(hostPort.substring(colon + 1));
        int managementPort = Integer.parseInt(MANAGEMENT_URL.substring(MANAGEMENT_URL.lastIndexOf(':') + 1)
                .replaceAll("/.*$", ""));

        page.fill("#connect-alias input", "ui-plugin-test");
        page.fill("#connect-host input", host);
        page.fill("#connect-db-port input", String.valueOf(port));
        page.fill("#connect-mgmt-port input", String.valueOf(managementPort));
        page.fill("#connect-database input", database);
        page.click("#connect-submit");
        page.waitForFunction("() => document.querySelector('#connection-status')"
                        + " && document.querySelector('#connection-status').textContent.includes('ui-plugin-test')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
    }

    private static void unloadPluginQuietly() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(MANAGEMENT_URL + "/plugin/unload"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"plugid\":\"plugin-example\"}"))
                .build();
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
