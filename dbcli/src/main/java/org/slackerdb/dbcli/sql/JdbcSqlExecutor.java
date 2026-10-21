package org.slackerdb.dbcli.sql;

import org.slackerdb.dbcli.assertion.ResultModel;
import org.slackerdb.dbcli.common.CommandOutcome;
import org.slackerdb.dbcli.common.UserNumber;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * {@link SqlExecutor} 的 JDBC 实现：连接走 {@link DriverManager}，因此**classpath 上有哪个驱动就能连哪个库**
 * （本类不自带任何第三方驱动，放进来的驱动由发行包/使用者决定；SlackerDB 用的是随包驱动）。
 *
 * <h2>IR → JDBC URL（本类唯一的"拼串"逻辑，已单测）</h2>
 * <pre>
 *   _CONNECT <host>:<port>/<service>                          →  {host, port, service}
 *   _CONNECT [<user>[/<password>]@]jdbc:slackerdb://<host>:<port>/[<database>]
 *                                                             →  {username, password, driverSchema,
 *                                                                 host, port, service[, parameters]}
 *   _CONNECT [<user>[/<password>]@]jdbc:<scheme>:<url>         →  {username, password, driverSchema, jdbcUrl}
 *   …?k=v&amp;k2=v2                                             →  {parameters=[{parameterName,parameterValue}…]}
 * </pre>
 * <ul>
 *   <li><b>简写（默认）形态</b> {@code <host>:<port>/<service>}：**没有缺省值**——端口与服务名
 *       都得写出来，客户端不替使用者补；也**不接受用户名/口令**（服务端没有鉴权）。拼出来就是
 *       {@code jdbc:slackerdb://<host>:<port>/<service>}；</li>
 *   <li><b>全写形态</b> {@code [<user>[/<password>]@]jdbc:slackerdb://<host>:<port>/[<database>]}：
 *       scheme 只接受 {@code slackerdb}（大小写不敏感）；host/端口仍然必填，用户名/口令与库名
 *       可以写——口令走 JDBC properties（不拼进 URL，因此错误回显 URL 不会带出口令）；库名省略时
 *       URL 以 {@code /} 收尾，交给驱动按它自己的默认库处理（当前驱动：默认库 = 用户名）；</li>
 *   <li><b>其它驱动</b> {@code [<user>[/<password>]@]jdbc:<scheme>:<url>}（例如
 *       {@code jdbc:sqlite:/tmp/demo.db}）：URL 是**驱动自己的语法**，本类一字不改地交给
 *       {@link DriverManager}（不解释 host/端口/库名）；对应驱动必须在 classpath 上，
 *       否则就是一条 {@code Cannot connect to [<url>]: No suitable driver}；</li>
 *   <li>只要连接串不构成上面三种形态之一（缺 host/端口、简写形态缺服务名或带了用户名/口令、
 *       {@code jdbc:} 后面没有 URL 正文、host 不是主机名/IP、端口不是数字…），就报**同一条**错误
 *       {@code Invalid connection string [<原值>].}（见 {@link #buildTarget}）——
 *       不解释原因，也不给"应该怎么写"的指令；</li>
 *   <li>{@code parameters} 原样接成 URL 查询参数（不做百分号转义，与脚本里写的字面量一致）。</li>
 * </ul>
 *
 * <h2>会话（{@code _SESSION}）的语义（与 SSH 会话注册表同风格：名字是**引用**）</h2>
 * <ul>
 *   <li>当前会话名默认 {@code NONAME}；{@code _CONNECT} 把新连接登记在当前名字下；</li>
 *   <li>{@code SAVE <名>}：把**当前连接**再挂一个名字（不复制连接）；</li>
 *   <li>{@code RESTORE <名>}：切换当前名字；名字不存在**立刻报错**（拼错当场暴露）。
 *       若该名字是 {@code SAVEURL} 存下的（只有 URL 没有活连接），则按 URL 重新连一条；</li>
 *   <li>{@code SAVEURL <名>}：只记下当前连接的 URL，不持有连接；</li>
 *   <li>{@code RELEASE <名>}：删掉这个名字；没有任何名字再引用该连接时才真正关闭它；</li>
 *   <li>{@code SHOW}：用结果集列出名字、URL 与"是否当前"（表头 {@code name/url/current}）。</li>
 * </ul>
 *
 * <p><b>为什么结果集只在 {@code execute} 里读</b>：{@code ResultModel} 已经把"断言要取的值"
 * 与"表格要渲染的素材"装齐（{@code headers}/{@code columnTypes}/{@code rows}），
 * 引擎拿它渲染即可，不需要再碰 {@link ResultSet}。</p>
 */
public final class JdbcSqlExecutor implements SqlExecutor {

    /** 默认当前会话名（与 SSH 会话注册表的 {@code NONAME} 对齐）。 */
    public static final String DEFAULT_SESSION_NAME = "NONAME";

    /**
     * 随包提供的驱动类（SlackerDB 自己的 JDBC 驱动）。
     *
     * <p>它不是"唯一能用的驱动"了：{@code _CONNECT} 也接受其它 {@code jdbc:<scheme>:…} URL，
     * 由 {@link DriverManager} 在 classpath 上找对应驱动（见 {@link #buildTarget}）。
     * 常量保留是因为发行包与文档都要说明"哪个驱动是自带的"。</p>
     */
    public static final String BUNDLED_DRIVER_CLASS = "org.slackerdb.jdbc.Driver";

    /** 主机名的合法字符（ASCII）：不接受非 ASCII 与空白，{@code 乱码} 这类输入因此直接判为无效连接串。 */
    private static final Pattern HOST_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    /** 方括号包起来的 IPv6 字面量（与脚本里 {@code [::1]:5432} 的写法一致）。 */
    private static final Pattern BRACKETED_IPV6 = Pattern.compile("\\[[0-9A-Fa-f:.]+]");

    /** 全写形态唯一认的 scheme，也就是驱动的前缀 {@code jdbc:slackerdb:}（大小写不敏感）。 */
    private static final String SLACKERDB_SCHEMA = "slackerdb";

    /**
     * 一次连接的目标：URL + 可选的用户名/口令（只有全写形态才会有后两者）。
     *
     * <p>口令**不拼进 URL**：JDBC 的 URL 里本来就没有口令的位置（驱动从 properties 读），
     * 因此"连不上"的错误里回显 URL 时也不会把口令带出来。</p>
     */
    record ConnectionTarget(String url, String user, String password) {
    }

    /** 一个命名会话：{@code target} 一定有（{@code SAVEURL} 就靠它），{@code connection} 可能为 null。 */
    private record SavedSession(ConnectionTarget target, Connection connection) {

        /** {@code _SESSION SHOW} 与旧记录只关心 URL。 */
        String url() {
            return target.url();
        }
    }

    private final Map<String, SavedSession> sessions = new LinkedHashMap<>();

    private String currentSessionName = DEFAULT_SESSION_NAME;

    private String lastError;

    /**
     * 当前正在执行的语句（**跨线程可见**）：作业被中止时，请求停止的那条线程要能取消它。
     *
     * <p>只有作业线程写它，取消线程只读，因此 {@code volatile} 足够；不需要额外加锁。</p>
     */
    private volatile Statement runningStatement;

    /** {@code _SET TIMEOUT}：单条语句的服务端超时秒数（0 = 不限）。 */
    private int queryTimeoutSeconds;

    public JdbcSqlExecutor() {
    }

    /** 当前会话名（供 {@code _SESSION SHOW} 与排错用）。 */
    public String currentSessionName() {
        return currentSessionName;
    }

    @Override
    public boolean isConnected() {
        SavedSession current = sessions.get(currentSessionName);
        if (current == null || current.connection() == null) {
            return false;
        }
        try {
            return !current.connection().isClosed();
        } catch (SQLException closedCheckFailed) {
            return false;
        }
    }

    @Override
    public void connect(Map<String, Object> ir) {
        ConnectionTarget target = buildTarget(ir);
        Connection connection;
        try {
            // 简写形态没有用户名/口令（服务端没有鉴权）；全写形态有就交给驱动，没有就与旧行为一致
            connection = open(target);
        } catch (SQLException connectFailed) {
            throw new SqlExecutorException("Cannot connect to [" + target.url() + "]: "
                    + connectFailed.getMessage(), connectFailed);
        }
        // 先建好新连接再替换当前槽位：连接失败时旧会话不受影响
        SavedSession previous = sessions.put(currentSessionName, new SavedSession(target, connection));
        // 旧连接只在**没有别的名字还指着它**时才关：`_connect` 之前 `_session SAVE A` 过的名字
        // 仍然代表那条活的连接，直接关掉会让 RESTORE A 得到一个已关闭的连接（报"connection is closed"）。
        closeIfUnreferenced(previous);
    }

    @Override
    public void disconnect() {
        SavedSession current = sessions.get(currentSessionName);
        Connection target = current == null ? null : current.connection();
        if (target == null) {
            return;
        }
        // 名字是引用语义：任何名字只要指向这条连接，断开后都失效，因此一并清掉，
        // 避免 isConnected() 对一条已关闭的连接报 true（"状态只反映真相"）。
        for (Map.Entry<String, SavedSession> entry : new ArrayList<>(sessions.entrySet())) {
            if (entry.getValue().connection() == target) {
                sessions.remove(entry.getKey());
            }
        }
        closeQuietly(target);
    }

    @Override
    public List<CommandOutcome> session(String action, String sessionName) {
        List<CommandOutcome> outcomes = new ArrayList<>();
        String name = sessionName == null ? null : sessionName.strip();
        String act = action == null ? "" : action.toUpperCase(Locale.ROOT);
        switch (act) {
            case "SAVE" -> save(name, outcomes);
            case "RESTORE" -> restore(name, outcomes);
            case "RELEASE" -> release(name, outcomes);
            case "SAVEURL" -> saveUrl(name, outcomes);
            case "SHOW" -> show(outcomes);
            default -> outcomes.add(new CommandOutcome.Error("Unsupported session action [" + action + "]"));
        }
        return outcomes;
    }

    private void save(String name, List<CommandOutcome> outcomes) {
        SavedSession current = sessions.get(currentSessionName);
        if (current == null || current.connection() == null) {
            outcomes.add(new CommandOutcome.Error("Session is not connected."));
            return;
        }
        if (name == null || name.isEmpty()) {
            outcomes.add(new CommandOutcome.Error("Session name is missing."));
            return;
        }
        // 覆盖同名时，旧条目独占的连接要收掉（否则它再也没有名字指向，句柄泄漏到进程结束）
        closeIfUnreferenced(sessions.put(name, current));
        outcomes.add(new CommandOutcome.Status("Session saved."));
    }

    private void restore(String name, List<CommandOutcome> outcomes) {
        SavedSession saved = name == null ? null : sessions.get(name);
        if (saved == null) {
            outcomes.add(new CommandOutcome.Error("Session [" + name + "] not found."));
            return;
        }
        if (saved.connection() != null) {
            currentSessionName = name;
            outcomes.add(new CommandOutcome.Status("Session restored."));
            return;
        }
        // SAVEURL 存下的名字只有 URL：按它重新连一条，再切为当前会话
        try {
            // 用户名/口令跟着目标一起存：全写形态的会话恢复后仍然连同一个身份
            Connection connection = open(saved.target());
            sessions.put(name, new SavedSession(saved.target(), connection));
            currentSessionName = name;
            outcomes.add(new CommandOutcome.Status("Session restored."));
        } catch (SQLException restoreFailed) {
            outcomes.add(new CommandOutcome.Error("Cannot restore session [" + name + "]: "
                    + restoreFailed.getMessage()));
        }
    }

    private void release(String name, List<CommandOutcome> outcomes) {
        SavedSession removed = name == null ? null : sessions.get(name);
        if (removed == null) {
            outcomes.add(new CommandOutcome.Error("Session [" + name + "] not found."));
            return;
        }
        sessions.remove(name);
        if (name.equals(currentSessionName)) {
            currentSessionName = DEFAULT_SESSION_NAME;
        }
        if (removed.connection() != null && !referenced(removed.connection())) {
            closeQuietly(removed.connection());
        }
        outcomes.add(new CommandOutcome.Status("Session released."));
    }

    private void saveUrl(String name, List<CommandOutcome> outcomes) {
        SavedSession current = sessions.get(currentSessionName);
        if (current == null) {
            outcomes.add(new CommandOutcome.Error("Session is not connected."));
            return;
        }
        if (name == null || name.isEmpty()) {
            outcomes.add(new CommandOutcome.Error("Session name is missing."));
            return;
        }
        closeIfUnreferenced(sessions.put(name, new SavedSession(current.target(), null)));
        outcomes.add(new CommandOutcome.Status("Session URL saved."));
    }

    private void show(List<CommandOutcome> outcomes) {
        outcomes.add(new CommandOutcome.Status("Current sessions:"));
        List<List<Object>> rows = new ArrayList<>();
        for (Map.Entry<String, SavedSession> entry : sessions.entrySet()) {
            rows.add(List.of(
                    entry.getKey(),
                    entry.getValue().url() == null ? "" : entry.getValue().url(),
                    entry.getKey().equals(currentSessionName) ? "True" : "False"));
        }
        outcomes.add(new CommandOutcome.Table(List.of("name", "url", "current"), rows));
    }

    /** 顶掉一个名字时收掉它**独占**的连接：还有别的名字指着同一条连接就不关（名字是引用语义）。 */
    private void closeIfUnreferenced(SavedSession replaced) {
        if (replaced != null && replaced.connection() != null && !referenced(replaced.connection())) {
            closeQuietly(replaced.connection());
        }
    }

    /** 是否还有别的名字（或当前会话）引用这条连接。 */
    private boolean referenced(Connection connection) {
        for (SavedSession session : sessions.values()) {
            if (session.connection() == connection) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ResultModel execute(String statement) {
        List<ResultModel> results = executeAll(statement);
        return results.isEmpty() ? errorModel() : results.get(results.size() - 1);
    }

    /**
     * 执行一条语句并返回**全部结果**（多结果集按顺序）：{@code CALL}/{@code DO} 这类语句可能给出
     * 多个结果集，以前只读第一个、其余的留在驱动里被静默丢弃。
     *
     * <p>JDBC 的标准循环：{@code execute()} 给第一个结果，之后用 {@code getMoreResults()} 推进，
     * 直到"没有更多结果"（update count 为 -1）。DDL 的 update count 也可能是 -1，按 0 处理，
     * 并保证**至少有一个**结果（否则状态行整条不见）。</p>
     */
    @Override
    public List<ResultModel> executeAll(String statement) {
        Connection connection = sessions.get(currentSessionName) == null
                ? null : sessions.get(currentSessionName).connection();
        if (connection == null) {
            lastError = "Not connected.";
            return List.of(errorModel());
        }
        try (Statement jdbcStatement = connection.createStatement()) {
            applyLimits(jdbcStatement, queryTimeoutSeconds);
            // 登记当前语句：作业被中止时，Statement.cancel() 能让一条长语句尽早失败
            runningStatement = jdbcStatement;
            List<ResultModel> results = readAll(jdbcStatement, statement);
            lastError = null;
            return results;
        } catch (SQLException executionFailed) {
            lastError = errorText(executionFailed.getMessage());
            return List.of(errorModel());
        } finally {
            runningStatement = null;
        }
    }


    /**
     * 把一条语句产生的**全部结果**读出来（多结果集按顺序）。
     *
     * <p>包可见并接收一个现成的 {@code Statement}：多结果集循环要能脱离真实数据库被验证
     * （见 {@code JdbcSqlExecutorMultiResultTest}）。标准 JDBC 循环是
     * {@code execute → getMoreResults → getUpdateCount}，直到 update count 为 -1。</p>
     */
    List<ResultModel> readAll(Statement jdbcStatement, String statement) throws SQLException {
        boolean query = jdbcStatement.execute(statement);
        boolean hasWarnings = jdbcStatement.getWarnings() != null;
        List<ResultModel> results = new ArrayList<>();
        while (true) {
            if (query) {
                results.add(readResultSet(jdbcStatement, hasWarnings));
            } else {
                int updateCount = jdbcStatement.getUpdateCount();
                if (updateCount < 0) {
                    break;
                }
                results.add(updateModel(updateCount, hasWarnings));
            }
            query = jdbcStatement.getMoreResults();
        }
        if (results.isEmpty()) {
            // DDL：驱动报 update count = -1，但状态行仍然要有
            results.add(updateModel(0, hasWarnings));
        }
        return results;
    }
    private static ResultModel updateModel(int affected, boolean hasWarnings) {
        return new ResultModel(0, affected,
                SqlTableRenderer.statusLine(false, affected, hasWarnings, true, false),
                List.of(), List.of(), List.of(), Map.of());
    }

    /**
     * 执行层错误的显示文本：以 {@code ERROR: } 开头，但**不重复前缀**。
     *
     * <p>驱动返回的消息本身已经带 {@code ERROR: }（例如
     * {@code ERROR: Parser Error: syntax error at or near "selec"}），以前无条件再加一次，
     * 于是使用者看到 {@code ERROR: ERROR: …}。这里只在缺少前缀时补。</p>
     */
    static String errorText(String message) {
        if (message == null || message.isBlank()) {
            return "ERROR: unknown failure.";
        }
        String text = message.strip();
        return text.regionMatches(true, 0, "ERROR:", 0, "ERROR:".length()) ? text : "ERROR: " + text;
    }

    /**
     * 取消**当前正在执行**的语句（控制台 Ctrl-C、作业被中止时都走这里；没有在跑的语句时什么都不做）。
     *
     * <p>为什么需要它：JDBC 的 {@code execute()} 是阻塞调用，中断线程并不能让它回来
     * （驱动不响应 {@code interrupt}）；{@code Statement.cancel()} 是 JDBC 给出的唯一标准手段。
     * 取消之后那次 {@code execute} 会以 SQLException 结束，错误路径照常走。</p>
     */
    @Override
    public void cancelCurrentStatement() {
        Statement statement = runningStatement;
        if (statement == null) {
            return;
        }
        try {
            statement.cancel();
        } catch (SQLException | RuntimeException cancelFailed) {
            // 取消是"尽力"：驱动不支持 / 语句已经结束时静默即可，作业的状态不靠它决定
        }
    }

    private ResultModel readResultSet(Statement jdbcStatement, boolean hasWarnings) throws SQLException {
        try (ResultSet resultSet = jdbcStatement.getResultSet()) {
            StoredResult stored = read(resultSet);
            lastError = null;
            return new ResultModel(0, stored.rows().size(),
                    SqlTableRenderer.statusLine(true, stored.rows().size(), hasWarnings, true, false),
                    stored.rows(), stored.headers(), stored.columnTypes(), Map.of());
        }
    }

    /**
     * 把语句级限额套到 JDBC {@code Statement} 上（**唯一**的落地点）。
     *
     * <p>包可见并接收一个现成的 {@code Statement}：这条接线是"`_SET TIMEOUT` 到底有没有生效"的判据，
     * 必须能脱离真实数据库被验证（见 {@code JdbcSqlExecutorLimitsTest}）。</p>
     *
     * @param queryTimeoutSeconds 0 表示不设超时（也不调用驱动：省掉一次可能抛
     *                            {@code SQLFeatureNotSupportedException} 的交互）
     */
    static void applyLimits(Statement jdbcStatement, int queryTimeoutSeconds) throws SQLException {
        if (queryTimeoutSeconds > 0) {
            jdbcStatement.setQueryTimeout(queryTimeoutSeconds);
        }
    }

    /** 一次结果集读取的产物（列信息 + 行）。 */
    record StoredResult(List<String> headers, List<String> columnTypes, List<List<Object>> rows) {
    }

    /**
     * 读结果集（**唯一**的读行循环）：读到没有为止，不做任何截断。
     *
     * <p>包可见并接收一个现成的 {@link ResultSet}：读行与类型映射要能脱离真实数据库被验证
     * （见 {@code JdbcSqlExecutorReadTest}）。</p>
     */
    static StoredResult read(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<String> headers = new ArrayList<>(columnCount);
        List<String> columnTypes = new ArrayList<>(columnCount);
        // timestamptz 在 JDBC 里同样是 TIMESTAMP(93)，但值必须按"带时区"读：
        // 否则时区被丢掉，相差 8 小时的两个时刻会打印成同一个字符串（--ref 完全看不出差别）。
        boolean[] timeZoneColumns = new boolean[columnCount + 1];
        for (int index = 1; index <= columnCount; index++) {
            headers.add(metaData.getColumnLabel(index));
            timeZoneColumns[index] = isTimestampWithZone(metaData.getColumnTypeName(index));
            columnTypes.add(SqlColumnTypes.nameOf(metaData.getColumnType(index),
                    metaData.getColumnTypeName(index)));
        }
        List<List<Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            List<Object> row = new ArrayList<>(columnCount);
            for (int index = 1; index <= columnCount; index++) {
                row.add(timeZoneColumns[index]
                        ? readTimestampWithZone(resultSet, index)
                        : resultSet.getObject(index));
            }
            rows.add(row);
        }
        return new StoredResult(headers, columnTypes, rows);
    }

    /**
     * 驱动的列类型名是否是"带时区的时间戳"。
     *
     * <p>JDBC 的 {@code Types} 里没有 timestamptz（它也报 93），所以只能看类型名；
     * 这是 SlackerDB 自己的客户端与驱动，名字是稳定的。</p>
     */
    private static boolean isTimestampWithZone(String driverTypeName) {
        if (driverTypeName == null) {
            return false;
        }
        String name = driverTypeName.strip().toLowerCase(Locale.ROOT);
        return name.equals("timestamptz") || name.equals("timestamp with time zone")
                || name.equals("timestamp with timezone");
    }

    /**
     * 按 {@link OffsetDateTime} 读取带时区的时间戳；驱动不支持时退回普通取值（仍然可读，只是丢时区）。
     */
    private static Object readTimestampWithZone(ResultSet resultSet, int index) throws SQLException {
        try {
            return resultSet.getObject(index, OffsetDateTime.class);
        } catch (SQLException | RuntimeException unsupported) {
            return resultSet.getObject(index);
        }
    }

    private ResultModel errorModel() {
        return new ResultModel(1, 0, null, List.of(), List.of(), List.of(), Map.of());
    }

    @Override
    public String lastError() {
        return lastError;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) {
        SavedSession current = sessions.get(currentSessionName);
        if (current == null || current.connection() == null) {
            throw new SqlExecutorException("Not connected.");
        }
        try {
            current.connection().setAutoCommit(autoCommit);
        } catch (SQLException autoCommitFailed) {
            throw new SqlExecutorException("Cannot switch autocommit: " + autoCommitFailed.getMessage(),
                    autoCommitFailed);
        }
    }

    @Override
    public void setQueryTimeout(int seconds) {
        this.queryTimeoutSeconds = Math.max(seconds, 0);
    }

    /** 当前语句超时秒数（0 = 不限）。 */
    public int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    @Override
    public void close() {
        for (SavedSession session : new ArrayList<>(sessions.values())) {
            closeQuietly(session.connection());
        }
        sessions.clear();
        currentSessionName = DEFAULT_SESSION_NAME;
    }

    // ------------------------------------------------------------------ URL 拼接

    /**
     * 按 IR 拼 JDBC URL（包可见，便于直接单测：这是"连不上时到底连的是什么"的唯一依据）。
     *
     * <p>全写形态里的用户名/口令不在 URL 里（见 {@link ConnectionTarget}），需要它们时用
     * {@link #buildTarget}。</p>
     *
     * @throws SqlExecutorException 连接串无效时（消息直接给用户看）
     */
    static String buildUrl(Map<String, Object> ir) {
        return buildTarget(ir).url();
    }

    /**
     * 按 IR 得到一次连接的目标（URL + 可选的用户名/口令）。
     *
     * <p>接受**三种**形态，其余一律无效：</p>
     * <ol>
     *   <li><b>简写（默认）形态</b> {@code <host>:<port>/<service>[?k=v&…]}——三段都必须写出来，
     *       客户端不替使用者补任何东西（没有默认端口、没有默认服务名），也**不接受用户名/口令**；</li>
     *   <li><b>全写形态</b> {@code [<user>[/<password>]@]jdbc:slackerdb://<host>:<port>/[<database>]}
     *       ——scheme 只认 {@code slackerdb}；host/端口仍必填；用户名/口令与库名可写可不写。
     *       库名省略时 URL 以 {@code /} 收尾，之后由驱动按它自己的默认库处理
     *       （当前驱动：默认库 = 用户名；本类不替使用者补库名，与简写形态"不补默认值"是同一原则）；</li>
     *   <li><b>其它驱动</b> {@code [<user>[/<password>]@]jdbc:<scheme>:<url>}（IR 里是
     *       {@code {driverSchema, jdbcUrl}}）：URL 原样透传，本类**不解释**它的 host/端口/库名；
     *       有没有对应驱动由 {@link DriverManager} 与 classpath 决定。</li>
     * </ol>
     *
     * <p>不构成这三种形态的都归为**同一条**错误 {@code Invalid connection string [<原值>].}
     * （见 {@link #invalidConnectionString}）：缺 host/端口、简写形态缺服务名或带用户名/口令、
     * {@code jdbc:} 后面没有 URL 正文、host 不是主机名/IP、端口不是 1..65535 的数字。</p>
     *
     * <p>文案刻意只报告"这个连接串无效"并原样回显取值：不解释哪一段不合法，也不给改法指令
     * —— 连接串是使用者自己写的，回显原文比一句通用指导更有用。</p>
     *
     * @throws SqlExecutorException 连接串无效时（消息直接给用户看）
     */
    static ConnectionTarget buildTarget(Map<String, Object> ir) {
        String host = text(ir.get("host"));
        String port = text(ir.get("port"));
        String service = text(ir.get("service"));
        String schema = text(ir.get("driverSchema"));
        String user = text(ir.get("username"));
        String password = text(ir.get("password"));
        String jdbcUrl = text(ir.get("jdbcUrl"));
        // 其它驱动的完整 URL（jdbc:<scheme>:…）：URL 是**驱动自己的语法**，本类一字不改地透传，
        // 只把用户名/口令接成 JDBC properties（见 {@link ConnectionTarget}）。
        if (jdbcUrl != null && !jdbcUrl.isBlank()) {
            String url = jdbcUrl.strip();
            if (!url.regionMatches(true, 0, "jdbc:", 0, "jdbc:".length())) {
                throw invalidConnectionString(ir);
            }
            return new ConnectionTarget(url, user == null || user.isBlank() ? null : user, password);
        }
        // 有 scheme 就是全写形态；简写形态的 IR 里根本没有 driverSchema 这个键
        if (schema != null && !schema.isBlank()) {
            return fullFormTarget(ir, schema, host, port, service, user, password);
        }
        // 简写（默认）形态：三段目标必须齐全，缺端口/缺服务名都不再"用默认值"补上
        boolean completeTarget = isValidHost(host) && isValidPort(port)
                && service != null && !service.isBlank();
        // 不支持用户名/口令；带用户名/口令与缺目标一样，都是无效连接串
        boolean hasCredentials = (user != null && !user.isBlank())
                || (password != null && !password.isBlank());
        if (!completeTarget || hasCredentials) {
            throw invalidConnectionString(ir);
        }
        return new ConnectionTarget(
                withParameters("jdbc:slackerdb://" + host + ":" + port + "/" + service, ir), null, null);
    }

    /**
     * 全写形态：{@code [<user>[/<password>]@]jdbc:slackerdb://<host>:<port>/[<database>]}。
     *
     * @param service 库名（语法里沿用 {@code service} 这个字段；省略时为 null）
     */
    private static ConnectionTarget fullFormTarget(Map<String, Object> ir, String schema, String host,
                                                   String port, String service, String user, String password) {
        // scheme 只认 slackerdb（大小写不敏感：词法本来就不区分大小写）
        boolean supportedSchema = SLACKERDB_SCHEMA.equalsIgnoreCase(schema.strip());
        boolean completeTarget = isValidHost(host) && isValidPort(port)
                && (service == null || !service.isBlank());
        if (!supportedSchema || !completeTarget) {
            throw invalidConnectionString(ir);
        }
        // 库名可以省略：省略时保留结尾的 '/'，交给驱动按自己的默认库处理（当前驱动：默认库 = 用户名）
        String url = "jdbc:slackerdb://" + host + ":" + port + "/" + (service == null ? "" : service);
        return new ConnectionTarget(withParameters(url, ir),
                user == null || user.isBlank() ? null : user, password);
    }

    /** 打开连接：有用户名就交给驱动（口令可选），没有就只用 URL（与旧行为完全一致）。 */
    private static Connection open(ConnectionTarget target) throws SQLException {
        if (target.user() == null) {
            return DriverManager.getConnection(target.url());
        }
        Properties properties = new Properties();
        properties.setProperty("user", target.user());
        if (target.password() != null) {
            properties.setProperty("password", target.password());
        }
        return DriverManager.getConnection(target.url(), properties);
    }

    /** 把 IR 的查询参数接到 URL 后面（没有参数就原样返回）。 */
    private static String withParameters(String url, Map<String, Object> ir) {
        String parameters = queryParameters(ir);
        return parameters.isEmpty() ? url : url + "?" + parameters;
    }

    /**
     * 无效连接串的统一错误：原样回显使用者写的连接串（{@code IR.connectString}），
     * 一条消息覆盖所有不合法形态（不解释、不给改法）。
     *
     * <p>IR 里没有 {@code connectString} 时（极少数直接构造 IR 的调用方）回显空方括号，
     * 仍然保持"同一个形状"，调用方不必为错误文案分叉。</p>
     */
    private static SqlExecutorException invalidConnectionString(Map<String, Object> ir) {
        String raw = text(ir.get("connectString"));
        return new SqlExecutorException(SqlExecutor.invalidConnectionStringMessage(raw));
    }

    /** 主机名或 {@code [IPv6]}/IPv4：能当连接目标写出来的形状（可达性不在这里判断）。 */
    private static boolean isValidHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        if (BRACKETED_IPV6.matcher(host).matches()) {
            return true;
        }
        if (!HOST_NAME.matcher(host).matches()) {
            return false;
        }
        // `.`/`-` 单独出现（如 `_connect .`）不是主机
        return host.chars().anyMatch(Character::isLetterOrDigit);
    }

    /** 端口必须给出，且是 1..65535 的十进制数字（客户端不替使用者补默认端口）。 */
    private static boolean isValidPort(String port) {
        if (port == null || port.isBlank()) {
            return false;
        }
        try {
            // 解析与范围都在 UserNumber 里：端口是连接串的一部分，非法值统一归为"无效连接串"
            UserNumber.port("connect port", port);
            return true;
        } catch (UserNumber.InvalidNumberException invalidPort) {
            // 端口是连接串的一部分：非法值统一归为"无效连接串"，由 buildUrl 报那一条固定文案
            return false;
        }
    }

    /** IR 的 {@code parameters} → 查询串（{@code k=v&k2=v2}，按脚本里给的顺序）。 */
    private static String queryParameters(Map<String, Object> ir) {
        Object raw = ir.get("parameters");
        if (!(raw instanceof List<?> parameters)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Object element : parameters) {
            if (!(element instanceof Map<?, ?> parameter)) {
                continue;
            }
            Object name = parameter.get("parameterName");
            Object value = parameter.get("parameterValue");
            if (name == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(name).append('=').append(value == null ? "" : value);
        }
        return builder.toString();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 关闭失败没有补救手段，也不该让脚本失败：连接对象随后被丢弃
        }
    }
}
