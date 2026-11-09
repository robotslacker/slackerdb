package org.slackerdb.dbserver.service.filter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * filter 表达式的词法分析器。
 *
 * <p>关键字（AND/OR/NOT/LIKE/…）不单独建类型：一律产出 {@link TokenType#IDENT}，
 * 由 parser 按大小写不敏感的方式匹配。这样列名与关键字同名时（如列名 {@code limit}）
 * 可以通过加双引号精确引用。</p>
 */
final class FilterLexer {

    enum TokenType {
        IDENT,
        QUOTED_IDENT,
        STRING,
        NUMBER,
        LPAREN,
        RPAREN,
        COMMA,
        EQ,
        NE,
        LT,
        LE,
        GT,
        GE,
        EOF
    }

    record Token(TokenType type, String text, BigDecimal number, int position) {
        boolean isKeyword(String keyword) {
            return type == TokenType.IDENT && text.equalsIgnoreCase(keyword);
        }
    }

    private FilterLexer() {
    }

    static List<Token> tokenize(String text) throws FilterException {
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            char ch = text.charAt(index);
            if (Character.isWhitespace(ch)) {
                index++;
                continue;
            }
            int position = index + 1;

            if (ch == '\'') {
                index = readQuoted(text, index, tokens, TokenType.STRING, position, "string literal");
                continue;
            }
            if (ch == '"') {
                index = readQuoted(text, index, tokens, TokenType.QUOTED_IDENT, position, "quoted identifier");
                continue;
            }
            if (Character.isDigit(ch)
                    || ((ch == '+' || ch == '-') && index + 1 < text.length()
                    && Character.isDigit(text.charAt(index + 1)))) {
                index = readNumber(text, index, tokens, position);
                continue;
            }
            if (Character.isLetter(ch) || ch == '_') {
                int begin = index;
                index++;
                while (index < text.length()) {
                    char current = text.charAt(index);
                    if (Character.isLetterOrDigit(current) || current == '_' || current == '$') {
                        index++;
                    } else {
                        break;
                    }
                }
                tokens.add(new Token(TokenType.IDENT, text.substring(begin, index), null, position));
                continue;
            }

            switch (ch) {
                case '(' -> {
                    tokens.add(new Token(TokenType.LPAREN, "(", null, position));
                    index++;
                }
                case ')' -> {
                    tokens.add(new Token(TokenType.RPAREN, ")", null, position));
                    index++;
                }
                case ',' -> {
                    tokens.add(new Token(TokenType.COMMA, ",", null, position));
                    index++;
                }
                case '=' -> {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '=') {
                        tokens.add(new Token(TokenType.EQ, "==", null, position));
                        index += 2;
                    } else {
                        tokens.add(new Token(TokenType.EQ, "=", null, position));
                        index++;
                    }
                }
                case '!' -> {
                    if (index + 1 >= text.length() || text.charAt(index + 1) != '=') {
                        throw FilterException.at("unexpected character '!' (expected '!=')", position);
                    }
                    tokens.add(new Token(TokenType.NE, "!=", null, position));
                    index += 2;
                }
                case '<' -> {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '=') {
                        tokens.add(new Token(TokenType.LE, "<=", null, position));
                        index += 2;
                    } else if (index + 1 < text.length() && text.charAt(index + 1) == '>') {
                        tokens.add(new Token(TokenType.NE, "<>", null, position));
                        index += 2;
                    } else {
                        tokens.add(new Token(TokenType.LT, "<", null, position));
                        index++;
                    }
                }
                case '>' -> {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '=') {
                        tokens.add(new Token(TokenType.GE, ">=", null, position));
                        index += 2;
                    } else {
                        tokens.add(new Token(TokenType.GT, ">", null, position));
                        index++;
                    }
                }
                default -> throw FilterException.at("unexpected character '" + ch + "'", position);
            }
        }
        tokens.add(new Token(TokenType.EOF, "", null, text.length() + 1));
        return tokens;
    }

    /**
     * 读取单引号字符串或双引号标识符；{@code ''}/{@code ""} 表示一个字面量引号。
     *
     * @return 结束引号之后的下标
     */
    private static int readQuoted(String text, int index, List<Token> tokens,
                                  TokenType type, int position, String what) throws FilterException {
        StringBuilder value = new StringBuilder();
        int cursor = index + 1;
        while (cursor < text.length()) {
            char current = text.charAt(cursor);
            if (type == TokenType.STRING ? current == '\'' : current == '"') {
                if (cursor + 1 < text.length()
                        && (type == TokenType.STRING ? text.charAt(cursor + 1) == '\''
                        : text.charAt(cursor + 1) == '"')) {
                    value.append(current);
                    cursor += 2;
                    continue;
                }
                tokens.add(new Token(type, value.toString(), null, position));
                return cursor + 1;
            }
            value.append(current);
            cursor++;
        }
        throw FilterException.at("unterminated " + what, position);
    }

    /** 读取数字字面量（允许正负号、小数与科学计数法）。 */
    private static int readNumber(String text, int index, List<Token> tokens, int position)
            throws FilterException {
        int begin = index;
        if (text.charAt(index) == '+' || text.charAt(index) == '-') {
            index++;
        }
        while (index < text.length() && Character.isDigit(text.charAt(index))) {
            index++;
        }
        if (index < text.length() && text.charAt(index) == '.') {
            index++;
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
        }
        if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
            int exponentStart = index;
            index++;
            if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
                index++;
            }
            int exponentDigits = 0;
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
                exponentDigits++;
            }
            if (exponentDigits == 0) {
                index = exponentStart;
            }
        }
        String raw = text.substring(begin, index);
        try {
            BigDecimal value = new BigDecimal(raw);
            tokens.add(new Token(TokenType.NUMBER, raw, value, position));
            return index;
        } catch (NumberFormatException invalidNumber) {
            throw FilterException.at("invalid number '" + raw + "'", position);
        }
    }
}
