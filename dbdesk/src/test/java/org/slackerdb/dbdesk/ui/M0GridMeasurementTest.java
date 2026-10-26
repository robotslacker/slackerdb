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
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M0-V2 的验收测量：把 100 万行 × 50 列接进 Vaadin Grid 之后，量三件事。
 *
 * <ol>
 *   <li><b>首屏</b>：点"加载"到第一屏行可见 ≤ 2 s；</li>
 *   <li><b>滚动帧率</b>：连续滚动 10 s 的平均帧率（目标 ≥ 50 fps，60 满分）；</li>
 *   <li><b>内存</b>：滚动期间 JS 堆的增量，以及 100 万行 vs 10 万行的对比（增长 &lt; 30%）。</li>
 * </ol>
 *
 * <p>外加单值 &gt; 1 MB 的截断验证（`m0_big` 表，2 MB 单值）。</p>
 *
 * <p><b>前置</b>：dbserver 上要有探针建的两张表。没有就先跑一遍
 * {@code java -cp <pgjdbc> dbdesk/m0/FetchProbe.java 127.0.0.1 4409 m0}。</p>
 *
 * <p><b>运行</b>（应用先起来）：</p>
 * <pre>
 *   mvn -Pdbdesk -pl dbdesk test "-Ddbdesk.ui.url=http://127.0.0.1:8090/" "-Dtest=M0GridMeasurementTest"
 * </pre>
 *
 * <p>数字全部打印成 {@code [M0-V2] …} 一行，可直接粘贴进验证报告。阈值按 m0-checklist §1：
 * 帧率 ≥ 50、内存增长 &lt; 30%、首屏 ≤ 2 s —— 不达标时断言会把原因说清楚，而不是只写"失败"。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.ui.url", matches = ".+")
class M0GridMeasurementTest {

    private static final String BASE_URL = System.getProperty("dbdesk.ui.url").replaceAll("/+$", "");
    private static final String GRID_URL = BASE_URL + "/m0-grid";

    /** 验收线（javagui-m0-checklist.md §1）。 */
    private static final double TARGET_FPS = 50.0;
    private static final long TARGET_FIRST_PAINT_MS = 2_000;
    private static final double TARGET_HEAP_GROWTH = 0.30;
    private static final int SCROLL_MILLIS = 10_000;
    /** 4 px/帧 ≈ 240 px/s：接近正常滚轮速度，且是同一"像素速度"下比较 1M / 100k 的基准。 */
    private static final int SCROLL_PX_PER_FRAME = 4;

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
                .setViewportSize(1600, 900)).newPage();
        page.setDefaultTimeout(60_000);
    }

    @Test
    void oneMillionRowsScrollAndStayFlatInMemory() throws Exception {
        long firstPaint = loadGrid("select * from m0_probe", 1_000_000L);
        long heapBefore = jsHeap();
        ScrollResult million = scroll(SCROLL_MILLIS, 1_000_000L);
        sweepToEnd();
        long endId = deepestVisibleId();
        long heapAfterMillion = jsHeap();

        long firstPaintSmall = loadGrid("select * from m0_probe limit 100000", 100_000L);
        long heapBeforeSmall = jsHeap();
        ScrollResult hundredK = scroll(SCROLL_MILLIS, 100_000L);
        sweepToEnd();
        long heapAfterSmall = jsHeap();

        double fpsMillion = million.framesPerSecond();
        double fpsSmall = hundredK.framesPerSecond();
        double growthMillion = growth(heapBefore, heapAfterMillion);
        double growthSmall = growth(heapBeforeSmall, heapAfterSmall);
        double heapRatio = heapAfterMillion / (double) Math.max(1, heapAfterSmall);

        System.out.printf("[M0-V2] 1M: first-paint=%d ms, fps=%.1f, frames=%d/%.0f ms, rows-rolled=%.0f (%.0f rows/s), id-at-bottom=%d, heap %d -> %d MB (%+.1f%%)%n",
                firstPaint, fpsMillion, million.frames(), million.elapsedMillis(),
                million.rowsAdvanced(), million.rowsAdvanced() * 1000 / Math.max(1, million.elapsedMillis()),
                endId, heapBefore >> 20, heapAfterMillion >> 20, growthMillion * 100);
        System.out.printf("[M0-V2] 100k: first-paint=%d ms, fps=%.1f, frames=%d/%.0f ms, rows-rolled=%.0f, heap %d -> %d MB (%+.1f%%)%n",
                firstPaintSmall, fpsSmall, hundredK.frames(), hundredK.elapsedMillis(),
                hundredK.rowsAdvanced(),
                heapBeforeSmall >> 20, heapAfterSmall >> 20, growthSmall * 100);
        System.out.printf("[M0-V2] heap-ratio 1M/100k = %.2fx (after scroll: %d MB vs %d MB)%n",
                heapRatio, heapAfterMillion >> 20, heapAfterSmall >> 20);

        Files.createDirectories(Paths.get("target"));
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(Paths.get("target", "m0-grid-scrolled.png")));

        assertTrue(firstPaint <= TARGET_FIRST_PAINT_MS,
                "首屏 " + firstPaint + " ms 超过 " + TARGET_FIRST_PAINT_MS + " ms");
        assertTrue(fpsMillion >= TARGET_FPS,
                "1M 行滚动帧率 " + String.format("%.1f", fpsMillion) + " fps 低于目标 " + TARGET_FPS);
        assertTrue(growthMillion < TARGET_HEAP_GROWTH,
                "滚动 10 s 的 JS 堆增长 " + String.format("%.1f%%", growthMillion * 100)
                        + " 超过 " + (TARGET_HEAP_GROWTH * 100) + "%（说明在客户端累积了结果集）");
        assertTrue(heapRatio < 1.30,
                "1M 滚动后的堆是 100k 的 " + String.format("%.2f", heapRatio) + " 倍，超过 1.30x");
        assertTrue(endId > 900_000,
                "跳到末尾后可见 id 应 > 900000（深分页真的翻到了），实际 " + endId);
    }

    @Test
    void truncatesSingleValueOverOneMegabyte() {
        loadGrid("select payload from m0_big", 1L);

        // 表头也是 vaadin-grid-cell-content，所以取"最长的那个单元格"，不要盲取 first()
        String cell = (String) page.evaluate("""
                () => {
                  const texts = Array.from(document.querySelectorAll('vaadin-grid-cell-content'))
                      .map(c => c.textContent);
                  texts.sort((a, b) => b.length - a.length);
                  return texts.length ? texts[0] : '';
                }
                """);
        int length = cell.length();

        System.out.printf("[M0-V2] 1MB 截断: 原值 2,000,000 字符 -> 渲染 %d 字符, 含标记=%s%n",
                length, cell.contains("已截断"));

        assertTrue(cell.contains("已截断"), "超长单元格应带截断标记，实际尾部："
                + cell.substring(Math.max(0, length - 40)));
        assertTrue(length >= 1_048_576 && length < 1_048_576 + 64,
                "截断后长度应在 1 MB 附近（实际 " + length + " 字符）");
    }

    // ------------------------------------------------------------------ 操作

    /** 打开网格页、填入 SQL、点加载，等到该 SQL 的行数出现在状态栏且行已渲染。返回首屏耗时。 */
    private long loadGrid(String sql, long expectedRows) {
        page.navigate(GRID_URL);
        page.waitForSelector("vaadin-grid", new Page.WaitForSelectorOptions().setTimeout(30_000));
        page.locator("#sql-field textarea").fill(sql);

        long start = System.nanoTime();
        page.click("#load-button");

        String expected = String.format("%,d 行", expectedRows);
        page.waitForFunction("expected => document.querySelector('#status-text').textContent.includes(expected)",
                expected, new Page.WaitForFunctionOptions().setTimeout(120_000));
        page.waitForSelector("vaadin-grid-cell-content", new Page.WaitForSelectorOptions().setTimeout(60_000));
        long firstPaint = (System.nanoTime() - start) / 1_000_000;

        String status = page.locator("#status-text").textContent();
        System.out.println("[M0-V2] status: " + status);
        return firstPaint;
    }

    /**
     * 在页面里用 rAF 连续滚动，返回帧数与滚动距离（fps 由它算）。
     *
     * <p><b>为什么用固定的 4 px/帧</b>：Vaadin Grid 会把超大数据集（100 万行）的滚动空间压缩到
     * 约 330 万 px（3.3 px/行），小数据集则是 33 px/行；"每帧一行"在两种规模下差 10 倍像素，
     * 测出来的帧率没法比较（实测 1M=55.8 fps、100k=27.1 fps，纯粹是像素速度差异）。
     * 4 px/帧 ≈ 240 px/s，接近正常滚轮速度；同一像素速度下 1M 与 100k 的帧率一致
     * （54.5 vs 54.6），差别只体现在"每秒翻过多少行"（72 vs 7 行/秒）。曲线见 handoff §3.4。</p>
     */
    private ScrollResult scroll(int millis, long totalRows) {
        Object raw = page.evaluate("""
                cfg => new Promise(resolve => {
                  const grid = document.querySelector('#result-grid');
                  const root = grid.shadowRoot || grid;
                  // Vaadin 25 的 Grid：真正可滚的是 shadow DOM 里的 <table id="table">，
                  // 外层 #scroller 是 overflow:hidden 的（量它只会得到 scrollHeight == clientHeight）
                  const scroller = root.querySelector('#table')
                      || root.querySelector('#scroller')
                      || grid;
                  const pxPerRow = scroller.scrollHeight / cfg.totalRows;
                  const step = cfg.step;
                  const start = performance.now();
                  const startTop = scroller.scrollTop;
                  let frames = 0;
                  function tick(now) {
                    frames++;
                    scroller.scrollTop = scroller.scrollTop + step;
                    if (now - start < cfg.millis) {
                      requestAnimationFrame(tick);
                    } else {
                      resolve({frames: frames, elapsed: now - start,
                               scrollTop: scroller.scrollTop - startTop,
                               scrollHeight: scroller.scrollHeight,
                               rowsAdvanced: (scroller.scrollTop - startTop) / pxPerRow});
                    }
                  }
                  requestAnimationFrame(tick);
                })
                """, Map.of("millis", millis, "totalRows", (double) totalRows, "step", SCROLL_PX_PER_FRAME));
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) raw;
        return new ScrollResult(
                ((Number) map.get("frames")).intValue(),
                ((Number) map.get("elapsed")).doubleValue(),
                ((Number) map.get("scrollTop")).doubleValue(),
                ((Number) map.get("scrollHeight")).doubleValue(),
                ((Number) map.get("rowsAdvanced")).doubleValue());
    }

    /** 跳到 25% / 50% / 75% / 100% 处，逼 DataCommunicator 去深处取页；返回末次 scrollTop。 */
    private double sweepToEnd() {
        Object raw = page.evaluate("""
                async () => {
                  const grid = document.querySelector('#result-grid');
                  const root = grid.shadowRoot || grid;
                  const scroller = root.querySelector('#table') || root.querySelector('#scroller') || grid;
                  for (const fraction of [0.25, 0.5, 0.75, 1.0]) {
                    scroller.scrollTop = scroller.scrollHeight * fraction;
                    await new Promise(r => setTimeout(r, 400));
                  }
                  return scroller.scrollTop;
                }
                """);
        return ((Number) raw).doubleValue();
    }

    /**
     * 当前可见单元格里最大的纯数字（id 列是 0…999999，其余数字列都 &lt; 1000）。
     * 跳到末尾后它应该接近 100 万 —— 这是"深分页真的翻到了"的硬证据。
     */
    private long deepestVisibleId() {
        Object raw = page.evaluate("""
                () => {
                  const texts = Array.from(document.querySelectorAll('vaadin-grid-cell-content'))
                      .map(c => c.textContent.trim())
                      .filter(t => /^\\d+$/.test(t))
                      .map(Number);
                  return texts.length ? Math.max(...texts) : -1;
                }
                """);
        return ((Number) raw).longValue();
    }

    private long jsHeap() {
        Object value = page.evaluate(
                "() => performance.memory ? performance.memory.usedJSHeapSize : 0");
        return ((Number) value).longValue();
    }

    private static double growth(long before, long after) {
        return (after - before) / (double) Math.max(1, before);
    }

    /** 一次滚动测量的原始数据。 */
    private record ScrollResult(int frames, double elapsedMillis, double scrollTop, double scrollHeight,
                                double rowsAdvanced) {
        double framesPerSecond() {
            return elapsedMillis <= 0 ? 0 : frames * 1000.0 / elapsedMillis;
        }
    }
}
