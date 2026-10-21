package org.slackerdb.dbcli;

import picocli.CommandLine.IVersionProvider;

/**
 * {@code --version} 的版本信息提供者：**唯一**提供版本信息的入口（口径见 {@link BuildInfo}）。
 *
 * <p>{@code --help} 里<b>不</b>打印版本与构建时间（用户只需要用法时不该被版本串干扰），
 * 控制台横幅也<b>不</b>打印（只打印客户端名字），因此版本信息只有这里一处输出。</p>
 *
 * <p>本类只做接线，取值与格式化都在 {@link BuildInfo} 里（读不到时是 {@code unknown}，
 * 不会漏出 {@code ${...}} 占位符，也不会因为资源缺失而抛异常）。</p>
 */
public class CliVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        return BuildInfo.versionLines();
    }
}
