package org.slackerdb.dbproxy;

/**
 * dbproxy 的构建信息出口：本类只固定“本构件是谁”，取值与格式化统一在
 * {@code org.slackerdb.common.version.BuildInfo}（读 classpath 上的
 * {@code META-INF/build-info.properties}，由 {@code spring-boot-maven-plugin} 的
 * {@code build-info} 目标在构建期生成，见 {@code dbproxy/pom.xml}）。
 *
 * <p>所以“代理报的版本号”与 POM 里的 {@code <project.version>} 不可能不一致：
 * 源码树里没有可手工维护的版本文件；构建时刻也不手写，而是构建期写入的 UTC 瞬时，
 * 展示时才转成本地时间并带偏移。</p>
 *
 * <p>读不到时一律是 {@code unknown}：不回显 {@code ${...}} 占位符，也不抛异常
 * （版本信息缺失不该让代理起不来）。</p>
 */
public final class BuildInfo {

    /** 本模块的 Maven 构件名：classpath 上有多份 build-info.properties 时靠它精确匹配。 */
    private static final String ARTIFACT = "slackerdb-dbproxy";

    private static final org.slackerdb.common.version.BuildInfo INFO =
            org.slackerdb.common.version.BuildInfo.of(ARTIFACT);

    private BuildInfo() {
    }

    /** 项目版本号，例如 {@code 0.2.2}；读不到时是 {@code unknown}。 */
    public static String version() {
        return INFO.version();
    }

    /** 本地构建时间（带偏移），例如 {@code 2026-10-20 23:13:25 +08:00}；读不到时是 {@code unknown}。 */
    public static String buildTime() {
        return INFO.buildTime();
    }
}
