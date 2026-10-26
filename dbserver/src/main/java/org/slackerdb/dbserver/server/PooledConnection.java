package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Logger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 连接池对外发放的连接句柄。
 *
 * <p><b>为什么需要它</b>：池在内部用<b>物理连接对象本身</b>作为
 * {@code usedConnectionPool} / {@code connectionMetaDataMap} 的键。如果直接把物理连接交给调用方，
 * 那么任何一处按 JDBC 习惯写的 {@code try (Connection c = pool.getConnection()) { ... }}
 * （或 {@code closeQuietly(c)}）调用的是 {@code c.close()} —— 那只是把 <b>物理连接</b>关掉，
 * 池并不知道这个槽位已经空出来了：连接仍留在 {@code connectionMetaDataMap} 与
 * {@code usedConnectionPool} 里，于是
 * <ol>
 *   <li>{@code connectionMetaDataMap.size()} 只增不减，最终顶到 {@code maximumPoolSize}；</li>
 *   <li>之后 {@code getConnection()} 走"池已满"分支，等一个永远不会到来的
 *       {@code connectionAvailable} 信号，超时后抛 "Timeout while waiting for connection"。</li>
 * </ol>
 * 实测：MCP 的 {@code list_tables} 每调用一次泄漏一个槽位，默认上限 256 次调用之后，
 * <b>整台服务器（pgwire、数据服务、MCP 共用同一个池）再也无法建立任何新连接</b>，且无法自愈。
 *
 * <p><b>本类做什么</b>：只拦截"结束连接"语义的方法，把它们翻译成 {@code pool.releaseConnection(物理连接)}，
 * 其余方法原样委托。这样"关闭即归还"对任何调用方都自动成立，不需要调用方知道池的存在。
 * 另外本类会跟踪自己发放出去的 {@link Statement}，归还连接时一并关闭
 * （JDBC 规定关闭连接要连带关闭它产生的语句，见 {@link #track}）。</p>
 *
 * <p><b>什么时候不归还而退役</b>：归还时若仍有语句<b>正在执行</b>（别的线程还卡在语句方法里），
 * 这条连接会被<b>物理关闭</b>而不是放回空闲池；若只是"语句没关"这类残留，则由池关掉残留语句、
 * 用 {@code SELECT 1} 探活，通过才复用、失败才退役——见 {@link #recycle}。
 * "关闭即归还"只在调用方确实放开了这条连接时才成立；还有线程在用它就说明前提不成立。</p>
 *
 * <p><b>需要向下转型时</b>：句柄不是 {@code DuckDBConnection}，用
 * {@link #unwrap(Connection)} / {@link #unwrapOrSame(Connection)} 取回物理连接，
 * 或直接 {@code conn.unwrap(DuckDBConnection.class)}（本类的 {@code unwrap} 会转交给物理连接）。
 *
 * <p><b>幂等性</b>：JDBC 规定 {@code close()} 可重复调用。这里用 {@link #released} 保证只归还一次，
 * 并且归还之后再调用其它方法会抛 {@link SQLException}（与已关闭连接的语义一致），
 * 避免调用方在连接已经回到池里、可能已被别的会话借走之后继续使用它。
 *
 * <p><b>关闭超时</b>：池是"服务端持有"模型，不需要 JDBC 的 {@code abort()} 强制断开能力——
 * {@code abort()} 同样按归还处理，池随后自行校验连接的可用性。
 */
public final class PooledConnection implements Connection {

    /** 物理连接；归还后置空，用于识别"已归还"。 */
    private Connection physical;
    private final DBDataSourcePool pool;
    private final Logger logger;
    private final AtomicBoolean released = new AtomicBoolean(false);

    /**
     * 本句柄发放出去、尚未关闭的语句（含 PreparedStatement / CallableStatement）。
     *
     * <p>JDBC 规定 {@code Connection.close()} 必须连带关闭该连接产生的语句。池化之后
     * 物理连接不会真的关闭，这个义务就落到本类头上：少了这一步，调用方"只关连接、不关语句"
     * 时语句会残留在物理连接上，而这条连接随后会被别的会话借走——DuckDB 的单条连接不是
     * 线程安全的，两个会话于是在同一条连接、同一批语句上互相踩。</p>
     */
    private final List<StatementHandler> trackedStatements = new CopyOnWriteArrayList<>();

    /**
     * 正在执行（已进入语句方法、尚未返回）的调用数。
     *
     * <p>与 {@link #trackedStatements} 分开统计是必要的：语句一旦 {@code close()} 就会从
     * 登记表里摘掉，但**另一个线程可能正卡在它的某个方法里**——只看登记表会漏掉这种
     * "已经不在表里、却仍在执行"的语句，而它恰恰是最危险的一种（连接被复用后，
     * 两个会话会在同一条连接上互相踩）。</p>
     */
    private final AtomicInteger inFlightStatements = new AtomicInteger();

    PooledConnection(Connection physical, DBDataSourcePool pool, Logger logger) {
        this.physical = physical;
        this.pool = pool;
        this.logger = logger;
    }

    /**
     * 取出物理连接，供池内部与"仍持有物理连接"的兼容路径使用。
     *
     * @throws SQLException 该句柄已归还给池
     */
    public static Connection unwrap(Connection connection) throws SQLException {
        if (connection instanceof PooledConnection pooled) {
            Connection physical = pooled.physical;
            if (physical == null) {
                throw new SQLException("Connection has already been returned to the pool.");
            }
            return physical;
        }
        return connection;
    }

    /**
     * 与 {@link #unwrap} 相同，但不抛异常：非池句柄或已归还时返回原值。
     *
     * <p>用于"先判定再转型"的场景（例如把会话连接转成 {@code DuckDBConnection}），
     * 调用方随后自行做 {@code instanceof} 判定。</p>
     */
    public static Connection unwrapOrSame(Connection connection) {
        if (connection instanceof PooledConnection pooled && pooled.physical != null) {
            return pooled.physical;
        }
        return connection;
    }

    /**
     * 尝试归还本句柄。
     *
     * <p>由 {@link DBDataSourcePool#releaseConnection} 优先调用：这样
     * {@code conn.close()}、{@code conn.abort()} 与 {@code pool.releaseConnection(句柄)}
     * 三条路径共享同一个"只归还一次"的原子标志，调用方无需关心自己拿的是句柄还是物理连接。
     * 归还动作复用 {@link #recycle}，因此不会再绕回池的释放入口造成递归。</p>
     *
     * @return 本次调用真的执行了归还；false 表示此前已归还（重复调用，安全忽略）
     */
    boolean release() {
        if (this.physical == null) {
            return false;
        }
        boolean wasFirst = !this.released.get();
        recycle("release()");
        return wasFirst;
    }

    private Connection physicalConnection() {
        return this.physical;
    }

    /**
     * 归还物理连接。幂等；失败只记日志，不向调用方抛出——
     * JDBC 的 {@code close()} 不期望因为池内部问题而失败。
     *
     * <p><b>什么情况下复用、什么情况下退役</b>（2026-10-22 起）：</p>
     * <ul>
     *   <li>语句都关干净了 → 正常归还（复位事务状态后放回空闲池）；</li>
     *   <li>只剩"语句没关"这类残留、且没有语句正在执行 → 由池关掉残留语句，再用
     *       {@code SELECT 1} 探活：通过就复用，失败才退役（见
     *       {@link DBDataSourcePool#recoverConnectionAfterResidue}）。改造前这里一律退役，
     *       代价是每个失败批处理都要新建/销毁一条 DuckDB 连接（实测 10 次失败批处理 → 9 次退役）；</li>
     *   <li>仍有语句正在执行（{@link #inFlightStatements} &gt; 0）→ <b>必须退役</b>：
     *       连接上还有别的线程在用，DuckDB 单连接非线程安全。</li>
     * </ul>
     */
    private void recycle(String how) {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        Connection target = this.physical;
        this.physical = null;
        if (target == null) {
            return;
        }

        // 判定必须在关闭语句<b>之前</b>做：语句代理的 close() 会把自己从登记表里摘走。
        // "正在执行"用连接级计数器而不是遍历登记表：已经 close() 过的语句虽然不在表里，
        // 仍可能有另一个线程正卡在它的某个方法里。
        boolean inFlight = inFlightStatements.get() > 0;
        List<StatementHandler> outstanding = new ArrayList<>(trackedStatements);
        trackedStatements.clear();

        // JDBC 规定关闭连接要连带关闭它产生的语句。物理连接一旦复用，这些语句就会残留到
        // 下一个借用者手里——所以无论退役还是复用，都要在这里关掉。
        for (StatementHandler handler : outstanding) {
            handler.closeDelegate();
        }

        // 关闭本身可能耗时（底层语句可能正忙）。期间若又有线程在这条连接上建语句，
        // track() 会看到 released 已置位并立刻拒掉它，所以这里不会漏掉"新冒出来的语句"；
        // 仍然把两侧相加，是为了让日志里的计数如实反映"这次归还欠了几条语句"。
        int residue = outstanding.size() + trackedStatements.size();
        if (residue > 0 || inFlight) {
            // 调用方归还连接时没有把语句收拾干净。
            //
            // 改造前这里**一律退役**（物理关闭）。问题在于"有语句没关"其实非常常见
            // （批处理失败、客户端不辞而别、Parse 出来但从未执行），每次退役都要新建一条
            // DuckDB 连接；长跑实例上这会持续消耗资源，最终把池拖进"连接建了又销毁"的抖动里。
            //
            // 这里改成：**先尝试修复再复用** —— 语句刚刚已经全部关掉，只要这条连接还能
            // 正常执行一条探活语句，就说明它没有残留的未消费结果，可以安全复用；
            // 探活失败才退役。安全性没有降低：不可用的连接依然进不了空闲池。
            trackedStatements.clear();
            this.logger.debug("[SERVER][CONN POOL  ]: Pool [{}] {} returned the connection with {} "
                            + "statement(s) not closed{}; trying to recover reuse.",
                    this.pool.poolName(), how, residue,
                    inFlight ? " and at least one still executing" : "");

            // 还有语句正在别的线程上执行时不允许复用（DuckDB 单连接非线程安全）。
            if (!inFlight && this.pool.recoverConnectionAfterResidue(target)) {
                return;
            }
            try {
                this.pool.retireConnectionFromHandle(target,
                        inFlight ? "closed while statements were still executing"
                                : "recovery after statement residue failed");
            } catch (RuntimeException runtimeException) {
                this.logger.warn("[SERVER][CONN POOL  ]: Pool [{}] {} failed to retire connection: {}",
                        this.pool.poolName(), how, runtimeException.getMessage());
            }
            return;
        }

        try {
            this.pool.releaseConnection(target);
        } catch (RuntimeException runtimeException) {
            this.logger.warn("[SERVER][CONN POOL  ]: Pool [{}] {} failed to return connection: {}",
                    this.pool.poolName(), how, runtimeException.getMessage());
        }
    }

    /**
     * 把物理连接产生的语句包一层"可跟踪"的代理后交给调用方。
     *
     * <p>代理只做三件事：登记自己、在 {@code close()} 时注销、其余方法原样委托。
     * 因此它对调用方是透明的（{@code unwrap} 也跟着委托下去），但本类因此知道
     * 归还连接时还欠哪些语句没关。用 {@link Proxy} 而不是手写包装类，是为了不必把
     * {@code Statement/PreparedStatement/CallableStatement} 的几十个方法再抄一遍
     * ——抄一遍就会有漏项，而漏项恰好会表现成"某些语句关不掉"。</p>
     *
     * <p><b>为什么登记后还要检查 {@link #released}</b>：{@code createStatement()} 先过
     * {@link #requireOpen()} 再建语句，中间有一段窗口；另一线程恰好在这段窗口里
     * {@code close()} 了连接时，这条语句会"归还之后才登记"，于是既不在
     * {@code recycle} 的待关闭快照里，也不会再被谁关掉。改造前一概退役（物理关闭）能兜住它，
     * 现在连接要复用，留着它就等于把一条活语句交给下一个借用者（DuckDB 单连接非线程安全）。
     * 因此这里立刻关掉并让调用方拿到明确的错误。</p>
     */
    private <T extends Statement> T track(Statement statement, Class<T> statementInterface) throws SQLException {
        if (statement == null) {
            return null;
        }
        StatementHandler handler = new StatementHandler(statement);
        T proxy = statementInterface.cast(
                Proxy.newProxyInstance(
                        PooledConnection.class.getClassLoader(),
                        new Class<?>[]{statementInterface},
                        handler));
        trackedStatements.add(handler);
        if (released.get()) {
            trackedStatements.remove(handler);
            handler.closeDelegate();
            throw new SQLException("Connection has already been returned to the pool.");
        }
        return proxy;
    }

    private void requireOpen() throws SQLException {
        if (this.physical == null) {
            throw new SQLException("Connection has already been returned to the pool.");
        }
    }

    /**
     * {@link Statement} 的委托处理器：登记/注销 + 原样委托。
     *
     * <p>注意 {@code equals/hashCode/toString} 必须在处理器里自己实现：
     * 动态代理会把它们也转到 {@code invoke}，转发给底层语句会得到"代理不等于自己"
     * 这种反直觉的结果（例如放进 {@code Set} 里去重会失效）。</p>
     */
    private final class StatementHandler implements InvocationHandler {

        private final Statement delegate;
        private final AtomicBoolean statementClosed = new AtomicBoolean(false);

        StatementHandler(Statement delegate) {
            this.delegate = delegate;
        }

        void closeDelegate() {
            if (statementClosed.compareAndSet(false, true)) {
                try {
                    delegate.close();
                } catch (SQLException ignored) {
                    // close() 不期望失败；底层语句已经不可用就算了
                }
            }
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "close":
                    closeDelegate();
                    trackedStatements.remove(this);
                    return null;
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "PooledConnection.Statement[" + delegate + "]";
                default:
                    break;
            }
            // 计数跨越整个委托调用：归还连接时若这个计数不为 0，说明有语句正在执行
            // （可能还是另一个线程上的），这条连接就不能复用。
            inFlightStatements.incrementAndGet();
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException invocationTargetException) {
                // 反射包装必须剥掉，否则调用方拿到的是 InvocationTargetException 而不是 SQLException
                throw invocationTargetException.getCause();
            } finally {
                inFlightStatements.decrementAndGet();
            }
        }
    }

    // ------------------------------------------------------------------
    // 连接结束语义：翻译成"归还给池"
    // ------------------------------------------------------------------

    @Override
    public void close() {
        recycle("close()");
    }

    @Override
    public void abort(Executor executor) {
        recycle("abort()");
    }

    @Override
    public boolean isClosed() {
        return this.physical == null;
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        Connection target = this.physical;
        return target != null && target.isValid(timeout);
    }

    // ------------------------------------------------------------------
    // 其余方法原样委托
    // ------------------------------------------------------------------

    @Override
    public Statement createStatement() throws SQLException {
        requireOpen();
        return track(physical.createStatement(), Statement.class);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        requireOpen();
        return track(physical.createStatement(resultSetType, resultSetConcurrency), Statement.class);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        requireOpen();
        return track(physical.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability),
                Statement.class);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        requireOpen();
        return track(physical.prepareStatement(sql), PreparedStatement.class);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
            throws SQLException {
        requireOpen();
        return track(physical.prepareStatement(sql, resultSetType, resultSetConcurrency),
                PreparedStatement.class);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
                                              int resultSetHoldability) throws SQLException {
        requireOpen();
        return track(physical.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability),
                PreparedStatement.class);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        requireOpen();
        return track(physical.prepareStatement(sql, autoGeneratedKeys), PreparedStatement.class);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        requireOpen();
        return track(physical.prepareStatement(sql, columnIndexes), PreparedStatement.class);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        requireOpen();
        return track(physical.prepareStatement(sql, columnNames), PreparedStatement.class);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        requireOpen();
        return track(physical.prepareCall(sql), CallableStatement.class);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
            throws SQLException {
        requireOpen();
        return track(physical.prepareCall(sql, resultSetType, resultSetConcurrency), CallableStatement.class);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
                                         int resultSetHoldability) throws SQLException {
        requireOpen();
        return track(physical.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability),
                CallableStatement.class);
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        requireOpen();
        return physical.nativeSQL(sql);
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        requireOpen();
        physical.setAutoCommit(autoCommit);
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        requireOpen();
        return physical.getAutoCommit();
    }

    @Override
    public void commit() throws SQLException {
        requireOpen();
        physical.commit();
    }

    @Override
    public void rollback() throws SQLException {
        requireOpen();
        physical.rollback();
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        requireOpen();
        physical.rollback(savepoint);
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        requireOpen();
        return physical.getMetaData();
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        requireOpen();
        physical.setReadOnly(readOnly);
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        requireOpen();
        return physical.isReadOnly();
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        requireOpen();
        physical.setCatalog(catalog);
    }

    @Override
    public String getCatalog() throws SQLException {
        requireOpen();
        return physical.getCatalog();
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        requireOpen();
        physical.setTransactionIsolation(level);
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        requireOpen();
        return physical.getTransactionIsolation();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        requireOpen();
        return physical.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        requireOpen();
        physical.clearWarnings();
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        requireOpen();
        return physical.getTypeMap();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        requireOpen();
        physical.setTypeMap(map);
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        requireOpen();
        physical.setHoldability(holdability);
    }

    @Override
    public int getHoldability() throws SQLException {
        requireOpen();
        return physical.getHoldability();
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        requireOpen();
        return physical.setSavepoint();
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        requireOpen();
        return physical.setSavepoint(name);
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        requireOpen();
        physical.releaseSavepoint(savepoint);
    }

    @Override
    public Clob createClob() throws SQLException {
        requireOpen();
        return physical.createClob();
    }

    @Override
    public Blob createBlob() throws SQLException {
        requireOpen();
        return physical.createBlob();
    }

    @Override
    public NClob createNClob() throws SQLException {
        requireOpen();
        return physical.createNClob();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        requireOpen();
        return physical.createSQLXML();
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        Connection target = this.physical;
        if (target == null) {
            throw new SQLClientInfoException("Connection has already been returned to the pool.", null);
        }
        target.setClientInfo(name, value);
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        Connection target = this.physical;
        if (target == null) {
            throw new SQLClientInfoException("Connection has already been returned to the pool.", null);
        }
        target.setClientInfo(properties);
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        requireOpen();
        return physical.getClientInfo(name);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        requireOpen();
        return physical.getClientInfo();
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        requireOpen();
        return physical.createArrayOf(typeName, elements);
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        requireOpen();
        return physical.createStruct(typeName, attributes);
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        requireOpen();
        physical.setSchema(schema);
    }

    @Override
    public String getSchema() throws SQLException {
        requireOpen();
        return physical.getSchema();
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        requireOpen();
        physical.setNetworkTimeout(executor, milliseconds);
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        requireOpen();
        return physical.getNetworkTimeout();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        requireOpen();
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        // 允许拿到 DuckDB 的原生连接类型（驱动/工具做向下转型时会用到）
        return physical.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        Connection target = this.physical;
        if (target == null) {
            return iface.isInstance(this);
        }
        return iface.isInstance(this) || target.isWrapperFor(iface);
    }
}
