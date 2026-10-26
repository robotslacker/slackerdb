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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL 控制台的新连接模型：先连接，再进入控制台；不再有 JDBC URL/历史连接下拉。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.ui.url", matches = ".+")
class SqlConsoleTest {

    private static final String BASE = System.getProperty("dbdesk.ui.url").replaceAll("/+$", "");
    private static final String JDBC_URL = System.getProperty("dbdesk.it.url", "jdbc:postgresql://127.0.0.1:4409/m0");

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
                .setViewportSize(1500, 950)).newPage();
        page.setDefaultTimeout(60_000);
        page.onConsoleMessage(message -> {
            if ("error".equalsIgnoreCase(message.type()) && !message.text().startsWith("Failed to load resource")) {
                consoleErrors.add(message.text());
            }
        });
    }

    @Test
    void runsSimpleQueryWithoutLegacyConnectionControls() {
        connect();
        page.navigate(BASE + "/sql");
        page.waitForSelector("#run-button");
        assertTrue(page.locator("#jdbc-url").count() == 0);
        assertTrue(page.locator("#connection-selector").count() == 0);
        assertTrue(page.locator("#manage-connections-button").count() == 0);

        page.evaluate("() => { document.querySelector('sql-editor').sql = 'select 42 as answer'; }");
        page.click("#run-button");
        page.waitForFunction("() => document.querySelector('#query-status').textContent.includes('1 条语句')");
        page.waitForFunction("() => document.querySelector('#result-grid').textContent.includes('42')");
        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }

    @Test
    void exportsCsvToChosenLocation() throws Exception {
        connect();
        page.navigate(BASE + "/sql");
        page.waitForSelector("#run-button");
        page.evaluate("() => { document.querySelector('sql-editor').sql = \"select 1 as a, 'x' as b\"; }");
        page.click("#run-button");
        page.waitForFunction("() => document.querySelector('#query-status').textContent.includes('1 条语句')");

        Path target = Files.createTempFile("dbdesk-ui-export-", ".csv");
        Files.deleteIfExists(target);
        page.click("#export-csv-button");
        page.waitForSelector("#save-path");
        page.fill("#save-path input", target.toString());
        page.click("#save-path-confirm");
        page.waitForFunction("() => document.querySelector('#export-status').textContent.includes('已导出')");
        String csv = Files.readString(target, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains("a") && csv.contains("b"), "CSV 内容不正确：" + csv);
    }

    @Test
    void ctrlEnterWithoutSelectionDoesNotExecute() {
        connect();
        page.navigate(BASE + "/sql");
        page.waitForSelector("#run-button");
        page.evaluate("""
                () => {
                  const ed = document.querySelector('sql-editor');
                  ed.sql = 'select 42 as answer';
                  ed.view.dispatch({ selection: { anchor: 0, head: 0 } });
                  ed.view.focus();
                }
                """);
        page.keyboard().press("Control+Enter");
        page.waitForFunction("() => document.querySelector('#query-status').textContent.includes('请先选中')");
        assertTrue(page.locator("#result-grid").textContent().isBlank(), "未选中时不应执行查询");
    }

    @Test
    void ctrlEnterRunsOnlySelectedStatement() {
        connect();
        page.navigate(BASE + "/sql");
        page.waitForSelector("#run-button");
        page.evaluate("""
                () => {
                  const ed = document.querySelector('sql-editor');
                  ed.sql = 'select 1 as a; select 2 as b;';
                  const view = ed.view;
                  view.dispatch({ selection: { anchor: 0, head: 14 } });
                  view.focus();
                }
                """);
        page.keyboard().press("Control+Enter");
        page.waitForFunction("() => document.querySelector('#query-status').textContent.includes('1 条语句')");
        String status = page.locator("#query-status").textContent();
        String grid = page.locator("#result-grid").textContent();
        assertTrue(status.contains("1 条语句"), "应只执行选中的一条语句，实际：" + status);
        assertTrue(grid.contains("1"), "结果应来自第一条语句");
    }

    @Test
    void metadataHidesSysauxAndShowsUserSchema() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(JDBC_URL, "", "");
             var statement = connection.createStatement()) {
            statement.execute("create schema if not exists dbdesk_meta_test");
            statement.execute("create table if not exists dbdesk_meta_test.ui_probe(id int)");
        }
        try {
            connect();
            page.navigate(BASE + "/sql");
            page.waitForSelector("#metadata-tree");
            page.waitForFunction("() => document.querySelector('#metadata-tree').textContent.includes('dbdesk_meta_test')");
            String metadata = page.locator("#metadata-tree").textContent();
            assertTrue(!metadata.contains("sysaux"), "元数据不应暴露 sysaux，实际：" + metadata);
            assertTrue(metadata.contains("ui_probe"), "元数据应能读取其他 schema，实际：" + metadata);
        } finally {
            try (var connection = java.sql.DriverManager.getConnection(JDBC_URL, "", "");
                 var statement = connection.createStatement()) {
                statement.execute("drop schema if exists dbdesk_meta_test cascade");
            } catch (Exception ignored) {
                // 测试清理失败不掩盖主断言
            }
        }
    }

    @Test
    void savesAndLoadsScriptInCurrentTab() throws Exception {
        connect();
        page.navigate(BASE + "/sql");
        page.waitForSelector("#run-button");
        page.evaluate("() => { document.querySelector('sql-editor').sql = 'select 99 as saved'; }");
        Path target = Files.createTempFile("dbdesk-ui-script-", ".sql");
        Files.deleteIfExists(target);
        page.click("#save-script");
        page.waitForSelector("#save-path");
        page.fill("#save-path input", target.toString());
        page.click("#save-path-confirm");
        page.waitForFunction("() => document.querySelector('#query-status').textContent.includes('已保存脚本')");
        String saved = Files.readString(target, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(saved.contains("select 99 as saved"), "保存的脚本内容不正确：" + saved);

        Path script = Files.createTempFile("dbdesk-ui-script-", ".sql");
        Files.writeString(script, "select 123 as loaded", java.nio.charset.StandardCharsets.UTF_8);
        page.locator("#load-script input[type=file]").setInputFiles(script);
        page.waitForFunction("() => document.querySelector('sql-editor').sql.includes('select 123 as loaded')");
        assertTrue(page.locator("#query-status").textContent().contains("已加载脚本"));
    }

    @Test
    void newTabAndDisconnectFlow() {
        connect();
        page.navigate(BASE + "/sql");
        page.waitForSelector("#new-tab-button");
        page.click("#new-tab-button");
        page.waitForSelector("#query-status-2");

        assertTrue(page.locator("#connection-menu-button").isVisible());
        page.click("#connection-disconnect");
        page.waitForFunction("() => location.pathname.endsWith('/main')");
        page.waitForSelector("#connection-status");
        assertTrue(page.locator("#connection-status").textContent().contains("未连接"));
        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }

    private void connect() {
        page.navigate(BASE + "/main");
        page.waitForSelector("#connect-server");
        page.click("#connect-server");
        page.waitForSelector("#connect-alias");
        Endpoint endpoint = endpoint();
        page.fill("#connect-alias input", "ui-sql-test");
        page.fill("#connect-host input", endpoint.host());
        page.fill("#connect-db-port input", String.valueOf(endpoint.databasePort()));
        page.fill("#connect-mgmt-port input", String.valueOf(endpoint.managementPort()));
        page.fill("#connect-database input", endpoint.database());
        page.click("#connect-submit");
        page.waitForFunction("() => document.querySelector('#connection-status')"
                + " && document.querySelector('#connection-status').textContent.includes('ui-sql-test')");
    }

    private static Endpoint endpoint() {
        String rest = JDBC_URL.substring("jdbc:postgresql://".length());
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
        String management = System.getProperty("dbdesk.management.url", "http://127.0.0.1:8409");
        int managementPort = Integer.parseInt(management.substring(management.lastIndexOf(':') + 1).replaceAll("/.*$", ""));
        return new Endpoint(host, port, managementPort, database);
    }

    private record Endpoint(String host, int databasePort, int managementPort, String database) {
    }
}
