package org.slackerdb.dbserver.sql;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code DEALLOCATE} 语句的识别。
 *
 * <p><b>为什么需要单独识别</b>：{@code postgres_fdw} 在两条路径上会发 {@code DEALLOCATE}：</p>
 * <ul>
 *   <li>出错清理：{@code DEALLOCATE ALL}（{@code connection.c} 的
 *       {@code have_prep_stmt && have_error} 分支）；</li>
 *   <li>重建预备语句前：{@code DEALLOCATE <它自己 Prepare 过的名字>}
 *       （{@code postgres_fdw.c} 的 {@code pgsql_fdw_prep_<n>}）。</li>
 * </ul>
 *
 * <p>DuckDB 对 {@code DEALLOCATE ALL} 直接报 {@code 42601 DEALLOCATE requires a name}，
 * 而对 {@code DEALLOCATE <name>} 会静默成功（但只作用于它自己的 SQL 级 PREPARE 命名空间，
 * 动不了我们协议级缓存里的同名语句）。因此这两种形态要在服务端分开处理：
 * {@code ALL} 由服务端自己应答并清空协议级缓存；带名字的照常交给 DuckDB，同时顺手释放
 * 协议级缓存里同名的条目。</p>
 */
public final class DeallocateSql {

    /** {@code DEALLOCATE [PREPARE] ALL}（大小写不敏感，允许结尾分号与空白）。 */
    private static final Pattern DEALLOCATE_ALL = Pattern.compile(
            "^\\s*DEALLOCATE\\s+(?:PREPARE\\s+)?ALL\\s*;?\\s*$", Pattern.CASE_INSENSITIVE);

    /** {@code DEALLOCATE [PREPARE] <name>}；名字可能是带引号的标识符。 */
    private static final Pattern DEALLOCATE_NAME = Pattern.compile(
            "^\\s*DEALLOCATE\\s+(?:PREPARE\\s+)?(\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private DeallocateSql() {
    }

    /** 是否为 {@code DEALLOCATE ALL}。 */
    public static boolean isDeallocateAll(String sql) {
        return sql != null && DEALLOCATE_ALL.matcher(sql).matches();
    }

    /**
     * 如果是 {@code DEALLOCATE <name>}，返回去引号后的名字；否则返回 {@code null}。
     */
    public static String deallocateName(String sql) {
        if (sql == null) {
            return null;
        }
        Matcher matcher = DEALLOCATE_NAME.matcher(sql);
        if (!matcher.matches()) {
            return null;
        }
        String name = matcher.group(1);
        if (name.length() > 1 && name.charAt(0) == '"' && name.charAt(name.length() - 1) == '"') {
            // 带引号的标识符：去掉引号，并把 "" 还原成 "
            name = name.substring(1, name.length() - 1).replace("\"\"", "\"");
        }
        return name.toLowerCase(Locale.ROOT);
    }
}
