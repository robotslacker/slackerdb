package org.slackerdb.dbcli.engine.flow;

/**
 * 控制流安全上限。
 *
 * @param maxDepth      块嵌套深度上限（执行靠递归，必须防栈溢出）
 * @param maxIterations 单个循环的最大轮数（防脚本跑飞；不是超时机制）
 */
public record FlowLimits(int maxDepth, int maxIterations) {

    public static FlowLimits defaults() {
        return new FlowLimits(64, 1_000_000);
    }
}
