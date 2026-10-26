package org.slackerdb.dbcli.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约 L-1：连接时带上 {@code application_name=slackerdb-dbcli/<ver>}，
 * 服务端的会话面板才能认出"这条连接来自 dbcli"。
 */
class JdbcSqlExecutorApplicationNameTest {

    @Test
    @DisplayName("pgwire URL gets ApplicationName appended")
    void appendsApplicationNameForPgwire() {
        String url = JdbcSqlExecutor.withApplicationName("jdbc:postgresql://127.0.0.1:4409/m0");
        assertTrue(url.startsWith("jdbc:postgresql://127.0.0.1:4409/m0?ApplicationName=slackerdb-dbcli/"),
                "ApplicationName should be appended, actual: " + url);
    }

    @Test
    @DisplayName("appends with & when a query string already exists")
    void appendsWithAmpersandWhenQueryPresent() {
        String url = JdbcSqlExecutor.withApplicationName("jdbc:postgresql://h:5432/db?sslmode=disable");
        assertTrue(url.contains("sslmode=disable&ApplicationName="), "should join with &, actual: " + url);
    }

    @Test
    @DisplayName("a user-provided value is never overridden (case insensitive)")
    void keepsUserProvidedApplicationName() {
        String original = "jdbc:postgresql://h:5432/db?applicationname=mine";
        assertEquals(original, JdbcSqlExecutor.withApplicationName(original));
    }

    @Test
    @DisplayName("jdbc:slackerdb:// (dbcli's own default form) also gets ApplicationName")
    void appendsApplicationNameForSlackerdbUrl() {
        String url = JdbcSqlExecutor.withApplicationName("jdbc:slackerdb://127.0.0.1:4409/m0");
        assertTrue(url.startsWith("jdbc:slackerdb://127.0.0.1:4409/m0?ApplicationName=slackerdb-dbcli/"),
                "dbcli uses jdbc:slackerdb:// by default; without this L-1 does nothing for dbcli, actual: " + url);
    }

    @Test
    @DisplayName("a user-provided value is not overridden for the slackerdb form either")
    void keepsUserProvidedApplicationNameForSlackerdbUrl() {
        String original = "jdbc:slackerdb://h:5432/db?ApplicationName=mine";
        assertEquals(original, JdbcSqlExecutor.withApplicationName(original));
        String lowerCaseProvided = "jdbc:slackerdb://h:5432/db?applicationname=mine";
        assertEquals(lowerCaseProvided, JdbcSqlExecutor.withApplicationName(lowerCaseProvided));
    }

    @Test
    @DisplayName("non-pgwire URLs are left untouched (other drivers do not know the parameter)")
    void ignoresNonPgwireUrls() {
        assertEquals("jdbc:duckdb:", JdbcSqlExecutor.withApplicationName("jdbc:duckdb:"));
        assertEquals(null, JdbcSqlExecutor.withApplicationName(null));
    }
}
