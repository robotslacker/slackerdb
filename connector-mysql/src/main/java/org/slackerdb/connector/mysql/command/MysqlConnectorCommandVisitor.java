package org.slackerdb.connector.mysql.command;

import ch.qos.logback.classic.Logger;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slackerdb.common.utils.StringUtil;
import org.slackerdb.connector.mysql.Connector;
import org.slackerdb.connector.mysql.ConnectorTask;

public class MysqlConnectorCommandVisitor extends MysqlConnectorSyntaxBaseVisitor<Void> {
    private CharStream inputStream;
    private Connection conn;
    private Logger logger = null;
    static class EventTermination extends RuntimeException {}
    static class ParserCommandError extends RuntimeException {
        ParserCommandError(String msg)
        {
            super(msg);
        }
        ParserCommandError(String msg, Exception ex) { super(msg, ex);}
    }

    public static Map<String ,Connector>  connectorMap = new HashMap<>();

    @Override
    public Void visitAlterConnector(MysqlConnectorSyntaxParser.AlterConnectorContext ctx) {
        String connectorName = ctx.connectorName().getText().trim();
        Connector connector;
        if (connectorMap.containsKey(connectorName)) {
            connector = connectorMap.get(connectorName);
        }
        else
        {
            throw new ParserCommandError("[MYSQL-BINLOG] Alter failed. Connector [" + connectorName + "] does not exist.");
        }

        if (ctx.TASK() != null)
        {
            String taskName = ctx.taskName().getText().trim();
            if (ctx.ADD() != null)
            {
                // add task
                String addTaskOptions = ctx.taskOptions().getText().trim();
                if (
                        (addTaskOptions.startsWith("'") && addTaskOptions.endsWith("'")) ||
                                (addTaskOptions.startsWith("\"") && addTaskOptions.endsWith("\""))
                )
                {
                    addTaskOptions = addTaskOptions.substring(1, addTaskOptions.length() - 1).trim();
                }
                Map<String, String> addTaskOptionMap = new HashMap<>();
                for (String taskOption : StringUtil.splitString(addTaskOptions, ' '))
                {
                    String[] keyValue = StringUtil.splitString(taskOption, '=');
                    if (keyValue.length == 2) {
                        String key = keyValue[0].trim().toLowerCase();
                        String value = keyValue[1];
                        addTaskOptionMap.put(key, value);
                    }
                    else
                    {
                        throw new ParserCommandError("[MYSQL-BINLOG] Invalid task option. [" + taskOption + "]");
                    }
                }
                ConnectorTask connectorTask = ConnectorTask.newTask(taskName);
                connectorTask.setSourceSchemaRule(addTaskOptionMap.getOrDefault("sourceSchema".toLowerCase(), ""));
                if (addTaskOptionMap.containsKey("sourceTable".toLowerCase()))
                {
                    connectorTask.setSourceTableRule(addTaskOptionMap.get("sourceTable".toLowerCase()));
                }
                if (addTaskOptionMap.containsKey("targetSchema".toLowerCase()))
                {
                    connectorTask.setTargetSchemaRule(addTaskOptionMap.get("targetSchema".toLowerCase()));
                }
                else
                {
                    connectorTask.setTargetSchemaRule(connectorTask.getSourceSchemaRule());
                }
                if (addTaskOptionMap.containsKey("targetTable".toLowerCase()))
                {
                    connectorTask.setTargetSchemaRule(addTaskOptionMap.get("targetTable".toLowerCase()));
                }
                else
                {
                    connectorTask.setTargetTableRule(connectorTask.getSourceTableRule());
                }
                if (addTaskOptionMap.containsKey("checkPointInterval".toLowerCase()))
                {
                    connectorTask.setCheckpointInterval(Long.parseLong(addTaskOptionMap.get("checkPointInterval".toLowerCase())));
                }
                else
                {
                    connectorTask.setCheckpointInterval(0L);
                }
                connectorTask.setBinlogFileName(addTaskOptionMap.getOrDefault("binlogFileName".toLowerCase(), null));
                if (addTaskOptionMap.containsKey("binLogPosition".toLowerCase()))
                {
                    connectorTask.setBinLogPosition(Long.parseLong(addTaskOptionMap.get("binLogPosition".toLowerCase())));
                }
                else
                {
                    connectorTask.setBinLogPosition(0L);
                }
                connectorTask.setConnectorHandler(connector);
                connector.addTask(connectorTask);
            }
        }
        return super.visitAlterConnector(ctx);
    }

    @Override
    public Void visitCreateConnector(MysqlConnectorSyntaxParser.CreateConnectorContext ctx) {
        String connectorName = ctx.connectorName().getText().trim();

        if (connectorMap.containsKey(connectorName))
        {
            if (ctx.ifnotexists() != null) {
                return null;
            }
            else
            {
                throw new ParserCommandError("[MYSQL-BINLOG] Create failed. Connector [" + connectorName + "] already exist.");
            }
        }
        Connector connector = Connector.newConnector(connectorName);
        connector.setLogger(logger);

        String connectOptions = ctx.connectorOptions().getText().trim();
        if (
                (connectOptions.startsWith("'") && connectOptions.endsWith("'")) ||
                        (connectOptions.startsWith("\"") && connectOptions.endsWith("\""))
        )
        {
            connectOptions = connectOptions.substring(1, connectOptions.length() - 1).trim();
        }
        Map<String, String> connectionOptionMap = new HashMap<>();
        for (String connectOption : connectOptions.split(" "))
        {
            if (connectOption.split("=").length == 2) {
                String key = connectOption.split("=")[0].trim().toLowerCase();
                String value = connectOption.split("=")[1].trim();
                connectionOptionMap.put(key, value);
            }
            else
            {
                throw new ParserCommandError("[MYSQL-BINLOG] Invalid connect option. [" + connectOption + "]");
            }
        }
        connector.setTargetDBConnection(this.conn);
        if (connectionOptionMap.containsKey("host")) {
            connector.setHostName(connectionOptionMap.get("host"));
        }
        if (connectionOptionMap.containsKey("port")) {
            connector.setPort(Integer.parseInt(connectionOptionMap.get("port")));
        }
        if (connectionOptionMap.containsKey("user")) {
            connector.setUserName(connectionOptionMap.get("user"));
        }
        if (connectionOptionMap.containsKey("password")) {
            connector.setPassword(connectionOptionMap.get("password"));
        }
        if (connectionOptionMap.containsKey("dbname")) {
            connector.setDatabase(connectionOptionMap.get("dbname"));
        }

        // 保存connector信息
        if (ctx.TEMPORARY() != null)
        {
            try {
                connector.save();
            }
            catch (SQLException sqlException)
            {
                throw new RuntimeException(sqlException);
            }
        }
        // 记录全局静态信息
        connectorMap.put(connectorName, connector);
        return null;
    }

    @Override
    public Void visitStartConnector(MysqlConnectorSyntaxParser.StartConnectorContext ctx) {
        String connectorName = ctx.connectorName().getText().trim();

        if (connectorMap.containsKey(connectorName))
        {
            Connector connector = connectorMap.get(connectorName);
            connector.start();
        }
        else
        {
            throw new ParserCommandError("[MYSQL-BINLOG] Start failed. Connector [" + connectorName + "] does not exist.");
        }
        return super.visitStartConnector(ctx);
    }

    @Override
    public Void visitDropConnector(MysqlConnectorSyntaxParser.DropConnectorContext ctx) {
        return super.visitDropConnector(ctx);
    }

    @Override
    public Void visitShowConnector(MysqlConnectorSyntaxParser.ShowConnectorContext ctx) {
        return super.visitShowConnector(ctx);
    }

    @Override
    public Void visitShutdownConnector(MysqlConnectorSyntaxParser.ShutdownConnectorContext ctx) {
        return super.visitShutdownConnector(ctx);
    }

    public MysqlConnectorCommandVisitor(Connection conn, CharStream charStream, Logger logger)
    {
        this.conn = conn;
        this.inputStream = charStream;
        this.logger = logger;
    }

    public static void runConnectorCommand(Connection conn, String command, Logger logger)
    {
        CharStream input = CharStreams.fromString(command);

        // 创建词法分析器, 语法解析器
        MysqlConnectorSyntaxLexer lexer = new MysqlConnectorSyntaxLexer(input);
        lexer.removeErrorListeners();
        lexer.addErrorListener(new ParserErrorListener());

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        MysqlConnectorSyntaxParser parser = new MysqlConnectorSyntaxParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new ParserErrorListener());

        // 创建解析表达式
        try {
            ParseTree tree = parser.command();
            // 执行 visitor
            MysqlConnectorCommandVisitor visitor = new MysqlConnectorCommandVisitor(conn, input, logger);
            try {
                visitor.visit(tree);
            } catch (EventTermination ignored) {
            }
        } catch (RuntimeException re)
        {
            throw new ParserCommandError("Parser Error:", re);
        }
    }
}
