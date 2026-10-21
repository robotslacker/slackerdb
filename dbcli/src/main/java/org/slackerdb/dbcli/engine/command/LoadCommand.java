package org.slackerdb.dbcli.engine.command;

import org.slackerdb.dbcli.common.PathArguments;
import org.slackerdb.dbcli.engine.ScriptOutput;
import org.slackerdb.dbcli.sql.JdbcDriverLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * {@code _LOAD JDBCDRIVER <jar>}：把外部 jar 里的 JDBC 驱动装进当前进程，之后 {@code _CONNECT}
 * 就能用该驱动（见 {@link JdbcDriverLoader}）。
 *
 * <p>命令只负责"取值 → 找文件 → 调加载器 → 一行结果"；把驱动变成可连接的是加载器那层
 * （垫片注册），本类不碰 {@code DriverManager}。目标不认识（例如历史上的 {@code _LOAD MAP}）
 * 在这里报"不支持的目标"——{@code _LOAD} 本身是认识的命令，不该说成"未知命令"。</p>
 *
 * <p>相对路径按**脚本工作目录**解析（与 {@code _SPOOL}/{@code _START} 同一条规则）；
 * 同一个 jar 重复加载不算错，只提示"已经加载过"。</p>
 */
public final class LoadCommand implements ScriptCommand {

    /** 唯一支持的目标。 */
    private static final String JDBC_DRIVER_TARGET = "JDBCDRIVER";

    @Override
    public Set<String> names() {
        return Set.of("LOAD");
    }

    @Override
    public void execute(CommandCall call, Map<String, Object> ir) {
        ScriptOutput output = call.context().output();
        String target = text(ir.get("target"));
        if (target.isEmpty()) {
            call.reportError("Load needs a target: only '_LOAD JDBCDRIVER <jar>' is supported.");
            return;
        }
        if (!JDBC_DRIVER_TARGET.equalsIgnoreCase(target)) {
            call.reportError("Unsupported _LOAD target [" + target
                    + "]: only '_LOAD JDBCDRIVER <jar>' is supported.");
            return;
        }
        String file = text(ir.get("file"));
        if (file.isEmpty()) {
            call.reportError("_LOAD JDBCDRIVER needs a jar file.");
            return;
        }
        Path jar;
        try {
            jar = call.resolvePath(file);
        } catch (PathArguments.InvalidPathException invalidPath) {
            call.reportError(invalidPath.getMessage());
            return;
        }
        if (!Files.isRegularFile(jar)) {
            call.reportError(Files.exists(jar)
                    ? "JDBC driver jar [" + jar + "] is not a file."
                    : "JDBC driver jar [" + jar + "] does not exist.");
            return;
        }
        try {
            JdbcDriverLoader.Loaded loaded = JdbcDriverLoader.load(jar);
            if (loaded.alreadyLoaded()) {
                output.printStatus("JDBC driver [" + jar + "] is already loaded: "
                        + String.join(", ", loaded.drivers()) + ".");
                return;
            }
            output.printStatus("Loaded JDBC driver" + (loaded.drivers().size() > 1 ? "s" : "")
                    + " from [" + jar + "]: " + String.join(", ", loaded.drivers()) + ".");
        } catch (JdbcDriverLoader.LoadException loadFailed) {
            call.reportError(loadFailed.getMessage());
        }
    }

    /** IR 字段 → 去掉首尾空白的文本（null 按空串）。 */
    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }
}
