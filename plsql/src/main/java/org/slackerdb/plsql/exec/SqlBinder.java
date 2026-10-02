package org.slackerdb.plsql.exec;

import org.slackerdb.plsql.PlSqlException;
import org.slackerdb.plsql.detect.SqlStatementSplitter;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 文本里的 {@code :name} 绑定处理。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>只识别<b>代码位置</b>上的 {@code :name}——字符串、注释、美元引用里的
 *       {@code :x} 是字面文本，<b>不</b>做替换（历史实现在这里既会误替换、又会
 *       用正则把变量名截断成 {@code **UNKNOWN**xxx}）；</li>
 *   <li>{@code ::} 类型转换语法原样保留；</li>
 *   <li>未声明的变量报 {@code 42601}。</li>
 * </ul>
 */
final class SqlBinder {

    private SqlBinder() {
    }

    /** 绑定结果：带 {@code ?} 的 SQL + 顺序一致的值。 */
    record Bound(String sql, List<Object> values) {
    }

    static Bound bind(String sql, Scope scope, int line) {
        if (sql == null || sql.indexOf(':') < 0) {
            return new Bound(sql, List.of());
        }
        StringBuilder out = new StringBuilder(sql.length() + 8);
        List<Object> values = new ArrayList<>();
        int index = 0;
        int length = sql.length();
        while (index < length) {
            int skipped = SqlStatementSplitter.skipNonCode(sql, index);
            if (skipped > index) {
                out.append(sql, index, skipped);
                index = skipped;
                continue;
            }
            char ch = sql.charAt(index);
            if (ch == ':' && index + 1 < length) {
                char next = sql.charAt(index + 1);
                if (next == ':') {
                    out.append("::");
                    index += 2;
                    continue;
                }
                if (Character.isLetter(next) || next == '_') {
                    int end = index + 2;
                    while (end < length) {
                        char c = sql.charAt(end);
                        if (!Character.isLetterOrDigit(c) && c != '_' && c != '$') {
                            break;
                        }
                        end++;
                    }
                    String name = sql.substring(index + 1, end);
                    Scope.Variable variable = scope.requireVariable(name, line);
                    if (variable.value.isNull()) {
                        values.add(null);
                    } else {
                        values.add(variable.value.value());
                    }
                    out.append('?');
                    index = end;
                    continue;
                }
            }
            out.append(ch);
            index++;
        }
        return new Bound(out.toString(), values);
    }

    /** 无绑定引用时的快速路径（避免每次扫描）。 */
    static boolean hasBindReference(String sql) {
        return sql != null && sql.indexOf(':') >= 0;
    }

    /**
     * 动态 SQL（{@code EXECUTE IMMEDIATE}）的绑定。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>代码位置的占位符按<b>出现顺序</b>依次替换成 {@code ?}：{@code ?}、{@code :1}（编号仅作
     *       书写便利，绑定仍按出现顺序）以及 {@code :name} 都算占位符；</li>
     *   <li>字符串、注释、美元引用、{@code ::} 里的内容原样保留，不当作占位符；</li>
     *   <li>{@code USING} 有值时一律按位置绑定；没有 {@code USING} 时，{@code :name} 退回
     *       "按当前作用域变量取值"（与本引擎内嵌 SQL 的 {@code :name} 语义一致）；</li>
     *   <li>个数不匹配报 {@code 07001}（可被 {@code EXCEPTION} 段捕获）。</li>
     * </ul>
     */
    static Bound bindDynamic(String sql, List<Object> using, Scope scope, int line) {
        if (sql == null) {
            return new Bound(null, List.of());
        }
        StringBuilder out = new StringBuilder(sql.length() + 8);
        List<Object> values = new ArrayList<>();
        int used = 0;
        int index = 0;
        int length = sql.length();
        while (index < length) {
            int skipped = SqlStatementSplitter.skipNonCode(sql, index);
            if (skipped > index) {
                out.append(sql, index, skipped);
                index = skipped;
                continue;
            }
            char ch = sql.charAt(index);
            if (ch == '?') {
                values.add(nextValue(using, used, line));
                used++;
                out.append('?');
                index++;
                continue;
            }
            if (ch == ':' && index + 1 < length) {
                char next = sql.charAt(index + 1);
                if (next == ':') {
                    out.append("::");
                    index += 2;
                    continue;
                }
                if (Character.isDigit(next)) {
                    int end = index + 1;
                    while (end < length && Character.isDigit(sql.charAt(end))) {
                        end++;
                    }
                    values.add(nextValue(using, used, line));
                    used++;
                    out.append('?');
                    index = end;
                    continue;
                }
                if (Character.isLetter(next) || next == '_') {
                    int end = index + 2;
                    while (end < length) {
                        char c = sql.charAt(end);
                        if (!Character.isLetterOrDigit(c) && c != '_' && c != '$') {
                            break;
                        }
                        end++;
                    }
                    if (using.isEmpty()) {
                        // 与内嵌 SQL 一致：:name 取当前作用域的变量值
                        Scope.Variable variable = scope.requireVariable(sql.substring(index + 1, end), line);
                        values.add(variable.value.isNull() ? null : variable.value.value());
                    } else {
                        values.add(nextValue(using, used, line));
                        used++;
                    }
                    out.append('?');
                    index = end;
                    continue;
                }
            }
            out.append(ch);
            index++;
        }
        if (used < using.size()) {
            throw new PlSqlException("EXECUTE IMMEDIATE: " + (using.size() - used)
                    + " extra value(s) in USING (dynamic SQL has " + used + " placeholder(s))",
                    PlSqlException.INVALID_BIND_COUNT, line, 1, null, null);
        }
        return new Bound(out.toString(), values);
    }

    private static Object nextValue(List<Object> using, int used, int line) {
        if (used >= using.size()) {
            throw new PlSqlException("EXECUTE IMMEDIATE: missing USING value for placeholder #"
                    + (used + 1) + " (USING has " + using.size() + " value(s))",
                    PlSqlException.INVALID_BIND_COUNT, line, 1, null, null);
        }
        return using.get(used);
    }

    static PlSqlException undeclared(String name, int line) {
        return new PlSqlException("Variable [" + name + "] has not been declared",
                PlSqlException.SYNTAX_ERROR, line, 1, null, null);
    }
}
