package org.slackerdb.dbcli.engine.flow;

import org.slackerdb.dbcli.assertion.ResultModel;

/**
 * 控制流对引擎的最小依赖：条件模型、变量展开、叶子执行、报错、退出/停止检查与进度输出。
 *
 * <p>刻意不暴露 {@code ScriptRunner} 的具体类型，这样 {@code ControlFlow} 可以在没有数据库、
 * 没有完整引擎的情况下单测；引擎侧只负责实现这几个动作。</p>
 */
public interface FlowHost {

    /** 与 {@code _ASSERT} 相同的结果模型（最近 SQL 结果 + 变量 + RUN/JOB 状态）。 */
    ResultModel conditionModel();

    /**
     * 展开条件文本里的 {@code ${name}}。
     *
     * @return 展开后的文本；展开失败时实现方已经报错并返回 {@code null}
     */
    String resolveText(String text);

    /** 执行一条叶子语句（不重复 echo；块体在读取时已经 echo 过一次）。 */
    void executeLeaf(String rawText);

    /** 报告一次普通错误（打印 + 计数 + {@code _WHENEVER}）。 */
    void reportError(String message);

    /** 是否已经请求退出（{@code _EXIT} / {@code _WHENEVER ERROR EXIT}）。 */
    boolean exitRequested();

    /** 作业停止检查；已请求停止时抛 {@code ScriptStoppedException}。 */
    void checkStopRequested();

    /** 一条状态行（循环进度）；由引擎按 {@code _SET FEEDBACK}/{@code LOOPTRACE} 决定是否打印。 */
    void printStatus(String line);
}
