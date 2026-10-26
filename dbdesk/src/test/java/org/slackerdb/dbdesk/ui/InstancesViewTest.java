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
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 实例面板的浏览器验收：能力/健康/会话三块都得由真实管理口驱动，而不是写死的假数据。
 *
 * <p>运行：</p>
 * <pre>
 *   mvn -Pdbdesk -pl dbdesk test "-Ddbdesk.ui.url=http://127.0.0.1:8090/" "-Dtest=InstancesViewTest"
 * </pre>
 *
 * <p>前置：dbserver 管理口开着（{@code --portX 8409}），并且页面里的地址指向它。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.ui.url", matches = ".+")
class InstancesViewTest {

    private static final String BASE = System.getProperty("dbdesk.ui.url").replaceAll("/+$", "");
    private static final String URL = BASE + "/m1-ops";
    /** 测试自己开一条 JDBC 连接，好让 /status 里必定有一条可识别的会话。 */
    private static final String JDBC_URL = System.getProperty("dbdesk.it.url", "jdbc:postgresql://127.0.0.1:4409/m0");
    private static final String MANAGEMENT_URL = System.getProperty("dbdesk.management.url", "http://127.0.0.1:8409");

    private Playwright playwright;
    private Browser browser;
    private Page page;
    private Connection heldConnection;
    private final CopyOnWriteArrayList<String> consoleErrors = new CopyOnWriteArrayList<>();

    @BeforeAll
    void launchBrowser() throws Exception {
        // 留着不关：它的 applicationName 会出现在会话面板里，作为"列表真的来自服务端"的证据
        heldConnection = DriverManager.getConnection(JDBC_URL + "?ApplicationName=dbdesk-ui-test");
        playwright = UiTestSupport.createPlaywright();
        browser = UiTestSupport.launchBrowser(playwright);
    }

    @AfterAll
    void closeBrowser() throws Exception {
        if (heldConnection != null) {
            heldConnection.close();
        }
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
                .setViewportSize(1400, 900)).newPage();
        page.setDefaultTimeout(30_000);
        page.onConsoleMessage(message -> {
            if ("error".equalsIgnoreCase(message.type()) && !message.text().startsWith("Failed to load resource")) {
                consoleErrors.add(message.text());
            }
        });
        page.navigate(BASE + "/main");
        connect();
        page.navigate(URL);
    }

    @Test
    void capabilitiesHealthAndSessionsComeFromTheServer() throws Exception {
        page.waitForSelector("#connect-button", new Page.WaitForSelectorOptions().setTimeout(30_000));
        page.waitForFunction("() => document.querySelector('#cap-summary').textContent.startsWith('apiVersion=1')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        // 会话必须真的来自 /status：测试自己开的那条连接（applicationName=dbdesk-ui-test）要出现在表里
        page.waitForFunction("() => document.querySelector('#session-grid')"
                        + " && document.querySelector('#session-grid').textContent.includes('dbdesk-ui-test')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        String capabilities = page.locator("#cap-summary").textContent();
        String health = page.locator("#health-status").textContent();
        String menu = page.locator("#menu-hint").textContent();
        String sessionText = page.locator("#session-grid").textContent();

        Files.createDirectories(Paths.get("target"));
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(Paths.get("target", "m1-instances.png")).setFullPage(true));

        System.out.printf("[M1] capabilities='%s' | health='%s' | menu='%s' | sessions含测试会话=%s | console errors=%d%n",
                capabilities, health, menu, sessionText.contains("dbdesk-ui-test"), consoleErrors.size());

        assertTrue(capabilities.startsWith("apiVersion=1"), "能力摘要应由 /capabilities 驱动，实际：" + capabilities);
        assertTrue(health.contains("UP"), "健康状态应来自 /health，实际：" + health);
        assertTrue(menu.contains("ETL："), "菜单提示应由 capabilities 驱动，实际：" + menu);
        assertTrue(sessionText.contains("dbdesk-ui-test"), "会话表应展示 /status 返回的真实会话");
        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }

    private void connect() {
        page.waitForSelector("#connect-server");
        page.click("#connect-server");
        page.waitForSelector("#connect-alias");
        String rest = JDBC_URL.substring("jdbc:postgresql://".length());
        int slash = rest.indexOf('/');
        String hostPort = slash < 0 ? rest : rest.substring(0, slash);
        String database = slash < 0 ? "m0" : rest.substring(slash + 1);
        int query = database.indexOf('?');
        if (query >= 0) {
            database = database.substring(0, query);
        }
        int colon = hostPort.lastIndexOf(':');
        String host = colon < 0 ? hostPort : hostPort.substring(0, colon);
        int port = colon < 0 ? 5432 : Integer.parseInt(hostPort.substring(colon + 1));
        page.fill("#connect-alias input", "ui-instances-test");
        page.fill("#connect-host input", host);
        page.fill("#connect-db-port input", String.valueOf(port));
        int managementPort = Integer.parseInt(MANAGEMENT_URL.substring(MANAGEMENT_URL.lastIndexOf(':') + 1)
                .replaceAll("/.*$", ""));
        page.fill("#connect-mgmt-port input", String.valueOf(managementPort));
        page.fill("#connect-database input", database);
        page.click("#connect-submit");
        page.waitForFunction("() => document.querySelector('#connection-status')"
                + " && document.querySelector('#connection-status').textContent.includes('ui-instances-test')");
    }
    @Test
    void killsSessionFromTheGrid() throws Exception {
        // 造一条"别人的"会话，然后在界面上把它杀掉
        try (Connection victim = DriverManager.getConnection(JDBC_URL + "?ApplicationName=dbdesk-kill-test")) {
            page.click("#connect-button");
            page.waitForFunction("() => document.querySelector('#session-grid')"
                            + " && document.querySelector('#session-grid').textContent.includes('dbdesk-kill-test')",
                    null, new Page.WaitForFunctionOptions().setTimeout(30_000));

            // 只点**目标那一行**的"终止"：按文本取第一个按钮会误杀同表里的其它会话
            //（之前误杀了 @BeforeAll 里那条 dbdesk-ui-test，导致整类用例互相踩）
            // Grid 的行在 shadow root 里、单元格内容通过 slot 投到 light DOM，
            // 所以从"包含目标文本的单元格"往后找同一行里的按钮。
            Object clicked = page.evaluate("""
                    () => {
                      const cells = Array.from(document.querySelectorAll('#session-grid vaadin-grid-cell-content'));
                      const index = cells.findIndex(c => c.textContent.includes('dbdesk-kill-test'));
                      if (index < 0) return 'no-cell';
                      for (let i = index; i < Math.min(index + 8, cells.length); i++) {
                        const button = cells[i].querySelector('vaadin-button');
                        if (button) { button.click(); return 'clicked'; }
                      }
                      return 'no-button';
                    }
                    """);
            assertTrue("clicked".equals(clicked), "应在目标行的单元格里点到终止按钮，实际：" + clicked);
            // 对话框宿主本身没有尺寸（内容在 overlay 里），只能等"attached"
            page.waitForSelector("vaadin-confirm-dialog",
                    new Page.WaitForSelectorOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED)
                            .setTimeout(30_000));
            page.click("vaadin-confirm-dialog vaadin-button[slot='confirm-button']");

            page.waitForFunction("() => document.querySelector('#error-text')"
                            + " && document.querySelector('#error-text').textContent.includes('已终止会话')",
                    null, new Page.WaitForFunctionOptions().setTimeout(30_000));

            // Vaadin Grid 会回收单元格：被删掉的行可能还留在 DOM 里，直接断言 textContent 会偶发失败。
            // 重载页面 + 重新刷新，再断言"服务端确实没了"。
            page.waitForTimeout(500);
            page.reload();
            page.click("#connect-button");
            page.waitForFunction("() => document.querySelector('#cap-summary')"
                            + " && document.querySelector('#cap-summary').textContent.includes('apiVersion')",
                    null, new Page.WaitForFunctionOptions().setTimeout(30_000));
            page.waitForFunction("() => !document.querySelector('#session-grid').textContent.includes('dbdesk-kill-test')",
                    null, new Page.WaitForFunctionOptions().setTimeout(30_000));

            System.out.printf("[M1] kill ok: %s%n", page.locator("#error-text").textContent());
        }
        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }
}
