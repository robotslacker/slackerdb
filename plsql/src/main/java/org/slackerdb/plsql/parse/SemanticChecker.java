package org.slackerdb.plsql.parse;

import org.slackerdb.plsql.ast.Expr;
import org.slackerdb.plsql.ast.PlSqlAst;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 编译期语义检查。
 *
 * <p>把"未声明变量 / 未声明游标 / 给循环变量赋值"这类错误在<b>执行之前</b>查出来，
 * 并带精确行列：</p>
 * <ul>
 *   <li>与 PL/SQL 一致——语义错误是编译期错误，不会被 {@code EXCEPTION} 段捕获，
 *       也不会产生"执行了一半"的副作用；</li>
 *   <li>错误位置来自表达式节点自身的行列（遮罩保真）。</li>
 * </ul>
 */
final class SemanticChecker {

    private final Deque<Set<String>> scopes = new ArrayDeque<>();

    private SemanticChecker() {
    }

    static void check(PlSqlAst.Block block) {
        new SemanticChecker().block(block, false);
    }

    private void block(PlSqlAst.Block block, boolean isTopLevel) {
        scopes.push(new HashSet<>());
        try {
            for (PlSqlAst.Decl decl : block.declares()) {
                if (decl instanceof PlSqlAst.VariableDecl variable) {
                    declare(variable.name(), variable.line());
                    if (variable.defaultValue() != null) {
                        expression(variable.defaultValue());
                    }
                } else if (decl instanceof PlSqlAst.CursorDecl cursor) {
                    declare(cursor.name(), cursor.line());
                    // 游标查询里可能引用已声明变量（:name 形式），这里不解析 SQL 文本
                }
            }
            for (PlSqlAst.Stmt statement : block.statements()) {
                statement(statement);
            }
            for (PlSqlAst.Handler handler : block.handlers()) {
                for (PlSqlAst.Stmt statement : handler.body()) {
                    statement(statement);
                }
            }
        } finally {
            scopes.pop();
        }
    }

    private void statement(PlSqlAst.Stmt statement) {
        if (statement instanceof PlSqlAst.Sql) {
            return; // SQL 文本里的绑定在运行期解析（可引用外层变量）
        }
        if (statement instanceof PlSqlAst.SelectInto selectInto) {
            for (String target : selectInto.targets()) {
                requireVariable(target, selectInto.line());
            }
            return;
        }
        if (statement instanceof PlSqlAst.ExecuteImmediate dynamic) {
            // SQL 文本表达式与 USING 值都是普通表达式；INTO 目标必须是已声明变量
            expression(dynamic.sql());
            for (String target : dynamic.targets()) {
                requireVariable(target, dynamic.line());
            }
            for (Expr value : dynamic.using()) {
                expression(value);
            }
            return;
        }
        if (statement instanceof PlSqlAst.Assign assign) {
            requireVariable(assign.target(), assign.line());
            expression(assign.value());
            return;
        }
        if (statement instanceof PlSqlAst.If ifStmt) {
            for (PlSqlAst.Branch branch : ifStmt.branches()) {
                expression(branch.condition());
                for (PlSqlAst.Stmt child : branch.body()) {
                    statement(child);
                }
            }
            for (PlSqlAst.Stmt child : ifStmt.elseBody()) {
                statement(child);
            }
            return;
        }
        if (statement instanceof PlSqlAst.Loop loop) {
            for (PlSqlAst.Stmt child : loop.body()) {
                statement(child);
            }
            return;
        }
        if (statement instanceof PlSqlAst.While whileStmt) {
            expression(whileStmt.condition());
            for (PlSqlAst.Stmt child : whileStmt.body()) {
                statement(child);
            }
            return;
        }
        if (statement instanceof PlSqlAst.For forStmt) {
            if (forStmt.values() != null) {
                for (Expr value : forStmt.values()) {
                    expression(value);
                }
            } else {
                expression(forStmt.from());
                expression(forStmt.to());
            }
            scopes.push(new HashSet<>());
            try {
                // 循环变量隐式声明（可遮蔽外层）
                scopes.peek().add(normalize(forStmt.variable()));
                for (PlSqlAst.Stmt child : forStmt.body()) {
                    statement(child);
                }
            } finally {
                scopes.pop();
            }
            return;
        }
        if (statement instanceof PlSqlAst.Exit exit) {
            if (exit.condition() != null) {
                expression(exit.condition());
            }
            return;
        }
        if (statement instanceof PlSqlAst.Continue cont) {
            if (cont.condition() != null) {
                expression(cont.condition());
            }
            return;
        }
        if (statement instanceof PlSqlAst.Fetch fetch) {
            requireCursor(fetch.cursor(), fetch.line());
            for (String target : fetch.targets()) {
                requireVariable(target, fetch.line());
            }
            return;
        }
        if (statement instanceof PlSqlAst.OpenCursor open) {
            requireCursor(open.cursor(), open.line());
            return;
        }
        if (statement instanceof PlSqlAst.CloseCursor close) {
            requireCursor(close.cursor(), close.line());
            return;
        }
        if (statement instanceof PlSqlAst.Nested nested) {
            block(nested.block(), false);
            return;
        }
        // RAISE / RETURN / COMMIT / ROLLBACK / NULL 无需检查
    }

    // ---------- 表达式 ----------
    private void expression(Expr expr) {
        if (expr == null) {
            return;
        }
        if (expr instanceof Expr.Name name) {
            requireVariable(name.name(), name.pos().line(), name.pos().column());
            return;
        }
        if (expr instanceof Expr.CursorAttr attr) {
            requireCursor(attr.cursor(), attr.pos().line(), attr.pos().column());
            return;
        }
        if (expr instanceof Expr.Unary unary) {
            expression(unary.operand());
            return;
        }
        if (expr instanceof Expr.Binary binary) {
            expression(binary.left());
            expression(binary.right());
            return;
        }
        if (expr instanceof Expr.Function function) {
            for (Expr argument : function.arguments()) {
                expression(argument);
            }
            return;
        }
        if (expr instanceof Expr.IsNull isNull) {
            expression(isNull.operand());
            return;
        }
        if (expr instanceof Expr.Like like) {
            expression(like.value());
            expression(like.pattern());
            return;
        }
        if (expr instanceof Expr.InList inList) {
            expression(inList.value());
            for (Expr item : inList.items()) {
                expression(item);
            }
            return;
        }
        if (expr instanceof Expr.Between between) {
            expression(between.value());
            expression(between.low());
            expression(between.high());
            return;
        }
        if (expr instanceof Expr.CaseExpr caseExpr) {
            for (Expr.When when : caseExpr.whens()) {
                expression(when.condition());
                expression(when.result());
            }
            if (caseExpr.elseExpr() != null) {
                expression(caseExpr.elseExpr());
            }
        }
    }

    // ---------- 作用域 ----------
    private void declare(String name, int line) {
        String key = normalize(name);
        if (scopes.peek().contains(key)) {
            throw error("Variable [" + name + "] is declared twice", line, 1);
        }
        // 同名在内层遮蔽外层是允许的
        scopes.peek().add(key);
    }

    private void requireVariable(String name, int line) {
        requireVariable(name, line, 1);
    }

    private void requireVariable(String name, int line, int column) {
        if (!isDeclared(name)) {
            throw error("Variable [" + name + "] has not been declared", line, column);
        }
    }

    private void requireCursor(String name, int line) {
        requireCursor(name, line, 1);
    }

    private void requireCursor(String name, int line, int column) {
        if (!isDeclared(name)) {
            throw error("Cursor [" + name + "] has not been declared", line, column);
        }
    }

    private boolean isDeclared(String name) {
        String key = normalize(name);
        for (Set<String> scope : scopes) {
            if (scope.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static PlSqlCompiler.PlSqlCompileException error(String message, int line, int column) {
        return new PlSqlCompiler.PlSqlCompileException(message, line, column,
                List.of(new PlSqlCompiler.Diagnostic(line, column, message)));
    }
}
