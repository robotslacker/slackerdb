package org.slackerdb.plsql.parse;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.slackerdb.plsql.ast.Expr;
import org.slackerdb.plsql.ast.PlSqlAst;
import org.slackerdb.plsql.block.PlSqlBlockParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 把结构文法解析树转成 {@link PlSqlAst}。
 *
 * <p>文本抽取要点：解析跑在**遮罩后的 Skepeton** 上，所有结构 token 的位置与原文
 * 一致（见 {@link PlSqlSourcePreparer}），因此表达式文本用
 * {@link PlSqlSourcePreparer.PreparedSource#originalOffset(int)} 映射回原文后取出；
 * SQL 文本直接从遮罩表按 id 取原文。</p>
 */
final class AstBuilder {

    private final PlSqlSourcePreparer.PreparedSource source;
    private final String original;
    private final ExpressionBuilder expressions;

    AstBuilder(PlSqlSourcePreparer.PreparedSource source, String original) {
        this.source = source;
        this.original = original;
        this.expressions = new ExpressionBuilder(source, original);
    }

    // ---------- 块 ----------
    PlSqlAst.Block block(PlSqlBlockParser.BlockContext ctx) {
        List<PlSqlAst.Decl> declares = new ArrayList<>();
        if (ctx.declare_section() != null) {
            for (PlSqlBlockParser.ItemContext item : ctx.declare_section().item()) {
                if (item.variable_decl() != null) {
                    declares.add(variableDecl(item.variable_decl()));
                } else if (item.cursor_decl() != null) {
                    declares.add(cursorDecl(item.cursor_decl()));
                }
            }
        }
        return new PlSqlAst.Block(declares, statements(ctx.body().statement()), handlers(ctx.body()), line(ctx));
    }

    private PlSqlAst.VariableDecl variableDecl(PlSqlBlockParser.Variable_declContext ctx) {
        Expr defaultValue = ctx.expr() == null ? null : expr(ctx.expr());
        return new PlSqlAst.VariableDecl(
                name(ctx.name_ref()), ctx.type_ref().getText(), ctx.NOT() != null, defaultValue, line(ctx));
    }

    private PlSqlAst.CursorDecl cursorDecl(PlSqlBlockParser.Cursor_declContext ctx) {
        List<PlSqlAst.Param> parameters = new ArrayList<>();
        if (ctx.parameter_list() != null) {
            for (PlSqlBlockParser.ParameterContext parameter : ctx.parameter_list().parameter()) {
                parameters.add(new PlSqlAst.Param(name(parameter.name_ref()), parameter.type_ref().getText()));
            }
        }
        StringBuilder query = new StringBuilder();
        for (TerminalNode segment : ctx.SQL_SEGMENT()) {
            int id = Integer.parseInt(segment.getText().substring("_SQL_".length()).replace("_", ""));
            PlSqlSourcePreparer.MaskedSql masked = source.maskedById(id);
            if (masked != null) {
                query.append(masked.text);
            }
        }
        return new PlSqlAst.CursorDecl(ctx.IDENTIFIER().getText(), parameters, query.toString(), line(ctx));
    }

    private List<PlSqlAst.Handler> handlers(PlSqlBlockParser.BodyContext body) {
        List<PlSqlAst.Handler> handlers = new ArrayList<>();
        if (body.exception_section() == null) {
            return handlers;
        }
        PlSqlBlockParser.Exception_sectionContext section = body.exception_section();
        if (section.handler().isEmpty()) {
            // 历史写法 `EXCEPTION:` + 语句：等价于 WHEN OTHERS
            handlers.add(new PlSqlAst.Handler(List.of("OTHERS"), statements(section.statement()),
                    line(section)));
            return handlers;
        }
        for (PlSqlBlockParser.HandlerContext ctx : section.handler()) {
            List<String> exceptions = new ArrayList<>();
            for (PlSqlBlockParser.Exception_nameContext name : ctx.exception_name()) {
                exceptions.add(name.getText().toUpperCase());
            }
            handlers.add(new PlSqlAst.Handler(exceptions, statements(ctx.statement()), line(ctx)));
        }
        return handlers;
    }

    // ---------- 语句 ----------
    private List<PlSqlAst.Stmt> statements(List<PlSqlBlockParser.StatementContext> contexts) {
        List<PlSqlAst.Stmt> statements = new ArrayList<>();
        for (PlSqlBlockParser.StatementContext ctx : contexts) {
            statements.add(statement(ctx));
        }
        return statements;
    }

    private PlSqlAst.Stmt statement(PlSqlBlockParser.StatementContext ctx) {
        if (ctx.sql_stmt() != null) {
            return new PlSqlAst.Sql(sqlText(ctx.sql_stmt().SQL_SEGMENT()), line(ctx));
        }
        if (ctx.select_into_stmt() != null) {
            PlSqlBlockParser.Select_into_stmtContext selectInto = ctx.select_into_stmt();
            List<TerminalNode> segments = selectInto.SQL_SEGMENT();
            String before = sqlText(segments.subList(0, 1));
            String after = segments.size() > 1 ? sqlText(segments.subList(1, segments.size())) : "";
            return new PlSqlAst.SelectInto(before, targets(selectInto.target_list()), after, line(ctx));
        }
        if (ctx.execute_immediate_stmt() != null) {
            PlSqlBlockParser.Execute_immediate_stmtContext execute = ctx.execute_immediate_stmt();
            List<Expr> using = new ArrayList<>();
            if (execute.expr_list() != null) {
                for (PlSqlBlockParser.ExprContext value : execute.expr_list().expr()) {
                    using.add(expr(value));
                }
            }
            List<String> intoTargets = execute.target_list() == null
                    ? List.of()
                    : targets(execute.target_list());
            return new PlSqlAst.ExecuteImmediate(expr(execute.expr()), intoTargets, using, line(ctx));
        }
        if (ctx.assignment() != null) {
            PlSqlBlockParser.AssignmentContext assignment = ctx.assignment();
            boolean legacyLet = assignment.LET() != null;
            return new PlSqlAst.Assign(name(assignment.name_ref()), expr(assignment.expr()),
                    legacyLet, line(ctx));
        }
        if (ctx.if_stmt() != null) {
            return ifStatement(ctx.if_stmt());
        }
        if (ctx.loop_stmt() != null) {
            return new PlSqlAst.Loop(label(ctx.loop_stmt().label_decl()),
                    statements(ctx.loop_stmt().statement()), line(ctx));
        }
        if (ctx.while_stmt() != null) {
            return new PlSqlAst.While(label(ctx.while_stmt().label_decl()),
                    expr(ctx.while_stmt().expr()),
                    statements(ctx.while_stmt().statement()), line(ctx));
        }
        if (ctx.for_stmt() != null) {
            PlSqlBlockParser.For_stmtContext forStmt = ctx.for_stmt();
            List<Expr> values = null;
            Expr from = null;
            Expr to = null;
            if (forStmt.list() != null) {
                values = new ArrayList<>();
                if (forStmt.list().expr_list() != null) {
                    for (PlSqlBlockParser.ExprContext item : forStmt.list().expr_list().expr()) {
                        values.add(expr(item));
                    }
                }
            } else {
                from = expr(forStmt.expr(0));
                to = expr(forStmt.expr(1));
            }
            return new PlSqlAst.For(label(forStmt.label_decl()), name(forStmt.name_ref()),
                    forStmt.REVERSE() != null, from, to, values,
                    statements(forStmt.statement()), line(ctx));
        }
        if (ctx.exit_stmt() != null) {
            PlSqlBlockParser.Exit_stmtContext exit = ctx.exit_stmt();
            return new PlSqlAst.Exit(exit.IDENTIFIER() == null ? null : exit.IDENTIFIER().getText(),
                    exit.expr() == null ? null : expr(exit.expr()), exit.BREAK() != null, line(ctx));
        }
        if (ctx.continue_stmt() != null) {
            PlSqlBlockParser.Continue_stmtContext cont = ctx.continue_stmt();
            return new PlSqlAst.Continue(cont.IDENTIFIER() == null ? null : cont.IDENTIFIER().getText(),
                    cont.expr() == null ? null : expr(cont.expr()), line(ctx));
        }
        if (ctx.fetch_stmt() != null) {
            PlSqlBlockParser.Fetch_stmtContext fetch = ctx.fetch_stmt();
            return new PlSqlAst.Fetch(fetch.IDENTIFIER().getText(), targets(fetch.target_list()), line(ctx));
        }
        if (ctx.open_stmt() != null) {
            PlSqlBlockParser.Open_stmtContext open = ctx.open_stmt();
            String args = open.expr_list() == null ? null : text(open.expr_list());
            return new PlSqlAst.OpenCursor(open.IDENTIFIER().getText(), args, line(ctx));
        }
        if (ctx.close_stmt() != null) {
            return new PlSqlAst.CloseCursor(ctx.close_stmt().IDENTIFIER().getText(), line(ctx));
        }
        if (ctx.raise_stmt() != null) {
            PlSqlBlockParser.Raise_stmtContext raise = ctx.raise_stmt();
            return new PlSqlAst.Raise(raise.IDENTIFIER() == null ? null : raise.IDENTIFIER().getText(), line(ctx));
        }
        if (ctx.return_stmt() != null) {
            return new PlSqlAst.Return(line(ctx));
        }
        if (ctx.commit_stmt() != null) {
            return new PlSqlAst.Commit(line(ctx));
        }
        if (ctx.rollback_stmt() != null) {
            return new PlSqlAst.Rollback(line(ctx));
        }
        if (ctx.null_stmt() != null) {
            return new PlSqlAst.Null(line(ctx));
        }
        if (ctx.nested_block() != null) {
            PlSqlBlockParser.Nested_blockContext nested = ctx.nested_block();
            List<PlSqlAst.Decl> declares = new ArrayList<>();
            if (nested.declare_section() != null) {
                for (PlSqlBlockParser.ItemContext item : nested.declare_section().item()) {
                    if (item.variable_decl() != null) {
                        declares.add(variableDecl(item.variable_decl()));
                    } else if (item.cursor_decl() != null) {
                        declares.add(cursorDecl(item.cursor_decl()));
                    }
                }
            }
            PlSqlAst.Block block = new PlSqlAst.Block(declares, statements(nested.body().statement()),
                    handlers(nested.body()), line(ctx));
            return new PlSqlAst.Nested(block, line(ctx));
        }
        throw new IllegalStateException("未识别的语句：" + ctx.getText());
    }

    private PlSqlAst.Stmt ifStatement(PlSqlBlockParser.If_stmtContext ctx) {
        List<PlSqlAst.Branch> branches = new ArrayList<>();
        branches.add(new PlSqlAst.Branch(expr(ctx.expr()), statements(ctx.statement())));
        for (PlSqlBlockParser.Elsif_branchContext elsif : ctx.elsif_branch()) {
            branches.add(new PlSqlAst.Branch(expr(elsif.expr()), statements(elsif.statement())));
        }
        List<PlSqlAst.Stmt> elseBody = ctx.else_branch() == null
                ? List.of()
                : statements(ctx.else_branch().statement());
        return new PlSqlAst.If(branches, elseBody, line(ctx));
    }

    // ---------- 基础工具 ----------
    private List<String> targets(PlSqlBlockParser.Target_listContext ctx) {
        List<String> targets = new ArrayList<>();
        for (PlSqlBlockParser.Name_refContext name : ctx.name_ref()) {
            targets.add(name(name));
        }
        return targets;
    }

    private String name(PlSqlBlockParser.Name_refContext ctx) {
        String text = ctx.getText();
        if (text.startsWith(":")) {
            text = text.substring(1);
        }
        return text;
    }

    /** 表达式：结构文法 → 表达式树（P5）。 */
    private Expr expr(PlSqlBlockParser.ExprContext ctx) {
        return expressions.build(ctx);
    }

    /** 取语法节点对应的**原文**文本。 */
    private String text(ParserRuleContext ctx) {
        return slice(ctx.getStart(), ctx.getStop());
    }

    private String slice(Token start, Token stop) {
        int from = source.originalOffset(start.getStartIndex());
        int to = source.originalOffset(stop.getStopIndex()) + 1;
        from = Math.max(0, Math.min(from, original.length()));
        to = Math.max(from, Math.min(to, original.length()));
        return original.substring(from, to);
    }

    /** 把若干遮罩片段按 id 还原成原文 SQL。 */
    private String sqlText(List<TerminalNode> segments) {
        StringBuilder sql = new StringBuilder();
        for (TerminalNode segment : segments) {
            int id = Integer.parseInt(segment.getText().substring("_SQL_".length()).replace("_", ""));
            PlSqlSourcePreparer.MaskedSql masked = source.maskedById(id);
            if (masked != null) {
                sql.append(masked.text);
            }
        }
        return sql.toString().trim();
    }

    private static int line(ParserRuleContext ctx) {
        return ctx.getStart().getLine();
    }

    /** 循环标签（{@code <<name>>}），无标签返回 null。 */
    private static String label(PlSqlBlockParser.Label_declContext ctx) {
        return ctx == null ? null : ctx.IDENTIFIER().getText();
    }
}
