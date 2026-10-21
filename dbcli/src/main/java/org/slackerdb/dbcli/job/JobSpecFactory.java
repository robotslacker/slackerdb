package org.slackerdb.dbcli.job;

import java.util.Map;

/**
 * 作业定义工厂：把"{@code _JOB CREATE <名字> key=value…}"变成 {@link JobSpec}
 * （**脚本 + 变量 + 运行选项**）。线程模型下不再有"拼命令行"这一步，接口保留是为了可测：
 * 生产实现是 {@link DbcliJobSpecFactory}（{@code script} 必需、几个运行选项透传、其余当变量），
 * 测试实现可以给任意定义（例如"打印几行再退出"的假作业）。
 */
@FunctionalInterface
public interface JobSpecFactory {

    /**
     * 产出作业定义。
     *
     * @param jobName    作业名
     * @param parameters CREATE/SET 累积的参数
     * @return 作业定义（{@code workingDirectory} 由 {@link JobManager} 统一补上）
     * @throws JobException 参数不合法（例如缺 {@code script}）
     */
    JobSpec specFor(String jobName, Map<String, String> parameters);
}
