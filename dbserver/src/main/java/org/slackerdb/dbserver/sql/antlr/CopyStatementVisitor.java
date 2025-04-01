// Generated from CopyStatement.g4 by ANTLR 4.13.2
package org.slackerdb.dbserver.sql.antlr;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link CopyStatementParser}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface CopyStatementVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by {@link CopyStatementParser#copyStatment}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCopyStatment(CopyStatementParser.CopyStatmentContext ctx);
	/**
	 * Visit a parse tree produced by {@link CopyStatementParser#tableName}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTableName(CopyStatementParser.TableNameContext ctx);
	/**
	 * Visit a parse tree produced by {@link CopyStatementParser#query}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitQuery(CopyStatementParser.QueryContext ctx);
	/**
	 * Visit a parse tree produced by {@link CopyStatementParser#column}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitColumn(CopyStatementParser.ColumnContext ctx);
	/**
	 * Visit a parse tree produced by {@link CopyStatementParser#columns}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitColumns(CopyStatementParser.ColumnsContext ctx);
	/**
	 * Visit a parse tree produced by {@link CopyStatementParser#filePath}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFilePath(CopyStatementParser.FilePathContext ctx);
	/**
	 * Visit a parse tree produced by {@link CopyStatementParser#option}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOption(CopyStatementParser.OptionContext ctx);
	/**
	 * Visit a parse tree produced by {@link CopyStatementParser#options}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOptions(CopyStatementParser.OptionsContext ctx);
	/**
	 * Visit a parse tree produced by {@link CopyStatementParser#literal}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLiteral(CopyStatementParser.LiteralContext ctx);
}