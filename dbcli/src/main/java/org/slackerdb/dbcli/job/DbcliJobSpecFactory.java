package org.slackerdb.dbcli.job;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 生产用的作业定义工厂：把 {@code _JOB CREATE <名字> key=value…} 拆成"脚本 + 变量 + 运行选项"。
 *
 * <h2>参数映射（固定、可预期；也是脚本可见语义的一部分）</h2>
 * <table border="1">
 *   <caption>参数的去处</caption>
 *   <tr><th>参数</th><th>去处</th><th>子脚本里怎么看</th></tr>
 *   <tr><td>{@code script}（必需）</td><td>{@link JobSpec#script()}</td>
 *       <td>作业线程跑的就是它</td></tr>
 *   <tr><td>{@code logon}</td><td>{@link JobSpec#options()}</td>
 *       <td>开头先连一次库（等价于脚本第一行写 {@code _connect <值>}）</td></tr>
 *   <tr><td>{@code profile}</td><td>{@link JobSpec#options()}</td>
 *       <td>脚本之前先跑一遍这个准备脚本（也是预置变量的推荐做法）</td></tr>
 *   <tr><td>{@code logfile}</td><td>{@link JobSpec#options()}</td>
 *       <td>作业自己的语句级执行日志</td></tr>
 *   <tr><td>{@code reference}</td><td>{@link JobSpec#options()}</td>
 *       <td>作业 stdout 的 golden 比对（不一致 → 作业退出码 1）</td></tr>
 *   <tr><td>{@code mode}</td><td>编排参数（{@link JobSpec#options()}）</td>
 *       <td>{@code thread}（默认，同 JVM 线程）/ {@code process}（独立子进程）；**不透传**给子进程</td></tr>
 *   <tr><td>{@code timeout}</td><td>编排参数（{@link JobSpec#options()}）</td>
 *       <td>作业运行时限（**秒**，允许小数）：到点进程模式杀进程树、线程模式请求停止 + 中断；
 *           终态 {@code TIMEOUT}。**不透传**给子进程</td></tr>
 *   <tr><td>其余 {@code k=v}</td><td>{@link JobSpec#variables()}</td>
 *       <td>{@code ${k}}（{@code _SET @k} 也能改）</td></tr>
 * </table>
 *
 * <h2>参数名大小写不敏感</h2>
 * <p>上面这张表里的**参数名一律大小写不敏感**：{@code SCRIPT=} / {@code Script=} / {@code script=}
 * 是同一个参数，{@code MODE=process} 与 {@code Mode=Process} 同样生效，{@code TIMEOUT=30} 也是。
 * 这与"命令关键字一律大小写不敏感"是同一条纪律。</p>
 *
 * <p><b>因此参数名不能当同名变量用</b>：{@code MODE=process} 会被当成编排参数吃掉，
 * 既不会出现在 {@link JobSpec#variables()} 里，子脚本也看不到 {@code ${MODE}}。要一个同名变量，
 * 换一个不被占用的变量名即可（{@link JobSpec#variables()} 里的键**区分大小写**，
 * {@code MyMode=x} 与 {@code mymode=y} 是两个不同的变量）。</p>
 *
 * <p>同名参数给了两种写法时**以第一次出现的写法为准**（{@code LOGON=a logon=b} → {@code a}）：
 * 参数本身是"一个名字一个取值"，重复给出属于脚本笔误，先出现的那份最接近写作者的意图。</p>
 *
 * <p>运行选项**不**同时变成变量：它们走 {@code --logon} 一类参数，不产生 {@code --var}。
 * {@code mode}/{@code timeout} 同理：它们是"父进程怎么管这个作业"，
 * 既不是子脚本的变量，也不会出现在子进程的命令行上（见 {@code SelfJobProcessLauncher}）。</p>
 */
public final class DbcliJobSpecFactory implements JobSpecFactory {

    /** 必需的参数名（规范形 = 小写）。 */
    private static final String SCRIPT_OPTION = "script";

    /**
     * 会被当成**运行选项**（而不是变量）的参数名（规范形 = 小写）。
     *
     * <p>前四个按同名命令行选项透传给 {@code mode=process} 的子进程；
     * {@code mode}/{@code timeout} 是**编排参数**，只由父进程解释，不透传。</p>
     */
    private static final List<String> RUN_OPTIONS =
            List.of("logon", "profile", "logfile", "reference", JobMode.OPTION,
                    JobSpec.OPTION_TIMEOUT);

    @Override
    public JobSpec specFor(String jobName, Map<String, String> parameters) {
        Map<String, String> remaining = new LinkedHashMap<>();
        Map<String, String> options = new LinkedHashMap<>();
        String script = null;
        if (parameters != null) {
            for (Map.Entry<String, String> parameter : parameters.entrySet()) {
                String canonical = canonicalName(parameter.getKey());
                if (canonical == null) {
                    // 不认识的参数照原样当变量：**变量名区分大小写不变**
                    remaining.put(parameter.getKey(), parameter.getValue());
                    continue;
                }
                if (SCRIPT_OPTION.equals(canonical)) {
                    if (script == null) {
                        script = parameter.getValue();
                    }
                    continue;
                }
                // 认识的参数名一律被"吃掉"：它们**不再**同时成为同名变量（见类注释的行为变化）
                if (!options.containsKey(canonical)) {
                    options.put(canonical, parameter.getValue());
                }
            }
        }
        if (script == null || script.isBlank()) {
            throw new JobException("Job [" + jobName + "] needs a 'script' parameter, "
                    + "for example: _JOB CREATE " + jobName + " script=child.sql");
        }
        return new JobSpec(jobName, script, remaining, options, null);
    }

    /**
     * 参数名的规范形（小写）；**不认识**的参数返回 {@code null}（它们照原样成为脚本变量）。
     *
     * <p>用 {@link Locale#ROOT} 而不是默认 Locale：土耳其语环境下 {@code "I"} 的
     * {@code toLowerCase} 会得到无点的 {@code "ı"}，同一个脚本在不同机器上就会认不出 {@code MODE}。
     * 会合点名字的规范化（{@code rendezvous.RendezvousBackend.normalizePoint}）出于同样的原因也钉死 ROOT。</p>
     */
    private static String canonicalName(String name) {
        if (name == null) {
            return null;
        }
        String canonical = name.strip().toLowerCase(Locale.ROOT);
        return SCRIPT_OPTION.equals(canonical) || RUN_OPTIONS.contains(canonical) ? canonical : null;
    }
}
