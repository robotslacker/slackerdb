package org.slackerdb.dbdesk.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * M0-V1 / M0-V4 的浏览器冒烟：证明"Vaadin 25 + Spring Boot 4 生产包"**在真实浏览器里**能渲染、能交互。
 *
 * <p>为什么需要它：{@code GET /} 返回 200 只证明 Tomcat 起来了，页面内容是 Flow 客户端渲染的，
 * 只有真浏览器能断言 "DbDesk" 出现了 —— 这也是 M0 第 1 项最后一条未完成的验收。</p>
 *
 * <p><b>运行方式</b>（先起应用，再指过去；默认不跑，对称 dbcli 的 {@code -Ddbcli.it.url}）：</p>
 * <pre>
 *   java -jar dbdesk/target/slackerdb-dbdesk-0.2.2-standalone.jar --port 8090
 *   mvn -pl dbdesk -am test -Ddbdesk.ui.url=http://127.0.0.1:8090/
 * </pre>
 *
 * <p><b>不下载浏览器</b>：Playwright Java 默认会在首次 {@code create()} 时去 cdn.playwright.dev
 * 拉 ~180 MB 的 Chromium，内网/国内网络经常卡住。这里做两件事绕开它：</p>
 * <ol>
 *   <li>{@code PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1} —— 禁止自动下载（实测不加这条，
 *       {@code Playwright.create()} 就开始下载，哪怕已指定 executablePath）；</li>
 *   <li>直接用系统已装的 Chrome/Chromium 可执行文件（{@code -Ddbdesk.ui.browser=...}
 *       可覆盖路径），找不到时回退到 {@code channel=chrome}。</li>
 * </ol>
 *
 * <p>截图落在 {@code dbdesk/target/ui-smoke.png}，作为"确实渲染过"的证据。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.ui.url", matches = ".+")
class MainViewSmokeTest {

    private static final String URL = System.getProperty("dbdesk.ui.url");
    private static final int VIEWPORT_WIDTH = 1280;
    private static final int VIEWPORT_HEIGHT = 800;

    private Playwright playwright;
    private Browser browser;
    private Page page;

    /** 浏览器控制台里的 error 级消息与未捕获异常：Vaadin 客户端炸了会最先出现在这里。 */
    private final List<String> consoleErrors = new CopyOnWriteArrayList<>();
    /** 资源 404（典型是 favicon）：只是噪音，记下来但不判失败。 */
    private final List<String> resourceErrors = new CopyOnWriteArrayList<>();
    private final List<String> pageErrors = new CopyOnWriteArrayList<>();

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
        resourceErrors.clear();
        pageErrors.clear();
        page = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)).newPage();
        page.onConsoleMessage(message -> {
            if ("error".equalsIgnoreCase(message.type())) {
                // 没有 favicon 时浏览器会报 404 资源错误，那是噪音，不算客户端故障
                if (message.text().startsWith("Failed to load resource")) {
                    resourceErrors.add(message.text() + " @ " + message.location());
                } else {
                    consoleErrors.add(message.text());
                }
            }
        });
        page.onPageError(error -> pageErrors.add(error));
    }

    @AfterEach
    void closePage() {
        if (page != null) {
            page.close();
        }
    }

    @Test
    void splashScreenThenBlankMainWithNavigation() throws Exception {
        long start = System.nanoTime();
        page.navigate(URL);

        // 启动画面：不挂 MainLayout，因此没有导航；2 秒后由服务端导航到 /main
        page.waitForSelector("#splash-title", new Page.WaitForSelectorOptions().setTimeout(30_000));
        long firstPaintMillis = (System.nanoTime() - start) / 1_000_000;
        assertEquals("DbDesk", page.locator("#splash-title").textContent().trim(),
                "启动画面标题应为 DbDesk");
        assertTrue(page.locator("#splash-subtitle").textContent().contains("SlackerDB"));
        assertEquals(0, page.locator("#main-nav").count(), "启动画面阶段不应显示左侧导航");

        page.waitForSelector("#main-nav", new Page.WaitForSelectorOptions().setTimeout(30_000));
        assertTrue(page.url().endsWith("/main"), "2 秒后应进入 /main，实际：" + page.url());
        page.waitForSelector("#main-content", new Page.WaitForSelectorOptions().setTimeout(30_000));
        assertTrue(page.locator("#main-content").textContent().isBlank(),
                "主界面右侧应为空白，实际：" + page.locator("#main-content").textContent());
        assertTrue(page.locator("#connection-status").textContent().contains("未连接"),
                "默认不应自动连接，实际：" + page.locator("#connection-status").textContent());
        assertTrue(page.locator("#connect-server").isVisible(), "未连接时应显示 Connect");

        // 未连接时直接访问 SQL 控制台：显示 guard，而不是自动探测服务端
        page.navigate(URL.replaceAll("/+$", "") + "/sql");
        page.waitForSelector("#sql-console-guard", new Page.WaitForSelectorOptions().setTimeout(30_000));
        assertEquals(0, page.locator("#run-button").count(), "未连接时不应创建 SQL 执行器");

        screenshot(Paths.get("target", "ui-smoke.png"));

        // 这行是给人看的证据，用英文标签，避免 Windows 控制台按 GBK 解码时中文变乱码
        System.out.printf("[splash] first-paint(navigate->splash): %d ms | browser: %s"
                        + " | console errors: %d | resource 404: %d | page errors: %d%n",
                firstPaintMillis, browser.version(),
                consoleErrors.size(), resourceErrors.size(), pageErrors.size());

        // 客户端 JS 崩了的话，即使 DOM 断言过了也不该算通过（Vaadin 的 500 页 / 组件树加载失败都在这里现形）
        if (!pageErrors.isEmpty()) {
            fail("页面出现未捕获的 JS 异常：" + pageErrors);
        }
        if (!consoleErrors.isEmpty()) {
            fail("浏览器控制台出现 error：" + consoleErrors);
        }
    }

    private void screenshot(Path path) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            page.screenshot(new Page.ScreenshotOptions().setPath(path).setFullPage(true));
        } catch (PlaywrightException | java.io.IOException e) {
            // 截图只是证据，失败不该让冒烟变红
            System.out.println("[M0-V1] 截图失败（忽略）：" + e.getMessage());
        }
    }
}
