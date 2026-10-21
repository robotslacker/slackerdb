package org.slackerdb.dbcli.job;

/**
 * 一次作业执行的输入（执行体视角）：**共享的管理器 + 作业实例 + 输出目标**。
 *
 * <p>{@link JobContext#manager()} 就是父脚本的那一个 {@link JobManager}——这是屏障可用的根本原因：
 * 作业线程里再写 {@code _JOB CREATE/START}（孙作业）也注册在同一个管理器上，
 * 于是"任意深度的作业都能互相放行/等待"。</p>
 *
 * <p>{@link JobContext#job()} 让执行体拿到作业的运行身份（状态、`<作业名>.out/.err` 的路径、
 * 登记子进程 pid），{@link #spec()} 与 {@link #control()} 是它的两个常用视图。</p>
 *
 * @param manager 共享的作业管理器
 * @param job     作业实例（定义 + 运行状态 + 输出文件路径 + 停止控制）
 * @param output  作业自己的输出目标（线程模式 = {@code <作业名>.out/.err} 的打印器；
 *                进程模式 = 丢弃型，真正的输出由子进程直接重定向写入）
 */
public record JobContext(JobManager manager, Job job, JobOutput output) {

    /** 作业定义。 */
    public JobSpec spec() {
        return job.spec();
    }

    /** 协作式停止控制（就是作业实例自己）。 */
    public JobControl control() {
        return job;
    }
}
