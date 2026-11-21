package org.slackerdb.dbcli.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 同一 WORK 内的 GLOBAL 变量存储。
 *
 * <p>SQLite 文件放在 {@code WORK/.dbcli/global-vars.db}，因此同一个 WORK 下的 root、线程作业、
 * 进程作业、孙作业看到的是同一份数据；跨 WORK 不共享（按约定不考虑）。</p>
 *
 * <p>所有访问都在 {@link #lock} 下串行化；SQLite 连接不是线程安全的。写入是单条 upsert 并立即提交，
 * 另一个进程下一次读取就能看到。WAL + busy_timeout 用来承受父子进程同时读写。</p>
 */
public final class GlobalVariableStore implements AutoCloseable {

    /** 库文件名（放在 WORK/.dbcli 下）。 */
    public static final String DATABASE_NAME = "global-vars.db";

    private static final int BUSY_TIMEOUT_MILLIS = 5_000;

    private final Path database;
    private final Object lock = new Object();
    private Connection connection;
    private boolean closed;
    /** 旧构造器/测试的进程内 GLOBAL（不落文件）。 */
    private final Map<String, String> memory;

    public GlobalVariableStore(Path database) {
        this.database = database == null ? null : database.toAbsolutePath().normalize();
        this.memory = database == null ? new LinkedHashMap<>() : null;
    }

    /** 进程内 GLOBAL（旧构造器/测试用；不创建文件）。 */
    public static GlobalVariableStore inMemory() {
        return new GlobalVariableStore(null);
    }

    public Path database() {
        return database;
    }

    /** 读取一个全局变量；未设置返回 {@code null}（空字符串是合法值）。 */
    public String get(String name) {
        requireName(name);
        if (memory != null) {
            synchronized (lock) {
                if (closed) {
                    throw new IllegalStateException("Global variable store is closed.");
                }
                return memory.get(name);
            }
        }
        synchronized (lock) {
            ensureOpen();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT value FROM global_vars WHERE name = ?")) {
                statement.setString(1, name);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            } catch (SQLException failed) {
                throw new IllegalStateException("Cannot read global variable [" + name + "]: "
                        + failed.getMessage(), failed);
            }
        }
    }

    /** 写入/覆盖一个全局变量。 */
    public void set(String name, String value) {
        requireName(name);
        if (value == null) {
            throw new IllegalArgumentException("Global variable value must not be null.");
        }
        if (memory != null) {
            synchronized (lock) {
                if (closed) {
                    throw new IllegalStateException("Global variable store is closed.");
                }
                memory.put(name, value);
            }
            return;
        }
        synchronized (lock) {
            ensureOpen();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO global_vars(name, value, updated_at) VALUES(?, ?, ?) "
                            + "ON CONFLICT(name) DO UPDATE SET value = excluded.value, "
                            + "updated_at = excluded.updated_at")) {
                statement.setString(1, name);
                statement.setString(2, value);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            } catch (SQLException failed) {
                throw new IllegalStateException("Cannot write global variable [" + name + "]: "
                        + failed.getMessage(), failed);
            }
        }
    }

    /** 当前全部全局变量（按名字排序）。 */
    public Map<String, String> snapshot() {
        if (memory != null) {
            synchronized (lock) {
                if (closed) {
                    throw new IllegalStateException("Global variable store is closed.");
                }
                return new LinkedHashMap<>(memory);
            }
        }
        synchronized (lock) {
            ensureOpen();
            Map<String, String> values = new LinkedHashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT name, value FROM global_vars ORDER BY name")) {
                while (rows.next()) {
                    values.put(rows.getString(1), rows.getString(2));
                }
                return values;
            } catch (SQLException failed) {
                throw new IllegalStateException("Cannot read global variables: "
                        + failed.getMessage(), failed);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // 退出路径：关不掉也不影响结论
                }
                connection = null;
            }
        }
    }

    private void open() {
        if (database == null) {
            return;
        }
        synchronized (lock) {
            try {
                Path parent = database.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MILLIS);
                    statement.execute("PRAGMA journal_mode = WAL");
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS global_vars (
                                name TEXT PRIMARY KEY,
                                value TEXT NOT NULL,
                                updated_at INTEGER NOT NULL
                            )
                            """);
                }
            } catch (IOException | SQLException failed) {
                closeQuietly();
                throw new IllegalStateException("Cannot open the global variable store [" + database
                        + "]: " + failed.getMessage(), failed);
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Global variable store is closed.");
        }
        if (connection == null) {
            open();
            return;
        }
        try {
            if (connection.isClosed()) {
                open();
            }
        } catch (SQLException failed) {
            open();
        }
    }

    private void closeQuietly() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 打开失败路径：尽量释放
            }
            connection = null;
        }
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Global variable name must not be blank.");
        }
    }
}
