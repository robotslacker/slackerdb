package org.slackerdb.dbcli.engine.command;

import java.util.Map;
import java.util.Set;

/**
 * 一条脚本命令（{@code _ASSERT}、{@code _SET}、{@code _JOB} 这类非 SQL 语句）的实现。
 *
 * <p>实现类只认 IR（{@code Map<String,Object>}）与 {@link CommandCall}：
 * 解析依旧由 {@code SqlIrVisitor} 负责，命令实现不碰词法/语法。</p>
 */
public interface ScriptCommand {

    /** 这个实现处理的 IR {@code name}（大写；{@code _EXIT}/{@code _QUIT} 这类同义命令可给多个）。 */
    Set<String> names();

    /** 执行一条命令。 */
    void execute(CommandCall call, Map<String, Object> ir);
}
