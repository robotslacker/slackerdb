// Generated from PostgresConnectorSyntax.g4 by ANTLR 4.13.2
package org.slackerdb.connector.postgres.command;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link PostgresConnectorSyntaxParser}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface PostgresConnectorSyntaxVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by {@link PostgresConnectorSyntaxParser#command}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCommand(PostgresConnectorSyntaxParser.CommandContext ctx);
	/**
	 * Visit a parse tree produced by {@link PostgresConnectorSyntaxParser#showConnector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitShowConnector(PostgresConnectorSyntaxParser.ShowConnectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link PostgresConnectorSyntaxParser#alterConnector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAlterConnector(PostgresConnectorSyntaxParser.AlterConnectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link PostgresConnectorSyntaxParser#dropConnector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDropConnector(PostgresConnectorSyntaxParser.DropConnectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link PostgresConnectorSyntaxParser#shutdownConnector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitShutdownConnector(PostgresConnectorSyntaxParser.ShutdownConnectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link PostgresConnectorSyntaxParser#startConnector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStartConnector(PostgresConnectorSyntaxParser.StartConnectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link PostgresConnectorSyntaxParser#createConnector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCreateConnector(PostgresConnectorSyntaxParser.CreateConnectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link PostgresConnectorSyntaxParser#connectorName}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConnectorName(PostgresConnectorSyntaxParser.ConnectorNameContext ctx);
	/**
	 * Visit a parse tree produced by {@link PostgresConnectorSyntaxParser#taskName}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTaskName(PostgresConnectorSyntaxParser.TaskNameContext ctx);
	/**
	 * Visit a parse tree produced by {@link PostgresConnectorSyntaxParser#connectorOptions}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConnectorOptions(PostgresConnectorSyntaxParser.ConnectorOptionsContext ctx);
	/**
	 * Visit a parse tree produced by {@link PostgresConnectorSyntaxParser#taskOptions}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTaskOptions(PostgresConnectorSyntaxParser.TaskOptionsContext ctx);
	/**
	 * Visit a parse tree produced by {@link PostgresConnectorSyntaxParser#tableName}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTableName(PostgresConnectorSyntaxParser.TableNameContext ctx);
	/**
	 * Visit a parse tree produced by {@link PostgresConnectorSyntaxParser#ifnotexists}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIfnotexists(PostgresConnectorSyntaxParser.IfnotexistsContext ctx);
}