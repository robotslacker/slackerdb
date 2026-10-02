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
    private final JSONObject ret = new JSONObject();

    public CopyVisitor(CharStream charStream)
    {
        this.inputStream = charStream;
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
            ret.put("query", ctx.query().getText());
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
            // 执行 visitor
            CopyVisitor visitor = new CopyVisitor(input);
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
