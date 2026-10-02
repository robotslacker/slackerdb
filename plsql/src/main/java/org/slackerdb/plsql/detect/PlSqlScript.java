package org.slackerdb.plsql.detect;

import java.util.Collections;
import java.util.List;

/**
 * 对一段客户端文本的分析结果。
 *
 * <p>可能的形态：</p>
 * <ul>
 *   <li>{@link #isEmpty()} —— 空脚本 / 只有注释；</li>
 *   <li>1 条 {@code SQL} —— 普通语句；</li>
 *   <li>1 条 {@code BLOCK} —— 单个匿名块（带或不带 {@code DO $$} 包装）；</li>
 *   <li>多条 —— 脚本（简单查询协议下顺序执行）。</li>
 * </ul>
 */
public final class PlSqlScript {

    private final List<PlSqlStatement> statements;
    private final String error;

    PlSqlScript(List<PlSqlStatement> statements, String error) {
        this.statements = Collections.unmodifiableList(statements);
        this.error = error;
    }

    static PlSqlScript empty() {
        return new PlSqlScript(List.of(), null);
    }

    static PlSqlScript error(String message) {
        return new PlSqlScript(List.of(), message);
    }

    public List<PlSqlStatement> statements() {
        return statements;
    }

    /** 词法/包装错误（未闭合字符串、未闭合 {@code $$}、{@code DO $$} 之后有多余内容等）；null 表示正常。 */
    public String error() {
        return error;
    }

    public boolean hasError() {
        return error != null;
    }

    public boolean isEmpty() {
        return statements.isEmpty() && error == null;
    }

    public int size() {
        return statements.size();
    }

    public boolean isSingle() {
        return statements.size() == 1;
    }

    /** 只有一条语句且是匿名块。 */
    public boolean isSingleBlock() {
        return statements.size() == 1 && statements.get(0).isBlock();
    }

    public boolean isScript() {
        return statements.size() > 1;
    }

    /** 第一条语句（空脚本返回 null）。 */
    public PlSqlStatement first() {
        return statements.isEmpty() ? null : statements.get(0);
    }
}
