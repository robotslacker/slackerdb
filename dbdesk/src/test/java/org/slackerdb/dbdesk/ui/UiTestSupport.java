package org.slackerdb.dbdesk.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * UI 测试的公共装置：怎么起 Playwright、怎么起浏览器。
 *
 * <p>两条硬约束（都是在真机上踩出来的，见 handoff §2.6）：</p>
 * <ol>
 *   <li>{@code PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1} —— 否则 {@code Playwright.create()} 会去
 *       下载 ~180 MB 的 Chromium（内网必卡）；</li>
 *   <li>统一使用系统已装的 Chrome/Chromium（{@code executablePath}），不依赖 Playwright
 *       自带的浏览器版本；Linux 没有 Edge，因此 Windows 也统一用 Chrome。</li>
 * </ol>
 */
final class UiTestSupport {

    /**
     * 浏览器路径优先级：
     * 1. {@code -Ddbdesk.ui.browser=...}
     * 2. 旧属性 {@code -Ddbdesk.ui.edge=...}（兼容）
     * 3. 按平台探测 Chrome/Chromium
     */
    private static final String BROWSER_PROPERTY = "dbdesk.ui.browser";
    private static final String LEGACY_BROWSER_PROPERTY = "dbdesk.ui.edge";
    private static final Path BROWSER = resolveBrowser();

    private UiTestSupport() {
    }

    static Playwright createPlaywright() {
        return Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
    }

    static Browser launchBrowser(Playwright playwright) {
        List<String> args = new ArrayList<>(List.of(
                "--no-first-run", "--no-default-browser-check", "--disable-gpu"));
        // 让 performance.memory 给出更细的数字（V2 的内存验收要用）
        args.add("--enable-precise-memory-info");

        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(args);
        if (BROWSER != null) {
            if (!Files.isRegularFile(BROWSER)) {
                throw new IllegalStateException("Browser not found: " + BROWSER
                        + ". Pass -Ddbdesk.ui.browser=<chrome executable>.");
            }
            options.setExecutablePath(BROWSER);
        } else {
            // 没有探测到固定路径时，让 Playwright 使用系统 Chrome。
            options.setChannel(System.getProperty("dbdesk.ui.channel", "chrome"));
        }
        return playwright.chromium().launch(options);
    }

    private static Path resolveBrowser() {
        String configured = System.getProperty(BROWSER_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty(LEGACY_BROWSER_PROPERTY);
        }
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        for (Path candidate : defaultCandidates()) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<Path> defaultCandidates() {
        List<Path> candidates = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String programFiles = System.getenv("ProgramFiles");
            String programFilesX86 = System.getenv("ProgramFiles(x86)");
            String localAppData = System.getenv("LOCALAPPDATA");
            if (programFiles != null) {
                candidates.add(Paths.get(programFiles,
                        "Google", "Chrome", "Application", "chrome.exe"));
            }
            if (programFilesX86 != null) {
                candidates.add(Paths.get(programFilesX86,
                        "Google", "Chrome", "Application", "chrome.exe"));
            }
            if (localAppData != null) {
                candidates.add(Paths.get(localAppData,
                        "Google", "Chrome", "Application", "chrome.exe"));
            }
        } else if (os.contains("mac")) {
            candidates.add(Paths.get("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
            candidates.add(Paths.get("/Applications/Chromium.app/Contents/MacOS/Chromium"));
        } else {
            candidates.add(Paths.get("/usr/bin/google-chrome"));
            candidates.add(Paths.get("/usr/bin/google-chrome-stable"));
            candidates.add(Paths.get("/usr/bin/chromium"));
            candidates.add(Paths.get("/usr/bin/chromium-browser"));
        }
        return candidates;
    }
}
