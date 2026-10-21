package org.slackerdb.dbcli.engine.command;

import org.slackerdb.dbcli.common.PathArguments;
import org.slackerdb.dbcli.engine.ScriptOutput;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * {@code _SPOOL <文件>} 开始 tee，{@code _SPOOL OFF} 停止 tee。
 *
 * <p>没有"无参"形态：光写 {@code _SPOOL} 是一条可读错误（以前它被当成"停止"，那是多余的第二种写法）。
 * {@code OFF} 是词法层关键字，因此没法把 {@code off} 当文件名——要输出到名为 {@code off} 的文件，
 * 写路径即可（{@code _SPOOL ./off}）。</p>
 */
public final class SpoolCommand implements ScriptCommand {

    /** {@code _SPOOL OFF} 里的 {@code off}：按用户本意当"停 spool"。 */
    private static final String STOP_SPOOL_WORD = "off";

    @Override
    public Set<String> names() {
        return Set.of("SPOOL");
    }

    @Override
    public void execute(CommandCall call, Map<String, Object> ir) {
        ScriptOutput output = call.context().output();
        Object rawFile = ir.get("file");
        String file = rawFile == null ? "" : String.valueOf(rawFile).strip();
        if (file.isEmpty()) {
            // 无参形态已取消：停止 spool 只有 _SPOOL OFF
            call.reportError("Spool needs a file name (use _SPOOL OFF to stop spooling).");
            return;
        }
        if (STOP_SPOOL_WORD.equalsIgnoreCase(file)) {
            if (output.isSpooling()) {
                // 先打状态再关文件：这样 spool 文件里两种标记（started/stopped）都在
                output.printStatus("Spool stopped.");
                output.stopSpool();
            } else {
                output.printStatus("Spool is not active.");
            }
            return;
        }
        Path target;
        try {
            target = call.resolvePath(file);
        } catch (PathArguments.InvalidPathException invalidPath) {
            call.reportError(invalidPath.getMessage());
            return;
        }
        try {
            output.startSpool(target);
            output.printStatus("Spool started [" + target + "].");
        } catch (IOException spoolFailed) {
            call.reportError("Cannot open spool file [" + target + "]: " + spoolFailed.getMessage());
        }
    }
}
