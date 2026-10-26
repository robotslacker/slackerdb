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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据导入页的浏览器验收：上传 CSV → 选表 → 导入 → 结果与库里的行数对得上。
 *
 * <pre>
 *   mvn -Pdbdesk -pl dbdesk test "-Ddbdesk.ui.url=http://127.0.0.1:8090/" "-Ddbdesk.it.url=jdbc:postgresql://127.0.0.1:4409/m0" "-Dtest=ImportViewTest"
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.ui.url", matches = ".+")
@EnabledIfSystemProperty(named = "dbdesk.it.url", matches = ".+")
class ImportViewTest {

    private static final String BASE = System.getProperty("dbdesk.ui.url").replaceAll("/+$", "");
    private static final String JDBC = System.getProperty("dbdesk.it.url");
    private static final String MANAGEMENT_URL = System.getProperty("dbdesk.management.url", "http://127.0.0.1:8409");
    private static final String TABLE = "dbdesk_import_ui";

    private Playwright playwright;
    private Browser browser;
    private Page page;
    private Path csv;
    private final CopyOnWriteArrayList<String> consoleErrors = new CopyOnWriteArrayList<>();

    @BeforeAll
    void setUp() throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC, "", "");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE);
            statement.execute("CREATE TABLE " + TABLE + " (id BIGINT, name VARCHAR, amount DECIMAL(10,2))");
        }
        csv = Files.createTempFile("import-ui-", ".csv");
        Files.writeString(csv, "id,name,amount\n1,Alice,1.50\n2,张三,2.50\n3,Bob,3.50\n", StandardCharsets.UTF_8);

        playwright = UiTestSupport.createPlaywright();
        browser = UiTestSupport.launchBrowser(playwright);
    }

    @AfterAll
    void tearDown() throws Exception {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        try (Connection connection = DriverManager.getConnection(JDBC, "", "");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE);
        }
    }

    @BeforeEach
    void openPage() {
        consoleErrors.clear();
        page = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1500, 1000)).newPage();
        page.setDefaultTimeout(30_000);
        page.onConsoleMessage(message -> {
            if ("error".equalsIgnoreCase(message.type()) && !message.text().startsWith("Failed to load resource")) {
                consoleErrors.add(message.text());
            }
        });
        connect();
        page.navigate(BASE + "/import");
    }

    private void connect() {
        page.navigate(BASE + "/main");
        page.waitForSelector("#connect-server");
        page.click("#connect-server");
        page.waitForSelector("#connect-alias");
        String rest = JDBC.substring("jdbc:postgresql://".length());
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
        int managementPort = Integer.parseInt(MANAGEMENT_URL.substring(MANAGEMENT_URL.lastIndexOf(':') + 1)
                .replaceAll("/.*$", ""));
        page.fill("#connect-alias input", "ui-import-test");
        page.fill("#connect-host input", host);
        page.fill("#connect-db-port input", String.valueOf(port));
        page.fill("#connect-mgmt-port input", String.valueOf(managementPort));
        page.fill("#connect-database input", database);
        page.click("#connect-submit");
        page.waitForFunction("() => document.querySelector('#connection-status')"
                + " && document.querySelector('#connection-status').textContent.includes('ui-import-test')");
    }

    @Test
    void importsCsvIntoTable() throws Exception {
        page.waitForSelector("#import-upload", new Page.WaitForSelectorOptions().setTimeout(30_000));

        page.locator("#import-upload input[type=file]").setInputFiles(csv);
        page.waitForFunction("() => document.querySelector('#import-status')"
                + " && document.querySelector('#import-status').textContent.includes('已上传到暂存目录')",
                null, new Page.WaitForFunctionOptions().setTimeout(60_000));

        // 必须从下拉里**选中**：只填文本不会提交值，会落到默认的第一张表上（实测踩到）
        page.click("#import-table input");
        page.locator("vaadin-combo-box-item", new Page.LocatorOptions().setHasText(TABLE))
                .first().click();
        page.waitForTimeout(500);
        page.click("#import-run");

        page.waitForFunction("() => document.querySelector('#import-status').textContent.includes('成功')",
                null, new Page.WaitForFunctionOptions().setTimeout(60_000));
        String status = page.locator("#import-status").textContent();

        Files.createDirectories(Paths.get("target"));
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(Paths.get("target", "m3-import.png")).setFullPage(true));

        long rows;
        try (Connection connection = DriverManager.getConnection(JDBC, "", "");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*), MAX(name) FROM " + TABLE)) {
            rs.next();
            rows = rs.getLong(1);
        }

        System.out.printf("[M3] import status='%s' | rows=%d | console errors=%d%n",
                status.replace("\n", " | "), rows, consoleErrors.size());

        assertTrue(status.contains("成功 3 行"), "应报告成功 3 行，实际：" + status);
        assertEquals(3, rows, "库里应有 3 行");
        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }
}
