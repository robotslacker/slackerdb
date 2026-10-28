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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语言切换：英文为默认，点一次变中文，刷新后仍是中文，再点回英文。
 *
 * <p>语言通过 cookie {@code dbdesk.lang} 固定，避免受开发机 profile 影响；
 * 断言同时检查 {@code <html lang>} 与菜单文案。</p>
 *
 * <pre>
 *   mvn -Pdbdesk -pl dbdesk test "-Ddbdesk.ui.url=http://127.0.0.1:8090/" "-Dtest=LanguageSwitchTest"
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.ui.url", matches = ".+")
class LanguageSwitchTest {

    private static final String BASE = System.getProperty("dbdesk.ui.url").replaceAll("/+$", "");

    private Playwright playwright;
    private Browser browser;
    private Page page;

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
        page = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1600, 950)).newPage();
        page.setDefaultTimeout(30_000);
        UiTestSupport.useEnglish(page);
        page.navigate(BASE + "/main");
        page.waitForSelector("#main-nav", new Page.WaitForSelectorOptions().setTimeout(30_000));
    }

    @Test
    void englishIsTheDefaultAndSwitchingPersists() {
        assertTrue(navText().contains("SQL Console"), "默认应为英文菜单，实际：" + navText());
        assertTrue(page.locator("#language-switch").textContent().contains("中文"),
                "英文界面下按钮应显示目标语言“中文”");
        assertEquals("en", htmlLang());

        page.click("#language-switch");
        page.waitForFunction("() => document.querySelector('#main-nav')"
                        + " && document.querySelector('#main-nav').textContent.includes('SQL 控制台')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        assertEquals("zh-CN", htmlLang());
        assertTrue(page.locator("#language-switch").textContent().contains("English"),
                "中文界面下按钮应显示目标语言“English”");

        // 刷新后仍保持中文：语言写进了 cookie（同时写 profile）
        page.reload();
        page.waitForSelector("#main-nav", new Page.WaitForSelectorOptions().setTimeout(30_000));
        assertTrue(navText().contains("SQL 控制台"), "刷新后应仍是中文，实际：" + navText());
        assertEquals("zh-CN", htmlLang());

        // 换一个没有 cookie 的全新浏览器上下文：应当从 profile 读到中文
        // （不要动当前上下文的 cookie：清掉 JSESSIONID 会让当前会话失效）
        Page fresh = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1600, 950)).newPage();
        fresh.setDefaultTimeout(30_000);
        fresh.navigate(BASE + "/main");
        fresh.waitForSelector("#main-nav", new Page.WaitForSelectorOptions().setTimeout(30_000));
        assertTrue(fresh.locator("#main-nav").textContent().contains("SQL 控制台"),
                "新会话应从 profile 继承中文，实际：" + fresh.locator("#main-nav").textContent());
        fresh.close();

        // 切回英文，别把开发机/后续用例留在中文
        page.click("#language-switch");
        page.waitForFunction("() => document.querySelector('#main-nav')"
                        + " && document.querySelector('#main-nav').textContent.includes('SQL Console')",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000));
        assertEquals("en", htmlLang());
    }

    private String navText() {
        return page.locator("#main-nav").textContent();
    }

    private String htmlLang() {
        return String.valueOf(page.evaluate("() => document.documentElement.lang"));
    }
}
