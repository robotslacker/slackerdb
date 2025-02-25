package org.slackerdb.connector.postgres.command;

import ch.qos.logback.classic.Logger;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.slackerdb.common.utils.StringUtil;
import org.slackerdb.connector.postgres.Connector;
import org.slackerdb.connector.postgres.ConnectorTask;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PostgresConnectorCommandVisitor extends PostgresConnectorSyntaxBaseVisitor<Void> {
    private final CharStream inputStream;
    private final Connection conn;
    private Logger logger = null;
    static class EventTermination extends RuntimeException {}
    static class ParserCommandError extends RuntimeException {
        ParserCommandError(String msg)
        {
            super(msg);
        }
        ParserCommandError(String msg, Exception ex) { super(msg, ex);}
    }

    public static ConcurrentHashMap<String ,Connector> connectorMap = new ConcurrentHashMap<>();

    @Override
    public Void visitAlterConnector(PostgresConnectorSyntaxParser.AlterConnectorContext ctx) {
        String connectorName = ctx.connectorName().getText().trim();
        Connector connector;
        if (connectorMap.containsKey(connectorName)) {
            connector = connectorMap.get(connectorName);
        }
        else
        {
            throw new ParserCommandError("[POSTGRES-WAL] Alter failed. Connector [" + connectorName + "] does not exist.");
        }

        if (ctx.TASK() != null)
        {
            String taskName = ctx.taskName().getText().trim();
            if (ctx.ADD() != null)
            {
                if (connector.connectorTasks.containsKey(taskName))
                {
                    if (ctx.ifnotexists() != null) {
                        return super.visitAlterConnector(ctx);
                    }
                    else {
                        throw new ParserCommandError(
                                "[POSTGRES-WAL] Alter failed. Connector task [" + connectorName + "," + taskName + "] already exist.");
                    }
                }
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
                for (String taskOption : addTaskOptions.trim().split("\\s+"))
                {
                    String[] keyValue = taskOption.split("=");
                    if (keyValue.length == 2) {
                        String key = keyValue[0].trim().toLowerCase();
                        String value = keyValue[1];
                        addTaskOptionMap.put(key, value);
                    }
                    else
                    {
                        throw new ParserCommandError("[POSTGRES-WAL] Invalid task option. [" + taskOption + "]");
                    }
                }
                try {
                    ConnectorTask connectorTask = ConnectorTask.newTask(connector, taskName,
                            addTaskOptionMap.get("sourceSchema".toLowerCase()), addTaskOptionMap.get("sourceTable".toLowerCase()),
                            addTaskOptionMap.getOrDefault("targetSchema".toLowerCase(), addTaskOptionMap.get("sourceSchema".toLowerCase())),
                            addTaskOptionMap.getOrDefault("targetTable".toLowerCase(), addTaskOptionMap.get("sourceTable".toLowerCase())),
                            Integer.parseInt(addTaskOptionMap.getOrDefault("pullInterval", "5000")));
                    connector.addTask(connectorTask);
                }
                catch (SQLException sqlException)
                {
                    throw new ParserCommandError("[POSTGRES-WAL] Create task failed.", sqlException);
                }
            }
        }
        return super.visitAlterConnector(ctx);
    }

    @Override
    public Void visitCreateConnector(PostgresConnectorSyntaxParser.CreateConnectorContext ctx) {
        String connectorName = ctx.connectorName().getText().trim();

        if (connectorMap.containsKey(connectorName))
        {
            if (ctx.ifnotexists() != null) {
                return null;
            }
            else
            {
                throw new ParserCommandError("[POSTGRES-WAL] Create failed. Connector [" + connectorName + "] already exist.");
            }
        }
        String connectOptions = ctx.connectorOptions().getText().trim();
        if (
                (connectOptions.startsWith("'") && connectOptions.endsWith("'")) ||
                        (connectOptions.startsWith("\"") && connectOptions.endsWith("\""))
        )
        {
            connectOptions = connectOptions.substring(1, connectOptions.length() - 1).trim();
        }

        // 信息持久化到数据库中
        Connector connector;
        try {
            connector = Connector.newConnector(this.conn, connectorName, connectOptions);
            connector.setLogger(logger);
        }
        catch (Exception sqlException)
        {
            throw new ParserCommandError("[POSTGRES-WAL] Create failed. ", sqlException);
        }

        // 记录全局静态信息
        connectorMap.put(connectorName, connector);

        logger.trace("[POSTGRES-WAL] Connector [{}] created. ", connectorName);
        return null;
    }

    @Override
    public Void visitStartConnector(PostgresConnectorSyntaxParser.StartConnectorContext ctx) {
        String connectorName = ctx.connectorName().getText().trim();

        if (connectorMap.containsKey(connectorName))
        {
            Connector connector = connectorMap.get(connectorName);
            connector.start();
        }
        else
        {
            throw new ParserCommandError("[POSTGRES-WAL] Start failed. Connector [" + connectorName + "] does not exist.");
        }
        return super.visitStartConnector(ctx);
    }

    @Override
    public Void visitDropConnector(PostgresConnectorSyntaxParser.DropConnectorContext ctx) {
        return super.visitDropConnector(ctx);
    }

    @Override
    public Void visitShowConnector(PostgresConnectorSyntaxParser.ShowConnectorContext ctx) {
        return super.visitShowConnector(ctx);
    }

    @Override
    public Void visitShutdownConnector(PostgresConnectorSyntaxParser.ShutdownConnectorContext ctx) {
        return super.visitShutdownConnector(ctx);
    }

    public PostgresConnectorCommandVisitor(Connection conn, CharStream charStream, Logger logger) throws Exception {
        this.conn = conn;
        this.inputStream = charStream;
        this.logger = logger;

        // 加载之前的连接器配置
        connectorMap = Connector.load(conn, logger);
    }

    public static void runConnectorCommand(Connection conn, String command, Logger logger)
    {
        CharStream input = CharStreams.fromString(command);

        // 创建词法分析器, 语法解析器
        PostgresConnectorSyntaxLexer lexer = new PostgresConnectorSyntaxLexer(input);
        lexer.removeErrorListeners();
        lexer.addErrorListener(new ParserErrorListener());

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        PostgresConnectorSyntaxParser parser = new PostgresConnectorSyntaxParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new ParserErrorListener());

        // 创建解析表达式
        try {
            ParseTree tree = parser.command();
            // 执行 visitor
            PostgresConnectorCommandVisitor visitor = new PostgresConnectorCommandVisitor(conn, input, logger);
            try {
                visitor.visit(tree);
            } catch (EventTermination ignored) {
            }
        } catch (Exception re)
        {
            throw new ParserCommandError("Parser Error:", re);
        }
    }
}
