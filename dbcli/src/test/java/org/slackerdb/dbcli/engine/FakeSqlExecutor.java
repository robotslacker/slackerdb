package org.slackerdb.dbcli.engine;

import org.slackerdb.dbcli.assertion.ResultModel;
import org.slackerdb.dbcli.common.CommandOutcome;
import org.slackerdb.dbcli.sql.SqlExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 假的 {@link SqlExecutor}：让引擎的行为性契约（语句累积、断言、{@code _WHENEVER}、spool、变量……）
 * 能在**没有数据库**的情况下单测 —— 这也是把执行层抽成接口的全部理由。
 *
 * <p>它只做三件事：记下执行过的语句、按登记好的脚本结果返回 {@link ResultModel}、
 * 记住连接状态。真实 JDBC 行为由 {@code JdbcSqlExecutor} 的单测与 gated 集成测试覆盖。</p>
 *
 * <p>记录语句这件事同时是"**这条文本有没有被送进执行层**"的观察点：引擎在解析失败时把原始文本
 * 原样交给执行层（而不是打印解析器文案），断言就落在 {@link #statements} 上。</p>
 */
final class FakeSqlExecutor implements SqlExecutor {

    /** 按执行顺序记下的语句原文。 */
    final List<String> statements = new ArrayList<>();

    /** {@code _CONNECT} 收到的 IR。 */
    final List<Map<String, Object>> connects = new ArrayList<>();

    /** 语句 → 结果（未登记的语句返回 {@link #defaultResult}）。 */
    private final Map<String, ResultModel> results = new LinkedHashMap<>();

    /** 语句 → 错误原文（配套的模型是 {@code errorCode=1}）。 */
    private final Map<String, String> errors = new LinkedHashMap<>();

    private ResultModel defaultResult = new ResultModel(0, 0, "0 row affected.",
            List.of(), List.of(), List.of(), Map.of());

    private boolean connected;
    private String lastError;
    private boolean autoCommit = true;

    /** {@code _SET} 套到执行层的限额（-1 表示"没被设置过"，用于区分 0 与未设置）。 */
    private int queryTimeoutSeconds = -1;

    /**
     * 是否按 {@link org.slackerdb.dbcli.sql.JdbcSqlExecutor} 的口径模拟"未连接即失败"
     * （{@code errorCode=1} + {@code lastError="Not connected."}）。
     *
     * <p>默认关闭：引擎的多数行为性契约（语句累积、断言、spool……）不需要先 {@code _connect}，
     * 让它们保持"执行层总会成功"最省事；需要验证"未连接时看到什么"的用例显式打开它。</p>
     */
    private boolean connectionRequired;

    /** 构造一个"查询返回 rows"的模型（status 由调用方给，便于逐字断言）。 */
    static ResultModel query(List<String> headers, List<String> columnTypes, List<List<Object>> rows,
                             String status) {
        return new ResultModel(0, rows.size(), status, rows, headers, columnTypes, Map.of());
    }

    void setDefaultResult(ResultModel result) {
        this.defaultResult = result;
    }

    void setResult(String statement, ResultModel result) {
        results.put(statement, result);
    }

    void setError(String statement, String message) {
        errors.put(statement, message);
    }

    boolean isAutoCommit() {
        return autoCommit;
    }

    /** 打开"未连接即失败"的模拟（见 {@link #connectionRequired}）。 */
    void requireConnection() {
        this.connectionRequired = true;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void connect(Map<String, Object> ir) {
        connects.add(ir);
        connected = true;
        lastError = null;
    }

    @Override
    public void disconnect() {
        connected = false;
    }

    @Override
    public List<CommandOutcome> session(String action, String sessionName) {
        return List.of(new CommandOutcome.Status(
                "Session " + (action == null ? "" : action.toLowerCase()) + " [" + sessionName + "]."));
    }

    /** 语句 → 多个结果（多结果集语句：CALL/DO）。登记了就覆盖单结果行为。 */
    private final Map<String, List<ResultModel>> multiResults = new LinkedHashMap<>();

    void setResults(String statement, List<ResultModel> models) {
        multiResults.put(statement, List.copyOf(models));
    }

    @Override
    public List<ResultModel> executeAll(String statement) {
        List<ResultModel> models = multiResults.get(statement);
        if (models != null) {
            statements.add(statement);
            lastError = null;
            return models;
        }
        return List.of(execute(statement));
    }

    @Override
    public ResultModel execute(String statement) {
        statements.add(statement);
        if (connectionRequired && !connected) {
            // 与 JdbcSqlExecutor.execute 的"未连接"分支逐字一致
            lastError = "Not connected.";
            return new ResultModel(1, 0, null, List.of(), List.of(), List.of(), Map.of());
        }
        if (errors.containsKey(statement)) {
            lastError = errors.get(statement);
            return new ResultModel(1, 0, null, List.of(), List.of(), List.of(), Map.of());
        }
        lastError = null;
        ResultModel result = results.get(statement);
        return result == null ? defaultResult : result;
    }

    @Override
    public String lastError() {
        return lastError;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    @Override
    public void setQueryTimeout(int seconds) {
        this.queryTimeoutSeconds = seconds;
    }

    /** 执行层看到的语句超时秒数（-1 = 从未设置）。 */
    int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }
}
