package org.slackerdb.plsql.exec;

import org.slackerdb.plsql.PlSqlException;
import org.slackerdb.plsql.ast.Expr;
import org.slackerdb.plsql.ast.PlSqlAst;
import org.slackerdb.plsql.expr.ExprEvaluator;
import org.slackerdb.plsql.spi.PlSqlCanceledException;
import org.slackerdb.plsql.spi.PlSqlHost;
import org.slackerdb.plsql.spi.StatementHandle;
import org.slackerdb.plsql.types.Coercions;
import org.slackerdb.plsql.types.PlSqlTypeException;
import org.slackerdb.plsql.types.PlType;
import org.slackerdb.plsql.types.PlValue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PL/SQL 解释器。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>AST 驱动，执行期间只经 {@link PlSqlHost} 访问数据库；</li>
 *   <li>每个块一个 {@link Scope}（嵌套/遮蔽）；块退出（含异常）时自动关闭块内游标；</li>
 *   <li>控制流信号用异常实现：{@code EXIT}/{@code CONTINUE} 只作用于最内层（或指定标签的）循环；</li>
 *   <li>异常处理：{@code WHEN 名字 [OR 名字] THEN} 自上而下首个匹配；{@code OTHERS} 兜底；</li>
 *   <li>安全阀：语句预算（{@code 54000}）+ 每语句/每轮循环调用宿主取消检查（{@code 57014}）。</li>
 * </ul>
 */
public final class Interpreter {

    /** 运行选项。 */
    public static final class Options {
        /** 语句预算：超过即认为疑似死循环（SQLSTATE 54000）。 */
        public int maxStatements = 200_000;

        public Options maxStatements(int value) {
            this.maxStatements = value;
            return this;
        }
    }

    private final PlSqlHost host;
    private final Options options;
    private final ExprEvaluator evaluator;
    private final ExprEvaluator.ValueResolver resolver;

    private long statements;

    public Interpreter(PlSqlHost host) {
        this(host, new Options());
    }

    public Interpreter(PlSqlHost host, Options options) {
        this.host = host;
        this.options = options;
        this.evaluator = new ExprEvaluator(host);
        this.resolver = node -> {
            Scope scope = currentScope;
            if (node instanceof Expr.Name name) {
                Scope.Variable variable = scope.requireVariable(name.name(), node.pos().line());
                return variable.value.value();
            }
            if (node instanceof Expr.CursorAttr attr) {
                Scope.Cursor cursor = scope.requireCursor(attr.cursor(), node.pos().line());
                return cursorAttribute(cursor, attr.attr());
            }
            throw new IllegalStateException("非绑定节点：" + node);
        };
    }

    /** 当前作用域（求值回调需要）。 */
    private Scope currentScope;
    /** 正在处理的异常（{@code RAISE;} 重抛用）。 */
    private Signals.RaisedException currentException;

    /** 执行一个块。 */
    public void run(PlSqlAst.Block block) {
        Scope scope = new Scope(null);
        try {
            executeBlock(block, scope);
        } catch (Signals.ExitSignal | Signals.ContinueSignal signal) {
            throw new PlSqlException("EXIT/CONTINUE used outside of a loop",
                    PlSqlException.SYNTAX_ERROR, null);
        } catch (Signals.RaisedException raised) {
            // 未被任何 handler 捕获：冒泡给协议层。
            // 位置仍只用于编译期错误（不改动客户端消息后缀），但把底层语句原文带出去，
            // 便于客户端/日志定位是哪条内嵌 SQL 或动态 SQL 失败（见 PlSqlException#getSqlText）。
            PlSqlException original = raised.getCause() instanceof PlSqlException pl ? pl : null;
            throw new PlSqlException("Unhandled PL/SQL exception [" + raised.name + "]"
                    + (raised.message == null ? "" : ": " + raised.message),
                    raised.sqlState, 0, 0,
                    original == null ? null : original.getSqlText(), raised);
        }
    }

    // ------------------------------------------------------------------
    // 块与声明
    // ------------------------------------------------------------------
    private void executeBlock(PlSqlAst.Block block, Scope scope) {
        Scope previous = currentScope;
        currentScope = scope;
        try {
            declare(block, scope);
            try {
                for (PlSqlAst.Stmt statement : block.statements()) {
                    execute(statement, scope);
                }
            } catch (Signals.ReturnSignal ignored) {
                // RETURN; 结束本块
            } catch (Signals.RaisedException raised) {
                if (!handleException(block, scope, raised)) {
                    throw raised;
                }
            }
        } finally {
            closeCursors(scope);
            currentScope = previous;
        }
    }

    private void declare(PlSqlAst.Block block, Scope scope) {
        for (PlSqlAst.Decl decl : block.declares()) {
            if (decl instanceof PlSqlAst.VariableDecl variable) {
                PlType type = PlType.parse(variable.typeText());
                if (type == null) {
                    throw new PlSqlException("Unsupported datatype [" + variable.typeText() + "]",
                            PlSqlException.SYNTAX_ERROR, variable.line(), 1, null, null);
                }
                scope.declare(variable.name(), type, variable.notNull(), false);
                if (variable.defaultValue() != null) {
                    Object value = evaluate(variable.defaultValue());
                    assign(scope, variable.name(), value, variable.line());
                }
            } else if (decl instanceof PlSqlAst.CursorDecl cursor) {
                List<String> parameters = new ArrayList<>();
                for (PlSqlAst.Param parameter : cursor.parameters()) {
                    parameters.add(parameter.name());
                }
                scope.declareCursor(cursor.name(), cursor.query(), parameters);
            }
        }
    }

    private boolean handleException(PlSqlAst.Block block, Scope scope, Signals.RaisedException raised) {
        for (PlSqlAst.Handler handler : block.handlers()) {
            if (matches(handler, raised)) {
                Signals.RaisedException previousException = currentException;
                currentException = raised;
                try {
                    for (PlSqlAst.Stmt statement : handler.body()) {
                        execute(statement, scope);
                    }
                } finally {
                    currentException = previousException;
                }
                // 处理块内 RAISE; 重抛：**不再**由本块处理，交给外层块，
                // 否则会自捕获形成无限递归（StackOverflowError）。
                return true;
            }
        }
        return false;
    }

    private static boolean matches(PlSqlAst.Handler handler, Signals.RaisedException raised) {
        for (String name : handler.exceptions()) {
            if (name.equalsIgnoreCase("OTHERS") || name.equalsIgnoreCase(raised.name)) {
                return true;
            }
        }
        return false;
    }

    private void closeCursors(Scope scope) {
        // 只关闭"本块声明并打开"的游标：外层块打开的游标由外层负责
        for (Scope.Cursor cursor : scope.openCursors()) {
            if (cursor.handle != null) {
                cursor.handle.close();
                cursor.handle = null;
            }
            cursor.opened = false;
        }
    }

    // ------------------------------------------------------------------
    // 语句
    // ------------------------------------------------------------------
    private void execute(PlSqlAst.Stmt statement, Scope scope) {
        currentScope = scope;
        checkCancelled();
        statements++;
        if (statements > options.maxStatements) {
            throw new PlSqlException("PL/SQL statement budget exceeded (" + options.maxStatements
                    + "); possible infinite loop", PlSqlException.PROGRAM_LIMIT_EXCEEDED, null);
        }
        try {
            if (statement instanceof PlSqlAst.Sql sql) {
                executeSql(sql.sql(), scope, sql.line());
            } else if (statement instanceof PlSqlAst.SelectInto selectInto) {
                executeSelectInto(selectInto, scope);
            } else if (statement instanceof PlSqlAst.ExecuteImmediate dynamic) {
                executeImmediate(dynamic, scope);
            } else if (statement instanceof PlSqlAst.Assign assign) {
                executeAssign(assign, scope);
            } else if (statement instanceof PlSqlAst.If ifStmt) {
                executeIf(ifStmt, scope);
            } else if (statement instanceof PlSqlAst.Loop loop) {
                executeLoop(loop, scope);
            } else if (statement instanceof PlSqlAst.While whileStmt) {
                executeWhile(whileStmt, scope);
            } else if (statement instanceof PlSqlAst.For forStmt) {
                executeFor(forStmt, scope);
            } else if (statement instanceof PlSqlAst.Exit exit) {
                if (exit.condition() == null || evaluateCondition(exit.condition())) {
                    throw new Signals.ExitSignal(exit.label());
                }
            } else if (statement instanceof PlSqlAst.Continue cont) {
                if (cont.condition() == null || evaluateCondition(cont.condition())) {
                    throw new Signals.ContinueSignal(cont.label());
                }
            } else if (statement instanceof PlSqlAst.Fetch fetch) {
                executeFetch(fetch, scope);
            } else if (statement instanceof PlSqlAst.OpenCursor open) {
                executeOpen(open, scope);
            } else if (statement instanceof PlSqlAst.CloseCursor close) {
                executeClose(close, scope);
            } else if (statement instanceof PlSqlAst.Raise raise) {
                executeRaise(raise);
            } else if (statement instanceof PlSqlAst.Return) {
                throw new Signals.ReturnSignal();
            } else if (statement instanceof PlSqlAst.Commit commit) {
                executeSql("commit", scope, commit.line());
            } else if (statement instanceof PlSqlAst.Rollback rollback) {
                executeSql("rollback", scope, rollback.line());
            } else if (statement instanceof PlSqlAst.Null) {
                // 空语句
            } else if (statement instanceof PlSqlAst.Nested nested) {
                executeBlock(nested.block(), new Scope(scope));
            } else {
                throw new IllegalStateException("未识别的语句：" + statement.getClass().getName());
            }
        } catch (PlSqlTypeException e) {
            // 类型/转换错误同样可以被 EXCEPTION 段捕获
            throw new Signals.RaisedException(Signals.RaisedException.errorNameOf(e.getSqlState()),
                    e.getSqlState(), e.getMessage(), e);
        } catch (PlSqlException e) {
            // 编译期语义错误（42601）不可被 EXCEPTION 段捕获（PL/SQL 语义）；
            // 其余运行期错误（约束、类型、除零、下层 SQL 错误…）可以被捕获。
            if (PlSqlException.SYNTAX_ERROR.equals(e.getSqlState())) {
                throw e;
            }
            throw Signals.RaisedException.of(e);
        }
    }

    private void executeIf(PlSqlAst.If ifStmt, Scope scope) {
        for (PlSqlAst.Branch branch : ifStmt.branches()) {
            if (evaluateCondition(branch.condition())) {
                for (PlSqlAst.Stmt statement : branch.body()) {
                    execute(statement, scope);
                }
                return;
            }
        }
        for (PlSqlAst.Stmt statement : ifStmt.elseBody()) {
            execute(statement, scope);
        }
    }

    private void executeLoop(PlSqlAst.Loop loop, Scope scope) {
        while (true) {
            checkCancelled();
            try {
                for (PlSqlAst.Stmt statement : loop.body()) {
                    execute(statement, scope);
                }
            } catch (Signals.ExitSignal exit) {
                if (exit.label == null || exit.label.equalsIgnoreCase(loop.label())) {
                    return;
                }
                throw exit;
            } catch (Signals.ContinueSignal cont) {
                if (cont.label != null && !cont.label.equalsIgnoreCase(loop.label())) {
                    throw cont;
                }
            }
        }
    }

    private void executeWhile(PlSqlAst.While whileStmt, Scope scope) {
        while (true) {
            checkCancelled();
            if (!evaluateCondition(whileStmt.condition())) {
                return;
            }
            try {
                for (PlSqlAst.Stmt statement : whileStmt.body()) {
                    execute(statement, scope);
                }
            } catch (Signals.ExitSignal exit) {
                if (exit.label == null || exit.label.equalsIgnoreCase(whileStmt.label())) {
                    return;
                }
                throw exit;
            } catch (Signals.ContinueSignal cont) {
                if (cont.label != null && !cont.label.equalsIgnoreCase(whileStmt.label())) {
                    throw cont;
                }
            }
        }
    }

    /** {@code FOR i IN [REVERSE] a..b LOOP}：闭区间；列表形式为兼容写法。 */
    private void executeFor(PlSqlAst.For forStmt, Scope scope) {
        if (forStmt.values() != null) {
            executeForEach(forStmt, scope);
            return;
        }
        long from = toLong(evaluate(forStmt.from()), forStmt.line());
        long to = toLong(evaluate(forStmt.to()), forStmt.line());
        // 闭区间 + 方向由 REVERSE 决定：
        //   1..5 → 1,2,3,4,5；5..1 → 0 次；REVERSE 5..1 → 5,4,3,2,1；REVERSE 1..5 → 0 次
        long step = forStmt.reverse() ? -1 : 1;
        long start = from;
        long end = to;

        Scope loopScope = new Scope(scope);
        loopScope.declare(forStmt.variable(), PlType.BIGINT, false, true);
        for (long i = start; step > 0 ? i <= end : i >= end; i += step) {
            checkCancelled();
            loopScope.requireVariable(forStmt.variable(), forStmt.line()).value = PlValue.of(PlType.BIGINT, i);
            if (!runLoopBody(forStmt.body(), loopScope, forStmt.label())) {
                return;
            }
        }
    }

    /** 列表形式：{@code FOR i IN [v1, v2] LOOP}（历史兼容）。 */
    private void executeForEach(PlSqlAst.For forStmt, Scope scope) {
        List<Object> items = new ArrayList<>();
        for (Expr item : forStmt.values()) {
            items.add(evaluate(item));
        }
        if (forStmt.reverse()) {
            java.util.Collections.reverse(items);
        }
        PlType itemType = items.isEmpty() ? PlType.TEXT : PlType.of(items.get(0));
        Scope loopScope = new Scope(scope);
        loopScope.declare(forStmt.variable(), itemType, false, true);
        for (Object item : items) {
            checkCancelled();
            Object converted;
            try {
                converted = Coercions.convert(item, itemType);
            } catch (PlSqlTypeException e) {
                throw new PlSqlException(e.getMessage(), e.getSqlState(), e);
            }
            loopScope.requireVariable(forStmt.variable(), forStmt.line()).value = PlValue.of(itemType, converted);
            if (!runLoopBody(forStmt.body(), loopScope, forStmt.label())) {
                return;
            }
        }
    }

    /**
     * 执行循环体一轮。
     *
     * @return false 表示 {@code EXIT} 命中本循环（调用方应结束循环）
     */
    private boolean runLoopBody(List<PlSqlAst.Stmt> body, Scope scope, String label) {
        try {
            for (PlSqlAst.Stmt statement : body) {
                execute(statement, scope);
            }
            return true;
        } catch (Signals.ExitSignal exit) {
            if (exit.label == null || exit.label.equalsIgnoreCase(label)) {
                return false;
            }
            throw exit;
        } catch (Signals.ContinueSignal cont) {
            if (cont.label != null && !cont.label.equalsIgnoreCase(label)) {
                throw cont;
            }
            return true;
        }
    }

    private static long toLong(Object value, int line) {
        if (value == null) {
            throw new PlSqlException("FOR loop bounds must not be NULL", PlSqlException.SYNTAX_ERROR,
                    line, 1, null, null);
        }
        return new java.math.BigDecimal(String.valueOf(value)).longValue();
    }

    // ------------------------------------------------------------------
    // SQL 与变量
    // ------------------------------------------------------------------
    private void executeSql(String sql, Scope scope, int line) {
        SqlBinder.Bound bound = SqlBinder.hasBindReference(sql) ? SqlBinder.bind(sql, scope, line)
                : new SqlBinder.Bound(sql, List.of());
        StatementHandle handle = null;
        try {
            handle = host.execute(bound.sql(), bound.values());
        } catch (SQLException e) {
            throw wrapSqlException(e, sql);
        } finally {
            if (handle != null) {
                handle.close();
            }
        }
    }

    private void executeSelectInto(PlSqlAst.SelectInto selectInto, Scope scope) {
        String sql = selectInto.sqlAfter() == null || selectInto.sqlAfter().isBlank()
                ? selectInto.sqlBefore()
                : selectInto.sqlBefore() + " " + selectInto.sqlAfter();
        SqlBinder.Bound bound = SqlBinder.hasBindReference(sql) ? SqlBinder.bind(sql, scope, selectInto.line())
                : new SqlBinder.Bound(sql, List.of());

        StatementHandle handle = null;
        try {
            handle = host.execute(bound.sql(), bound.values());
            assignSingleRow(handle, selectInto.targets(), scope, selectInto.line(), "SELECT INTO");
        } catch (SQLException e) {
            throw wrapSqlException(e, sql);
        } finally {
            if (handle != null) {
                handle.close();
            }
        }
    }

    /**
     * 动态 SQL：{@code EXECUTE IMMEDIATE <sql> [INTO ...] [USING ...]}。
     *
     * <p>SQL 文本在运行期求值（字面量/变量/{@code ||} 拼接），{@code USING} 的值按出现顺序绑定到
     * 文本里的占位符（{@code ?} / {@code :1} / {@code :name}）。带 {@code INTO} 时按单行语义处理
     * （0 行 {@code P0002}、多行 {@code P0003}、列数不符 {@code 24000}）；不带 {@code INTO} 时
     * 结果集直接丢弃（语句本身已执行）。动态 SQL 自身的错误由后端给出，可被 {@code EXCEPTION} 段捕获。</p>
     */
    private void executeImmediate(PlSqlAst.ExecuteImmediate dynamic, Scope scope) {
        Object raw = evaluate(dynamic.sql());
        if (raw == null) {
            throw new PlSqlException("EXECUTE IMMEDIATE: dynamic SQL text is NULL",
                    PlSqlTypeException.NULL_NOT_ALLOWED, dynamic.line(), 1, null, null);
        }
        String text = raw instanceof String string ? string : String.valueOf(raw);

        List<Object> using = new ArrayList<>();
        for (Expr value : dynamic.using()) {
            using.add(evaluate(value));
        }
        SqlBinder.Bound bound = SqlBinder.bindDynamic(text, using, scope, dynamic.line());

        StatementHandle handle = null;
        try {
            handle = host.execute(bound.sql(), bound.values());
            if (dynamic.targets().isEmpty()) {
                return;
            }
            assignSingleRow(handle, dynamic.targets(), scope, dynamic.line(), "EXECUTE IMMEDIATE INTO");
        } catch (SQLException e) {
            throw wrapSqlException(e, text);
        } finally {
            if (handle != null) {
                handle.close();
            }
        }
    }

    /**
     * 单行结果写入变量：{@code SELECT INTO} 与 {@code EXECUTE IMMEDIATE ... INTO} 共用。
     *
     * @param what 用于错误消息的语句名
     */
    private void assignSingleRow(StatementHandle handle, List<String> targets, Scope scope, int line, String what)
            throws SQLException {
        if (handle.columnCount() != targets.size()) {
            throw new PlSqlException(what + " target count does not match column count",
                    PlSqlException.INVALID_CURSOR_STATE, line, 1, null, null);
        }
        if (!handle.next()) {
            throw new PlSqlException("no data found", PlSqlException.NO_DATA_FOUND, line, 1, null, null);
        }
        List<Object> row = new ArrayList<>();
        for (int i = 1; i <= handle.columnCount(); i++) {
            row.add(handle.get(i));
        }
        if (handle.next()) {
            throw new PlSqlException("too many rows", PlSqlException.TOO_MANY_ROWS, line, 1, null, null);
        }
        for (int i = 0; i < targets.size(); i++) {
            assign(scope, targets.get(i), row.get(i), line);
        }
    }

    private void executeAssign(PlSqlAst.Assign assign, Scope scope) {
        Scope.Variable variable = scope.requireVariable(assign.target(), assign.line());
        if (variable.constant) {
            throw new PlSqlException("Cannot assign to loop variable [" + assign.target() + "]",
                    PlSqlException.SYNTAX_ERROR, assign.line(), 1, null, null);
        }
        Object value = evaluate(assign.value());
        assign(scope, assign.target(), value, assign.line());
    }

    private void assign(Scope scope, String name, Object value, int line) {
        Scope.Variable variable = scope.requireVariable(name, line);
        Object converted;
        try {
            converted = Coercions.convert(value, variable.type);
        } catch (PlSqlTypeException e) {
            throw new PlSqlException(e.getMessage(), e.getSqlState(), line, 1, null, e);
        }
        if (converted == null && variable.notNull) {
            throw new PlSqlException("Variable [" + name + "] is NOT NULL",
                    PlSqlTypeException.NULL_NOT_ALLOWED, line, 1, null, null);
        }
        variable.value = new PlValue(variable.type, converted);
    }

    private void executeFetch(PlSqlAst.Fetch fetch, Scope scope) {
        Scope.Cursor cursor = scope.requireCursor(fetch.cursor(), fetch.line());
        if (!cursor.isOpen()) {
            throw new PlSqlException("Cursor [" + cursor.name + "] is not open",
                    PlSqlException.INVALID_CURSOR_STATE, fetch.line(), 1, null, null);
        }
        try {
            if (cursor.handle.columnCount() != fetch.targets().size()) {
                throw new PlSqlException("FETCH target count does not match column count",
                        PlSqlException.INVALID_CURSOR_STATE, fetch.line(), 1, null, null);
            }
            if (cursor.handle.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= cursor.handle.columnCount(); i++) {
                    row.add(cursor.handle.get(i));
                }
                cursor.found = true;
                cursor.notFound = false;
                cursor.rowCount++;
                for (int i = 0; i < fetch.targets().size(); i++) {
                    assign(scope, fetch.targets().get(i), row.get(i), fetch.line());
                }
            } else {
                // 到 EOF：变量保持原值，%NOTFOUND=true
                cursor.found = false;
                cursor.notFound = true;
            }
        } catch (SQLException e) {
            throw wrapSqlException(e, cursor.query);
        }
    }

    private void executeOpen(PlSqlAst.OpenCursor open, Scope scope) {
        Scope.Cursor cursor = scope.requireCursor(open.cursor(), open.line());
        if (cursor.isOpen()) {
            throw new PlSqlException("Cursor [" + cursor.name + "] is already open",
                    PlSqlException.INVALID_CURSOR_STATE, open.line(), 1, null, null);
        }
        SqlBinder.Bound bound = SqlBinder.hasBindReference(cursor.query)
                ? SqlBinder.bind(cursor.query, scope, open.line())
                : new SqlBinder.Bound(cursor.query, List.of());
        try {
            cursor.handle = host.execute(bound.sql(), bound.values());
        } catch (SQLException e) {
            throw wrapSqlException(e, cursor.query);
        }
        cursor.opened = true;
        cursor.found = false;
        cursor.notFound = false;
        cursor.rowCount = 0;
    }

    private void executeClose(PlSqlAst.CloseCursor close, Scope scope) {
        Scope.Cursor cursor = scope.requireCursor(close.cursor(), close.line());
        if (!cursor.isOpen()) {
            throw new PlSqlException("Cursor [" + cursor.name + "] is not open",
                    PlSqlException.INVALID_CURSOR_STATE, close.line(), 1, null, null);
        }
        if (cursor.handle != null) {
            cursor.handle.close();
            cursor.handle = null;
        }
        cursor.opened = false;
    }

    private void executeRaise(PlSqlAst.Raise raise) {
        if (raise.exceptionName() == null) {
            // RAISE; 重抛当前异常
            if (currentException != null) {
                throw currentException;
            }
            throw new PlSqlException("RAISE without an active exception",
                    PlSqlException.RAISE_ERROR, raise.line(), 1, null, null);
        }
        throw new Signals.RaisedException(raise.exceptionName(), PlSqlException.RAISE_ERROR,
                raise.exceptionName(), null);
    }

    // ------------------------------------------------------------------
    // 求值与工具
    // ------------------------------------------------------------------
    private Object evaluate(Expr expr) {
        try {
            return evaluator.evaluate(expr, resolver);
        } catch (SQLException e) {
            throw wrapSqlException(e);
        }
    }

    private boolean evaluateCondition(Expr expr) {
        try {
            return evaluator.evaluateCondition(expr, resolver);
        } catch (SQLException e) {
            throw wrapSqlException(e);
        }
    }

    private Object cursorAttribute(Scope.Cursor cursor, Expr.CursorAttr.Attr attr) {
        return switch (attr) {
            case FOUND -> cursor.found;
            case NOTFOUND -> cursor.notFound;
            case ROWCOUNT -> cursor.rowCount;
            case ISOPEN -> cursor.isOpen();
        };
    }

    private void checkCancelled() {
        host.checkInterrupted();
    }

    private static PlSqlException wrapSqlException(SQLException e) {
        return wrapSqlException(e, null);
    }

    /**
     * 包装后端 SQL 错误。
     *
     * @param sqlText 出错语句原文（供 {@link PlSqlException#getSqlText()} 定位）；拿不到时传 null
     */
    private static PlSqlException wrapSqlException(SQLException e, String sqlText) {
        String sqlState = e.getSQLState();
        return new PlSqlException(e.getMessage(),
                sqlState == null || sqlState.isBlank() ? PlSqlException.INTERNAL_ERROR : sqlState,
                0, 0, sqlText, e);
    }

    /** 供审计/调试：已执行语句数。 */
    public long statementCount() {
        return statements;
    }
}
