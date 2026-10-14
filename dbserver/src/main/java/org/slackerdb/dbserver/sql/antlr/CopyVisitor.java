package org.slackerdb.dbserver.sql.antlr;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.Locale;

public class CopyVisitor extends CopyStatementBaseVisitor<Void> {
    private CharStream inputStream;
    private CommonTokenStream tokenStream;
    /** 客户端发来的原始 SQL：子查询原文靠它按字符扫描提取（见 queryTextFromSource）。 */
    private String fullSql;
    private final JSONObject ret = new JSONObject();

    public CopyVisitor(CharStream charStream)
    {
        this.inputStream = charStream;
    }

    /**
     * 传入与 lexer 共享的 token 流，以及原始 SQL。
     *
     * <p>子查询原文不走解析树：词法器把空白 {@code skip} 掉了，{@code ctx.stop} 会落在
     * "最后一个可见 token"而不是右括号上，按位置切片会丢右括号。因此改为按字符扫描配对括号。</p>
     */
    public CopyVisitor(CharStream charStream, CommonTokenStream pTokenStream, String pFullSql)
    {
        this.inputStream = charStream;
        this.tokenStream = pTokenStream;
        this.fullSql = pFullSql;
    }

    @Override
    public Void visitCopyStatment(CopyStatementParser.CopyStatmentContext ctx)
    {
        if (ctx.tableName() != null)
        {
            ret.put("copyType", "table");
            ret.put("table", ctx.tableName().getText());
            if (ctx.columns() != null)
            {
                ret.put("columns", new JSONArray());
                visitColumns(ctx.columns());
            }
        }
        else
        {
            ret.put("copyType", "query");
            // 子查询原文**不走解析树**去取，原因有两个，都会导致拿到坏 SQL：
            //  1) ParseTree.getText() 只做 token 拼接，会丢掉 token 之间的空白
            //     （`(select id, nm ...)` → `(selectid,nm...)`）；
            //  2) 词法器把空白 `skip` 掉了，解析树里没有空白 token，于是
            //     `ctx.stop` 落在"最后一个可见 token"上而不是右括号上
            //     （`(select count(*) as n from co)` 的 stop 是 `co`），
            //     按字符位置切片会把右括号切掉。
            // 因此改用括号扫描：从 COPY 之后第一个 '(' 起，按配对找到它的右括号。
            ret.put("query", queryTextFromSource());
        }
        if (ctx.TO() != null)
        {
            ret.put("copyDirection", "TO");
        }
        else
        {
            ret.put("copyDirection", "FROM");
        }
        ret.put("copyFilePath", ctx.filePath().getText());

        if (ctx.options() != null)
        {
            visitOptions(ctx.options());
        }
        return null;
    }

    @Override
    public Void visitColumns(CopyStatementParser.ColumnsContext ctx)
    {
        for (CopyStatementParser.ColumnContext columnCtx : ctx.column())
        {
            visitColumn(columnCtx);
        }
        return null;
    }

    @Override
    public Void visitColumn(CopyStatementParser.ColumnContext ctx)
    {
        ret.getJSONArray("columns").add(ctx.getText());
        return null;
    }

    @Override
    public Void visitOptions(CopyStatementParser.OptionsContext ctx)
    {
        ret.put("options", new JSONObject());
        for (CopyStatementParser.OptionContext option : ctx.option())
        {
            visitOption(option);
        }
        return null;
    }

    @Override
    public Void visitOption(CopyStatementParser.OptionContext ctx)
    {
        if (ctx.key == null)
        {
            return null;
        }
        // 选项名统一大写（语法是大小写不敏感的，`format csv` 与 `FORMAT csv` 必须等价），
        // 选项值统一去掉外层引号：PG 的 COPY 选项值可以有引号也可以没有，
        // `FORMAT csv` / `FORMAT 'csv'` / `FORMAT "csv"` 三种写法完全等价。
        // 裸开关（如 `HEADER`）没有值，按 PG 语义等价于 `HEADER true`。
        String key = ctx.key.getText().toUpperCase(Locale.ROOT);
        String value = (ctx.value == null) ? "TRUE" : unquote(ctx.value.getText());
        ret.getJSONObject("options").put(key, value);
        return null;
    }

    /**
     * 从原始 SQL 里取出 {@code COPY ( … )} 的子查询原文（含最外层括号）。
     *
     * <p>按字符扫描并配对括号，<b>正确跳过字符串字面量</b>（{@code 'a)b'}、{@code "a""b"}）与
     * 注释，因此子查询里出现括号、字符串里的括号都不会配错。返回 {@code null} 表示没找到，
     * 调用方按"解析失败"处理。</p>
     */
    private String queryTextFromSource() {
        if (fullSql == null) {
            return null;
        }
        int open = fullSql.indexOf('(');
        if (open < 0) {
            return null;
        }
        int depth = 0;
        char quote = 0;
        for (int i = open; i < fullSql.length(); i++) {
            char c = fullSql.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    // 成对引号（'' / ""）视作字面量里的一个引号，继续留在字符串里
                    if (i + 1 < fullSql.length() && fullSql.charAt(i + 1) == quote) {
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return fullSql.substring(open, i + 1);
                }
            }
        }
        return null;
    }

    /**
     * 去掉 SQL 字符串字面量的外层引号，并把成对引号还原成一个
     * （{@code 'it''s'} → {@code it's}，{@code "a""b"} → {@code a"b}）。
     * 不是字符串字面量（标识符/数字/布尔）时原样返回。
     */
    static String unquote(String text)
    {
        if (text == null || text.length() < 2)
        {
            return text;
        }
        char quote = text.charAt(0);
        if ((quote != '\'' && quote != '"') || text.charAt(text.length() - 1) != quote)
        {
            return text;
        }
        String doubled = new String(new char[]{quote, quote});
        return text.substring(1, text.length() - 1).replace(doubled, String.valueOf(quote));
    }

    public static JSONObject parseCopyStatement(String copySql)
    {
        CharStream input = CharStreams.fromString(copySql);

        // 创建词法分析器, 语法解析器
        CopyStatementLexer lexer = new CopyStatementLexer(input);
        lexer.removeErrorListeners();
        lexer.addErrorListener(new ParserErrorListener());

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        CopyStatementParser parser = new CopyStatementParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new ParserErrorListener());

        JSONObject ret = new JSONObject();
        // 创建解析表达式
        try {
            ParseTree tree = parser.copyStatment();
            // 执行 visitor（带上原始 SQL 与 token 流：子查询原文按字符扫描提取）
            CopyVisitor visitor = new CopyVisitor(input, tokens, copySql);
            visitor.visit(tree);
            ret = visitor.ret;
            ret.put("errorCode", 0);
            ret.put("errorMsg", "");
        }
        catch (RuntimeException re)
        {
            ret.put("errorCode", -1);
            ret.put("errorMsg", re.getMessage());
        }
        return ret;
    }
}
