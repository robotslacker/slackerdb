package org.slackerdb.plsql.exec;

import org.slackerdb.plsql.PlSqlException;
import org.slackerdb.plsql.spi.StatementHandle;
import org.slackerdb.plsql.types.PlType;
import org.slackerdb.plsql.types.PlValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时作用域：嵌套块形成父子链，内层可遮蔽外层。
 *
 * <p>变量值为 {@link PlValue}（可为 NULL）；游标持有 SPI 句柄与运行态属性
 * （{@code %FOUND}/{@code %NOTFOUND}/{@code %ROWCOUNT}/{@code %ISOPEN}）。</p>
 */
final class Scope {

    /** 变量：声明类型 + 当前值 + 约束。 */
    static final class Variable {
        final PlType type;
        final boolean notNull;
        final boolean constant;
        PlValue value;

        Variable(PlType type, boolean notNull, boolean constant) {
            this.type = type;
            this.notNull = notNull;
            this.constant = constant;
            this.value = PlValue.nullOf(type);
        }
    }

    /** 游标运行态。 */
    static final class Cursor {
        final String name;
        final String query;
        final List<String> parameterNames;
        StatementHandle handle;
        boolean opened;
        boolean found;
        boolean notFound;
        long rowCount;

        Cursor(String name, String query, List<String> parameterNames) {
            this.name = name;
            this.query = query;
            this.parameterNames = parameterNames;
        }

        boolean isOpen() {
            return opened;
        }
    }

    private final Scope parent;
    private final Map<String, Variable> variables = new LinkedHashMap<>();
    private final Map<String, Cursor> cursors = new LinkedHashMap<>();

    Scope(Scope parent) {
        this.parent = parent;
    }

    Scope parent() {
        return parent;
    }

    // ---------- 变量 ----------
    void declare(String name, PlType type, boolean notNull, boolean constant) {
        String key = normalize(name);
        if (variables.containsKey(key)) {
            throw new PlSqlException("Variable [" + name + "] is declared twice", PlSqlException.SYNTAX_ERROR, null);
        }
        variables.put(key, new Variable(type, notNull, constant));
    }

    boolean isDeclared(String name) {
        String key = normalize(name);
        if (variables.containsKey(key)) {
            return true;
        }
        return parent != null && parent.isDeclared(key);
    }

    Variable variable(String name) {
        String key = normalize(name);
        Variable local = variables.get(key);
        if (local != null) {
            return local;
        }
        if (parent != null) {
            return parent.variable(key);
        }
        return null;
    }

    Variable requireVariable(String name, int line) {
        Variable variable = variable(name);
        if (variable == null) {
            throw new PlSqlException("Variable [" + name + "] has not been declared", PlSqlException.SYNTAX_ERROR,
                    line, 1, null, null);
        }
        return variable;
    }

    // ---------- 游标 ----------
    void declareCursor(String name, String query, List<String> parameterNames) {
        String key = normalize(name);
        if (cursors.containsKey(key)) {
            throw new PlSqlException("Cursor [" + name + "] is declared twice", PlSqlException.SYNTAX_ERROR, null);
        }
        cursors.put(key, new Cursor(name, query, parameterNames));
    }

    Cursor cursor(String name) {
        String key = normalize(name);
        Cursor local = cursors.get(key);
        if (local != null) {
            return local;
        }
        if (parent != null) {
            return parent.cursor(key);
        }
        return null;
    }

    Cursor requireCursor(String name, int line) {
        Cursor cursor = cursor(name);
        if (cursor == null) {
            throw new PlSqlException("Cursor [" + name + "] has not been declared", PlSqlException.SYNTAX_ERROR,
                    line, 1, null, null);
        }
        return cursor;
    }

    /** 本作用域内已打开的游标（块退出时自动关闭）。 */
    List<Cursor> openCursors() {
        List<Cursor> open = new ArrayList<>();
        for (Cursor cursor : cursors.values()) {
            if (cursor.isOpen()) {
                open.add(cursor);
            }
        }
        return open;
    }

    /** 供嵌套块继承的外层游标（异常/退出时也需要关闭外层已打开的游标）。 */
    List<Cursor> allOpenCursors() {
        List<Cursor> open = new ArrayList<>(openCursors());
        if (parent != null) {
            open.addAll(parent.allOpenCursors());
        }
        return open;
    }

    /** 未加引号的标识符统一按小写归一化。 */
    static String normalize(String name) {
        return name.toLowerCase(java.util.Locale.ROOT);
    }
}
