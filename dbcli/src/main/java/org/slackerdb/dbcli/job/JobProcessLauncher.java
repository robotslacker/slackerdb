package org.slackerdb.dbcli.job;

import java.util.List;

/**
 * 把 {@code mode=process} 的作业定义拼成"再起一个 dbcli 跑这份脚本"的命令行。
 *
 * <p>接口化是为了可测：调度逻辑（启动/等待/超时/强杀/收尾）不该依赖"子进程必须真的是 dbcli"，
 * 测试可以给一个"读脚本、打印几行、按需挂住"的任意命令。生产实现是
 * {@link SelfJobProcessLauncher}（当前 JVM 的 java + 当前 dbcli 主程序）。</p>
 *
 * <p><b>不负责的事</b>：工作目录、stdout/stderr 重定向、环境变量都由
 * {@link ProcessJobExecutor} 统一设置（那些是"怎么跑进程"，不是"跑哪个命令"）。</p>
 */
@FunctionalInterface
public interface JobProcessLauncher {

    /** 拼出子进程的命令行（第一个元素是可执行文件）。 */
    List<String> commandFor(JobSpec spec);
}
