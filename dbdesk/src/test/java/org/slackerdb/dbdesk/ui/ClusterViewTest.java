package org.slackerdb.dbdesk.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集群连接 + “实例管理”页的浏览器验收。
 *
 * <p>不依赖 dbserver：本测试自己起一个假的 dbproxy 管理口，只实现
 * {@code /capabilities} 与 {@code /admin/databases}；DbDesk 只需要管理端口即可进入集群模式。</p>
 *
 * <pre>
 *   java -Ddbdesk.profiles.file=... -jar dbdesk/target/slackerdb-dbdesk-*-standalone.jar --port 8090
 *   mvn -Pdbdesk -pl dbdesk test "-Ddbdesk.ui.url=http://127.0.0.1:8090/" "-Dtest=ClusterViewTest"
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.ui.url", matches = ".+")
class ClusterViewTest {

    private static final String BASE = System.getProperty("dbdesk.ui.url").replaceAll("/+$", "");

    private Playwright playwright;
    private Browser browser;
    private Page page;
    private HttpServer proxy;
    private int proxyPort;
    private final Map<String, String> states = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> consoleErrors = new CopyOnWriteArrayList<>();

    @BeforeAll
    void startProxyAndBrowser() throws Exception {
        states.put("demo", "RUNNING");
        states.put("stopped", "STOPPED");

        proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.start();
        proxyPort = proxy.getAddress().getPort();
        proxy.createContext("/capabilities", exchange -> respond(exchange, 200, """
                {"apiVersion":1,"role":"proxy",
                 "ports":{"pg":4409,"management":%d,"managementEnabled":true},
                 "provisioning":{"enabled":true,"mode":"process","adminBase":"/admin",
                   "features":["list","create","start","shutdown","destroy"],
                   "backendSources":["management","external"]},
                 "managementForwarding":{"enabled":true,"prefixMode":"alias"}}
                """.formatted(proxyPort)));
        proxy.createContext("/admin/databases", this::handleAdmin);

        playwright = UiTestSupport.createPlaywright();
        browser = UiTestSupport.launchBrowser(playwright);
    }

    @AfterAll
    void close() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        if (proxy != null) {
            proxy.stop(0);
        }
    }

    @BeforeEach
    void openPage() {
        consoleErrors.clear();
        page = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1500, 950)).newPage();
        page.setDefaultTimeout(30_000);
        page.onConsoleMessage(message -> {
            if ("error".equalsIgnoreCase(message.type())
                    && !message.text().startsWith("Failed to load resource")) {
                consoleErrors.add(message.text());
            }
        });
        UiTestSupport.useChinese(page);
        page.navigate(BASE + "/main");
    }

    @Test
    void clusterConnectionOnlyEnablesInstanceManagement() {
        String alias = "ui-cluster-" + System.nanoTime();
        connect(alias);

        page.waitForSelector("#cluster-grid", new Page.WaitForSelectorOptions().setTimeout(30_000));
        page.waitForFunction("() => document.querySelector('#cluster-grid')"
                        + " && document.querySelector('#cluster-grid').textContent.includes('demo')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));

        assertTrue(page.locator("#connection-status").textContent().contains(alias));
        assertTrue(page.locator("#main-nav").textContent().contains("实例管理"));
        assertTrue(navDisabled("sql"), "集群连接下 SQL 控制台应禁用");
        assertTrue(navDisabled("instances"), "集群连接下实例页应禁用");
        assertTrue(page.locator("#cluster-view-demo").isVisible());
        assertTrue(page.locator("#cluster-start-stopped").isVisible(),
                "停止的内部实例应可启动");
        assertTrue(!page.locator("#cluster-start-ext").isVisible(),
                "外部实例只能查看，不应显示启动按钮");
        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }

    @Test
    void canAddInstance() {
        String alias = "ui-cluster-" + System.nanoTime();
        connect(alias);

        page.waitForSelector("#cluster-grid", new Page.WaitForSelectorOptions().setTimeout(30_000));
        page.click("#cluster-add");
        page.waitForSelector("#cluster-create-alias", new Page.WaitForSelectorOptions().setTimeout(30_000));
        page.fill("#cluster-create-alias input", "created" + alias.substring(alias.length() - 6));
        page.click("#cluster-create-submit");
        page.waitForFunction("() => document.querySelector('#cluster-grid')"
                        + " && document.querySelector('#cluster-grid').textContent.includes('created')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }

    private void connect(String alias) {
        page.waitForSelector("#connect-server", new Page.WaitForSelectorOptions().setTimeout(30_000));
        page.click("#connect-server");
        page.waitForSelector("#connect-alias", new Page.WaitForSelectorOptions().setTimeout(30_000));
        page.fill("#connect-alias input", alias);
        page.fill("#connect-host input", "127.0.0.1");
        page.fill("#connect-mgmt-port input", String.valueOf(proxyPort));
        page.click("#connect-submit");
        page.waitForFunction("() => document.querySelector('#connection-status')"
                        + " && document.querySelector('#connection-status').textContent.includes('" + alias + "')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
    }

    private boolean navDisabled(String path) {
        Object value = page.locator("#main-nav vaadin-side-nav-item[path='" + path + "']")
                .evaluate("e => e.hasAttribute('disabled') || e.getAttribute('aria-disabled') === 'true'");
        return Boolean.TRUE.equals(value);
    }

    private void handleAdmin(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if ("GET".equals(method) && "/admin/databases".equals(path)) {
            respond(exchange, 200, listJson());
            return;
        }
        if ("POST".equals(method) && "/admin/databases".equals(path)) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String alias = between(body, "\"alias\":\"", "\"");
            if (alias == null) {
                respond(exchange, 400, "{\"retCode\":400,\"retMsg\":\"alias is required\"}");
                return;
            }
            states.put(alias, "RUNNING");
            respond(exchange, 201, instanceJson(alias, "management", true, "RUNNING"));
            return;
        }
        String alias = path.substring("/admin/databases/".length()).split("/")[0];
        if ("POST".equals(method) && path.endsWith("/start")) {
            states.put(alias, "RUNNING");
            respond(exchange, 200, instanceJson(alias, "management", true, "RUNNING"));
            return;
        }
        if ("POST".equals(method) && path.endsWith("/shutdown")) {
            states.put(alias, "STOPPED");
            respond(exchange, 200, instanceJson(alias, "management", true, "STOPPED"));
            return;
        }
        if ("DELETE".equals(method)) {
            states.remove(alias);
            respond(exchange, 200, "{\"alias\":\"" + alias + "\",\"destroyed\":true}");
            return;
        }
        if ("GET".equals(method)) {
            respond(exchange, 200, instanceJson(alias, "management", true,
                    states.getOrDefault(alias, "STOPPED")));
            return;
        }
        respond(exchange, 404, "{\"retCode\":404,\"retMsg\":\"not found\"}");
    }

    private String listJson() {
        StringBuilder databases = new StringBuilder();
        states.forEach((alias, state) -> {
            if (!databases.isEmpty()) {
                databases.append(',');
            }
            databases.append(instanceJson(alias, "management", true, state));
        });
        if (!databases.isEmpty()) {
            databases.append(',');
        }
        databases.append(instanceJson("ext", "external", false, "RUNNING"));
        return "{\"instanceManagementEnabled\":true,\"storage\":{\"dataDir\":\"/tmp/instances\"},"
                + "\"databases\":[" + databases + "]}";
    }

    private static String instanceJson(String alias, String source, boolean manageable, String state) {
        return """
                {"alias":"%s","source":"%s","manageable":%s,"name":"%s","state":"%s",
                 "pid":100,"pgPort":4409,"mgmtPort":8409,"health":"UP","uptimeSeconds":10,
                 "error":null,"dir":"/tmp/instances/%s","target":%s}
                """.formatted(alias, source, manageable, alias, state, alias,
                "external".equals(source) ? "\"127.0.0.1:5500\"" : "null");
    }

    private static String between(String text, String prefix, String suffix) {
        int start = text.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        start += prefix.length();
        int end = text.indexOf(suffix, start);
        return end < 0 ? null : text.substring(start, end);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }
}
