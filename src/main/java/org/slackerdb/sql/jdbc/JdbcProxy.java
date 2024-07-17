package org.slackerdb.sql.jdbc;

import org.slackerdb.Main;
import org.slackerdb.iterators.QueryResultIterator;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.protocol.context.NetworkProtoContext;
import org.slackerdb.protocol.context.ProtoContext;
import org.slackerdb.proxy.Proxy;
import org.slackerdb.proxy.ProxyConnection;
import org.slackerdb.server.ServerConfiguration;
import org.slackerdb.sql.jdbc.storage.JdbcStorage;
import org.slackerdb.sql.jdbc.storage.NullJdbcStorage;
import org.slackerdb.sql.parser.SQLReplacer;
import org.slackerdb.sql.parser.SqlStringParser;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.sql.Date;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class JdbcProxy extends Proxy<JdbcStorage> {
    private final String driver;
    private final String connectionString;
    private static Connection connection = null;
    private final Pattern shutDownClausePattern;

    public JdbcProxy(String driver, String connectionString) {

        this.driver = driver;
        this.connectionString = connectionString;
        setStorage(new NullJdbcStorage());

        String regex = "(?i)\\bALTER\\s+DATABASE\\s+SHUTDOWN\\b";
        shutDownClausePattern = Pattern.compile(regex);
    }

    private static ParametrizedStatement buildParametrizedStatement(String query,
                                                                    List<BindingParameter> parameterValues,
                                                                    SqlStringParser parser, Connection c,
                                                                    ArrayList<JDBCType> concreteTypes, boolean insert) throws SQLException {
        query = parseParameters(query, parser);
        PreparedStatement ps;

        if (query.toLowerCase().startsWith("{call") || query.toLowerCase().startsWith("{? = call")) {
            ps = c.prepareCall(query);
        } else {
//            ps = c.prepareStatement(query, insert ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS);
            ps = c.prepareStatement(query);
        }
        for (var i = 0; i < parameterValues.size(); i++) {
            var pv = parameterValues.get(i);
            if (!pv.isOutput()) {
                ps.setObject(i + 1, convertObject(pv));
            } else {
                if (pv.getValue() == null
                        || pv.getType() == JDBCType.NULL
                ) {
                    ((CallableStatement) ps).registerOutParameter(i + 1, pv.isBinary() ? Types.VARBINARY : Types.VARCHAR);
                } else {
                    ((CallableStatement) ps).registerOutParameter(i + 1, pv.getType());
                }
            }


        }
        return new ParametrizedStatement(query, ps);
    }

    private static Object convertObject(BindingParameter value) {
        if (value == null) return null;
        var val = value.getValue();
        switch (value.getType()) {
            case INTEGER:
            case BIGINT:
            case TINYINT:
            case SMALLINT:
                return Long.parseLong(val);
            case BIT:
            case BOOLEAN:
                return Boolean.parseBoolean(val);
            case BINARY:
            case BLOB:
            case VARBINARY:
            case LONGVARBINARY:
                return Base64.getDecoder().decode(val);
            case FLOAT:
                return Float.parseFloat(val);
            case DOUBLE:
                return Double.parseDouble(val);
            case REAL:
            case NUMERIC:
                return new BigDecimal(val);
            case TIME:
            case TIME_WITH_TIMEZONE:
                return Time.valueOf(val);
            case DATE:
                if (val.length() > 10) {
                    val = val.substring(0, 10);
                }
                return Date.valueOf(val);
            case TIMESTAMP:
            case TIMESTAMP_WITH_TIMEZONE:
                return Timestamp.valueOf(val);
            default:
                return val;
        }
    }

    public static String parseParameters(String query, SqlStringParser parser) {
        var parsed = parser.parseString(query);
        StringBuilder realQuery = new StringBuilder();
        for (String item : parsed) {
            if (item.startsWith(parser.getParameterSeparator()) &&
                    item.length() > 1 && item.charAt(1) != '$') {
                realQuery.append("?");
            } else {
                realQuery.append(item);
            }
        }
        query = realQuery.toString();
        return query;
    }

    public static byte[] toBytes(ProxyMetadata field, ResultSet rs, int i) throws SQLException {
        if (!field.isByteData()) {
            if (rs.getString(i) == null) {
                return new byte[]{};
            } else {
                var str = rs.getString(i);
                return str.getBytes(StandardCharsets.UTF_8);
            }
        }

        return rs.getBytes(i);
    }

    public static boolean isByteOut(String clName) {
        var ns = clName.split("\\.");
        var name = ns[ns.length - 1].toLowerCase(Locale.ROOT);
        switch (name) {
            case ("[b"):
            case ("[c"):
            case ("byte"):
                return true;
            default:
                return false;
        }
    }

    private static void fillOutputParametersOnRecordset(List<BindingParameter> parameterValues, PreparedStatement statement, SelectResult result, AtomicLong count) {
        var maxRecordsAtomic = new AtomicLong(1);
        result.setIntResult(false);
        var cs = (CallableStatement) statement;
        for (int i = 0; i < parameterValues.size(); i++) {
            var op = parameterValues.get(i);
            if (!op.isOutput()) continue;
            result.getMetadata().add(new ProxyMetadata(
                    "" + i,
                    op.isBinary()));
        }
        var qrIterator = new QueryResultIterator<List<String>>(
                () -> maxRecordsAtomic.get() > 0,
                () -> {
                    try {
                        var byteRow = new ArrayList<String>();
                        for (int i = 0; i < parameterValues.size(); i++) {
                            var op = parameterValues.get(i);
                            if (!op.isOutput()) continue;
                            if (op.isBinary()) {
                                byteRow.add(Base64.getEncoder().encodeToString((cs.getBytes(i + 1))));
                            } else {
                                byteRow.add(cs.getString(i + 1));
                            }
                        }
                        count.incrementAndGet();
                        maxRecordsAtomic.decrementAndGet();
                        return byteRow;
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                },
                () -> {
                    try {
                        statement.close();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
        while (qrIterator.hasNext()) {
            result.getRecords().add(qrIterator.next());
        }
        result.setCount((int) count.get());
    }

    public String getConnectionString() {
        return connectionString;
    }

    public QueryResultIterator<List<String>> iterateThroughRecSet(final ResultSet resultSet,
                                                                  AtomicLong maxRecordsAtomic,
                                                                  SelectResult result,
                                                                  Statement statement,
                                                                  AtomicLong count) {
        return new QueryResultIterator<>(
                () -> {
                    try {
                        return resultSet.next() && maxRecordsAtomic.get() > 0;
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                },
                () -> {
                    try {
                        var byteRow = new ArrayList<String>();
                        for (var i = 0; i < result.getMetadata().size(); i++) {
                            var current = result.getMetadata().get(i);
                            if (current.isByteData()) {
                                var data = toBytes(current, resultSet, i + 1);
                                if (data == null) {
                                    data = new byte[0];
                                }
                                byteRow.add(Base64.getEncoder().encodeToString(data));
                            } else {
                                byteRow.add(resultSet.getString(i + 1));
                            }
                        }
                        count.incrementAndGet();
                        maxRecordsAtomic.decrementAndGet();
                        return byteRow;
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                },
                () -> {
                    try {
                        resultSet.close();
                        statement.close();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                }
        );
    }

    public SelectResult executeQuery(int connectionId, boolean insert, String query,
                                     Object connection, int maxRecords,
                                     List<BindingParameter> parameterValues,
                                     SqlStringParser parser,
                                     ArrayList<JDBCType> concreteTypes, ProtoContext context) {

        // 特殊的SQL语句,SHUTDOWN
        Matcher matcher = shutDownClausePattern.matcher(query);
        if (matcher.find())
        {
            // 特殊的SQL语句
            try {
                AppLogger.logger.info("[SERVER] RECEIVED SHUTDOWN REQUEST. System is going down...");
                Main.stop();
            }
            catch (Exception ex)
            {
                AppLogger.logger.trace("[SERVER] Peaceful shutdown failed.", ex);
            }
            AppLogger.logger.info("[SERVER] Shutdown complete. BYE!");
            System.exit(0);
        }

        String oldQuery = query;
        query = SQLReplacer.replaceSQL(query);
        if (!Objects.equals(oldQuery, query))
        {
            AppLogger.logger.trace("[PARSER] Replace SQL from [{}] to [{}]", oldQuery, query);
        }
        if (query.isEmpty())
        {
            return null;
        }
        if (replayer) {
            var storageItem = storage.read(query, parameterValues, "QUERY");
            return storageItem.getOutput().getSelectResult();
        }
        try {
            long start = System.currentTimeMillis();
            var result = new SelectResult();

            PreparedStatement statement;
            ResultSet resultSet;

            var c = ((Connection) ((ProxyConnection) connection).getConnection());
            if (parameterValues.isEmpty()) {
//                statement = c.prepareStatement(query, insert ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS);
                statement = c.prepareStatement(query);
            } else {
                ParametrizedStatement parametrizedStatementBuilder = buildParametrizedStatement(
                        query, parameterValues, parser, c,
                        concreteTypes, insert);
                statement = parametrizedStatementBuilder.ps;
            }

            context.setValue("EXECUTING_NOW", statement);
            var count = new AtomicLong(0);
            if (statement.execute()) {
                runThroughRecordset(maxRecords, statement, result, count);
            } else {
                runThroughSingleResult(insert, parameterValues, statement, result, count);
            }

            long end = System.currentTimeMillis();
            storage.write(connectionId, query, result, parameterValues, end - start, "QUERY");
            return result;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void runThroughSingleResult(boolean insert, List<BindingParameter> parameterValues, PreparedStatement statement, SelectResult result, AtomicLong count) throws SQLException {
        ResultSet resultSet;
        result.setIntResult(true);
        var updateCount = statement.getUpdateCount();

//        if (insert) {
//            resultSet = statement.getGeneratedKeys();
//            if (resultSet != null) {
//                result.setIntResult(false);
//                result.getMetadata().addAll(identifyFields(resultSet));
//                var maxRecordsAtomic = new AtomicLong(Integer.MAX_VALUE);
//                var qrIterator = iterateThroughRecSet(resultSet,
//                        maxRecordsAtomic,
//                        result,
//                        statement,
//                        count);
//                while (qrIterator.hasNext()) {
//                    result.getRecords().add(qrIterator.next());
//                }
//
//                result.setCount(result.getRecords().size());
//
//            }
//        }

        var outputParams = parameterValues.stream().filter(BindingParameter::isOutput).collect(Collectors.toList());
        if (!outputParams.isEmpty()) {
            fillOutputParametersOnRecordset(parameterValues, statement, result, count);
        } else {
            result.setCount(updateCount);
        }
    }

    private void runThroughRecordset(int maxRecords, PreparedStatement statement, SelectResult result, AtomicLong count) throws SQLException {
        ResultSet resultSet;
        if (maxRecords == 0) {
            maxRecords = Integer.MAX_VALUE;
        }
        var maxRecordsAtomic = new AtomicLong(maxRecords);
        resultSet = statement.getResultSet();

        result.getMetadata().addAll(identifyFields(resultSet));

        var qrIterator = iterateThroughRecSet(resultSet,
                maxRecordsAtomic,
                result,
                statement,
                count);
        while (qrIterator.hasNext()) {
            result.getRecords().add(qrIterator.next());
        }
        result.setCount((int) count.get());
    }

    private List<ProxyMetadata> identifyFields(ResultSet resultSet) {
        try {
            var resultSetMetaData = resultSet.getMetaData();
            var fields = new ArrayList<ProxyMetadata>();
            for (var i = 0; i < resultSetMetaData.getColumnCount(); i++) {

                var isByte = isByteOut(resultSetMetaData.getColumnClassName(i + 1));

                fields.add(new ProxyMetadata(
                        resultSetMetaData.getColumnName(i + 1),
                        resultSetMetaData.getColumnLabel(i + 1),
                        isByte,
                        resultSetMetaData.getCatalogName(i + 1),
                        resultSetMetaData.getSchemaName(i + 1),
                        resultSetMetaData.getTableName(i + 1),
                        resultSetMetaData.getColumnDisplaySize(i + 1),
                        resultSetMetaData.getColumnType(i + 1),
                        resultSetMetaData.getPrecision(i + 1)));
            }
            return fields;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ProxyConnection connect(NetworkProtoContext context) {
        if (replayer) {
            return new ProxyConnection(null);
        }
        try {
            if (connection == null) {
                synchronized (JdbcProxy.class) {
                    if (ServerConfiguration.getAccess_mode().equals("READ_ONLY")) {
                        Properties readOnlyProperty = new Properties();
                        readOnlyProperty.setProperty("duckdb.read_only", "true");
                        AppLogger.logger.info("[SERVER] Create database in read only mode ...");
                        connection = DriverManager.getConnection(getConnectionString(), readOnlyProperty);
                    }
                    else
                    {
                        AppLogger.logger.info("[SERVER] Create database in read write mode ...");
                        connection = DriverManager.getConnection(getConnectionString());
                    }

                    Statement statement = connection.createStatement();
                    // 第一次创建连接，设置需要的相应参数到数据库中
                    if (!ServerConfiguration.getCurrentSchema().isEmpty())
                    {
                        statement.execute("CREATE SCHEMA IF NOT EXISTS " + ServerConfiguration.getCurrentSchema());
                        statement.execute("USE " + ServerConfiguration.getCurrentSchema());
                    }
                    if (!ServerConfiguration.getMemory_limit().equals("DEFAULT"))
                    {
                        statement.execute("SET MEMORY_LIMIT = " + ServerConfiguration.getMemory_limit());
                    }
                    if (ServerConfiguration.getThreads() != -1)
                    {
                        statement.execute("SET THREADS = " + ServerConfiguration.getThreads());
                    }
                    statement.close();
                }
            }
            return new ProxyConnection(connection);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void initialize() {
        if (replayer) return;
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }


    public void executeBegin(ProtoContext protoContext) {
        if (replayer) return;
        try {
            var c = ((Connection) ((ProxyConnection) protoContext.getValue("CONNECTION")).getConnection());
            c.setAutoCommit(false);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void executeCommit(ProtoContext protoContext) {
        if (replayer) return;
        try {
            var c = ((Connection) ((ProxyConnection) protoContext.getValue("CONNECTION")).getConnection());
            c.setAutoCommit(true);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void executeRollback(ProtoContext protoContext) {
        if (replayer) return;
        try {
            var c = ((Connection) ((ProxyConnection) protoContext.getValue("CONNECTION")).getConnection());
            c.rollback();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void setIsolation(ProtoContext protoContext, int transactionIsolation) {
        if (replayer) return;
        try {
            var c = ((Connection) ((ProxyConnection) protoContext.getValue("CONNECTION")).getConnection());
            c.setTransactionIsolation(transactionIsolation);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
