package org.slackerdb.dbserver.sql.antlr;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

public class CopyVisitor extends CopyStatementBaseVisitor<Void> {
    private CharStream inputStream;
    private JSONObject ret = new JSONObject();

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

        ret.getJSONObject("options").put(ctx.key.getText(), ctx.value.getText());
        return null;
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
