/**
 * 作业（{@code _JOB}）：定义、启动、等待、中止、超时与跨进程会合。
 *
 * <h2>阅读顺序</h2>
 * <ol>
 *   <li>{@link org.slackerdb.dbcli.job.SqliteJobStore}：唯一真相——注册表、状态机、停止意图、
 *       超时、认领、定时点，全部在这一个 SQLite 库里；</li>
 *   <li>{@link org.slackerdb.dbcli.job.JobManager}：门面——校验、写库、启动监督者、等待、收尾；</li>
 *   <li>{@link org.slackerdb.dbcli.job.JobSupervisor}：每个作业一个监督线程，跑完执行体后写终态；</li>
 *   <li>{@link org.slackerdb.dbcli.job.Job} / {@link org.slackerdb.dbcli.job.JobRecord}：
 *       只读视图与行快照；</li>
 *   <li>{@link org.slackerdb.dbcli.job.LocalJobRuntime}：本地停止镜像与取消回调（不是状态来源）；</li>
 *   <li>执行层：{@code engine.ScriptJobExecutor}（线程）与
 *       {@link org.slackerdb.dbcli.job.ProcessJobExecutor}（独立进程）。</li>
 * </ol>
 *
 * <h2>关键约束</h2>
 * <ul>
 *   <li>线程作业与进程作业走**同一条**编排路径；差异只在 {@code JobExecutor}；</li>
 *   <li>所有状态迁移都是 SQL CAS；**终态只有一个写者**（监督者），停止方只写意图；</li>
 *   <li>作业按 scope 隔离（{@code root} / {@code root/<作业名>}），定时点不分 scope；</li>
 *   <li>每次运行固定 {@code WORK/dbcli_log.db}；启动阶段无论是否使用 {@code _JOB} 都创建，
 *       正常关闭不删除，下一次 root 清空 WORK 时回收；</li>
 *   <li><b>先落盘再置终态</b>：父脚本看到 {@code DONE}/{@code FAILED}/{@code TIMEOUT} 时
 *       {@code <作业名>.out} 一定完整；</li>
 *   <li>作业失败只**计入错误数**并报一行；进程退出码仍按 README §9.3；</li>
 *   <li>作业名同时是输出文件名与 scope 的一段：不能含路径分隔符或 {@code ..}；</li>
 *   <li>监督线程一律 <b>daemon</b>；管理器关闭只做"写意图 + 限时等待"，绝不拖住 JVM 退出。</li>
 * </ul>
 */
package org.slackerdb.dbcli.job;
