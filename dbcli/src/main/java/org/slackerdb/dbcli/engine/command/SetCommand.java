package org.slackerdb.dbcli.engine.command;

import org.slackerdb.dbcli.engine.ScriptContext;
import org.slackerdb.dbcli.engine.SetOption;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code _SET}：无参打印选项表；{@code _SET @名字 值} 定义脚本变量；其它则按 {@link SetOption} 校验并生效。
 *
 * <p>选项的默认值、类型、合法取值与"落到哪一层"全在 {@link SetOption}：这里只负责
 * "取值 → 校验 → 写进会话选项表 → 交给选项自己去 apply"，因此新增一个选项不需要改本类。</p>
 */
public final class SetCommand implements ScriptCommand {

    @Override
    public Set<String> names() {
        return Set.of("SET");
    }

    @Override
    public void execute(CommandCall call, Map<String, Object> ir) {
        ScriptContext context = call.context();
        Object rawOptionName = ir.get("optionName");
        if (rawOptionName == null) {
            printOptionTable(context);
            return;
        }
        String optionName = String.valueOf(rawOptionName);
        Object rawOptionValue = ir.get("optionValue");
        if (optionName.startsWith("@")) {
            String variableName = optionName.substring(1);
            if (variableName.isEmpty()) {
                call.reportError("Variable name is missing for _SET.");
                return;
            }
            if (rawOptionValue == null) {
                call.reportError("Missing value for variable [" + variableName + "].");
                return;
            }
            context.variables().setVariable(variableName, String.valueOf(rawOptionValue));
            return;
        }
        SetOption option = SetOption.of(optionName);
        if (option == null) {
            call.reportError("Unknown set option [" + optionName + "].");
            return;
        }
        String normalized;
        try {
            normalized = option.validate(rawOptionValue == null ? "" : String.valueOf(rawOptionValue));
        } catch (SetOption.InvalidValueException invalidValue) {
            call.reportError(invalidValue.getMessage());
            return;
        }
        context.setOption(option.optionName(), normalized);
        try {
            // 落地点由选项自己负责（输出层/执行层）：不会出现"登记了但没人消费"的静默选项
            option.apply(context);
        } catch (RuntimeException applyFailed) {
            call.reportError(messageOf(applyFailed));
        }
    }

    /** {@code _SET} 无参：{@code Option / Current value / Default value} 三列。 */
    private static void printOptionTable(ScriptContext context) {
        context.output().printStatus("Current set options:");
        List<List<Object>> rows = new ArrayList<>();
        for (String option : context.optionOrder()) {
            rows.add(List.of(option, String.valueOf(context.option(option)),
                    context.optionDefault(option)));
        }
        context.output().printTable(List.of("Option", "Current value", "Default value"), null, rows);
    }

    private static String messageOf(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message == null || message.isBlank()
                ? (failure == null ? "unknown failure" : failure.getClass().getSimpleName())
                : message;
    }
}
