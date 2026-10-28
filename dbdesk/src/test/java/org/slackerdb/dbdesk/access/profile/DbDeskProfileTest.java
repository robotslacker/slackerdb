package org.slackerdb.dbdesk.access.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbDeskProfileTest {

    @Test
    @DisplayName("managementUrl: 管理口就是根地址，不再有路径")
    void managementUrlIsRoot() {
        DbDeskProfile direct = new DbDeskProfile("local", "127.0.0.1", 4409, 8409, "m0");
        assertEquals("http://127.0.0.1:8409", direct.managementUrl());
        assertTrue(direct.hasManagement());

        DbDeskProfile cluster = new DbDeskProfile("proxy", "127.0.0.1", null, 8409, null);
        assertEquals("http://127.0.0.1:8409", cluster.managementUrl());
        assertFalse(cluster.hasDatabase());
        assertNull(cluster.jdbcUrl());
    }

    @Test
    @DisplayName("端口: 两个都空拒绝；只填管理口允许")
    void atLeastOnePort() {
        assertThrows(org.slackerdb.dbdesk.i18n.LocalizedException.class,
                () -> new DbDeskProfile("x", "127.0.0.1", null, null, "m0"));

        DbDeskProfile cluster = new DbDeskProfile("proxy", "127.0.0.1", null, 8409, null);
        assertTrue(cluster.hasManagement());
        assertFalse(cluster.hasDatabasePort());
    }

    @Test
    @DisplayName("数据库名: 填数据库端口时必填，只填管理口时可空")
    void databaseNameRequiredOnlyWithDatabasePort() {
        assertThrows(org.slackerdb.dbdesk.i18n.LocalizedException.class,
                () -> new DbDeskProfile("x", "127.0.0.1", 4409, 8409, " "));
        assertThrows(org.slackerdb.dbdesk.i18n.LocalizedException.class,
                () -> new DbDeskProfile("x", "127.0.0.1", 4409, null, null));

        DbDeskProfile cluster = new DbDeskProfile("proxy", "127.0.0.1", null, 8409, " ");
        assertNull(cluster.database());
    }

    @Test
    @DisplayName("端口范围仍然校验")
    void portRangesAreValidated() {
        assertThrows(org.slackerdb.dbdesk.i18n.LocalizedException.class,
                () -> new DbDeskProfile("x", "127.0.0.1", 0, null, "m0"));
        assertThrows(org.slackerdb.dbdesk.i18n.LocalizedException.class,
                () -> new DbDeskProfile("x", "127.0.0.1", 65536, null, "m0"));
        assertThrows(org.slackerdb.dbdesk.i18n.LocalizedException.class,
                () -> new DbDeskProfile("x", "127.0.0.1", null, 0, null));
    }

    @Test
    @DisplayName("withAlias: 改别名不会丢掉端口和数据库")
    void withAliasKeepsOtherFields() {
        DbDeskProfile renamed = new DbDeskProfile("p", "127.0.0.1", 4409, 8409, "sales")
                .withAlias("报表库");
        assertEquals("报表库", renamed.alias());
        assertEquals(4409, renamed.databasePort());
        assertEquals(8409, renamed.managementPort());
        assertEquals("sales", renamed.database());
    }

    @Test
    @DisplayName("jdbcUrl: 只由主机/端口/库名决定")
    void jdbcUrlIsUnchanged() {
        assertEquals("jdbc:postgresql://127.0.0.1:4409/sales",
                new DbDeskProfile("p", "127.0.0.1", 4409, 8409, "sales").jdbcUrl());
    }
}
