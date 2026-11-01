package org.slackerdb.dbcli.engine.flow;

import java.util.List;

/** 控制树节点：捕获期建树，关根块后解释执行。 */
public sealed interface FlowNode
        permits FlowNode.StatementNode, FlowNode.IfNode, FlowNode.LoopNode,
                FlowNode.BreakNode, FlowNode.ContinueNode {

    /** 一条已经完整切出的叶子语句原文（执行时再展开变量/分析）。 */
    record StatementNode(String rawText) implements FlowNode {
    }

    /** 一个 IF 分支；{@code isElse=true} 时 {@code condition} 为 null。 */
    record IfBranch(String condition, boolean isElse, boolean failed, List<FlowNode> body) {
    }

    record IfNode(List<IfBranch> branches) implements FlowNode {
    }

    record LoopNode(LoopSpec spec, List<FlowNode> body) implements FlowNode {
    }

    record BreakNode() implements FlowNode {
    }

    record ContinueNode() implements FlowNode {
    }
}
