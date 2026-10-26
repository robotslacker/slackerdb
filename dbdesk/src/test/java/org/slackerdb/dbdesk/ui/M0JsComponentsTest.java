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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M0-V3 的浏览器验收：CodeMirror 6 与 ECharts 5 两个包装件在生产构建里是否真的能用。
 *
 * <p>断言的是"用户能感知的行为"，不是 DOM 结构细节：</p>
 * <ol>
 *   <li>编辑器渲染出 CodeMirror 的 DOM（{@code .cm-editor} / {@code .cm-gutters} 行号栏）；</li>
 *   <li>Ctrl+Enter 能回到服务端（状态文本变成"运行：…"）——这是 Java↔JS 双向通路的证明；</li>
 *   <li>图表能出 canvas 且尺寸 &gt; 0（ECharts 按需构建后真的在画）；</li>
 *   <li>整个过程浏览器控制台没有 error。</li>
 * </ol>
 *
 * <p>运行：{@code mvn -Pdbdesk -pl dbdesk test "-Ddbdesk.ui.url=http://127.0.0.1:8090/" "-Dtest=M0JsComponentsTest"}</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.ui.url", matches = ".+")
class M0JsComponentsTest {

    private static final String URL = System.getProperty("dbdesk.ui.url").replaceAll("/+$", "") + "/m0-js";

    private Playwright playwright;
    private Browser browser;
    private Page page;
    private final List<String> consoleErrors = new java.util.concurrent.CopyOnWriteArrayList<>();

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
                .setViewportSize(1280, 1000)).newPage();
        page.setDefaultTimeout(60_000);
        page.onConsoleMessage(message -> {
            if ("error".equalsIgnoreCase(message.type()) && !message.text().startsWith("Failed to load resource")) {
                consoleErrors.add(message.text());
            }
        });
    }

    @Test
    void codeMirrorAndEChartsWorkInProductionBuild() throws Exception {
        page.navigate(URL);

        // ---- CodeMirror ----
        page.waitForSelector(".cm-editor", new Page.WaitForSelectorOptions().setTimeout(60_000));
        page.waitForSelector(".cm-gutters", new Page.WaitForSelectorOptions().setTimeout(30_000));

        String initialText = page.locator(".cm-content").textContent();
        assertTrue(initialText.contains("select id, c1, c2"), "编辑器应载入示例 SQL，实际：" + initialText);
        int lineNumbers = page.locator(".cm-lineNumbers .cm-gutterElement").count();

        page.locator(".cm-content").click();
        page.keyboard().press("Control+Enter");
        page.waitForFunction("() => document.querySelector('#sql-status').textContent.startsWith('运行')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        String runStatus = page.locator("#sql-status").textContent();

        // ---- ECharts ----
        page.click("#chart-button");
        page.waitForSelector("#chart canvas", new Page.WaitForSelectorOptions().setTimeout(30_000));
        page.waitForTimeout(500); // 让 ResizeObserver 完成一次 resize
        Object size = page.evaluate("""
                () => {
                  const chart = document.querySelector('#chart');
                  const canvas = chart && chart.querySelector('canvas');
                  return canvas ? {width: canvas.width, height: canvas.height,
                                   cssWidth: chart.clientWidth, cssHeight: chart.clientHeight} : null;
                }
                """);
        String chartStatus = page.locator("#chart-status").textContent();

        Files.createDirectories(Paths.get("target"));
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(Paths.get("target", "m0-js.png")).setFullPage(true));

        System.out.printf("[M0-V3] CodeMirror: lines=%d, ctrl+enter='%s' | ECharts: size=%s, status='%s' | console errors=%d%n",
                lineNumbers, runStatus, size, chartStatus, consoleErrors.size());

        assertTrue(runStatus.startsWith("运行"), "Ctrl+Enter 应回到服务端，实际状态：" + runStatus);
        assertTrue(size != null, "ECharts 应渲染出 canvas");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> map = (java.util.Map<String, Object>) size;
        assertTrue(((Number) map.get("cssWidth")).intValue() > 100, "图表宽度应 >100px，实际：" + map);
        assertTrue(((Number) map.get("cssHeight")).intValue() > 100, "图表高度应 >100px，实际：" + map);
        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }
}
