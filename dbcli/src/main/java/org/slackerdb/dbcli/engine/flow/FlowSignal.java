package org.slackerdb.dbcli.engine.flow;

/** 控制树解释执行的局部信号：只有最内层循环消费 BREAK/CONTINUE。 */
public enum FlowSignal {
    NONE,
    BREAK,
    CONTINUE,
    ABORT
}
