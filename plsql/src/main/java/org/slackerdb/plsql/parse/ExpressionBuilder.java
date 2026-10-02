package org.slackerdb.plsql.parse;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.slackerdb.plsql.ast.Expr;
import org.slackerdb.plsql.block.PlSqlBlockParser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 结构文法解析树 → {@link Expr} 表达式树。
 *
 * <p>字面量的值在这里就地解析（数字 → BigDecimal，字符串 → 去引号并还原 {@code ''}，
 * 布尔/NULL → 对应值）；变量与游标属性保留为绑定来源。</p>
 */
final class ExpressionBuilder {

    private final PlSqlSourcePreparer.PreparedSource source;
    private final String original;

    ExpressionBuilder(PlSqlSourcePreparer.PreparedSource source, String original) {
        this.source = source;
        this.original = original;
    }

    Expr build(PlSqlBlockParser.ExprContext ctx) {
        return or(ctx.or_expr());
    }

    // ---------- 逻辑 ----------
    private Expr or(PlSqlBlockParser.Or_exprContext ctx) {
        List<PlSqlBlockParser.And_exprContext> parts = ctx.and_expr();
        Expr left = and(parts.get(0));
        for (int i = 1; i < parts.size(); i++) {
            left = new Expr.Binary(pos(ctx), Expr.Op.OR, left, and(parts.get(i)));
        }
        return left;
    }

    private Expr and(PlSqlBlockParser.And_exprContext ctx) {
        List<PlSqlBlockParser.Not_exprContext> parts = ctx.not_expr();
        Expr left = not(parts.get(0));
        for (int i = 1; i < parts.size(); i++) {
            // AND 与兼容写法 && 语义相同
            left = new Expr.Binary(pos(ctx), Expr.Op.AND, left, not(parts.get(i)));
        }
        return left;
    }

    private Expr not(PlSqlBlockParser.Not_exprContext ctx) {
        if (ctx.NOT() != null) {
            return new Expr.Unary(pos(ctx), Expr.Op.NOT, not(ctx.not_expr()));
        }
        return comparison(ctx.comparison());
    }

    // ---------- 比较 ----------
    private Expr comparison(PlSqlBlockParser.ComparisonContext ctx) {
        Expr left = additive(ctx.additive());
        PlSqlBlockParser.Comparison_tailContext tail = ctx.comparison_tail();
        if (tail == null) {
            return left;
        }
        if (tail.IS() != null) {
            return new Expr.IsNull(pos(ctx), left, tail.NOT() != null);
        }
        if (tail.LIKE() != null) {
            return new Expr.Like(pos(ctx), left, additive(tail.additive(0)), tail.NOT() != null);
        }
        if (tail.IN() != null) {
            List<Expr> items = new ArrayList<>();
            for (PlSqlBlockParser.ExprContext item : tail.expr_list().expr()) {
                items.add(build(item));
            }
            return new Expr.InList(pos(ctx), left, items, tail.NOT() != null);
        }
        if (tail.BETWEEN() != null) {
            return new Expr.Between(pos(ctx), left, additive(tail.additive(0)),
                    additive(tail.additive(1)), tail.NOT() != null);
        }
        Token operator = tail.getStart();
        Expr.Op op = switch (operator.getType()) {
            case org.slackerdb.plsql.block.PlSqlBlockLexer.EQ, org.slackerdb.plsql.block.PlSqlBlockLexer.EQ2 ->
                    Expr.Op.EQ;
            case org.slackerdb.plsql.block.PlSqlBlockLexer.NEQ, org.slackerdb.plsql.block.PlSqlBlockLexer.NEQ2 ->
                    Expr.Op.NE;
            case org.slackerdb.plsql.block.PlSqlBlockLexer.LT -> Expr.Op.LT;
            case org.slackerdb.plsql.block.PlSqlBlockLexer.LTE -> Expr.Op.LE;
            case org.slackerdb.plsql.block.PlSqlBlockLexer.GT -> Expr.Op.GT;
            default -> Expr.Op.GE;
        };
        return new Expr.Binary(pos(ctx), op, left, additive(tail.additive(0)));
    }

    // ---------- 算术 ----------
    private Expr additive(PlSqlBlockParser.AdditiveContext ctx) {
        List<PlSqlBlockParser.MultiplicativeContext> parts = ctx.multiplicative();
        Expr left = multiplicative(parts.get(0));
        for (int i = 1; i < parts.size(); i++) {
            Token operator = ctx.op.get(i - 1);
            Expr.Op op = switch (operator.getType()) {
                case org.slackerdb.plsql.block.PlSqlBlockLexer.PLUS -> Expr.Op.ADD;
                case org.slackerdb.plsql.block.PlSqlBlockLexer.MINUS -> Expr.Op.SUB;
                default -> Expr.Op.CONCAT;
            };
            left = new Expr.Binary(pos(ctx), op, left, multiplicative(parts.get(i)));
        }
        return left;
    }

    private Expr multiplicative(PlSqlBlockParser.MultiplicativeContext ctx) {
        List<PlSqlBlockParser.UnaryContext> parts = ctx.unary();
        Expr left = unary(parts.get(0));
        for (int i = 1; i < parts.size(); i++) {
            Token operator = ctx.op.get(i - 1);
            Expr.Op op = switch (operator.getType()) {
                case org.slackerdb.plsql.block.PlSqlBlockLexer.STAR -> Expr.Op.MUL;
                case org.slackerdb.plsql.block.PlSqlBlockLexer.SLASH -> Expr.Op.DIV;
                default -> Expr.Op.MOD;
            };
            left = new Expr.Binary(pos(ctx), op, left, unary(parts.get(i)));
        }
        return left;
    }

    private Expr unary(PlSqlBlockParser.UnaryContext ctx) {
        if (ctx.unary() != null) {
            Expr.Op op = switch (ctx.getStart().getType()) {
                case org.slackerdb.plsql.block.PlSqlBlockLexer.MINUS -> Expr.Op.NEG;
                case org.slackerdb.plsql.block.PlSqlBlockLexer.PLUS -> Expr.Op.ADD;
                default -> Expr.Op.NOT;
            };
            if (op == Expr.Op.ADD) {
                // 一元正号无意义，直接透传操作数
                return unary(ctx.unary());
            }
            return new Expr.Unary(pos(ctx), op, unary(ctx.unary()));
        }
        return power(ctx.power());
    }

    private Expr power(PlSqlBlockParser.PowerContext ctx) {
        Expr base = primary(ctx.primary());
        if (ctx.POWER() == null) {
            return base;
        }
        return new Expr.Binary(pos(ctx), Expr.Op.POW, base, unary(ctx.unary()));
    }

    // ---------- 基本项 ----------
    private Expr primary(PlSqlBlockParser.PrimaryContext ctx) {
        // 注意判定顺序：函数调用与括号表达式都带 LPAREN，
        // 必须先判函数调用（它的参数在 expr_list 里，ctx.expr() 为空）。
        if (ctx.function_name() != null && ctx.LPAREN() != null) {
            List<Expr> arguments = new ArrayList<>();
            if (ctx.expr_list() != null) {
                for (PlSqlBlockParser.ExprContext argument : ctx.expr_list().expr()) {
                    arguments.add(build(argument));
                }
            }
            return new Expr.Function(pos(ctx), ctx.function_name().getText(), arguments);
        }
        if (ctx.PERCENT() != null) {
            String cursor = name(ctx.name_ref());
            Expr.CursorAttr.Attr attr = ctx.FOUND() != null ? Expr.CursorAttr.Attr.FOUND
                    : ctx.NOTFOUND() != null ? Expr.CursorAttr.Attr.NOTFOUND
                    : ctx.ROWCOUNT() != null ? Expr.CursorAttr.Attr.ROWCOUNT
                    : Expr.CursorAttr.Attr.ISOPEN;
            return new Expr.CursorAttr(pos(ctx), cursor, attr);
        }
        if (ctx.LPAREN() != null && !ctx.expr().isEmpty()) {
            return build(ctx.expr(0));
        }
        if (ctx.CASE() != null) {
            List<PlSqlBlockParser.ExprContext> parts = ctx.expr();
            int whenCount = ctx.WHEN().size();
            List<Expr.When> whens = new ArrayList<>();
            for (int i = 0; i < whenCount; i++) {
                whens.add(new Expr.When(build(parts.get(i * 2)), build(parts.get(i * 2 + 1))));
            }
            Expr elseExpr = parts.size() > whenCount * 2 ? build(parts.get(parts.size() - 1)) : null;
            return new Expr.CaseExpr(pos(ctx), whens, elseExpr);
        }
        if (ctx.NUMBER() != null) {
            String text = ctx.NUMBER().getText();
            return new Expr.Literal(pos(ctx), text, new BigDecimal(text));
        }
        if (ctx.STRING() != null) {
            String raw = ctx.STRING().getText();
            String value = raw.substring(1, raw.length() - 1).replace("''", "'");
            return new Expr.Literal(pos(ctx), raw, value);
        }
        if (ctx.TRUE() != null) {
            return new Expr.Literal(pos(ctx), ctx.TRUE().getText(), Boolean.TRUE);
        }
        if (ctx.FALSE() != null) {
            return new Expr.Literal(pos(ctx), ctx.FALSE().getText(), Boolean.FALSE);
        }
        if (ctx.NULL() != null) {
            return new Expr.Literal(pos(ctx), ctx.NULL().getText(), null);
        }
        return new Expr.Name(pos(ctx), name(ctx.name_ref()));
    }

    private String name(PlSqlBlockParser.Name_refContext ctx) {
        String text = ctx.getText();
        if (text.startsWith(":")) {
            text = text.substring(1);
        }
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() > 1) {
            text = text.substring(1, text.length() - 1).replace("\"\"", "\"");
        }
        return text;
    }

    private Expr.Pos pos(ParserRuleContext ctx) {
        return new Expr.Pos(ctx.getStart().getLine(), ctx.getStart().getCharPositionInLine() + 1);
    }

    /** 原文文本（错误消息用）。 */
    String text(ParserRuleContext ctx) {
        int from = source.originalOffset(ctx.getStart().getStartIndex());
        int to = source.originalOffset(ctx.getStop().getStopIndex()) + 1;
        from = Math.max(0, Math.min(from, original.length()));
        to = Math.max(from, Math.min(to, original.length()));
        return original.substring(from, to);
    }
}
