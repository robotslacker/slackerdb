package org.slackerdb.dbcli.sql;

import org.slackerdb.dbcli.assertion.ResultModel;
import org.slackerdb.dbcli.common.CommandOutcome;

import java.util.List;
import java.util.Map;

/**
 * SQL 执行层：执行引擎与"真正的数据库"之间**唯一**的接缝。
 *
 * <h2>为什么要有这个接口</h2>
 * <p>引擎（{@code engine} 包）的逻辑主体是"逐行累积 → 分析 → 分派 → 输出"，其中只有 SQL 那一条路
 * 需要数据库。把数据库访问收进本接口之后，引擎的语句累积、{@code _ASSERT}、{@code _WHENEVER}、
 * {@code _SPOOL}、{@code _START}、变量替换这些**行为性**契约都能在没有数据库的情况下用假实现单测
 * （见 {@code engine} 包的测试），不必依赖外部服务端。生产实现是 {@link JdbcSqlExecutor}。</p>
 *
 * <h2>契约</h2>
 * <ul>
 *   <li>{@link #execute(String)} <b>不抛异常</b>：SQL 异常收敛成 {@code errorCode=1} 的
 *       {@link ResultModel}（成功 0、SQL 异常 1），错误原文由 {@link #lastError()} 取；
 *       这样"上一条语句的结果"对 {@code _ASSERT} 始终是模型而不是异常；
 *       {@code status} 用 {@link SqlTableRenderer#statusLine} 生成（如 {@code 2 rows selected.}）。</li>
 *   <li>{@link #connect(Map)} 失败（URL 拼不出来、连不上）抛 {@link SqlExecutorException}，
 *       消息直接可打印。</li>
 *   <li>{@link #session(String, String)} 覆盖 {@code _SESSION} 的 5 个动作，返回
 *       {@link CommandOutcome}（状态行 / 结果集 / 错误），由引擎按同一条输出路径打印。</li>
 *   <li>状态文案一律**英文**（与 {@code compare}/{@code job}/{@code ssh} 一致）。</li>
 * </ul>
 */
public interface SqlExecutor extends AutoCloseable {

    /**
     * "无效连接串"的**唯一**文案：{@code _connect} 的取值既不是简写的
     * {@code <host>:<port>/<service>[?k=v]}，也不是
     * {@code [<user>[/<password>]@]jdbc:slackerdb://<host>:<port>/[<database>]}，
     * 也不是带 URL 正文的 {@code [<user>[/<password>]@]jdbc:<scheme>:<url>} 时报这一条，
     * 原文回显使用者写的取值。
     *
     * <p>放在接口上是因为执行层（{@link JdbcSqlExecutor#connect(Map)}）与引擎
     * （{@code ScriptRunner}：解析阶段就失败的 {@code _connect}）都要报它 ——
     * 两处各写一遍迟早会长出两种说法。</p>
     *
     * @param raw 使用者写的连接串原文（null 按空串处理）
     */
    static String invalidConnectionStringMessage(String raw) {
        return "Invalid connection string [" + (raw == null ? "" : raw) + "].";
    }

    /** 当前是否有一条可用的连接。 */
    boolean isConnected();

    /**
     * 按 {@code _CONNECT} 的 IR 打开连接（IR 键见 {@code SqlIrVisitor.visitConnect*}）。
     *
     * @throws SqlExecutorException URL 拼不出来或驱动报错（消息可直接打印）
     */
    void connect(Map<String, Object> ir);

    /** 关闭**当前**会话的连接；名字是引用语义（与 {@code SshSessionRegistry.disconnectCurrent} 同风格）。 */
    void disconnect();

    /**
     * {@code _SESSION} 的 5 个动作。
     *
     * @param action      IR 的 {@code action}：{@code SAVE}/{@code RESTORE}/{@code RELEASE}/{@code SAVEURL}/{@code SHOW}
     * @param sessionName 会话名（可为 null）
     */
    List<CommandOutcome> session(String action, String sessionName);

    /**
     * 执行一条 SQL 语句。
     *
     * @return 结果模型（成功 {@code errorCode=0}；SQL 异常 {@code errorCode=1} 且 {@code status=null}）
     */
    ResultModel execute(String statement);

    /**
     * 执行一条语句并返回它产生的**全部结果**（多个结果集按顺序）。
     *
     * <p>默认实现就是单结果：既有执行器（测试夹具、嵌入实现）不必改。
     * JDBC 实现覆盖它来处理 {@code CALL}/{@code DO} 这类多结果语句——
     * 以前只读第一个结果，后面的被静默丢弃。</p>
     */
    default List<ResultModel> executeAll(String statement) {
        return List.of(execute(statement));
    }

    /** 最近一次 {@link #execute(String)} 的错误原文（成功时为 null）。 */
    String lastError();

    /** {@code _SET AUTOCOMMIT}：可选能力，默认什么都不做。 */
    default void setAutoCommit(boolean autoCommit) {
    }

    /**
     * {@code _SET TIMEOUT n}：单条语句的服务端超时（秒；0 = 不限）。
     *
     * <p>为什么会话级而不是每条语句写一个：测试脚本里经常是"某一段等待类查询可能挂很久"，
     * 用 {@code _SET TIMEOUT 30} 把这一段包起来，比给每条语句加参数更可读；
     * 改动也是显式的（写在脚本里，看得见、能复核）。</p>
     *
     * <p>注意它依赖驱动的支持（JDBC {@code Statement.setQueryTimeout}）：驱动不实现时抛出的
     * {@code SQLFeatureNotSupportedException} 会照常变成一条可读的语句错误，不会静默忽略。</p>
     */
    default void setQueryTimeout(int seconds) {
    }

    /**
     * 取消**当前正在执行**的语句（控制台 Ctrl-C；作业中止也走同一条能力）。
     *
     * <p>没有在跑的语句时什么都不做。默认空实现：假执行器与"不支持取消"的实现不需要关心它。</p>
     */
    default void cancelCurrentStatement() {
    }

    @Override
    default void close() {
    }
}
