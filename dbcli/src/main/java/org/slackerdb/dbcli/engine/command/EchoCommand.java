package org.slackerdb.dbcli.engine.command;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code _ECHO <标题>} … {@code echo off}：块体逐行按**数据**输出（变量已在引擎层替换过）。
 *
 * <p>{@code _ECHO} 那一行上的标题只当标题，不输出；空正文是合法的（什么都不打印）。</p>
 *
 * <p>{@code _ECHO @name} 是同一块的**捕获形态**：把块正文存进脚本变量 {@code name}，
 * 不打印。它给 {@code _API} 的 {@code bodyvar=} 提供多行请求体，避免把多行文本塞进
 * {@code _API} 的单行命令行。</p>
 */
public final class EchoCommand implements ScriptCommand {

    /** 捕获变量名：与 {@code VariableResolver} 的普通变量名规则一致。 */
    private static final Pattern CAPTURE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");

    @Override
    public Set<String> names() {
        return Set.of("ECHO");
    }

    @Override
    public void execute(CommandCall call, Map<String, Object> ir) {
        String block = ir.get("block") == null ? "" : String.valueOf(ir.get("block"));
        Object rawParam = ir.get("param");
        String param = rawParam == null ? "" : String.valueOf(rawParam).strip();
        if (param.startsWith("@")) {
            String name = param.substring(1);
            if (!CAPTURE_NAME.matcher(name).matches()) {
                call.reportError("Invalid echo capture name [" + name + "].");
                return;
            }
            // 空块也要登记成空串：bodyvar= 需要区分“未捕获”与“捕获到空正文”
            call.context().variables().setVariable(name, block);
            return;
        }
        if (block.isEmpty()) {
            return;
        }
        for (String line : block.split("\n", -1)) {
            call.context().output().printData(line);
        }
    }
}
