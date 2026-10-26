package org.slackerdb.plsql.spi;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * DuckDB transaction-policy compatibility for PL/SQL exception handlers.
 *
 * <p><b>Why this exists</b>: Oracle-style PL/SQL allows an {@code EXCEPTION} handler to
 * catch a failed SQL statement and keep executing in the same transaction. DuckDB 1.5.x
 * behaved that way for parser/binder errors. DuckDB 2.0 changed the default to
 * {@code ALL_ERRORS_INVALIDATE_TRANSACTION}: after a catalog/binder error the next
 * statement fails with {@code Current transaction is aborted (please ROLLBACK)}, so a
 * block that catches "table does not exist" can no longer insert into its log table.
 * DuckDB has no {@code SAVEPOINT}, so the only recovery mechanism is the configurable
 * invalidation policy.</p>
 *
 * <p><b>Why feature-detect</b>: the setting only exists in DuckDB 2.0+, and it is detected
 * through {@code duckdb_settings()} (a plain catalog query) instead of a version string.
 * On 1.5.x nothing is executed; that engine already had the old behavior. The check also
 * keeps the helper harmless for 2.0 builds where the setting is unavailable. It is not a
 * promise to keep 1.5.6 as a supported target.</p>
 *
 * <p>On 2.0+ the legacy policy is restored for the current transaction (covers a
 * transaction that is already open) and for future transactions (covers
 * {@code COMMIT}/{@code ROLLBACK} inside the block).</p>
 *
 * <p>This is intentionally best-effort: an embedded non-DuckDB backend, an old driver,
 * or a read-only setting must not turn a PL/SQL block into a hard failure.</p>
 */
public final class DuckDbTransactionCompat {

    /** The value that restores DuckDB 1.5.x behavior (only non-syntactic errors invalidate). */
    static final String LEGACY_POLICY = "SYNTACTIC_ERRORS_DO_NOT_INVALIDATE";

    private DuckDbTransactionCompat() {
    }

    /**
     * Apply the legacy transaction-invalidation policy when the connected backend is
     * DuckDB 2.0+; otherwise do nothing.
     *
     * <p>Idempotent and safe to call more than once: it runs at instance startup and once
     * per {@link DefaultJdbcHost} (standalone embedding). Every failure is ignored.</p>
     */
    public static void configure(Connection connection) {
        if (connection == null) {
            return;
        }
        if (!isDuckDb(connection)) {
            return;
        }

        try (Statement statement = connection.createStatement()) {
            // Feature detection, not a version check: 1.5.x has neither setting, while
            // duckdb_settings() itself exists in both versions.
            int supportedSettings;
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT count(*) FROM duckdb_settings() WHERE name IN ("
                            + "'default_transaction_invalidation_policy',"
                            + "'current_transaction_invalidation_policy')")) {
                supportedSettings = resultSet.next() ? resultSet.getInt(1) : 0;
            }
            if (supportedSettings < 2) {
                return;
            }

            // Setting is database-global: applies to every connection of this instance,
            // including sessions created later.
            try {
                statement.execute("SET default_transaction_invalidation_policy='" + LEGACY_POLICY + "'");
            } catch (SQLException ignored) {
                // Older 2.0 build without the setting: keep going, current policy below
                // may still work.
            }
            // Setting on an already-started transaction; without this, the transaction
            // that is currently running would keep the new ALL_ERRORS policy.
            try {
                statement.execute("SET current_transaction_invalidation_policy='" + LEGACY_POLICY + "'");
            } catch (SQLException ignored) {
                // e.g. no active transaction / setting read-only: best effort only.
            }
        } catch (SQLException | RuntimeException ignored) {
            // Backend without duckdb_settings(), closed connection, sandboxed settings:
            // not worth failing a PL/SQL block over.
        }
    }

    private static boolean isDuckDb(Connection connection) {
        try {
            String product = connection.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("duckdb");
        } catch (SQLException | RuntimeException ignored) {
            return false;
        }
    }
}
