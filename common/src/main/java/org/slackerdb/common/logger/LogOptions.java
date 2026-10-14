package org.slackerdb.common.logger;

/**
 * {@link AppLogger} 的可选配置项。
 *
 * <p><b>默认值刻意等于"历史行为"</b>：不滚动、单文件追加、沿用原 pattern。
 * 也就是说 {@code AppLogger.createLogger(name, level, logs)} 这个三参重载的行为
 * 不会因为本类的引入而发生任何变化；只有调用方显式传入本对象时才启用新能力。</p>
 *
 * <h2>两种滚动模式（语义不同，别混用）</h2>
 * <table border="1">
 *   <caption>滚动模式对比</caption>
 *   <tr><th>模式</th><th>实现</th><th>maxHistory 含义</th><th>磁盘上界</th></tr>
 *   <tr>
 *     <td>{@link Mode#SIZE}</td>
 *     <td>FixedWindowRollingPolicy + SizeBasedTriggeringPolicy</td>
 *     <td>保留的历史文件个数（不含活动文件）</td>
 *     <td><b>硬上界</b>：{@code maxFileSize × (maxHistory + 1)}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link Mode#SIZE_AND_TIME}</td>
 *     <td>SizeAndTimeBasedRollingPolicy</td>
 *     <td>保留的<b>时间段</b>个数（如"7 天"），不是文件个数</td>
 *     <td><b>软上界</b>：{@code totalSizeCap} 只能清理"更早时间段"的文件，
 *         同一时间段内的多次滚动不会被回收</td>
 *   </tr>
 * </table>
 *
 * <p>要"保证磁盘不被写满"，请用 {@link Mode#SIZE}（或 {@link #sizeBoundedDefaults()}）；
 * 要"按天保留 N 天"，请用 {@link Mode#SIZE_AND_TIME}。</p>
 *
 * <p>典型用法（未来接入时）：</p>
 * <pre>{@code
 * // 沿用历史行为（单文件、不滚动）
 * AppLogger.createLogger(name, level, "CONSOLE,logs/slackerdb.log");
 *
 * // 硬上界：100MB × (10 + 1) ≈ 1.1GB，超出即滚动并回收最旧文件
 * AppLogger.createLogger(name, level, "CONSOLE,logs/slackerdb.log",
 *         LogOptions.sizeBoundedDefaults());
 *
 * // 保留 7 天（同一时间段内的文件数不受 maxHistory 限制）
 * AppLogger.createLogger(name, level, "CONSOLE,logs/slackerdb.log",
 *         LogOptions.dailyDefaults().maxHistory(7).totalSizeCap("2GB"));
 * }</pre>
 */
public final class LogOptions {

    /** 滚动模式。 */
    public enum Mode {
        /** 不滚动：单文件追加（历史行为，默认）。 */
        NONE,
        /** 按大小滚动，固定窗口：文件数有硬上界，磁盘占用可预测。 */
        SIZE,
        /** 按大小 + 日期滚动：按时间段保留，同一时间段内的文件数不设上界。 */
        SIZE_AND_TIME
    }

    /**
     * FixedWindowRollingPolicy 的窗口上限（logback 的 {@code MAX_WINDOW_SIZE} 默认值）。
     * 超过该值 logback 会在 {@code start()} 时报错，因此这里主动收敛并告警。
     */
    private static final int MAX_WINDOW_SIZE = 20;

    /** 日志行格式；默认与历史实现逐字符一致。 */
    private String pattern = AppLogger.DEFAULT_PATTERN;

    /** 滚动模式；默认 {@link Mode#NONE}，即与历史行为一致。 */
    private Mode mode = Mode.NONE;

    /** 单个日志文件的大小上限，例如 {@code 100MB}。仅滚动模式下生效。 */
    private String maxFileSize = "100MB";

    /**
     * {@link Mode#SIZE}：保留的历史文件个数（不含活动文件），会被收敛到 logback 允许的窗口上限内。
     * {@link Mode#SIZE_AND_TIME}：保留的时间段个数（如 7 表示保留 7 天）。
     */
    private int maxHistory = 10;

    /**
     * 所有日志文件（含历史）的总占用上限，例如 {@code 1GB}。
     * 仅 {@link Mode#SIZE_AND_TIME} 且 {@link #getMaxHistory()} &gt; 0 时生效；
     * {@link Mode#SIZE} 不需要它（上界由 maxFileSize × maxHistory 决定）。
     */
    private String totalSizeCap = "1GB";

    /** 滚动出的历史文件是否 gzip 压缩。 */
    private boolean compress = true;

    /**
     * {@link Mode#SIZE} 模式下"多久允许检查一次活动文件大小"（毫秒）。
     *
     * <p><b>这个值必须显式设置</b>：logback 1.5 的 {@code SizeBasedTriggeringPolicy} 内置
     * {@code SimpleInvocationGate}，默认检查间隔是 <b>60 秒</b>。也就是说在默认配置下，
     * 一次密集写入（例如压测、批量导入、启动风暴）可以让日志文件涨到远超 maxFileSize
     * 才会触发下一次滚动——"磁盘上界 = maxFileSize × (maxHistory+1)"根本不成立。</p>
     *
     * <ul>
     *   <li>{@code 0}（默认）：每条日志都检查文件大小，滚动上界精确；代价是每条日志多一次
     *       {@code File.length()} 调用（与默认的 {@code immediateFlush=true} 每次写盘相比可以忽略）。</li>
     *   <li>{@code >0}：最多每 N 毫秒检查一次，写入路径更轻；代价是超调量约为
     *       {@code N 毫秒内的日志量}。</li>
     * </ul>
     */
    private long checkIncrementMillis = 0;

    /** 启动时清理超出保留范围的历史文件（仅 {@link Mode#SIZE_AND_TIME} 支持）。 */
    private boolean cleanHistoryOnStart = true;

    /** 是否每条日志立即 flush（默认 true：崩溃/断电场景尽量不丢日志）。 */
    private boolean immediateFlush = true;

    /** 与历史行为一致的默认配置：单文件、不滚动。 */
    public static LogOptions defaults() {
        return new LogOptions();
    }

    /** 推荐的**硬上界**配置：100MB × (10 + 1) ≈ 1.1GB，gzip 压缩。 */
    public static LogOptions sizeBoundedDefaults() {
        return new LogOptions().mode(Mode.SIZE);
    }

    /** 推荐的**按时间段**配置：单文件 100MB、保留 10 个时间段、总量 1GB、gzip、启动清理。 */
    public static LogOptions dailyDefaults() {
        return new LogOptions().mode(Mode.SIZE_AND_TIME);
    }

    public String getPattern() {
        return pattern;
    }

    public LogOptions pattern(String pattern) {
        if (pattern != null && !pattern.isBlank()) {
            this.pattern = pattern;
        }
        return this;
    }

    public Mode getMode() {
        return mode;
    }

    public LogOptions mode(Mode mode) {
        this.mode = mode == null ? Mode.NONE : mode;
        return this;
    }

    /** 便捷开关：{@code true} 等价于 {@link Mode#SIZE}，{@code false} 等价于 {@link Mode#NONE}。 */
    public LogOptions rotation(boolean rotation) {
        return mode(rotation ? Mode.SIZE : Mode.NONE);
    }

    public String getMaxFileSize() {
        return maxFileSize;
    }

    public LogOptions maxFileSize(String maxFileSize) {
        if (maxFileSize != null && !maxFileSize.isBlank()) {
            this.maxFileSize = maxFileSize;
        }
        return this;
    }

    public int getMaxHistory() {
        return maxHistory;
    }

    public LogOptions maxHistory(int maxHistory) {
        this.maxHistory = maxHistory;
        return this;
    }

    public String getTotalSizeCap() {
        return totalSizeCap;
    }

    public LogOptions totalSizeCap(String totalSizeCap) {
        this.totalSizeCap = totalSizeCap;
        return this;
    }

    public boolean isCompress() {
        return compress;
    }

    public LogOptions compress(boolean compress) {
        this.compress = compress;
        return this;
    }

    public long getCheckIncrementMillis() {
        return checkIncrementMillis;
    }

    /** 设置大小检查间隔（毫秒）；负数按 0 处理（= 每条日志都检查）。 */
    public LogOptions checkIncrementMillis(long checkIncrementMillis) {
        this.checkIncrementMillis = Math.max(0, checkIncrementMillis);
        return this;
    }

    public boolean isCleanHistoryOnStart() {
        return cleanHistoryOnStart;
    }

    public LogOptions cleanHistoryOnStart(boolean cleanHistoryOnStart) {
        this.cleanHistoryOnStart = cleanHistoryOnStart;
        return this;
    }

    public boolean isImmediateFlush() {
        return immediateFlush;
    }

    public LogOptions immediateFlush(boolean immediateFlush) {
        this.immediateFlush = immediateFlush;
        return this;
    }

    /** 供 {@link AppLogger} 使用：把窗口大小收敛到 logback 允许的范围。 */
    int effectiveWindowSize() {
        if (maxHistory < 1) {
            return 1;
        }
        return Math.min(maxHistory, MAX_WINDOW_SIZE);
    }

    /** 供 {@link AppLogger} 使用：maxHistory 是否被窗口上限收敛过。 */
    boolean windowSizeWasClamped() {
        return maxHistory > MAX_WINDOW_SIZE;
    }

    /** 供 {@link AppLogger} 使用：FixedWindow 窗口上限（用于日志与文档）。 */
    static int maxWindowSize() {
        return MAX_WINDOW_SIZE;
    }
}
