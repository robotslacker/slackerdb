package org.slackerdb.dbcli;

/**
 * dbcli 的构建信息出口：本类只固定“本构件是谁”和 {@code --version} 的两行形态，
 * 读到的东西（构件名 / 版本号 / 构建时刻）与格式化口径全部来自
 * {@code org.slackerdb.common.version.BuildInfo}。
 *
 * <p>数据的来源是构建期生成的 classpath 资源 {@code META-INF/build-info.properties}
 * （{@code spring-boot-maven-plugin} 的 {@code build-info} 目标，见 {@code dbcli/pom.xml}）。
 * 源码树里<b>没有</b>可手工编辑的版本文件，因此“jar 里的版本”与 POM 不可能不一致；
 * 构建时刻由构建器写入（{@code build.time}，UTC 瞬时），运行期再转成本地时间展示。</p>
 *
 * <p><b>两个事实分开放</b>：{@link #version()} 只是项目版本号（例如 {@code 0.2.2}），
 * {@link #buildTime()} 才是“哪一次构建”。版本号里不掺构建时刻、也不掺 git 信息，
 * 这样 {@code --version} 的第一行在任何一次构建里都是同一个字符串。</p>
 *
 * <p><b>读不到时的降级</b>：一律给可读的 {@code unknown}，绝不把 {@code ${project.version}}
 * 这类未替换的占位符漏到用户看得见的输出里；版本信息缺失也不该让客户端起不来。</p>
 *
 * <p><b>输出的两行形态</b>：第一行 {@code [CLIENT] VERSION: …}，第二行
 * {@code [CLIENT] Build Time: …}；前导的 {@code [CLIENT]} 让它在日志里一眼看出是谁打印的。
 * 客户端名字（{@link #name()}）不出现在 {@code --version} 里 —— 横幅与 {@code --help} 抬头
 * 已经带着名字，{@code --version} 只回答“版本与构建时刻”这两个问题。</p>
 */
public final class BuildInfo {

    /** 本模块的 Maven 构件名：classpath 上有多份 build-info.properties 时靠它精确匹配。 */
    private static final String ARTIFACT = "slackerdb-dbcli";

    private static final org.slackerdb.common.version.BuildInfo INFO =
            org.slackerdb.common.version.BuildInfo.of(ARTIFACT);

    private BuildInfo() {
    }

    /** 客户端名字，例如 {@code dbcli (SlackerDB command line client)}（构建期声明为 {@code build.product}）。 */
    public static String name() {
        return INFO.name();
    }

    /** 项目版本号，例如 {@code 0.2.2}（不含构建时刻、不含 git 信息）；读不到时是 {@code unknown}。 */
    public static String version() {
        return INFO.version();
    }

    /** 本地构建时间（带偏移），例如 {@code 2026-10-20 23:13:25 +08:00}；读不到时是 {@code unknown}。 */
    public static String buildTime() {
        return INFO.buildTime();
    }

    /**
     * {@code --version} 的两行输出：第一行 {@code [CLIENT] VERSION: <项目版本号>}，
     * 第二行 {@code [CLIENT] Build Time: <本地时间 + 偏移>}。
     *
     * <p>构建时刻**只**出现在第二行：第一行是“哪个发行版本”（同一个版本在任何一次构建里都相同），
     * 第二行才是“哪一次构建”。picocli 的 {@code IVersionProvider} 用法见 {@link CliVersionProvider}；
     * 这里把行拼装放在一处，命令行与测试都只认这一个口径。</p>
     */
    public static String[] versionLines() {
        return new String[]{"[CLIENT] VERSION: " + version(), "[CLIENT] Build Time: " + buildTime()};
    }
}
