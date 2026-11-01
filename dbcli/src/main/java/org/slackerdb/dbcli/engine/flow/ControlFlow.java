package org.slackerdb.dbcli.engine.flow;

import org.slackerdb.dbcli.assertion.ConditionEvaluator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一个脚本会话的控制流状态机：识别单行结构标记、捕获块体、建树，并在根块关闭后解释执行。
 *
 * <p>语法层一次只解析一条语句（{@code prog : command EOF}），所以块配对不能放在 ANTLR 里；
 * 这里按“语句”接收输入，负责：</p>
 * <ol>
 *   <li>把 {@code _IF}/{@code _ELSEIF}/{@code _ELSE}/{@code _ENDIF} 与
 *       {@code _LOOP}/{@code _ENDLOOP} 组织成 {@link FlowNode} 树；</li>
 *   <li>结构错误可读、可恢复（failed/discard 帧只配对，不执行）；</li>
 *   <li>关闭根块后按树执行，循环按轮次重放并输出 {@code Loop iteration ...} 进度行。</li>
 * </ol>
 *
 * <p>每个 {@code ScriptSession} 一个实例：{@code _START} 子脚本、作业、控制台互不串块。</p>
 */
public final class ControlFlow {

    private static final Set<String> COMMANDS = Set.of(
            "IF", "ELSEIF", "ELSE", "ENDIF", "LOOP", "ENDLOOP");

    private final FlowHost host;
    private final FlowLimits limits;

    private final Deque<Frame> frames = new ArrayDeque<>();

    /** 深度超限只报一次，避免一行一个错误。 */
    private boolean depthReported;

    public ControlFlow(FlowHost host) {
        this(host, FlowLimits.defaults());
    }

    ControlFlow(FlowHost host, FlowLimits limits) {
        this.host = host;
        this.limits = limits;
    }

    // ------------------------------------------------------------------ 查询

    /** 这条语句是不是控制结构标记（参数是首词，如 {@code _IF}）。 */
    public static boolean isControlCommand(String firstWord) {
        return controlName(firstWord) != null;
    }

    /** 帮助/一致性测试使用的结构命令名（不带下划线）。 */
    public static List<String> commandNames() {
        return List.of("IF", "ELSEIF", "ELSE", "ENDIF", "LOOP", "ENDLOOP");
    }

    public static boolean supports(String name) {
        return name != null && COMMANDS.contains(name.toUpperCase(Locale.ROOT));
    }

    public boolean isCapturing() {
        return !frames.isEmpty();
    }

    public boolean isOpen() {
        return !frames.isEmpty();
    }

    // ------------------------------------------------------------------ 捕获

    /**
     * 接收一条已分析的结构命令。
     *
     * @param raw           语句原文（用于错误文案/动作恢复）
     * @param ir            分析器 IR（可能为 null）
     * @param readableError 访问器给出的可读错误（解析层错误不要传进来）
     * @param parserError   {@code SqlAnalyzer.Result.parserError()}：true 时忽略 readableError
     */
    public void accept(String raw, Map<String, Object> ir, String readableError, boolean parserError) {
        String name = controlName(firstWord(raw));
        if (name == null) {
            return;
        }
        switch (name) {
            case "IF" -> acceptIf(ir, readableError, parserError);
            case "ELSEIF" -> acceptElseIf(ir, readableError, parserError);
            case "ELSE" -> acceptElse(parserError);
            case "ENDIF" -> acceptEndIf(parserError);
            case "LOOP" -> acceptLoop(raw, ir, readableError, parserError);
            case "ENDLOOP" -> acceptEndLoop(parserError);
            default -> {
                // unreachable
            }
        }
    }

    /** 块内普通语句：挂到当前帧（失败/丢弃帧里直接丢）。 */
    public void appendStatement(String rawText) {
        append(new FlowNode.StatementNode(rawText));
    }

    private void acceptIf(Map<String, Object> ir, String readableError, boolean parserError) {
        String expression = text(ir, "expression");
        boolean valid = !parserError && readableError == null
                && "IF".equals(nameOf(ir)) && expression != null && !expression.isBlank();
        IfFrame frame = new IfFrame();
        if (valid) {
            frame.startBranch(expression, false, false);
        } else {
            host.reportError(message(readableError, parserError,
                    "_IF requires a {% <left> <op> <right> %} condition."));
            frame.failed = true;
            frame.startBranch(null, false, true);
        }
        push(frame);
    }

    private void acceptElseIf(Map<String, Object> ir, String readableError, boolean parserError) {
        IfFrame frame = topIf();
        if (frame == null) {
            host.reportError("_ELSEIF without a matching _IF.");
            return;
        }
        if (frame.sawElse) {
            host.reportError("_ELSEIF cannot follow _ELSE.");
            return;
        }
        String expression = text(ir, "expression");
        boolean valid = !parserError && readableError == null
                && "ELSEIF".equals(nameOf(ir)) && expression != null && !expression.isBlank();
        if (!valid) {
            host.reportError(message(readableError, parserError,
                    "_ELSEIF requires a {% <left> <op> <right> %} condition."));
            frame.startBranch(null, false, true);
            return;
        }
        frame.startBranch(expression, false, false);
    }

    private void acceptElse(boolean parserError) {
        IfFrame frame = topIf();
        if (frame == null) {
            host.reportError("_ELSE without a matching _IF.");
            return;
        }
        if (frame.sawElse) {
            host.reportError("_ELSE must be the last branch of an _IF block.");
            return;
        }
        if (parserError) {
            host.reportError("_ELSE does not take arguments.");
        }
        frame.sawElse = true;
        frame.startBranch(null, true, false);
    }

    private void acceptEndIf(boolean parserError) {
        Frame top = frames.peek();
        if (!(top instanceof IfFrame frame)) {
            if (top instanceof LoopFrame) {
                host.reportError("_ENDIF cannot close an open _LOOP (use _ENDLOOP).");
            } else {
                host.reportError("_ENDIF without a matching _IF.");
            }
            return;
        }
        if (parserError) {
            host.reportError("_ENDIF does not take arguments.");
        }
        frames.pop();
        if (frame.failed || frame.discard) {
            return;
        }
        List<FlowNode.IfBranch> branches = new ArrayList<>(frame.branches.size());
        for (IfBranchBuilder branch : frame.branches) {
            branches.add(new FlowNode.IfBranch(branch.condition, branch.isElse, branch.failed,
                    List.copyOf(branch.nodes)));
        }
        attachOrExecute(new FlowNode.IfNode(List.copyOf(branches)));
    }

    private void acceptLoop(String raw, Map<String, Object> ir, String readableError, boolean parserError) {
        String action = text(ir, "action");
        if ("BREAK".equalsIgnoreCase(action) || "CONTINUE".equalsIgnoreCase(action)) {
            if (parserError) {
                host.reportError("_LOOP " + action.toUpperCase(Locale.ROOT) + " does not take arguments.");
            }
            append("BREAK".equalsIgnoreCase(action) ? new FlowNode.BreakNode() : new FlowNode.ContinueNode());
            return;
        }

        boolean valid = !parserError && readableError == null
                && "LOOP".equals(nameOf(ir)) && "BLOCK".equalsIgnoreCase(action);
        if (!valid) {
            String actionWord = secondWord(raw);
            if ("BREAK".equalsIgnoreCase(actionWord)) {
                host.reportError(message(readableError, parserError, "_LOOP BREAK does not take arguments."));
                append(new FlowNode.BreakNode());
                return;
            }
            if ("CONTINUE".equalsIgnoreCase(actionWord)) {
                host.reportError(message(readableError, parserError, "_LOOP CONTINUE does not take arguments."));
                append(new FlowNode.ContinueNode());
                return;
            }
            host.reportError(message(readableError, parserError,
                    "Invalid _LOOP header [" + header(raw) + "]."));
            pushFailedLoop();
            return;
        }

        LoopSpec spec = loopSpec(ir);
        if (spec == null) {
            host.reportError("Invalid _LOOP header [" + header(raw) + "].");
            pushFailedLoop();
            return;
        }
        if (spec.mode() == LoopSpec.Mode.COUNT && spec.count() > limits.maxIterations()) {
            host.reportError("Loop count [" + spec.count() + "] exceeds the limit ["
                    + limits.maxIterations() + "].");
            pushFailedLoop();
            return;
        }

        LoopFrame frame = new LoopFrame();
        frame.spec = spec;
        push(frame);
    }

    private void acceptEndLoop(boolean parserError) {
        Frame top = frames.peek();
        if (!(top instanceof LoopFrame frame)) {
            if (top instanceof IfFrame) {
                host.reportError("_ENDLOOP cannot close an open _IF (use _ENDIF).");
            } else {
                host.reportError("_ENDLOOP without a matching _LOOP.");
            }
            return;
        }
        if (parserError) {
            host.reportError("_ENDLOOP does not take arguments.");
        }
        frames.pop();
        if (frame.failed || frame.discard) {
            return;
        }
        attachOrExecute(new FlowNode.LoopNode(frame.spec, List.copyOf(frame.body)));
    }

    // ------------------------------------------------------------------ 帧栈

    private void push(IfFrame frame) {
        checkDepthAndMark(frame);
        frames.push(frame);
    }

    private void push(LoopFrame frame) {
        checkDepthAndMark(frame);
        frames.push(frame);
    }

    private void pushFailedLoop() {
        LoopFrame frame = new LoopFrame();
        frame.failed = true;
        frame.spec = LoopSpec.forever();
        push(frame);
    }

    private void checkDepthAndMark(Frame frame) {
        if (frames.size() < limits.maxDepth()) {
            return;
        }
        frame.markDiscard();
        if (!depthReported) {
            depthReported = true;
            host.reportError("Block nesting is too deep (limit " + limits.maxDepth() + ").");
        }
    }

    private IfFrame topIf() {
        return frames.peek() instanceof IfFrame frame ? frame : null;
    }

    private void append(FlowNode node) {
        if (frames.isEmpty()) {
            if (node instanceof FlowNode.BreakNode) {
                host.reportError("_LOOP BREAK used outside a loop.");
            } else if (node instanceof FlowNode.ContinueNode) {
                host.reportError("_LOOP CONTINUE used outside a loop.");
            }
            return;
        }
        Frame top = frames.peek();
        if (top.discard() || top.failed()) {
            return;
        }
        if (top instanceof IfFrame frame) {
            if (frame.current != null) {
                frame.current.nodes.add(node);
            }
        } else if (top instanceof LoopFrame frame) {
            frame.body.add(node);
        }
    }

    private void attachOrExecute(FlowNode node) {
        if (frames.isEmpty()) {
            execute(node);
        } else {
            append(node);
        }
    }

    // ------------------------------------------------------------------ 结束/重置

    /** 脚本/会话结束：报告仍未关闭的块，丢弃半截块（不执行）。 */
    public void finish() {
        if (host.exitRequested()) {
            reset();
            return;
        }
        while (!frames.isEmpty()) {
            Frame frame = frames.pop();
            if (host.exitRequested()) {
                break;
            }
            if (frame instanceof IfFrame) {
                host.reportError("_IF requires _ENDIF before the end of the script.");
            } else {
                host.reportError("_LOOP requires _ENDLOOP before the end of the script.");
            }
        }
        depthReported = false;
    }

    /** Ctrl-C / 显式退出：丢弃当前块。 */
    public void reset() {
        frames.clear();
        depthReported = false;
    }

    // ------------------------------------------------------------------ 执行

    private void execute(FlowNode root) {
        FlowSignal signal = executeNode(root, 1);
        if (signal == FlowSignal.BREAK) {
            host.reportError("_LOOP BREAK used outside a loop.");
        } else if (signal == FlowSignal.CONTINUE) {
            host.reportError("_LOOP CONTINUE used outside a loop.");
        }
    }

    private FlowSignal executeNodes(List<FlowNode> nodes, int depth) {
        for (FlowNode node : nodes) {
            host.checkStopRequested();
            if (host.exitRequested()) {
                return FlowSignal.ABORT;
            }
            FlowSignal signal = executeNode(node, depth);
            if (signal != FlowSignal.NONE) {
                return signal;
            }
        }
        return FlowSignal.NONE;
    }

    private FlowSignal executeNode(FlowNode node, int depth) {
        if (node instanceof FlowNode.StatementNode statement) {
            host.executeLeaf(statement.rawText());
            return host.exitRequested() ? FlowSignal.ABORT : FlowSignal.NONE;
        }
        if (node instanceof FlowNode.IfNode ifNode) {
            return executeIf(ifNode, depth);
        }
        if (node instanceof FlowNode.LoopNode loopNode) {
            return executeLoop(loopNode, depth);
        }
        if (node instanceof FlowNode.BreakNode) {
            return FlowSignal.BREAK;
        }
        if (node instanceof FlowNode.ContinueNode) {
            return FlowSignal.CONTINUE;
        }
        return FlowSignal.NONE;
    }

    private FlowSignal executeIf(FlowNode.IfNode ifNode, int depth) {
        for (FlowNode.IfBranch branch : ifNode.branches()) {
            if (branch.failed()) {
                // 条件在读取时已报错：整块放弃（不再尝试后面分支）
                return FlowSignal.NONE;
            }
            boolean take;
            if (branch.isElse()) {
                take = true;
            } else {
                ConditionOutcome outcome = evaluate(branch.condition(), "If");
                if (outcome.error()) {
                    return host.exitRequested() ? FlowSignal.ABORT : FlowSignal.NONE;
                }
                take = outcome.value();
            }
            if (take) {
                return executeNodes(branch.body(), depth);
            }
        }
        return FlowSignal.NONE;
    }

    private FlowSignal executeLoop(FlowNode.LoopNode loopNode, int depth) {
        LoopSpec spec = loopNode.spec();
        Integer total = spec.mode() == LoopSpec.Mode.COUNT ? Integer.valueOf(spec.count()) : null;

        if (spec.mode() == LoopSpec.Mode.COUNT && spec.count() == 0) {
            traceSkipped(depth);
            return FlowSignal.NONE;
        }

        int iteration = 0;
        while (true) {
            host.checkStopRequested();
            if (host.exitRequested()) {
                return FlowSignal.ABORT;
            }
            if (spec.mode() == LoopSpec.Mode.COUNT && iteration >= spec.count()) {
                return FlowSignal.NONE;
            }
            if (spec.mode() == LoopSpec.Mode.WHILE) {
                ConditionOutcome outcome = evaluate(spec.condition(), "Loop");
                if (outcome.error()) {
                    return host.exitRequested() ? FlowSignal.ABORT : FlowSignal.NONE;
                }
                if (!outcome.value()) {
                    if (iteration == 0) {
                        traceSkipped(depth);
                    }
                    return FlowSignal.NONE;
                }
            }
            if (iteration >= limits.maxIterations()) {
                host.reportError("Loop iteration limit (" + limits.maxIterations()
                        + ") exceeded; the loop was stopped.");
                return host.exitRequested() ? FlowSignal.ABORT : FlowSignal.NONE;
            }

            iteration = iteration + 1;
            traceIteration(depth, iteration, total);

            FlowSignal signal = executeNodes(loopNode.body(), depth + 1);
            if (signal == FlowSignal.ABORT) {
                return FlowSignal.ABORT;
            }
            if (signal == FlowSignal.BREAK) {
                return FlowSignal.NONE;
            }
            if (host.exitRequested()) {
                return FlowSignal.ABORT;
            }

            if (spec.mode() == LoopSpec.Mode.UNTIL) {
                ConditionOutcome outcome = evaluate(spec.condition(), "Loop");
                if (outcome.error()) {
                    return host.exitRequested() ? FlowSignal.ABORT : FlowSignal.NONE;
                }
                if (outcome.value()) {
                    return FlowSignal.NONE;
                }
            }
        }
    }

    private ConditionOutcome evaluate(String expression, String label) {
        String resolved = host.resolveText(expression);
        if (resolved == null) {
            return new ConditionOutcome(false, true);
        }
        ConditionEvaluator.Condition condition =
                ConditionEvaluator.evaluate(resolved, host.conditionModel());
        if (!condition.ok()) {
            host.reportError(label + " condition error: [" + condition.error() + "].");
            return new ConditionOutcome(false, true);
        }
        return new ConditionOutcome(condition.value(), false);
    }

    private void traceIteration(int depth, int iteration, Integer total) {
        String totalText = total == null ? "" : " of " + total;
        String depthText = depth > 1 ? " (depth " + depth + ")" : "";
        host.printStatus("Loop iteration " + iteration + totalText + depthText + ".");
    }

    private void traceSkipped(int depth) {
        String depthText = depth > 1 ? ", depth " + depth : "";
        host.printStatus("Loop did not execute (0 iterations" + depthText + ").");
    }

    // ------------------------------------------------------------------ 工具

    private LoopSpec loopSpec(Map<String, Object> ir) {
        String mode = text(ir, "mode");
        if (mode == null) {
            return null;
        }
        return switch (mode.toUpperCase(Locale.ROOT)) {
            case "COUNT" -> LoopSpec.count(intValue(ir.get("count")));
            case "WHILE" -> {
                String expression = text(ir, "expression");
                yield expression == null || expression.isBlank() ? null : LoopSpec.whileTrue(expression);
            }
            case "UNTIL" -> {
                String expression = text(ir, "expression");
                yield expression == null || expression.isBlank() ? null : LoopSpec.until(expression);
            }
            case "FOREVER" -> LoopSpec.forever();
            default -> null;
        };
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String nameOf(Map<String, Object> ir) {
        return text(ir, "name");
    }

    private static String text(Map<String, Object> ir, String key) {
        if (ir == null) {
            return null;
        }
        Object value = ir.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String message(String readableError, boolean parserError, String fallback) {
        if (!parserError && readableError != null && !readableError.isBlank()) {
            return readableError;
        }
        return fallback;
    }

    private static String header(String raw) {
        return raw == null ? "" : raw.strip();
    }

    private static String firstWord(String raw) {
        String text = raw == null ? "" : raw.strip();
        int end = 0;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end)) && text.charAt(end) != ';') {
            end++;
        }
        return end == 0 ? null : text.substring(0, end);
    }

    private static String secondWord(String raw) {
        String text = raw == null ? "" : raw.strip();
        int index = 0;
        while (index < text.length() && !Character.isWhitespace(text.charAt(index)) && text.charAt(index) != ';') {
            index++;
        }
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        int start = index;
        while (index < text.length() && !Character.isWhitespace(text.charAt(index)) && text.charAt(index) != ';') {
            index++;
        }
        return start == index ? null : text.substring(start, index);
    }

    private static String controlName(String firstWord) {
        if (firstWord == null) {
            return null;
        }
        String name = firstWord.toUpperCase(Locale.ROOT);
        if (name.startsWith("_")) {
            name = name.substring(1);
        }
        return COMMANDS.contains(name) ? name : null;
    }

    // ------------------------------------------------------------------ 帧

    private sealed interface Frame permits IfFrame, LoopFrame {
        boolean failed();

        boolean discard();

        void markDiscard();
    }

    private static final class IfFrame implements Frame {
        private final List<IfBranchBuilder> branches = new ArrayList<>();
        private IfBranchBuilder current;
        private boolean sawElse;
        private boolean failed;
        private boolean discard;

        @Override
        public boolean failed() {
            return failed;
        }

        @Override
        public boolean discard() {
            return discard;
        }

        @Override
        public void markDiscard() {
            discard = true;
        }

        private void startBranch(String condition, boolean isElse, boolean branchFailed) {
            current = new IfBranchBuilder(condition, isElse, branchFailed);
            branches.add(current);
        }
    }

    private static final class IfBranchBuilder {
        private final String condition;
        private final boolean isElse;
        private final boolean failed;
        private final List<FlowNode> nodes = new ArrayList<>();

        private IfBranchBuilder(String condition, boolean isElse, boolean failed) {
            this.condition = condition;
            this.isElse = isElse;
            this.failed = failed;
        }
    }

    private static final class LoopFrame implements Frame {
        private LoopSpec spec;
        private final List<FlowNode> body = new ArrayList<>();
        private boolean failed;
        private boolean discard;

        @Override
        public boolean failed() {
            return failed;
        }

        @Override
        public boolean discard() {
            return discard;
        }

        @Override
        public void markDiscard() {
            discard = true;
        }
    }

    private record ConditionOutcome(boolean value, boolean error) {
    }
}
