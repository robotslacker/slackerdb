package org.slackerdb.dbserver.service.filter;

import org.slackerdb.dbserver.service.filter.FilterAst.And;
import org.slackerdb.dbserver.service.filter.FilterAst.ColumnRef;
import org.slackerdb.dbserver.service.filter.FilterAst.Comparison;
import org.slackerdb.dbserver.service.filter.FilterAst.Expr;
import org.slackerdb.dbserver.service.filter.FilterAst.FilterProgram;
import org.slackerdb.dbserver.service.filter.FilterAst.FunctionCall;
import org.slackerdb.dbserver.service.filter.FilterAst.InList;
import org.slackerdb.dbserver.service.filter.FilterAst.IsNull;
import org.slackerdb.dbserver.service.filter.FilterAst.Like;
import org.slackerdb.dbserver.service.filter.FilterAst.Literal;
import org.slackerdb.dbserver.service.filter.FilterAst.Not;
import org.slackerdb.dbserver.service.filter.FilterAst.Op;
import org.slackerdb.dbserver.service.filter.FilterAst.Or;
import org.slackerdb.dbserver.service.filter.FilterAst.Predicate;
import org.slackerdb.dbserver.service.filter.FilterAst.SortKey;
import org.slackerdb.dbserver.service.filter.FilterLexer.Token;
import org.slackerdb.dbserver.service.filter.FilterLexer.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * filter 表达式的递归下降解析器。
 *
 * <p>语法见 {@code docs/design/api-filter.md}：WHERE 可选，后接可选的
 * ORDER BY / LIMIT / OFFSET。优先级为 {@code OR < AND < NOT < 比较/IS/IN/LIKE}。</p>
 *
 * <p>所有防护性上限（长度、嵌套深度、IN 元素数、排序键数量）都在这里执行，
 * 使得解析阶段就能拒绝明显的滥用输入。</p>
 */
final class FilterParser {

    static final int MAX_FILTER_LENGTH = 8192;
    static final int MAX_DEPTH = 32;
    static final int MAX_IN_ITEMS = 1000;
    static final int MAX_ORDER_KEYS = 32;

    private static final Set<String> SUPPORTED_FUNCTIONS = Set.of("lower", "upper", "trim", "length");

    /** 这些词在表达式里是保留字；与它们同名的列必须用双引号引用。 */
    private static final Set<String> RESERVED_KEYWORDS = Set.of(
            "AND", "OR", "NOT", "IS", "NULL", "IN", "LIKE", "ESCAPE",
            "ORDER", "BY", "ASC", "DESC", "NULLS", "FIRST", "LAST",
            "LIMIT", "OFFSET", "ALL", "WHERE");

    private final List<Token> tokens;
    private int position;
    private int depth;

    private FilterParser(List<Token> tokens) {
        this.tokens = tokens;
    }

    static FilterProgram parse(String text) throws FilterException {
        if (text == null) {
            throw new FilterException("filter is empty.");
        }
        if (text.length() > MAX_FILTER_LENGTH) {
            throw new FilterException("filter text exceeds " + MAX_FILTER_LENGTH + " characters.");
        }
        FilterParser parser = new FilterParser(FilterLexer.tokenize(text));
        return parser.parseProgram();
    }

    private FilterProgram parseProgram() throws FilterException {
        boolean sawClause = false;
        Predicate predicate = null;

        if (matchKeyword("WHERE")) {
            predicate = parseOr();
            sawClause = true;
        } else if (!isClauseStart() && !check(TokenType.EOF)) {
            predicate = parseOr();
            sawClause = true;
        }

        List<SortKey> orderBy = List.of();
        if (matchKeyword("ORDER")) {
            orderBy = parseOrderBy();
            sawClause = true;
        }

        Integer limit = null;
        int offset = 0;
        boolean sawLimit = false;
        boolean sawOffset = false;
        while (true) {
            if (!sawLimit && matchKeyword("LIMIT")) {
                limit = parseLimit();
                sawLimit = true;
                sawClause = true;
            } else if (!sawOffset && matchKeyword("OFFSET")) {
                offset = parseOffset();
                sawOffset = true;
                sawClause = true;
            } else {
                break;
            }
        }

        if (!check(TokenType.EOF)) {
            throw unexpected();
        }
        if (!sawClause) {
            throw error("empty filter expression");
        }
        return new FilterProgram(predicate, orderBy, limit, offset);
    }

    // ---------------------------------------------------------------- 谓词

    private Predicate parseOr() throws FilterException {
        Predicate left = parseAnd();
        while (matchKeyword("OR")) {
            left = new Or(left, parseAnd());
        }
        return left;
    }

    private Predicate parseAnd() throws FilterException {
        Predicate left = parseNot();
        while (matchKeyword("AND")) {
            left = new And(left, parseNot());
        }
        return left;
    }

    private Predicate parseNot() throws FilterException {
        // 迭代处理 NOT 链，避免 "NOT NOT NOT ..." 造成深递归
        int notCount = 0;
        while (matchKeyword("NOT")) {
            notCount++;
            if (notCount > MAX_DEPTH) {
                throw error("expression nesting exceeds " + MAX_DEPTH + " levels");
            }
        }
        Predicate predicate = parsePredicate();
        for (int i = 0; i < notCount; i++) {
            predicate = new Not(predicate);
        }
        return predicate;
    }

    private Predicate parsePredicate() throws FilterException {
        if (match(TokenType.LPAREN)) {
            enter();
            try {
                Predicate nested = parseOr();
                expect(TokenType.RPAREN, "')'");
                return nested;
            } finally {
                depth--;
            }
        }

        Expr left = parseOperand();

        if (matchKeyword("IS")) {
            boolean negated = matchKeyword("NOT");
            if (!matchKeyword("NULL")) {
                throw error("expected NULL after IS");
            }
            return new IsNull(left, negated);
        }
        if (matchKeyword("NOT")) {
            if (matchKeyword("IN")) {
                return parseIn(left, true);
            }
            if (matchKeyword("LIKE")) {
                return parseLike(left, true);
            }
            throw error("expected IN or LIKE after NOT");
        }
        if (matchKeyword("IN")) {
            return parseIn(left, false);
        }
        if (matchKeyword("LIKE")) {
            return parseLike(left, false);
        }

        Token operatorToken = peek();
        Op operator = matchOperator();
        if (operator == null) {
            throw error("expected a comparison operator");
        }
        Expr right = parseOperand();
        return new Comparison(left, operator, right, operatorToken.position());
    }

    private InList parseIn(Expr value, boolean negated) throws FilterException {
        expect(TokenType.LPAREN, "'('");
        if (check(TokenType.RPAREN)) {
            throw error("IN list must not be empty");
        }
        List<Expr> items = new ArrayList<>();
        do {
            items.add(parseOperand());
        } while (match(TokenType.COMMA));
        expect(TokenType.RPAREN, "')'");
        if (items.size() > MAX_IN_ITEMS) {
            throw error("IN list exceeds " + MAX_IN_ITEMS + " items");
        }
        return new InList(value, List.copyOf(items), negated);
    }

    private Like parseLike(Expr value, boolean negated) throws FilterException {
        Token pattern = peek();
        if (pattern.type() != TokenType.STRING) {
            throw error("LIKE pattern must be a string literal");
        }
        position++;

        String escape = null;
        if (matchKeyword("ESCAPE")) {
            Token escapeToken = peek();
            if (escapeToken.type() != TokenType.STRING) {
                throw error("ESCAPE must be a string literal");
            }
            position++;
            if (escapeToken.text().codePointCount(0, escapeToken.text().length()) != 1) {
                throw FilterException.at("ESCAPE must be a single character", escapeToken.position());
            }
            escape = escapeToken.text();
        }
        return new Like(value, pattern.text(), escape, negated, pattern.position());
    }

    // ---------------------------------------------------------------- 值

    private Expr parseOperand() throws FilterException {
        Token token = peek();
        switch (token.type()) {
            case STRING -> {
                position++;
                return new Literal(token.text(), token.text());
            }
            case NUMBER -> {
                position++;
                return new Literal(token.number(), token.text());
            }
            case QUOTED_IDENT -> {
                position++;
                return new ColumnRef(token.text(), true, token.position());
            }
            case IDENT -> {
                if (token.isKeyword("TRUE")) {
                    position++;
                    return new Literal(Boolean.TRUE, "TRUE");
                }
                if (token.isKeyword("FALSE")) {
                    position++;
                    return new Literal(Boolean.FALSE, "FALSE");
                }
                if (token.isKeyword("NULL")) {
                    position++;
                    return new Literal(null, "NULL");
                }
                if (RESERVED_KEYWORDS.contains(token.text().toUpperCase(Locale.ROOT))) {
                    throw FilterException.at("expected a value but found keyword '"
                            + token.text() + "'", token.position());
                }
                position++;
                if (match(TokenType.LPAREN)) {
                    return parseFunction(token);
                }
                return new ColumnRef(token.text(), false, token.position());
            }
            default -> throw error("expected a value");
        }
    }

    private Expr parseFunction(Token nameToken) throws FilterException {
        enter();
        try {
            String name = nameToken.text().toLowerCase(Locale.ROOT);
            if (!SUPPORTED_FUNCTIONS.contains(name)) {
                throw FilterException.at("unknown function '" + nameToken.text()
                        + "'. Supported functions: lower, upper, trim, length", nameToken.position());
            }
            if (check(TokenType.RPAREN)) {
                throw FilterException.at("function '" + name + "' expects exactly 1 argument, got 0",
                        nameToken.position());
            }
            List<Expr> arguments = new ArrayList<>();
            arguments.add(parseOperand());
            while (match(TokenType.COMMA)) {
                arguments.add(parseOperand());
            }
            expect(TokenType.RPAREN, "')'");
            if (arguments.size() != 1) {
                throw FilterException.at("function '" + name + "' expects exactly 1 argument, got "
                        + arguments.size(), nameToken.position());
            }
            return new FunctionCall(name, arguments.get(0), nameToken.position());
        } finally {
            depth--;
        }
    }

    // ---------------------------------------------------------------- 排序/分页

    private List<SortKey> parseOrderBy() throws FilterException {
        if (!matchKeyword("BY")) {
            throw error("expected BY after ORDER");
        }
        List<SortKey> keys = new ArrayList<>();
        do {
            if (!check(TokenType.IDENT) && !check(TokenType.QUOTED_IDENT)) {
                throw error("ORDER BY requires a column name or function");
            }
            Expr expression = parseOperand();

            boolean ascending = true;
            if (matchKeyword("ASC")) {
                ascending = true;
            } else if (matchKeyword("DESC")) {
                ascending = false;
            }

            boolean nullsFirst = false;
            if (matchKeyword("NULLS")) {
                if (matchKeyword("FIRST")) {
                    nullsFirst = true;
                } else if (matchKeyword("LAST")) {
                    nullsFirst = false;
                } else {
                    throw error("expected FIRST or LAST after NULLS");
                }
            }
            keys.add(new SortKey(expression, ascending, nullsFirst));
            if (keys.size() > MAX_ORDER_KEYS) {
                throw error("ORDER BY supports at most " + MAX_ORDER_KEYS + " keys");
            }
        } while (match(TokenType.COMMA));
        return List.copyOf(keys);
    }

    private Integer parseLimit() throws FilterException {
        if (matchKeyword("ALL")) {
            return null;
        }
        return parseNonNegativeInteger("LIMIT");
    }

    private int parseOffset() throws FilterException {
        return parseNonNegativeInteger("OFFSET");
    }

    private int parseNonNegativeInteger(String clause) throws FilterException {
        Token token = peek();
        if (token.type() != TokenType.NUMBER || token.number() == null) {
            throw FilterException.at(clause + " must be a non-negative integer", token.position());
        }
        position++;
        try {
            int value = token.number().intValueExact();
            if (value < 0) {
                throw new ArithmeticException("negative");
            }
            return value;
        } catch (ArithmeticException invalid) {
            throw FilterException.at(clause + " must be a non-negative integer", token.position());
        }
    }

    // ---------------------------------------------------------------- 工具

    private boolean isClauseStart() {
        Token token = peek();
        return token.isKeyword("ORDER") || token.isKeyword("LIMIT") || token.isKeyword("OFFSET");
    }

    private Token peek() {
        return tokens.get(position);
    }

    private boolean check(TokenType type) {
        return peek().type() == type;
    }

    private boolean match(TokenType type) {
        if (check(type)) {
            position++;
            return true;
        }
        return false;
    }

    private boolean matchKeyword(String keyword) {
        if (peek().isKeyword(keyword)) {
            position++;
            return true;
        }
        return false;
    }

    private void expect(TokenType type, String what) throws FilterException {
        if (!match(type)) {
            throw error("expected " + what + " but found " + describe(peek()));
        }
    }

    private Op matchOperator() {
        Op operator = switch (peek().type()) {
            case EQ -> Op.EQ;
            case NE -> Op.NE;
            case LT -> Op.LT;
            case LE -> Op.LE;
            case GT -> Op.GT;
            case GE -> Op.GE;
            default -> null;
        };
        if (operator != null) {
            position++;
        }
        return operator;
    }

    private void enter() throws FilterException {
        depth++;
        if (depth > MAX_DEPTH) {
            throw FilterException.at("expression nesting exceeds " + MAX_DEPTH + " levels",
                    peek().position());
        }
    }

    private FilterException unexpected() {
        Token token = peek();
        return FilterException.at("unexpected token " + describe(token), token.position());
    }

    private FilterException error(String message) {
        return FilterException.at(message, peek().position());
    }

    private static String describe(Token token) {
        if (token.type() == TokenType.EOF) {
            return "end of filter";
        }
        if (token.type() == TokenType.STRING) {
            return "'" + token.text() + "'";
        }
        if (token.type() == TokenType.QUOTED_IDENT) {
            return "\"" + token.text() + "\"";
        }
        return "'" + token.text() + "'";
    }
}
