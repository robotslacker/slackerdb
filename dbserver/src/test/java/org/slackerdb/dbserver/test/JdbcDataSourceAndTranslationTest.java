package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.jdbc.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：两处**重命名残留**修复后的行为（见 {@code doc/README.md} §11.1）。
 *
 * <ol>
 *   <li>{@code BaseDataSource#getUrl()} 曾经仍拼 {@code jdbc:postgresql://}，
 *       导致 {@code PGSimpleDataSource.getConnection()} 拼出的 URL 被本驱动直接忽略
 *       （{@code Driver.connect} 只认 {@code jdbc:slackerdb:}，其余返回 null）。</li>
 *   <li>{@code GT} 的 ResourceBundle 基名曾是 {@code org.postgresql.translation.messages}，
 *       包已改名后必然 {@code MissingResourceException}，17 个翻译类全部失效（永远英文）。</li>
 * </ol>
 */
public class JdbcDataSourceAndTranslationTest {
    static int dbPort;
    static DBInstance dbInstance;
    static final String DB = "jdbcdsurl";

    @BeforeAll
    static void initAll() throws ServerException {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setData(DB);
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("OFF");
        dbPort = serverConfiguration.getPort();

        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
        assert dbInstance.instanceState.equalsIgnoreCase("IDLE");
    }

    /** 数据源拼出的 URL 必须是本驱动的协议，并且真的能连上。 */
    @Test
    void dataSourceUrlUsesSlackerdbProtocol() throws SQLException {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setServerName("127.0.0.1");
        ds.setPortNumber(dbPort);
        ds.setDatabaseName(DB);

        String url = ds.getUrl();
        assertNotNull(url);
        assertTrue(url.startsWith("jdbc:slackerdb://"),
                "DataSource 必须拼本驱动的协议前缀，实际: " + url);
        assertTrue(url.contains("/" + DB), "URL 里应带数据库名，实际: " + url);

        // 端到端：用数据源自己的 URL 建连（getConnection 内部走 getUrl）
        try (Connection conn = ds.getConnection("", "");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 42")) {
            assertTrue(rs.next());
            assertEquals(42, rs.getInt(1));
        }
    }

    /**
     * 翻译包必须能被解析（基名与 GT 里写的一致）。
     *
     * <p>注意：这里查的是 {@code org.slackerdb.jdbc.translation.messages} 这个**基名**，
     * 与 {@code GT.java} 中的字符串是同一份约定；改一处必须改另一处。</p>
     */
    @Test
    void translationBundleResolves() {
        ResourceBundle zh = ResourceBundle.getBundle(
                "org.slackerdb.jdbc.translation.messages", Locale.SIMPLIFIED_CHINESE);
        assertEquals("不良的类型值 {0} : {1}", zh.getString("Bad value for type {0} : {1}"),
                "zh_CN 翻译应能取到；取不到说明基名/翻译类被改坏了");

        // 英文**没有** bundle（源码里的英文就是原文），所以 getBundle 对 en 必然抛 ——
        // GT 正是靠 catch MissingResourceException 来回退原文。这条断言把这个设计钉住：
        // 谁要是给 GT 加个 "找不到就返回 null" 之类的改动，这里会提醒他英文路径本来就找不到。
        //
        // 必须禁止默认 Locale 回退：默认 Locale 是 zh_CN 时，普通 getBundle(en) 会
        // 回退到 messages_zh_CN，不抛异常，导致测试结果随机器 Locale 变化。
        assertThrows(MissingResourceException.class, () ->
                ResourceBundle.getBundle(
                        "org.slackerdb.jdbc.translation.messages",
                        Locale.ENGLISH,
                        ResourceBundle.Control.getNoFallbackControl(
                                ResourceBundle.Control.FORMAT_DEFAULT)));
    }
}
