// Generated from MysqlConnectorSyntax.g4 by ANTLR 4.13.2
package org.slackerdb.connector.mysql.command;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link MysqlConnectorSyntaxParser}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface MysqlConnectorSyntaxVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by {@link MysqlConnectorSyntaxParser#command}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCommand(MysqlConnectorSyntaxParser.CommandContext ctx);
	/**
	 * Visit a parse tree produced by {@link MysqlConnectorSyntaxParser#showConnector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitShowConnector(MysqlConnectorSyntaxParser.ShowConnectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link MysqlConnectorSyntaxParser#alterConnector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAlterConnector(MysqlConnectorSyntaxParser.AlterConnectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link MysqlConnectorSyntaxParser#dropConnector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDropConnector(MysqlConnectorSyntaxParser.DropConnectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link MysqlConnectorSyntaxParser#shutdownConnector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitShutdownConnector(MysqlConnectorSyntaxParser.ShutdownConnectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link MysqlConnectorSyntaxParser#startConnector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStartConnector(MysqlConnectorSyntaxParser.StartConnectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link MysqlConnectorSyntaxParser#createConnector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCreateConnector(MysqlConnectorSyntaxParser.CreateConnectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link MysqlConnectorSyntaxParser#connectorName}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConnectorName(MysqlConnectorSyntaxParser.ConnectorNameContext ctx);
	/**
	 * Visit a parse tree produced by {@link MysqlConnectorSyntaxParser#taskName}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTaskName(MysqlConnectorSyntaxParser.TaskNameContext ctx);
	/**
	 * Visit a parse tree produced by {@link MysqlConnectorSyntaxParser#connectorOptions}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConnectorOptions(MysqlConnectorSyntaxParser.ConnectorOptionsContext ctx);
	/**
	 * Visit a parse tree produced by {@link MysqlConnectorSyntaxParser#taskOptions}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTaskOptions(MysqlConnectorSyntaxParser.TaskOptionsContext ctx);
	/**
	 * Visit a parse tree produced by {@link MysqlConnectorSyntaxParser#tableName}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTableName(MysqlConnectorSyntaxParser.TableNameContext ctx);
	/**
	 * Visit a parse tree produced by {@link MysqlConnectorSyntaxParser#ifnotexists}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIfnotexists(MysqlConnectorSyntaxParser.IfnotexistsContext ctx);
}