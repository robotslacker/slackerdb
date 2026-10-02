package org.slackerdb.plsql.harness;

import org.slackerdb.plsql.ast.PlSqlAst;

import java.util.StringJoiner;

/**
 * AST 的**结构摘要**（仅供测试断言用）：只保留控制流形状，不含 SQL/表达式原文，
 * 这样用例既能锁定"文法如何解释"，又不会因为 SQL 文本微调而脆断。
 *
 * <p>形如：{@code block(decls=[var,cursor],stmts=[if[b=2,else=1],loop[exit],sql],handlers=[OTHERS[sql]])}</p>
 */
final class AstShape {

    private AstShape() {
    }

    static String of(PlSqlAst.Block block) {
        StringJoiner declares = new StringJoiner(",", "[", "]");
        for (PlSqlAst.Decl decl : block.declares()) {
            if (decl instanceof PlSqlAst.VariableDecl variable) {
                declares.add(variable.notNull() ? "var!" : "var");
            } else if (decl instanceof PlSqlAst.CursorDecl) {
                declares.add("cursor");
            }
        }

        StringBuilder statements = new StringBuilder("[");
        for (int i = 0; i < block.statements().size(); i++) {
            if (i > 0) {
                statements.append(',');
            }
            statements.append(stmt(block.statements().get(i)));
        }
        statements.append(']');

        StringBuilder handlers = new StringBuilder("[");
        for (int i = 0; i < block.handlers().size(); i++) {
            if (i > 0) {
                handlers.append(',');
            }
            PlSqlAst.Handler handler = block.handlers().get(i);
            handlers.append(String.join("|", handler.exceptions())).append('[');
            for (int j = 0; j < handler.body().size(); j++) {
                if (j > 0) {
                    handlers.append(',');
                }
                handlers.append(stmt(handler.body().get(j)));
            }
            handlers.append(']');
        }
        handlers.append(']');

        return "block(decls=" + declares + ",stmts=" + statements + ",handlers=" + handlers + ")";
    }

    private static String stmt(PlSqlAst.Stmt stmt) {
        if (stmt instanceof PlSqlAst.Sql) {
            return "sql";
        }
        if (stmt instanceof PlSqlAst.SelectInto selectInto) {
            return "into:" + selectInto.targets().size();
        }
        if (stmt instanceof PlSqlAst.ExecuteImmediate dynamic) {
            return "executeImmediate"
                    + (dynamic.targets().isEmpty() ? "" : ":into" + dynamic.targets().size())
                    + (dynamic.using().isEmpty() ? "" : ":using" + dynamic.using().size());
        }
        if (stmt instanceof PlSqlAst.Assign assign) {
            return assign.legacyLet() ? "let:" + assign.target() : "assign:" + assign.target();
        }
        if (stmt instanceof PlSqlAst.If ifStmt) {
            StringBuilder sb = new StringBuilder("if[b=").append(ifStmt.branches().size());
            if (!ifStmt.elseBody().isEmpty()) {
                sb.append(",else=").append(ifStmt.elseBody().size());
            }
            int total = 0;
            for (PlSqlAst.Branch branch : ifStmt.branches()) {
                total += branch.body().size();
            }
            sb.append(",stmts=").append(total).append(']');
            return sb.toString();
        }
        if (stmt instanceof PlSqlAst.Loop loop) {
            return "loop" + bodies(loop.body());
        }
        if (stmt instanceof PlSqlAst.While whileStmt) {
            return "while" + bodies(whileStmt.body());
        }
        if (stmt instanceof PlSqlAst.For forStmt) {
            return "for" + (forStmt.reverse() ? "r" : "") + bodies(forStmt.body());
        }
        if (stmt instanceof PlSqlAst.Exit exit) {
            return "exit" + (exit.isBreak() ? "(break)" : "") + (exit.condition() != null ? "(when)" : "")
                    + (exit.label() != null ? "(label)" : "");
        }
        if (stmt instanceof PlSqlAst.Continue cont) {
            return "continue" + (cont.condition() != null ? "(when)" : "");
        }
        if (stmt instanceof PlSqlAst.Fetch fetch) {
            return "fetch:" + fetch.cursor() + "/" + fetch.targets().size();
        }
        if (stmt instanceof PlSqlAst.OpenCursor open) {
            return "open:" + open.cursor() + (open.argumentsText() != null ? "(args)" : "");
        }
        if (stmt instanceof PlSqlAst.CloseCursor) {
            return "close";
        }
        if (stmt instanceof PlSqlAst.Raise raise) {
            return raise.exceptionName() == null ? "raise" : "raise:" + raise.exceptionName();
        }
        if (stmt instanceof PlSqlAst.Return) {
            return "return";
        }
        if (stmt instanceof PlSqlAst.Commit) {
            return "commit";
        }
        if (stmt instanceof PlSqlAst.Rollback) {
            return "rollback";
        }
        if (stmt instanceof PlSqlAst.Null) {
            return "null";
        }
        if (stmt instanceof PlSqlAst.Nested nested) {
            return "nested" + bodies(nested.block().statements());
        }
        return stmt.getClass().getSimpleName();
    }

    private static String bodies(java.util.List<PlSqlAst.Stmt> statements) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < statements.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(stmt(statements.get(i)));
        }
        return sb.append(']').toString();
    }
}
