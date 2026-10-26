/**
 * 作业（{@code _JOB}）：定义、启动、等待、中止、超时与跨进程会合。
 *
 * <h2>阅读顺序</h2>
 * <ol>
 *   <li>{@link org.slackerdb.dbcli.job.JobManager}：注册表 + 状态推进 + 名字解析 + 收尾；</li>
 *   <li>{@link org.slackerdb.dbcli.job.Job}：单个作业的状态机与停止控制（{@code JobControl}）；</li>
 *   <li>{@link org.slackerdb.dbcli.job.JobCommandDispatcher}：{@code _JOB} 的 IR → 结果项与冻结文案；</li>
 *   <li>执行层：{@code engine.ScriptJobExecutor}（线程模式）与
 *       {@link org.slackerdb.dbcli.job.ProcessJobExecutor}（{@code mode=process} 起子进程）；</li>
 *   <li>{@code rendezvous} 包：定时点/屏障的落地后端。</li>
 * </ol>
 *
 * <h2>关键约束</h2>
 * <ul>
 *   <li>作业线程一律 <b>daemon</b>；管理器关闭只做"请求停止 + 限时 join"，绝不拖住 JVM 退出；</li>
 *   <li><b>先落盘再置终态</b>：父脚本看到 {@code DONE}/{@code FAILED}/{@code TIMEOUT} 时
 *       {@code <作业名>.out} 一定完整；</li>
 *   <li>作业失败只**计入错误数**并报一行；进程退出码仍按 README §9.3（脚本内部错误默认不改退出码），
 *       要门禁就写 {@code _WHENEVER ERROR EXIT 1}；</li>
 *   <li>作业名同时是输出文件名：不能含路径分隔符或 {@code ..}。</li>
 * </ul>
 */
package org.slackerdb.dbcli.job;
