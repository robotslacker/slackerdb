package org.slackerdb.dbserver.scheduler;

import lombok.Getter;

/**
 * 调度能力的配置错误：键有值，但值不可用。
 *
 * <p>语义与"没有配置"严格区分：没有配置 = 这个能力不存在（不报错）；配错了 = 拒绝启动。</p>
 */
@Getter
public class SchedulerConfigException extends Exception {

    /**
     * -- GETTER --
     * 出错的配置键（含
     *  前缀）。
     */
    private final String key;
    /**
     * -- GETTER --
     * 出错时读到的值。
     */
    private final String value;
    /**
     * -- GETTER --
     * 期望的取值形态。
     */
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

}
