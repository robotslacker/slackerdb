package org.slackerdb.dbcli.engine.command;

import org.slackerdb.dbcli.assertion.AssertEvaluator;

import java.util.Map;
import java.util.Set;

/**
 * {@code _ASSERT}：把 IR 里的断言正文交给固定形态求值器，产出状态行/失败。
 *
 * <p>通过 → 一条状态行；失败 → 同一条消息只打印一次并计一次错误（见
 * {@link CommandHost#countFailure(String)}），{@code _WHENEVER} 因此照常生效。</p>
 */
public final class AssertCommand implements ScriptCommand {

    @Override
    public Set<String> names() {
        return Set.of("ASSERT");
    }

    @Override
    public void execute(CommandCall call, Map<String, Object> ir) {
        String expression = ir.get("expression") == null ? "" : String.valueOf(ir.get("expression"));
        String assertName = ir.get("assertName") == null ? null : String.valueOf(ir.get("assertName"));
        AssertEvaluator.Verdict verdict =
                AssertEvaluator.evaluate(expression, call.context().assertModel(), assertName);

        // 状态文案是输出契约（§3.4.2）：通过时就是一条状态行；失败时同一句话既是状态行又是错误，
        // 因此走 countFailure（同一个流只打一次、两个流才各记一行）并计一次错误。
        if (verdict.passed()) {
            call.context().output().printStatus(verdict.status());
        } else {
            call.countFailure(verdict.status());
        }
    }
}
