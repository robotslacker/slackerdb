package org.slackerdb.dbcli.job;

import org.slackerdb.dbcli.common.UserNumber;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个作业的定义：**要跑的脚本 + 脚本里可见的变量 + 运行选项 + 工作目录**。
 *
 * <p>与线程模型配套：作业不再是"一条命令行"，而是"在同一 JVM 里跑另一份脚本"。参数因此只有两种去处：</p>
 * <ul>
 *   <li>{@link #variables()}：子脚本里用 {@code ${名}} 取到的变量（{@code _SET @名} 也可改）；</li>
 *   <li>{@link #options()}：**运行选项**（{@code logon} 先在作业里连一次库、{@code profile} 先跑一段准备脚本、
 *       {@code logfile} 记作业自己的语句日志、{@code reference} 做 golden 比对）；
 *       这些不是变量，与命令行上的同名选项语义一致。</li>
 * </ul>
 *
 * <p>{@code mode} 与 {@code timeout} 也放在 {@link #options()} 里：它们是**编排参数**
 * （"父进程怎么管这个作业"），既不是子脚本的变量，也不透传给子进程的命令行。</p>
 *
 * @param name             作业名（脚本里引用它）
 * @param script           要跑的脚本（相对路径按 {@link #workingDirectory()} 解析）
 * @param variables        子脚本可见的变量（保持插入顺序）
 * @param options          运行选项（保持插入顺序；含 {@code mode} 与 {@code timeout}）
 * @param workingDirectory 作业定义的基准目录（相对 {@code script} 与相对选项路径按它解析；
 *                         null 表示"进程当前目录"）
 */
public record JobSpec(String name, String script, Map<String, String> variables, Map<String, String> options,
                      Path workingDirectory) {

    /** 作业运行时限的选项名（**秒**，允许小数；缺省表示不限时）。 */
    public static final String OPTION_TIMEOUT = "timeout";

    public JobSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("作业名不能为空");
        }
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("作业脚本不能为空");
        }
        variables = ordered(variables);
        options = ordered(options);
    }

    /** 执行层（{@code mode=thread} 默认 / {@code mode=process}）。 */
    public JobMode mode() {
        return JobMode.fromOption(options.get(JobMode.OPTION));
    }

    /**
     * 运行时限（**秒**，允许小数）；{@code null} 表示不限时。
     *
     * <p>语义在两种模式下有区别：进程模式是**硬超时**
     * （到点杀整棵进程树），线程模式只能**协作式**（到点请求停止 + 中断线程）。</p>
     *
     * @throws UserNumber.InvalidNumberException 不是**正的有限秒数**
     *         （文案 {@code Invalid job [<名字>] timeout [...]}；NaN/Infinity 也在这里挡住）
     */
    public Double timeoutSeconds() {
        String value = options.get(OPTION_TIMEOUT);
        if (value == null || value.isBlank()) {
            return null;
        }
        return UserNumber.positiveSeconds("job [" + name + "] timeout", value);
    }

    /** 换一个基准目录（{@code _JOB CREATE/SET} 时由管理器补上它自己的工作目录）。 */
    public JobSpec withWorkingDirectory(Path directory) {
        return new JobSpec(name, script, variables, options, directory);
    }

    /** 只读、定序的副本：{@code Map.copyOf} 不保证迭代顺序，而参数顺序是给人看的。 */
    private static Map<String, String> ordered(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
