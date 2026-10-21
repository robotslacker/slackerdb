package org.slackerdb.dbcli.common;

import java.util.List;

/**
 * 一条脚本命令的执行结果项。
 *
 * <p>{@code SshCommandDispatcher} 的 {@code _SSH} 动作与 {@code CompareCommandDispatcher}
 * 都产出本类型，因此放在公共包。四种结果项：</p>
 * <ul>
 *   <li>{@link Status}：一行状态/输出文本，引擎按"状态行"输出。{@code text} 为 null 表示**无输出**。</li>
 *   <li>{@link Table}：结果集，引擎按"表格"输出。</li>
 *   <li>{@link Error}：错误，引擎据此计错误数并决定 {@code _WHENEVER ERROR} 行为。</li>
 *   <li>{@link Failure}：**既是一条正常状态行、又是一次失败**（见该类型的注释）。</li>
 * </ul>
 *
 * <p><b>为什么没有 {@code title}</b>：设置表的标题（如 {@code "Current compare settings:"}）本质是
 * "表头之上的一条状态行"，因此不需要单独的 title 字段：需要标题的调用方在 {@link Table} **前面**
 * 加一条 {@link Status}（见 {@code CompareCommandDispatcher} 的 {@code _COMPARE SET} 打印），
 * 保持本类型与 {@code _SSH} 一致。</p>
 */
public sealed interface CommandOutcome {

    /** 一行状态/输出文本；{@code text} 为 null 表示"无输出"。 */
    record Status(String text) implements CommandOutcome {
    }

    /** 结果集（SSH 的 {@code sftp_listdir}、{@code _COMPARE} 的差异表与设置表都会产生）。 */
    record Table(List<String> headers, List<List<Object>> rows) implements CommandOutcome {
    }

    /** 错误（引擎据此计错误数、决定 WHENEVER 行为）。 */
    record Error(String message) implements CommandOutcome {
    }

    /**
     * 一次失败，但它的文本仍是一条**正常状态行**。
     *
     * <p>典型例子：{@code _COMPARE} 的 {@code Compare failed.}、{@code _JOB WAIT} 等到的作业以非 0
     * 退出码结束（{@code Job [x] finished with exit code 5.}）。这类文本是输出契约的一部分（打 stdout、
     * 与外层工具/夹具比对），同时**必须**影响退出码与 {@code _WHENEVER ERROR}——
     * 把它塞进 {@link Status} 会让"子作业失败"静默通过（CI 绿灯骗人），
     * 塞进 {@link Error} 又会把状态行从 stdout 挪到 stderr（破坏输出契约）。</p>
     *
     * <p>引擎的处理是两条都做：状态行照旧打 stdout，同时计一次错误（错误文本进 stderr）。</p>
     */
    record Failure(String text) implements CommandOutcome {
    }
}
