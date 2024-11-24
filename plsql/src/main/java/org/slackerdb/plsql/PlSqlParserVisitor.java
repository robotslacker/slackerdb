// Generated from PlSqlParser.g4 by ANTLR 4.13.2
package org.slackerdb.plsql;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link PlSqlParserParser}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface PlSqlParserVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#plsql_script}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPlsql_script(PlSqlParserParser.Plsql_scriptContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#declare_block}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDeclare_block(PlSqlParserParser.Declare_blockContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#begin_block}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBegin_block(PlSqlParserParser.Begin_blockContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#begin_code_block}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBegin_code_block(PlSqlParserParser.Begin_code_blockContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#begin_exception_block}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBegin_exception_block(PlSqlParserParser.Begin_exception_blockContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#sql_block}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSql_block(PlSqlParserParser.Sql_blockContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#if_block}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIf_block(PlSqlParserParser.If_blockContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#elseif_block}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitElseif_block(PlSqlParserParser.Elseif_blockContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#else_block}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitElse_block(PlSqlParserParser.Else_blockContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#exception}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitException(PlSqlParserParser.ExceptionContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#loop_block}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLoop_block(PlSqlParserParser.Loop_blockContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#for_block}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFor_block(PlSqlParserParser.For_blockContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#variable_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVariable_declaration(PlSqlParserParser.Variable_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#variable_name}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVariable_name(PlSqlParserParser.Variable_nameContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#variable_defaultValue}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVariable_defaultValue(PlSqlParserParser.Variable_defaultValueContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#let}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLet(PlSqlParserParser.LetContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#sql}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSql(PlSqlParserParser.SqlContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#fetchsql}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFetchsql(PlSqlParserParser.FetchsqlContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#sql_part}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSql_part(PlSqlParserParser.Sql_partContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#sql_token}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSql_token(PlSqlParserParser.Sql_tokenContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#cursor_open_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCursor_open_statement(PlSqlParserParser.Cursor_open_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#cursor_close_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCursor_close_statement(PlSqlParserParser.Cursor_close_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#fetchstatement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFetchstatement(PlSqlParserParser.FetchstatementContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#exit_when_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExit_when_statement(PlSqlParserParser.Exit_when_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#fetch_list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFetch_list(PlSqlParserParser.Fetch_listContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#if}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIf(PlSqlParserParser.IfContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#elseif}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitElseif(PlSqlParserParser.ElseifContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#else}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitElse(PlSqlParserParser.ElseContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#breakloop}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBreakloop(PlSqlParserParser.BreakloopContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#endif}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEndif(PlSqlParserParser.EndifContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#datatype}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDatatype(PlSqlParserParser.DatatypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#exit}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExit(PlSqlParserParser.ExitContext ctx);
	/**
	 * Visit a parse tree produced by the {@code identifierFunctionCall}
	 * labeled alternative in {@link PlSqlParserParser#functionCall}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIdentifierFunctionCall(PlSqlParserParser.IdentifierFunctionCallContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#exprList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExprList(PlSqlParserParser.ExprListContext ctx);
	/**
	 * Visit a parse tree produced by the {@code boolExpression}
	 * labeled alternative in {@link PlSqlParserParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBoolExpression(PlSqlParserParser.BoolExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code numberExpression}
	 * labeled alternative in {@link PlSqlParserParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNumberExpression(PlSqlParserParser.NumberExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code identifierExpression}
	 * labeled alternative in {@link PlSqlParserParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIdentifierExpression(PlSqlParserParser.IdentifierExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code notExpression}
	 * labeled alternative in {@link PlSqlParserParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNotExpression(PlSqlParserParser.NotExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code bindIdentifierExpression}
	 * labeled alternative in {@link PlSqlParserParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBindIdentifierExpression(PlSqlParserParser.BindIdentifierExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code orExpression}
	 * labeled alternative in {@link PlSqlParserParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOrExpression(PlSqlParserParser.OrExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code unaryMinusExpression}
	 * labeled alternative in {@link PlSqlParserParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUnaryMinusExpression(PlSqlParserParser.UnaryMinusExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code powerExpression}
	 * labeled alternative in {@link PlSqlParserParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPowerExpression(PlSqlParserParser.PowerExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code eqExpression}
	 * labeled alternative in {@link PlSqlParserParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEqExpression(PlSqlParserParser.EqExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code andExpression}
	 * labeled alternative in {@link PlSqlParserParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAndExpression(PlSqlParserParser.AndExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code inExpression}
	 * labeled alternative in {@link PlSqlParserParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInExpression(PlSqlParserParser.InExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code stringExpression}
	 * labeled alternative in {@link PlSqlParserParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStringExpression(PlSqlParserParser.StringExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code expressionExpression}
	 * labeled alternative in {@link PlSqlParserParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExpressionExpression(PlSqlParserParser.ExpressionExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code addExpression}
	 * labeled alternative in {@link PlSqlParserParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAddExpression(PlSqlParserParser.AddExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code compExpression}
	 * labeled alternative in {@link PlSqlParserParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCompExpression(PlSqlParserParser.CompExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code nullExpression}
	 * labeled alternative in {@link PlSqlParserParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNullExpression(PlSqlParserParser.NullExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code functionCallExpression}
	 * labeled alternative in {@link PlSqlParserParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFunctionCallExpression(PlSqlParserParser.FunctionCallExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code multExpression}
	 * labeled alternative in {@link PlSqlParserParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMultExpression(PlSqlParserParser.MultExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code listExpression}
	 * labeled alternative in {@link PlSqlParserParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitListExpression(PlSqlParserParser.ListExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ternaryExpression}
	 * labeled alternative in {@link PlSqlParserParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTernaryExpression(PlSqlParserParser.TernaryExpressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#bindIdentifier}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBindIdentifier(PlSqlParserParser.BindIdentifierContext ctx);
	/**
	 * Visit a parse tree produced by {@link PlSqlParserParser#list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList(PlSqlParserParser.ListContext ctx);
}