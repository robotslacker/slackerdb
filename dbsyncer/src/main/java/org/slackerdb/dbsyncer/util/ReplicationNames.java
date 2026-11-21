package org.slackerdb.dbsyncer.util;

import java.util.Locale;

/**
 * PostgreSQL 复制对象命名：publication / 复制槽都按作业名派生。
 *
 * <p>校验层要用同一套规则判断"两个作业是否会抢同一个槽"，所以派生规则必须只有一份实现。</p>
 */
public final class ReplicationNames {

    public static final String PUBLICATION_PREFIX = "sdb_pub_";
    public static final String SLOT_PREFIX = "sdb_slot_";
    /** PostgreSQL 标识符上限。 */
    private static final int MAX_LENGTH = 63;

    private ReplicationNames() {
    }

    public static String publication(String jobName) {
        return derive(PUBLICATION_PREFIX, jobName);
    }

    public static String slot(String jobName) {
        return derive(SLOT_PREFIX, jobName);
    }

    /**
     * 作业名 → PG 标识符：小写、非 {@code [a-z0-9_]} 一律换成 {@code _}、超过 63 截断。
     * 正因为会归一化，{@code a-b} 和 {@code a_b} 会得到同一个名字，必须在配置校验阶段拦下。
     */
    public static String derive(String prefix, String jobName) {
        String cleaned = (jobName == null ? "" : jobName).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "_");
        String value = prefix + cleaned;
        return value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) : value;
    }
}
