package org.slackerdb.dbcli.engine;

import org.slackerdb.dbcli.common.CommandOutcome;

import java.util.List;

/**
 * {@link CommandOutcome} → 输出的**唯一**映射：状态行、表格、错误各归各的通道。
 *
 * <p>分派器的三个实现（{@code _COMPARE}/{@code _JOB}/{@code _SSH} 与 {@code _CONNECT} 等）都产出同一形状的
 * 结果项，输出纪律因此只该有一份实现：状态行 → stdout、结果集 → 表格、错误 → stdout 并计错误数。
 * 以前这段 switch 在 {@link ScriptRunner#dispatch(Map)} 里，与语句分派混在一起。</p>
 *
 * <p>{@link CommandOutcome.Failure} 是"既打状态行、又算失败"的那一类（见类型注释），
 * 因此只打印一次、计一次错误——重复打印会让同一句话在 stdout/日志里出现两遍。
 * 它的判定由**产出侧**负责（{@code new CommandOutcome.Failure(...)}），这里不再靠匹配文案。</p>
 */
final class CommandOutcomeRenderer {

    private final ScriptContext context;
    private final ErrorReporter errors;

    CommandOutcomeRenderer(ScriptContext context, ErrorReporter errors) {
        this.context = context;
        this.errors = errors;
    }

    void render(List<CommandOutcome> outcomes) {
        if (outcomes == null) {
            return;
        }
        for (CommandOutcome outcome : outcomes) {
            if (outcome instanceof CommandOutcome.Failure failure) {
                if (failure.text() != null) {
                    errors.fail(failure.text());
                }
            } else if (outcome instanceof CommandOutcome.Status status) {
                if (status.text() != null) {
                    context.output().printStatus(status.text());
                }
            } else if (outcome instanceof CommandOutcome.Table table) {
                // 分派器的表格没有列类型信息（CommandOutcome 里没有这个字段）：按字符串全左对齐渲染
                context.output().printTable(table.headers(), null, table.rows());
            } else if (outcome instanceof CommandOutcome.Error error) {
                errors.report(error.message());
            }
        }
    }
}
