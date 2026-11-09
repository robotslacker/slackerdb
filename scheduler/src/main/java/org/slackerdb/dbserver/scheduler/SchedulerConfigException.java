package org.slackerdb.dbserver.scheduler;

/**
 * 调度能力的配置错误：键有值，但值不可用。
 *
 * <p>语义与"没有配置"严格区分：没有配置 = 这个能力不存在（不报错）；配错了 = 拒绝启动。</p>
 */
public class SchedulerConfigException extends Exception {

    private final String key;
    private final String value;
    private final String expected;

    public SchedulerConfigException(String key, String value, String expected) {
        super(key + "=" + value + " is invalid (expected: " + expected + ")");
        this.key = key;
        this.value = value;
        this.expected = expected;
    }

    public SchedulerConfigException(String key, String value, String expected, Throwable cause) {
        super(key + "=" + value + " is invalid (expected: " + expected + ")", cause);
        this.key = key;
        this.value = value;
        this.expected = expected;
    }

    /** 出错的配置键（含 {@code plugin-scheduler.} 前缀）。 */
    public String getKey() {
        return key;
    }

    /** 出错时读到的值。 */
    public String getValue() {
        return value;
    }

    /** 期望的取值形态。 */
    public String getExpected() {
        return expected;
    }
}
