package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.server.VaadinSession;
import org.slackerdb.dbdesk.access.ConnectionProbe;
import org.slackerdb.dbdesk.access.ManagementScope;
import org.slackerdb.dbdesk.access.http.Capabilities;
import org.slackerdb.dbdesk.access.http.ManagementClient;
import org.slackerdb.dbdesk.access.http.ProxyAdminClient;
import org.slackerdb.dbdesk.access.profile.DbDeskProfile;
import org.slackerdb.dbdesk.access.profile.DbDeskProfileStore;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 当前会话的服务器连接状态。
 *
 * <p>启动时固定为 {@link State#DISCONNECTED}；只有用户在顶栏点 Connect 并通过
 * {@link ConnectionProbe} 才会进入连接态。连接后不轮询健康、不自动降级：
 * 管理口/数据库后续失联时，页面操作按原样报错，状态仍保持，直到用户主动断开。</p>
 *
 * <p>管理范围是独立的维度：{@link ManagementScope#INSTANCE} 才有实例级管理页面，
 * {@link ManagementScope#CLUSTER} 只有“实例管理”，{@link ManagementScope#NONE} 只有 SQL。</p>
 */
public final class ConnectionManager {

    public enum State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        SQL_ONLY,
        CLUSTER_ONLY
    }

    private final DbDeskProfileStore store = DbDeskProfileStore.defaultStore();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private State state = State.DISCONNECTED;
    private ManagementScope scope = ManagementScope.NONE;
    private DbDeskProfile profile;
    private String jdbcUrl;
    private String managementUrl = "";
    private String clusterUrl = "";
    private Capabilities capabilities;
    private Capabilities proxyCapabilities;
    private String warning;

    private ConnectionManager() {
    }

    /** 取当前 VaadinSession 的连接管理器；没有就创建。 */
    public static ConnectionManager current() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null) {
            throw new IllegalStateException("ConnectionManager 只能在 Vaadin 会话内使用");
        }
        ConnectionManager manager = session.getAttribute(ConnectionManager.class);
        if (manager == null) {
            manager = new ConnectionManager();
            session.setAttribute(ConnectionManager.class, manager);
        }
        return manager;
    }

    public DbDeskProfileStore store() {
        return store;
    }

    public State state() {
        return state;
    }

    public ManagementScope scope() {
        return scope;
    }

    public boolean isConnected() {
        return state == State.CONNECTED || state == State.SQL_ONLY || state == State.CLUSTER_ONLY;
    }

    public boolean isSqlOnly() {
        return state == State.SQL_ONLY;
    }

    public boolean isClusterOnly() {
        return state == State.CLUSTER_ONLY;
    }

    /** 当前连接是否有可用的 SQL 通道。 */
    public boolean hasDatabase() {
        return jdbcUrl != null && !jdbcUrl.isBlank();
    }

    /** 是否有实例级管理口（dbserver，或 dbproxy 的 {@code /<database>}）。 */
    public boolean hasInstanceManagement() {
        return scope == ManagementScope.INSTANCE
                && managementUrl != null && !managementUrl.isBlank()
                && capabilities != null;
    }

    /** 是否有集群级管理口（只有 dbproxy 根）。 */
    public boolean hasClusterManagement() {
        return scope == ManagementScope.CLUSTER
                && clusterUrl != null && !clusterUrl.isBlank()
                && proxyCapabilities != null;
    }

    public DbDeskProfile profile() {
        return profile;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    /** 实例级管理 URL；集群连接时为空。 */
    public String managementUrl() {
        return managementUrl == null ? "" : managementUrl;
    }

    /** 代理集群管理 URL；只有集群连接时非空。 */
    public String clusterUrl() {
        return clusterUrl == null ? "" : clusterUrl;
    }

    public Capabilities capabilities() {
        return capabilities;
    }

    public Capabilities proxyCapabilities() {
        return proxyCapabilities;
    }

    public String warning() {
        return warning;
    }

    /** 初始连接：失败时状态回 DISCONNECTED，并把异常抛给对话框。 */
    public void connect(DbDeskProfile candidate) throws ConnectionProbe.ProbeException {
        warning = null;
        state = State.CONNECTING;
        notifyListeners();
        ConnectionProbe.Result result;
        try {
            result = ConnectionProbe.probe(candidate);
        } catch (ConnectionProbe.ProbeException e) {
            state = State.DISCONNECTED;
            notifyListeners();
            throw e;
        }
        apply(result, true);
    }

    /** 切换连接：先探测新目标，成功后才替换；失败保留旧连接。 */
    public void switchTo(DbDeskProfile candidate) throws ConnectionProbe.ProbeException {
        ConnectionProbe.Result result = ConnectionProbe.probe(candidate);
        apply(result, true);
    }

    private void apply(ConnectionProbe.Result result, boolean saveHistory) {
        this.profile = result.profile();
        this.jdbcUrl = result.jdbcUrl();
        this.managementUrl = result.managementUrl();
        this.clusterUrl = result.clusterUrl();
        this.capabilities = result.capabilities();
        this.proxyCapabilities = result.proxyCapabilities();
        this.scope = result.scope();
        this.warning = result.warning();
        this.state = switch (scope) {
            case CLUSTER -> State.CLUSTER_ONLY;
            case INSTANCE -> State.CONNECTED;
            case NONE -> result.hasDatabase() ? State.SQL_ONLY : State.DISCONNECTED;
        };

        if (saveHistory) {
            try {
                store.recordSuccessful(result.profile());
            } catch (IOException e) {
                String historyError = "连接成功，但历史保存失败：" + e.getMessage();
                warning = warning == null ? historyError : warning + "；" + historyError;
            }
        }
        notifyListeners();
    }

    public void disconnect() {
        state = State.DISCONNECTED;
        scope = ManagementScope.NONE;
        profile = null;
        jdbcUrl = null;
        managementUrl = "";
        clusterUrl = "";
        capabilities = null;
        proxyCapabilities = null;
        warning = null;
        notifyListeners();
    }

    /** 每次新建一个客户端；页面自己负责关闭。 */
    public ManagementClient managementClient() {
        if (!hasInstanceManagement()) {
            throw new IllegalStateException("当前连接没有实例级管理口");
        }
        return new ManagementClient(managementUrl, Duration.ofSeconds(5));
    }

    /** 每次新建一个代理管理客户端；页面自己负责关闭。 */
    public ProxyAdminClient clusterClient() {
        if (!hasClusterManagement()) {
            throw new IllegalStateException("当前连接没有 dbproxy 集群管理口");
        }
        return new ProxyAdminClient(clusterUrl, Duration.ofSeconds(30));
    }

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // 单个页面刷新失败不影响连接状态
            }
        }
    }
}
