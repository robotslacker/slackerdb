package org.slackerdb.plsql;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;

import java.math.BigInteger;
import java.sql.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlSqlVisitor extends PlSqlParserBaseVisitor<Void> {
    static class EventTermination extends RuntimeException {}
    static class EventBreakLoop extends RuntimeException {}
    static class ParserError extends RuntimeException {
        ParserError(String msg)
        {
            super(msg);
        }
    }
    HashMap<String, Object>  declareVariables = new HashMap<>();
    HashMap<String, DeclareCursor> declareCursors = new HashMap<>();
    CharStream inputStream;
    Connection conn;

    public PlSqlVisitor(Connection conn, CharStream charStream)
    {
        this.conn = conn;
        this.inputStream = charStream;
    }

    @Override
    public Void visitExit(PlSqlParserParser.ExitContext ctx)
    {
        throw new EventTermination();
    }

    @Override
    public Void visitBreakloop(PlSqlParserParser.BreakloopContext ctx) {throw new EventBreakLoop(); }

    @Override
    public Void visitDeclare_block(PlSqlParserParser.Declare_blockContext ctx)
    {
        for (PlSqlParserParser.Variable_declarationContext variable : ctx.variable_declaration())
        {
            if (variable.CURSOR() != null)
            {
                // 这里定义了一个游标
                String cursorName = variable.variable_name().getText().toLowerCase().trim();
                String sql = this.inputStream.getText(
                        new Interval(variable.sql().getStart().getStartIndex(), variable.sql().getStop().getStopIndex())).trim();
                DeclareCursor declareCursor = new DeclareCursor();
                declareCursor.sql = sql;
                declareCursors.put(cursorName, declareCursor);
            }
            else
            {
                // 这里定义了一个变量
                String variableName = variable.variable_name().getText().toLowerCase().trim();
                // 变量必须用字母开头，不能用数字开头
                if (!Character.isLetter(variableName.charAt(0)))
                {
                    throw new ParserError("Declare variable must start with letter. [" + variableName + "].");
                }
                String variableDefaultValue = null;
                if (variable.variable_defaultValue() != null)
                {
                    variableDefaultValue = variable.variable_defaultValue().getText().trim();
                }
                switch (
                        variable.datatype().getText().toUpperCase()
                )
                {
                    case "INT":
                        if (variableDefaultValue != null)
                        {
                            try {
                                declareVariables.put("__" + variableName + "__", Integer.parseInt(variableDefaultValue));
                            }
                            catch (NumberFormatException ignored)
                            {
                                throw new ParserError("Invalid default value for [" + variableName + "].");
                            }
                        }
                        else {
                            declareVariables.put("__" + variableName + "__", 0);
                        }
                        break;
                    case "BIGINT":
                        if (variableDefaultValue != null)
                        {
                            try {
                                declareVariables.put("__" + variableName + "__", new BigInteger(variableDefaultValue));
                            }
                            catch (NumberFormatException ignored)
                            {
                                throw new ParserError("Invalid default value for [" + variableName + "].");
                            }
                        }
                        else {
                            declareVariables.put("__" + variableName + "__", 0);
                        }
                        break;
                    case "TEXT":
                        declareVariables.put("__" + variableName + "__", variableDefaultValue);
                        break;
                    case "FLOAT":
                        if (variableDefaultValue != null) {
                            try {
                                declareVariables.put("__" + variableName + "__", Float.parseFloat(variableDefaultValue));
                            } catch (NumberFormatException ignored)
                            {
                                throw new ParserError("Invalid default value for [" + variableName + "].");
                            }
                        }
                        else
                        {
                            declareVariables.put("__" + variableName + "__", 0.0d);
                        }
                        break;
                    case "DOUBLE":
                        if (variableDefaultValue != null) {
                            try {
                                declareVariables.put("__" + variableName + "__", Double.parseDouble(variableDefaultValue));
                            } catch (NumberFormatException ignored)
                            {
                                throw new ParserError("Invalid default value for [" + variableName + "].");
                            }
                        }
                        else
                        {
                            declareVariables.put("__" + variableName + "__", 0.0d);
                        }
                        break;
                    case "DATE":
                        if (variableDefaultValue != null) {
                            try {
                                declareVariables.put("__" + variableName + "__", new SimpleDateFormat("yyyy-MM-dd").parse(variableDefaultValue));
                            } catch (ParseException ignored)
                            {
                                throw new ParserError("Invalid default value for [" + variableName + "].");
                            }
                        }
                        else
                        {
                            declareVariables.put("__" + variableName + "__", null);
                        }
                        break;
                    case "TIMESTAMP":
                        if (variableDefaultValue != null) {
                            try {
                                declareVariables.put("__" + variableName + "__",
                                        LocalDateTime.parse(variableDefaultValue, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                            } catch (DateTimeParseException ignored)
                            {
                                throw new ParserError("Invalid default value for [" + variableName + "].");
                            }
                        }
                        else
                        {
                            declareVariables.put("__" + variableName + "__", null);
                        }
                        break;
                    default:
                        throw new ParserError("Invalid default type [" + variable.datatype().getText() + "] for [" + variableName + "].");
                }
            }
        }
        return null;
    }

    @Override
    public Void visitBegin_block(PlSqlParserParser.Begin_blockContext ctx) throws RuntimeException
    {
        try
        {
            visitBegin_code_block(ctx.begin_code_block());
        }
        catch (RuntimeException se)
        {
            if (se instanceof ParseSQLException)
            {
                if (ctx.exception() != null)
                {
                    visitBegin_exception_block(ctx.begin_exception_block());
                }
                else
                {
                    throw se;
                }
            }
            else {
                throw se;
            }
        }
        return null;
    }

    @Override
    public Void visitBegin_code_block(PlSqlParserParser.Begin_code_blockContext ctx) throws RuntimeException
    {
        for (int i = 0; i < ctx.getChildCount(); i++) {
            ParseTree child = ctx.getChild(i);
            if (child instanceof PlSqlParserParser.Sql_blockContext)
            {
                visitSql_block((PlSqlParserParser.Sql_blockContext) child);
            }
            if (child instanceof PlSqlParserParser.For_blockContext)
            {
                visitFor_block((PlSqlParserParser.For_blockContext)child);
            }
            if (child instanceof  PlSqlParserParser.Loop_blockContext)
            {
                visitLoop_block((PlSqlParserParser.Loop_blockContext)child);
            }
            if (child instanceof  PlSqlParserParser.If_blockContext)
            {
                visitIf_block((PlSqlParserParser.If_blockContext)child);
            }
        }
        return null;
    }

    @Override
    public Void visitBegin_exception_block(PlSqlParserParser.Begin_exception_blockContext ctx)
    {
        for (int i = 0; i < ctx.getChildCount(); i++) {
            ParseTree child = ctx.getChild(i);
            if (child instanceof PlSqlParserParser.Sql_blockContext)
            {
                visitSql_block((PlSqlParserParser.Sql_blockContext) child);
            }
            if (child instanceof PlSqlParserParser.For_blockContext)
            {
                visitFor_block((PlSqlParserParser.For_blockContext)child);
            }
            if (child instanceof  PlSqlParserParser.Loop_blockContext)
            {
                visitLoop_block((PlSqlParserParser.Loop_blockContext)child);
            }
        }
        return null;
    }

    @Override
    public Void visitSql_block(PlSqlParserParser.Sql_blockContext ctx) throws RuntimeException
    {
        if (ctx.let() != null)
        {
            visitLet(ctx.let());
        }
        if (ctx.begin_block() != null)
        {
            visitBegin_block(ctx.begin_block());
        }
        if (ctx.fetchsql() != null)
        {
            visitFetchsql(ctx.fetchsql());
        }
        if (ctx.breakloop() != null)
        {
            visitBreakloop(ctx.breakloop());
        }
        if (ctx.fetchstatement() != null)
        {
            visitFetchstatement(ctx.fetchstatement());
        }
        if (ctx.cursor_open_statement() != null)
        {
            visitCursor_open_statement(ctx.cursor_open_statement());
        }
        if (ctx.cursor_close_statement() != null)
        {
            visitCursor_close_statement(ctx.cursor_close_statement());
        }
        if (ctx.exit() != null)
        {
            visitExit(ctx.exit());
        }
        if (ctx.sql() != null)
        {
            visitSql(ctx.sql());
        }
        return null;
    }

    @Override
    public Void visitLet(PlSqlParserParser.LetContext ctx) throws ParseSQLException
    {
        String variableName = ctx.Identifier().getText().trim();
        String expression = this.inputStream.getText(
                new Interval(ctx.expression().getStart().getStartIndex(), ctx.expression().getStop().getStopIndex())).trim();
        // 必须是冒号开头，则必须变量用字母开头
        expression = expression.replaceAll(":(\\b[a-zA-Z]\\w*\\b)", "__$1__");

        // 解析并计算表达式
        if (!declareVariables.containsKey("__" + variableName + "__"))
        {
            throw new ParserError("Variable [" + variableName + "] has not been declared." );
        }
        Object result = evaluate(expression, declareVariables);
        // 输出结果
        declareVariables.put("__" + variableName + "__", result);
        return null;
    }

    @Override
    public Void visitSql(PlSqlParserParser.SqlContext ctx) throws RuntimeException{
        // PASS 语句直接返回
        if (ctx.PASS() != null)
        {
            return null;
        }
        String sql = this.inputStream.getText(new Interval(ctx.getStart().getStartIndex(), ctx.getStop().getStopIndex())).trim();
        try {
            List<Object> bindObjects = new ArrayList<>();
            for (PlSqlParserParser.Sql_tokenContext tokenCtx : ctx.sql_part().sql_token()) {
                if (tokenCtx.bindIdentifier() != null) {
                    // 记录绑定的变量, 去掉前面的：
                    String variableName = tokenCtx.bindIdentifier().getText().substring(1) ;
                    if (!declareVariables.containsKey("__" + variableName + "__"))
                    {
                        throw new ParserError("Parse error: " + ctx.getStart().getLine() + ":" + ctx.getStart().getCharPositionInLine() + ".\n" +
                                "Variable [" + variableName + "] has not been declared." );
                    }
                    else
                    {
                        bindObjects.add(declareVariables.get("__" + variableName + "__"));
                    }
                    sql = sql.replace(tokenCtx.bindIdentifier().getText(), "?");
                }
            }
            // 替换__XX__标记的变量
            // 定义匹配 __XX__ 的正则表达式
            Pattern pattern = Pattern.compile("__(?!_)(\\w+?)__");
            while (true) {
                Matcher matcher = pattern.matcher(sql);
                if (matcher.find()) {
                    String variableName = matcher.group(1);
                    if (declareVariables.containsKey("__" + variableName + "__")) {
                        sql = sql.replace("__" + variableName + "__", String.valueOf(declareVariables.get("__" + variableName + "__")));
                    }
                    else
                    {
                        sql = sql.replace("__" + variableName + "__", "**UNKNOWN**");
                    }
                }
                else
                {
                    break;
                }
            }
            PreparedStatement pStmt = this.conn.prepareStatement(sql);
            int nBindPos = 1;
            for (Object obj : bindObjects)
            {
                pStmt.setObject(nBindPos, obj);
                nBindPos = nBindPos + 1;
            }
            pStmt.execute();
        }
        catch (SQLException se)
        {
            throw new ParseSQLException("SQL Error: [" + sql + "] (" + se.getMessage() + ")", se);
        }
        return null;
    }

    @Override
    public Void visitCursor_open_statement(PlSqlParserParser.Cursor_open_statementContext ctx)
    {
        String cursorName = ctx.Identifier().getText().toLowerCase().trim();
        if (!declareCursors.containsKey(cursorName))
        {
            throw new ParserError("Parse error: " + ctx.getStart().getLine() + ":" + ctx.getStart().getCharPositionInLine() + ".\n" +
                    "Cursor [" + cursorName + "] has not been declared." );
        }

        DeclareCursor cursor = declareCursors.get(cursorName);
        try {
            cursor.pStmt = this.conn.prepareStatement(cursor.sql);
            cursor.rs = cursor.pStmt.executeQuery();
            cursor.fetchEOF = false;
        }
        catch (SQLException se)
        {
            throw new ParseSQLException("SQL Error: [" + cursor.sql + "]\n", se);
        }
        declareCursors.put(cursorName, cursor);

        return null;
    }

    @Override
    public Void visitCursor_close_statement(PlSqlParserParser.Cursor_close_statementContext ctx)
    {
        String cursorName = ctx.Identifier().getText().toLowerCase().trim();
        if (!declareCursors.containsKey(cursorName))
        {
            throw new ParserError("Parse error: " + ctx.getStart().getLine() + ":" + ctx.getStart().getCharPositionInLine() + ".\n" +
                    "Cursor [" + cursorName + "] has not been declared." );
        }
        DeclareCursor cursor = declareCursors.get(cursorName);
        try {
            if (cursor.rs != null && !cursor.rs.isClosed())
            {
                cursor.rs.close();
            }
            if (cursor.pStmt != null && !cursor.pStmt.isClosed())
            {
                cursor.pStmt.close();
            }
        }
        catch (SQLException se)
        {
            throw new ParseSQLException("SQL Error: [" + cursor.sql + "]\n", se);
        }
        declareCursors.put(cursorName, cursor);
        return null;
    }

    @Override
    public Void visitFetchsql(PlSqlParserParser.FetchsqlContext ctx)
    {
        StringBuilder fetchSql = new StringBuilder();
        for (PlSqlParserParser.Sql_partContext sqlPart : ctx.sql_part())
        {
            fetchSql.append(this.inputStream.getText(
                    new Interval(sqlPart.getStart().getStartIndex(), sqlPart.getStop().getStopIndex())).trim());
            fetchSql.append(" ");
        }
        List<String> bindVariables = new ArrayList<>();
        for (PlSqlParserParser.BindIdentifierContext bindCtx : ctx.fetch_list().bindIdentifier())
        {
            String variableName = bindCtx.getText().trim().substring(1);
            if (!declareVariables.containsKey("__" + variableName + "__"))
            {
                throw new ParserError("Parse error: " + ctx.getStart().getLine() + ":" + ctx.getStart().getCharPositionInLine() + ".\n" +
                        "Variable " + variableName + " in FETCH statement is undefined.");
            }
            bindVariables.add(variableName);
        }
        if (bindVariables.isEmpty())
        {
            throw new ParserError("Parse error: " + ctx.getStart().getLine() + ":" + ctx.getStart().getCharPositionInLine() + ".\n" +
                    "The required INTO statement is missing.");
        }
        try {
            Statement stmt = this.conn.createStatement();
            ResultSet rs = stmt.executeQuery(fetchSql.toString());
            if (rs.getMetaData().getColumnCount() != bindVariables.size())
            {
                rs.close();
                stmt.close();
                throw new ParserError("Parse error: " + ctx.getStart().getLine() + ":" + ctx.getStart().getCharPositionInLine() + ".\n" +
                        "Incorrect number of variables in INTO clause.");
            }
            if (rs.next())
            {
                int nPos = 1;
                for (String bindVariable : bindVariables)
                {
                    declareVariables.put("__" + bindVariable + "__", rs.getObject(nPos));
                    nPos = nPos + 1;
                }
            }
            rs.close();
            stmt.close();
        }
        catch (SQLException se)
        {
            throw new ParseSQLException("SQL Error: [" + fetchSql + "]\n", se);
        }
        return null;
    }

    @Override
    public Void visitFetchstatement(PlSqlParserParser.FetchstatementContext ctx)
    {
        String cursorName = ctx.Identifier().getText().toLowerCase().trim();
        if (!declareCursors.containsKey(cursorName))
        {
            throw new ParserError("Parse error: " + ctx.getStart().getLine() + ":" + ctx.getStart().getCharPositionInLine() + ".\n" +
                    "Cursor [" + cursorName + "] has not been declared." );
        }
        List<String> bindVariables = new ArrayList<>();
        for (PlSqlParserParser.BindIdentifierContext bindCtx : ctx.fetch_list().bindIdentifier())
        {
            String variableName = bindCtx.getText().trim().substring(1);
            if (!declareVariables.containsKey("__" + variableName + "__"))
            {
                throw new ParserError("Parse error: " + ctx.getStart().getLine() + ":" + ctx.getStart().getCharPositionInLine() + ".\n" +
                        "Variable " + variableName + " in FETCH statement is undefined.");
            }
            bindVariables.add(variableName);
        }
        DeclareCursor cursor = declareCursors.get(cursorName);
        try {
            if (cursor.rs.getMetaData().getColumnCount() != bindVariables.size())
            {
                cursor.rs.close();
                throw new ParserError("Parse error: " + ctx.getStart().getLine() + ":" + ctx.getStart().getCharPositionInLine() + ".\n" +
                        "Incorrect number of variables in INTO clause.");
            }
            if (cursor.rs.next())
            {
                int nPos = 1;
                for (String bindVariable : bindVariables)
                {
                    declareVariables.put("__" + bindVariable + "__", cursor.rs.getObject(nPos));
                    nPos = nPos + 1;
                }
            }
            else
            {
                cursor.fetchEOF = true;
            }
        }
        catch (SQLException se)
        {
            throw new ParseSQLException("SQL Error: [" + cursor.sql + "]\n", se);
        }
        return null;
    }

    @Override
    public Void visitIf_block(PlSqlParserParser.If_blockContext ctx) throws ParseSQLException
    {
        String ifExpression =
                this.inputStream.getText(
                        new Interval(ctx.if_().expression().getStart().getStartIndex(),
                                ctx.if_().expression().getStop().getStopIndex())
                ).trim();
        ifExpression = ifExpression.replaceAll(":(\\w+)", "__$1__");
        boolean result = (boolean)evaluate(ifExpression, declareVariables);
        if (result)
        {
            // 结果If命中
            for (PlSqlParserParser.Sql_blockContext sqlCtx: ctx.sql_block())
            {
                visitSql_block(sqlCtx);
            }
        }
        else
        {
            // ElseIf
            boolean matchedElseIf = false;
            for (PlSqlParserParser.Elseif_blockContext elseIfCtx : ctx.elseif_block())
            {
                ifExpression =
                        this.inputStream.getText(
                                new Interval(
                                        elseIfCtx.elseif().expression().getStart().getStartIndex(),
                                        elseIfCtx.elseif().expression().getStop().getStopIndex())
                        ).trim();
                result = (boolean)evaluate(ifExpression, declareVariables);
                if (result)
                {
                    for (PlSqlParserParser.Sql_blockContext sqlCtx: elseIfCtx.sql_block())
                    {
                        visitSql_block(sqlCtx);
                    }
                    matchedElseIf = true;
                    break;
                }
            }
            if (!matchedElseIf)
            {
                // 执行Else操作
                for (PlSqlParserParser.Sql_blockContext sqlCtx: ctx.else_block().sql_block())
                {
                    visitSql_block(sqlCtx);
                }
            }
        }
        return null;
    }

    @Override
    public Void visitLoop_block(PlSqlParserParser.Loop_blockContext ctx)
    {
        while (true)
        {
            boolean breakLoop = false;
            try {
                for (int i = 0; i < ctx.getChildCount(); i++) {
                    ParseTree child = ctx.getChild(i);
                    if (child instanceof PlSqlParserParser.Sql_blockContext) {
                        visitSql_block((PlSqlParserParser.Sql_blockContext) child);
                    }
                    if (child instanceof PlSqlParserParser.Exit_when_statementContext exitWhenCtx) {
                        String cursorName = exitWhenCtx.Identifier().getText().toLowerCase().trim();
                        if (!declareCursors.containsKey(cursorName)) {
                            throw new ParserError("Parse error: " + ctx.getStart().getLine() + ":" + ctx.getStart().getCharPositionInLine() + ".\n" +
                                    "Cursor [" + cursorName + "] has not been declared.");
                        } else {
                            if (declareCursors.get(cursorName).fetchEOF) {
                                breakLoop = true;
                                break;
                            }
                        }
                    }
                }
                if (breakLoop) {
                    break;
                }
            }
            catch (EventBreakLoop eventBreakLoop)
            {
                break;
            }
        }
        return null;
    }

    @Override
    public Void visitFor_block(PlSqlParserParser.For_blockContext ctx)
    {
        String bindIdentifierName = ctx.bindIdentifier().getText().trim().substring(1);
        if (!declareVariables.containsKey("__" + bindIdentifierName + "__"))
        {
            throw new ParserError("Variable [" + bindIdentifierName + "] has not been declared." );
        }
        if (ctx.list() != null) {
            for (PlSqlParserParser.ExpressionContext exprCtx : ctx.list().exprList().expression()) {
                declareVariables.put("__" + bindIdentifierName + "__", exprCtx.getText());
                try {
                    for (int i = 0; i < ctx.getChildCount(); i++) {
                        ParseTree child = ctx.getChild(i);
                        if (child instanceof PlSqlParserParser.Sql_blockContext) {
                            visitSql_block((PlSqlParserParser.Sql_blockContext) child);
                        }
                    }
                }
                catch (EventBreakLoop eventBreakLoop)
                {
                    break;
                }
            }
        } else {
            for (int i = Integer.parseInt(ctx.expression(0).getText()); i < Integer.parseInt(ctx.expression(1).getText()); i++) {
                declareVariables.put("__" + bindIdentifierName + "__", i);
                try
                {
                    for (int j = 0; j < ctx.getChildCount(); j++) {
                        ParseTree child = ctx.getChild(j);
                        if (child instanceof PlSqlParserParser.Sql_blockContext) {
                            visitSql_block((PlSqlParserParser.Sql_blockContext) child);
                        }
                    }
                }
                catch (EventBreakLoop eventBreakLoop)
                {
                    break;
                }
            }
        }
        return null;
    }

    public Object evaluate(String expression, Map<String, Object> bindingObjects) throws ParseSQLException
    {
        Object ret = null;
        for (Map.Entry<String, Object> bindingObjectEntry: bindingObjects.entrySet())
        {
            if (bindingObjectEntry.getValue() instanceof Integer) {
                expression = expression.replaceAll("\\b" + bindingObjectEntry.getKey() + "\\b",
                        String.valueOf(bindingObjectEntry.getValue()));
            } else if (bindingObjectEntry.getValue() instanceof String) {
                expression = expression.replaceAll("\\b" + bindingObjectEntry.getKey() + "\\b",
                        "'" +  bindingObjectEntry.getValue() + "'");
            } else if (bindingObjectEntry.getValue() instanceof Double) {
                expression = expression.replaceAll("\\b" + bindingObjectEntry.getKey() + "\\b",
                        String.valueOf(bindingObjectEntry.getValue()));
            } else if (bindingObjectEntry.getValue() instanceof java.math.BigDecimal) {
                expression = expression.replaceAll("\\b" + bindingObjectEntry.getKey() + "\\b",
                        String.valueOf(bindingObjectEntry.getValue()));
            }
            else if (bindingObjectEntry.getValue() == null)
            {
                expression = expression.replaceAll("\\b" + bindingObjectEntry.getKey() + "\\b", "null");
            }
            else
            {
                throw new ParserError("Evaluate Error:" + bindingObjectEntry.getValue().getClass().getName());
            }
        }
        try {
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("select " + expression);
            if (rs.next()) {
                ret = rs.getObject(1);
            }
            if (rs.next()) {
                // 对于表达式计算，不能包含多值
                throw new SQLException("Evaluate got too much rows.");
            }
            rs.close();
            stmt.close();
        }
        catch (SQLException se)
        {
            throw new ParseSQLException(se.getMessage(), se);
        }
        return ret;
    }

    public static void runPlSql(Connection conn, String plSql)
    {
        CharStream input = CharStreams.fromString(plSql);

        // 创建词法分析器, 语法解析器
        PlSqlParserLexer lexer = new PlSqlParserLexer(input);
        lexer.removeErrorListeners();
        lexer.addErrorListener(new ParserErrorListener());

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        PlSqlParserParser parser = new PlSqlParserParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new ParserErrorListener());

        // 创建解析表达式
        try {
            ParseTree tree = parser.plsql_script();
            // 执行 visitor
            PlSqlVisitor visitor = new PlSqlVisitor(conn, input);
            try {
                visitor.visit(tree);
            } catch (EventTermination ignored) {
            }
        }
        catch (ParseSQLException parseSQLException)
        {
            throw parseSQLException;
        }
        catch (RuntimeException re)
        {
            throw new ParseSQLException("Parser Error:", re);
        }
    }
}
