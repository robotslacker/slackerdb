package org.slackerdb.dbcli.job;

/**
 * 作业执行体：**在当前线程**里把 {@link JobSpec} 跑完，返回作业退出码。
 *
 * <p>{@code job} 包只定义"跑什么"和"谁在管"，具体怎么跑由 {@code engine} 侧实现
 * （{@code engine.ScriptJobExecutor}：为作业新建独立 {@code ScriptContext} + {@code ScriptRunner}）。
 * 这样依赖方向仍然是 {@code engine → job}，{@code job} 不知道脚本引擎的存在。</p>
 *
 * <p><b>契约</b>：</p>
 * <ul>
 *   <li>在**作业线程**里被调用；不得把异常抛给别的线程（{@link JobManager} 会兜住并把作业置为
 *       {@code FAILED}，错误写进 {@code <作业名>.out}）；</li>
 *   <li>返回值即作业退出码：{@code 0} → {@code DONE}，非 0 → {@code FAILED}；</li>
 *   <li>长操作要能被打断：{@link JobContext#control()} 给出停止请求与取消回调；</li>
 *   <li>不需要关闭 {@link JobContext#output()}（{@link JobManager} 负责收尾）。</li>
 * </ul>
 */
@FunctionalInterface
public interface JobExecutor {

    /** 跑完这个作业并返回退出码。 */
    int execute(JobContext context) throws Exception;
}
