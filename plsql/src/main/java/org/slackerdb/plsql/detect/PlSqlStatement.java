package org.slackerdb.plsql.detect;

/**
 * 脚本中的一条语句。
 */
public final class PlSqlStatement {

    public enum Kind {
        /** 普通 SQL 语句（交给数据库直接执行）。 */
        SQL,
        /** PL/SQL 匿名块（交给 PL/SQL 引擎执行）。 */
        BLOCK
    }

    private final Kind kind;
    /** 完整语句文本：SQL 不含结尾分号；块含 {@code DECLARE/BEGIN ... END;}。 */
    private final String sql;
    /** 仅块：去掉 {@code DO $$}/{@code $$}/{@code $tag$} 包装后的块体。 */
    private final String body;
    private final int start;
    private final int end;
    private final int line;

    PlSqlStatement(Kind kind, String sql, String body, int start, int end, int line) {
        this.kind = kind;
        this.sql = sql;
        this.body = body;
        this.start = start;
        this.end = end;
        this.line = line;
    }

    public Kind kind() {
        return kind;
    }

    public boolean isBlock() {
        return kind == Kind.BLOCK;
    }

    public String sql() {
        return sql;
    }

    /** 块的执行体（{@link Kind#BLOCK} 时有效）。 */
    public String body() {
        return body;
    }

    public int start() {
        return start;
    }

    public int end() {
        return end;
    }

    /** 1 起的起始行号。 */
    public int line() {
        return line;
    }

    @Override
    public String toString() {
        return kind + "(line " + line + "): " + sql;
    }
}
