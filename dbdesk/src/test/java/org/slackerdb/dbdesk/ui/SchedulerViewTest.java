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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调度中心页面的浏览器验收：建 run → 建任务 → 登记调度 → 删任务 → 丢弃 run。
 *
 * <p>需要一个**带 plugin-scheduler 的实例**，并把控制台指过去（见 {@code m2-kickoff.md} §3）：</p>
 * <pre>
 *   mvn -Pdbdesk -pl dbdesk test "-Ddbdesk.scheduler.ui.url=http://127.0.0.1:8091/" "-Dtest=SchedulerViewTest"
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.scheduler.ui.url", matches = ".+")
class SchedulerViewTest {

    private static final String URL =
            System.getProperty("dbdesk.scheduler.ui.url").replaceAll("/+$", "") + "/scheduler";
    private static final String RUN_ID =
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    private static final String TASK_NAME = "it_task";

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
                .setViewportSize(1600, 1000)).newPage();
        page.setDefaultTimeout(30_000);
        page.onConsoleMessage(message -> {
            String text = message.text();
            if ("error".equalsIgnoreCase(message.type())
                    && !text.startsWith("Failed to load resource")) {
                consoleErrors.add(text);
            }
        });
        page.navigate(URL);
    }

    @Test
    void uploadsProjectTemplate() throws Exception {
        page.waitForSelector("#template-button", new Page.WaitForSelectorOptions().setTimeout(30_000));
        String project = (String) page.evaluate("() => document.querySelector('#run-project').value || ''");
        org.junit.jupiter.api.Assumptions.assumeTrue(project != null && !project.isBlank(),
                "模板目录里没有项目类型，跳过");

        page.click("#template-button");
        page.waitForSelector("#tpl-upload", new Page.WaitForSelectorOptions().setTimeout(30_000));
        page.fill("#tpl-content textarea",
                "{ \"tasks\": [ { \"taskName\": \"ui_tpl_task\", \"taskScript\": \"x.hwf\" } ] }");
        page.click("#tpl-upload");
        page.waitForFunction("() => document.querySelector('#tpl-result').textContent.includes('已上传')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        String uploadResult = page.locator("#tpl-result").textContent();

        page.click("#tpl-delete");
        page.waitForFunction("() => document.querySelector('#tpl-result').textContent.includes('已删除')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        String deleteResult = page.locator("#tpl-result").textContent();

        System.out.printf("[M2] template project=%s | upload='%s' | delete='%s'%n",
                project, uploadResult, deleteResult);

        assertTrue(uploadResult.contains("已上传"), "上传应成功，实际：" + uploadResult);
        assertTrue(deleteResult.contains("已删除"), "删除应成功，实际：" + deleteResult);
        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }

    @Test
    void runsShellTaskAndSeesItInHistory() throws Exception {
        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        page.waitForFunction("() => document.querySelector('#sched-cap')"
                        + " && document.querySelector('#sched-cap').textContent.includes('contract')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));

        // 建 run
        page.fill("#run-id input", runId);
        page.click("#run-create");
        page.waitForFunction("(id) => document.querySelector('#run-grid').textContent.includes(id)",
                runId, new Page.WaitForFunctionOptions().setTimeout(30_000));
        page.locator("#run-grid vaadin-grid-cell-content", new Page.LocatorOptions().setHasText(runId))
                .first().click();

        // 建一个 SHELL 任务（不需要 HOP 环境），策略 RUN_ONCE
        page.fill("#task-name input", "ui_shell");
        page.fill("#task-script input", "echo ui-history");
        selectOption("#task-type", "SHELL");
        selectOption("#task-policy", "RUN_ONCE");
        page.click("#task-create");
        page.waitForFunction("() => document.querySelector('#task-grid').textContent.includes('ui_shell')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));

        // 启动 run → Quartz 真的跑一次
        page.click("#run-start");
        page.waitForTimeout(4000);

        // 历史区：点工具栏「历史流水」→ 刷新 → 应出现这条流水
        page.click("#history-button-open");
        page.waitForTimeout(1200);
        System.out.println("[M2][debug] summary=" + page.locator("#history-summary").textContent()
                + " | grid=" + page.locator("#history-grid").textContent().replace("\n", " "));
        page.waitForFunction("() => document.querySelector('#history-grid')"
                        + " && document.querySelector('#history-grid').textContent.includes('ui_shell')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        String summary = page.locator("#history-summary").textContent();
        String historyText = page.locator("#history-grid").textContent();

        Files.createDirectories(Paths.get("target"));
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(Paths.get("target", "m2-history.png")).setFullPage(true));

        System.out.printf("[M2] shell run %s | summary='%s' | history has 成功=%s%n",
                runId, summary, historyText.contains("成功"));

        assertTrue(summary.contains("成功"), "历史汇总应显示成功计数，实际：" + summary);
        assertTrue(historyText.contains("成功"), "历史行应显示成功，实际：" + historyText);

        // 清理：丢弃 run
        page.click("#run-drop");
        page.waitForTimeout(800);
    }

    /** 选中 Vaadin ComboBox 的某个选项（点开 → 点条目）。 */
    private void selectOption(String selector, String option) {
        page.click(selector + " input");
        page.locator("vaadin-combo-box-item", new Page.LocatorOptions().setHasText(option))
                .first().click();
    }

    @Test
    void createsRunAndTaskThenDropsRun() throws Exception {
        page.waitForFunction("() => document.querySelector('#sched-cap')"
                        + " && document.querySelector('#sched-cap').textContent.includes('contract')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        String capability = page.locator("#sched-cap").textContent();

        // 新建 run（项目类型已由下拉框自动选中模板目录里的第一条）
        page.fill("#run-id input", RUN_ID);
        page.click("#run-create");
        page.waitForFunction("(runId) => document.querySelector('#run-grid').textContent.includes(runId)",
                RUN_ID, new Page.WaitForFunctionOptions().setTimeout(30_000));

        // 选中 run → 建任务
        page.locator("#run-grid vaadin-grid-cell-content", new Page.LocatorOptions().setHasText(RUN_ID))
                .first().click();
        page.fill("#task-name input", TASK_NAME);
        page.fill("#task-script input", "it_task.hwf");
        page.fill("#task-cron input", "0 0 4 * * ?");
        page.click("#task-create");
        page.waitForFunction("(name) => document.querySelector('#task-grid').textContent.includes(name)",
                TASK_NAME, new Page.WaitForFunctionOptions().setTimeout(30_000));

        // 登记调度 → 调度列变"已登记"
        page.locator("#task-grid vaadin-grid-cell-content", new Page.LocatorOptions().setHasText(TASK_NAME))
                .first().click();
        page.click("#task-schedule");
        page.waitForFunction("() => document.querySelector('#task-grid').textContent.includes('已登记')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));

        Files.createDirectories(Paths.get("target"));
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(Paths.get("target", "m2-scheduler.png")).setFullPage(true));

        // 删任务：Vaadin Grid 会复用/残留单元格，直接断言 textContent 会误判；
        // 这里"删 → 重载页面 → 重新选中 run"再断言，确认服务端真的没了。
        page.click("#task-delete");
        page.waitForTimeout(600);
        page.reload();
        page.waitForFunction("(runId) => document.querySelector('#run-grid')"
                        + " && document.querySelector('#run-grid').textContent.includes(runId)",
                RUN_ID, new Page.WaitForFunctionOptions().setTimeout(30_000));
        page.locator("#run-grid vaadin-grid-cell-content", new Page.LocatorOptions().setHasText(RUN_ID))
                .first().click();
        page.waitForTimeout(800);
        assertTrue(!page.locator("#task-grid").textContent().contains(TASK_NAME),
                "删除后重载页面，任务不应再出现");

        // 丢弃 run（自清理）
        page.click("#run-drop");
        page.waitForTimeout(600);
        page.reload();
        page.waitForFunction("(runId) => document.querySelector('#run-grid')"
                        + " && !document.querySelector('#run-grid').textContent.includes(runId)",
                RUN_ID, new Page.WaitForFunctionOptions().setTimeout(30_000));

        String error = page.locator("#sched-error").textContent();

        System.out.printf("[M2] capability='%s' | runId=%s | 清理后 error='%s' | console errors=%d%n",
                capability, RUN_ID, error, consoleErrors.size());

        assertTrue(capability.contains("run:可写"), "能力行应显示 run 可写，实际：" + capability);
        assertTrue(capability.contains("任务:可写"), "能力行应显示任务可写，实际：" + capability);
        assertTrue(error.isBlank(), "全流程不应报错，实际：" + error);
        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }

    @Test
    void importsProjectZip() throws Exception {
        page.waitForSelector("#template-button", new Page.WaitForSelectorOptions().setTimeout(30_000));
        String project = (String) page.evaluate("() => document.querySelector('#run-project').value || ''");
        org.junit.jupiter.api.Assumptions.assumeTrue(project != null && !project.isBlank(),
                "模板目录里没有项目类型，跳过");

        // 造一个 zip：conf/defaultSchedulerTask.json + scripts/z.hwf
        java.nio.file.Path zip = java.nio.file.Paths.get("target", "it-import-" + System.nanoTime() + ".zip");
        Files.createDirectories(zip.getParent());
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("conf/defaultSchedulerTask.json"));
            out.write(("{ \"tasks\": [ { \"taskName\": \"zip_ui_task\", \"taskScript\": \"scripts/z.hwf\" } ] }")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new java.util.zip.ZipEntry("scripts/z.hwf"));
            out.write("// imported by ui test".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }

        page.click("#template-button");
        page.waitForSelector("#tpl-import-zip", new Page.WaitForSelectorOptions().setTimeout(30_000));
        page.setInputFiles("#tpl-import-zip input[type=file]", zip);
        page.waitForFunction("() => document.querySelector('#tpl-result').textContent.includes('已导入')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        String result = page.locator("#tpl-result").textContent();

        System.out.printf("[M2] zip import project=%s | result='%s'%n", project, result);

        assertTrue(result.contains("已导入 2 个文件"), "应导入 2 个文件，实际：" + result);
        assertTrue(result.contains("模板任务 1 个"), "导入后应解析出 1 个任务，实际：" + result);

        // 清理：删掉导入的清单文件（其余文件留着无害）
        page.click("#tpl-delete");
        page.waitForFunction("() => document.querySelector('#tpl-result').textContent.includes('已删除') ||"
                        + " document.querySelector('#tpl-result').textContent.includes('删除失败')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        assertTrue(consoleErrors.isEmpty(), "浏览器控制台出现 error：" + consoleErrors);
    }
}
