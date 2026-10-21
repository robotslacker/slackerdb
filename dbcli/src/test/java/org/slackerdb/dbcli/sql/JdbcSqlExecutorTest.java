package org.slackerdb.dbcli.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbcli.assertion.ResultModel;
import org.slackerdb.dbcli.common.CommandOutcome;
import org.slackerdb.dbcli.script.SqlAnalyzer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDBC 执行层的 hermetic 测试：**不连数据库**，只锁"IR → URL"的拼法、会话动作的文案、
 * 以及"没连上时的契约"（{@code execute} 给 {@code errorCode=1} 而不是抛异常）。
 *
 * <p>真实的往返行为由 {@code JdbcChannelIntegrationTest} 与
 * {@code ScriptEngineIntegrationTest}（gated）覆盖。</p>
 */
class JdbcSqlExecutorTest {

    @Test
    @DisplayName("IR → URL：host/port/service 拼成 jdbc:slackerdb://host:port/service")
    void urlFromHostPortService() {
        assertEquals("jdbc:slackerdb://127.0.0.1:43175/db1",
                JdbcSqlExecutor.buildUrl(Map.of("driver", "jdbc",
                        "host", "127.0.0.1", "port", "43175", "service", "db1")));
    }

    @Test
    @DisplayName("IR → URL：缺 port / 缺 service / 带用户名口令 都算无效连接串（没有默认值）")
    void urlWithoutCompleteTargetIsRejected() {
        // 没有默认端口、没有默认服务名：三段缺一不可
        for (Map<String, Object> incomplete : List.of(
                Map.<String, Object>of("host", "dbhost", "service", "db1"),
                Map.<String, Object>of("host", "dbhost", "port", "5432"),
                Map.<String, Object>of("host", "dbhost"),
                Map.<String, Object>of("host", "dbhost", "port", "5432", "service", "db1",
                        "username", "main"),
                Map.<String, Object>of("host", "dbhost", "port", "5432", "service", "db1",
                        "username", "main", "password", "secret"))) {
            SqlExecutorException failure = assertThrows(SqlExecutorException.class,
                    () -> JdbcSqlExecutor.buildUrl(incomplete), "应当无效: " + incomplete);
            assertEquals("Invalid connection string [].", failure.getMessage());
        }
    }

    @Test
    @DisplayName("IR → URL：parameters 接成查询参数（保持脚本里的顺序）")
    void urlWithParameters() {
        assertEquals("jdbc:slackerdb://dbhost:1/db1?timeout=30&mode=read",
                JdbcSqlExecutor.buildUrl(Map.of(
                        "host", "dbhost", "port", "1", "service", "db1",
                        "parameters", List.of(
                                Map.of("parameterName", "timeout", "parameterValue", "30"),
                                Map.of("parameterName", "mode", "parameterValue", "read")))));
    }

    @Test
    @DisplayName("IR → URL：带 jdbc:slackerdb: 前缀的连接串（全写形态）拼成同一条 URL")
    void fullFormFromJdbcPrefix() {
        SqlAnalyzer.Result result = SqlAnalyzer.analyze(
                "_connect jdbc:slackerdb://127.0.0.1:43175/db1");

        assertEquals(0, result.errorCode(), String.valueOf(result.errorMsg()));
        JdbcSqlExecutor.ConnectionTarget target = JdbcSqlExecutor.buildTarget(result.parsedObject());

        assertEquals("jdbc:slackerdb://127.0.0.1:43175/db1", target.url());
        assertNull(target.user(), "全写形态没写用户名时不该有用户名");
        assertNull(target.password());
    }

    @Test
    @DisplayName("IR → URL：全写形态的用户名/口令不进 URL（它们走 JDBC properties）")
    void fullFormWithCredentials() {
        SqlAnalyzer.Result result = SqlAnalyzer.analyze(
                "_connect main/secret@jdbc:slackerdb://127.0.0.1:43175/db1");

        assertEquals(0, result.errorCode(), String.valueOf(result.errorMsg()));
        JdbcSqlExecutor.ConnectionTarget target = JdbcSqlExecutor.buildTarget(result.parsedObject());

        assertEquals("jdbc:slackerdb://127.0.0.1:43175/db1", target.url(),
                "URL 里不得出现用户名/口令: " + target.url());
        assertEquals("main", target.user());
        assertEquals("secret", target.password());
    }

    @Test
    @DisplayName("IR → URL：全写形态省略库名 → URL 以 '/' 收尾（交给驱动按默认库处理）")
    void fullFormWithoutDatabase() {
        SqlAnalyzer.Result result = SqlAnalyzer.analyze("_connect jdbc:slackerdb://127.0.0.1:43175/");

        assertEquals(0, result.errorCode(), String.valueOf(result.errorMsg()));
        assertEquals("jdbc:slackerdb://127.0.0.1:43175/", JdbcSqlExecutor.buildUrl(result.parsedObject()));

        // 连结尾的 '/' 都没写：补的是分隔符，不是默认库名，驱动看到的仍是同一条 URL
        SqlAnalyzer.Result withoutSlash = SqlAnalyzer.analyze("_connect jdbc:slackerdb://127.0.0.1:43175");
        assertEquals(0, withoutSlash.errorCode(), String.valueOf(withoutSlash.errorMsg()));
        assertEquals("jdbc:slackerdb://127.0.0.1:43175/",
                JdbcSqlExecutor.buildUrl(withoutSlash.parsedObject()));
    }

    @Test
    @DisplayName("IR → URL：全写形态同样保留查询参数")
    void fullFormWithParameters() {
        SqlAnalyzer.Result result = SqlAnalyzer.analyze(
                "_connect jdbc:slackerdb://dbhost:1/db1?timeout=30&mode=read");

        assertEquals(0, result.errorCode(), String.valueOf(result.errorMsg()));
        assertEquals("jdbc:slackerdb://dbhost:1/db1?timeout=30&mode=read",
                JdbcSqlExecutor.buildUrl(result.parsedObject()));
    }

    @Test
    @DisplayName("IR → URL：slackerdb 全写形态的 host/端口仍必填；其它 scheme 走原样透传")
    void fullFormErrors() {
        for (String bad : List.of(
                "_connect jdbc:slackerdb://127.0.0.1/db1",
                "_connect jdbc:slackerdb:///db1",
                "_connect jdbc:slackerdb://127.0.0.1:99999/db1")) {
            SqlAnalyzer.Result result = SqlAnalyzer.analyze(bad);

            SqlExecutorException failure = assertThrows(SqlExecutorException.class,
                    () -> JdbcSqlExecutor.buildUrl(result.parsedObject()), "应当无效: " + bad);
            assertEquals("Invalid connection string ["
                            + bad.substring("_connect ".length()) + "].",
                    failure.getMessage());
        }
    }

    @Test
    @DisplayName("IR → URL：其它驱动的 URL 原样透传（不解释 host/port/database）")
    void otherDriverUrlPassesThrough() {
        SqlAnalyzer.Result sqlite = SqlAnalyzer.analyze("_connect jdbc:sqlite:/tmp/demo.db");

        assertEquals(0, sqlite.errorCode(), String.valueOf(sqlite.errorMsg()));
        JdbcSqlExecutor.ConnectionTarget target = JdbcSqlExecutor.buildTarget(sqlite.parsedObject());
        assertEquals("jdbc:sqlite:/tmp/demo.db", target.url());
        assertNull(target.user(), "没写用户名时不该有用户名");
        assertNull(target.password());

        // 用户名/口令仍然走 JDBC properties，不进 URL
        SqlAnalyzer.Result postgres = SqlAnalyzer.analyze(
                "_connect main/secret@jdbc:postgresql://127.0.0.1:5432/db?useSSL=false");
        JdbcSqlExecutor.ConnectionTarget credentialed =
                JdbcSqlExecutor.buildTarget(postgres.parsedObject());
        assertEquals("jdbc:postgresql://127.0.0.1:5432/db?useSSL=false", credentialed.url(),
                "URL 里不得出现用户名/口令: " + credentialed.url());
        assertEquals("main", credentialed.user());
        assertEquals("secret", credentialed.password());
    }

    @Test
    @DisplayName("IR → URL：其它驱动的 URL 逐字保留（冒号/反斜杠/空格）；只有 scheme 的 jdbc: 无效")
    void otherDriverUrlIsVerbatim() {
        SqlAnalyzer.Result sqlite = SqlAnalyzer.analyze("_connect jdbc:sqlite:C:\\my data\\x.db");

        assertEquals(0, sqlite.errorCode(), String.valueOf(sqlite.errorMsg()));
        assertEquals("jdbc:sqlite:C:\\my data\\x.db", JdbcSqlExecutor.buildUrl(sqlite.parsedObject()));

        // `jdbc:sqlite:` 只有 scheme 没有 URL 正文：交回连接语法，按"无效连接串"处理
        SqlAnalyzer.Result empty = SqlAnalyzer.analyze("_connect jdbc:sqlite:");
        assertNotEquals(0, empty.errorCode(), "只有 scheme 的连接串应当无效: " + empty.errorMsg());

        // URL 里有 ';'（脚本的语句分隔符）：整段加引号，客户端只去掉引号
        SqlAnalyzer.Result quoted = SqlAnalyzer.analyze(
                "_connect \"jdbc:h2:mem:t;DB_CLOSE_DELAY=-1\"");
        assertEquals("jdbc:h2:mem:t;DB_CLOSE_DELAY=-1",
                JdbcSqlExecutor.buildUrl(quoted.parsedObject()));
    }

    @Test
    @DisplayName("IR → URL：不支持的 schema / 没有 host / host 不是主机名 → 同一条无效连接串错误")
    void urlErrors() {
        SqlExecutorException schema = assertThrows(SqlExecutorException.class,
                () -> JdbcSqlExecutor.buildUrl(Map.of("driverSchema", "postgresql", "host", "h")));
        // 直接构造的 IR 没有 connectString：仍然保持同一种错误形状（回显空方括号）
        assertEquals("Invalid connection string [].", schema.getMessage());

        SqlExecutorException noHost = assertThrows(SqlExecutorException.class,
                () -> JdbcSqlExecutor.buildUrl(Map.of()));
        assertEquals("Invalid connection string [].", noHost.getMessage());

        SqlAnalyzer.Result garbage = SqlAnalyzer.analyze("_connect 乱码");
        SqlExecutorException notAHost = assertThrows(SqlExecutorException.class,
                () -> JdbcSqlExecutor.buildUrl(garbage.parsedObject()));
        assertEquals("Invalid connection string [乱码].", notAHost.getMessage());
    }

    @Test
    @DisplayName("_connect /mem：语法里不再有本地内存库分支——解析即失败，IR 里也没有 localService")
    void localMemoryIsGone() {
        SqlAnalyzer.Result result = SqlAnalyzer.analyze("_connect /mem");

        assertEquals(1, result.errorCode(), "/mem 这种写法已从语法删除，应当报错: " + result.errorMsg());
        assertEquals("CONNECT", result.parsedObject().get("name"));
        assertFalse(result.parsedObject().containsKey("localService"),
                "IR 里不该再有 localService: " + result.parsedObject());
        assertFalse(result.parsedObject().containsKey("host"),
                "也不该凭空认出一个 host: " + result.parsedObject());
        // 引擎侧同样没有"本地服务"这条路径：这种写法就是一条无效连接串
        SqlExecutorException failure = assertThrows(SqlExecutorException.class,
                () -> JdbcSqlExecutor.buildUrl(result.parsedObject()));
        assertTrue(failure.getMessage().startsWith("Invalid connection string ["), failure.getMessage());
    }

    @Test
    @DisplayName("_CONNECT 的 IR 直接喂给 buildUrl（无 scheme、无用户名：host:port/service）")
    void urlFromRealIr() {
        SqlAnalyzer.Result result = SqlAnalyzer.analyze("_connect 127.0.0.1:43175/db1");

        assertEquals(0, result.errorCode());
        assertFalse(result.parsedObject().containsKey("driverSchema"),
                "无 scheme 的写法不该有 driverSchema: " + result.parsedObject());
        assertEquals("jdbc:slackerdb://127.0.0.1:43175/db1", JdbcSqlExecutor.buildUrl(result.parsedObject()));
        assertFalse(result.parsedObject().containsKey("username"), "不再有用户名: " + result.parsedObject());
    }

    @Test
    @DisplayName("没连上：execute 返回 errorCode=1 + lastError='Not connected.'（不抛异常）")
    void executeWithoutConnection() {
        JdbcSqlExecutor executor = new JdbcSqlExecutor();

        assertFalse(executor.isConnected());
        ResultModel model = executor.execute("select 1");

        assertEquals(1, model.errorCode());
        assertNull(model.status());
        assertEquals("Not connected.", executor.lastError());
        assertTrue(model.rows().isEmpty());
    }

    @Test
    @DisplayName("_SESSION：未连接时的 save/saveurl、未知名字的 restore/release 都给错误而不是异常")
    void sessionErrors() {
        JdbcSqlExecutor executor = new JdbcSqlExecutor();

        List<CommandOutcome> save = executor.session("SAVE", "main");
        assertInstanceOf(CommandOutcome.Error.class, save.get(0));
        assertEquals("Session is not connected.", ((CommandOutcome.Error) save.get(0)).message());

        List<CommandOutcome> restore = executor.session("RESTORE", "nope");
        assertInstanceOf(CommandOutcome.Error.class, restore.get(0));
        assertEquals("Session [nope] not found.", ((CommandOutcome.Error) restore.get(0)).message());

        List<CommandOutcome> release = executor.session("RELEASE", "nope");
        assertInstanceOf(CommandOutcome.Error.class, release.get(0));

        List<CommandOutcome> unknown = executor.session("WHAT", "x");
        assertInstanceOf(CommandOutcome.Error.class, unknown.get(0));
    }

    @Test
    @DisplayName("_SESSION SHOW：标题状态行 + name/url/current 结果集（未连接时为空表）")
    void sessionShowShape() {
        JdbcSqlExecutor executor = new JdbcSqlExecutor();

        List<CommandOutcome> outcomes = executor.session("SHOW", null);

        assertEquals(2, outcomes.size());
        assertEquals("Current sessions:", ((CommandOutcome.Status) outcomes.get(0)).text());
        CommandOutcome.Table table = assertInstanceOf(CommandOutcome.Table.class, outcomes.get(1));
        assertEquals(List.of("name", "url", "current"), table.headers());
        assertTrue(table.rows().isEmpty());
    }

    @Test
    @DisplayName("连接失败（schema 不认识）在碰网络之前就抛无效连接串异常")
    void connectFailsBeforeTouchingTheNetwork() {
        JdbcSqlExecutor executor = new JdbcSqlExecutor();

        SqlExecutorException failure = assertThrows(SqlExecutorException.class,
                () -> executor.connect(Map.of("driverSchema", "postgresql", "host", "127.0.0.1")));

        assertTrue(failure.getMessage().startsWith("Invalid connection string ["), failure.getMessage());
        assertFalse(executor.isConnected());
    }

    @Test
    @DisplayName("随包驱动常量就是项目自带驱动")
    void bundledDriverConstant() {
        assertEquals("org.slackerdb.jdbc.Driver", JdbcSqlExecutor.BUNDLED_DRIVER_CLASS);
    }

    @Test
    @DisplayName("随包驱动登记表：standalone jar 只留一份 service 文件，随包驱动都必须在里面")
    void bundledDriversAreRegisteredInTheServiceFile() throws Exception {
        // 为什么锁它：standalone jar 把依赖拍平，各依赖自带的 META-INF/services/java.sql.Driver
        // 只会留下一份（实测曾只剩 slackerdb，于是 `_CONNECT jdbc:sqlite:…` 报 "No suitable driver"）。
        // 本项目自己的这份是拍平后的唯一来源（见 src/main/assembly/standalone.xml 的 exclude）。
        try (InputStream serviceFile = JdbcSqlExecutor.class
                .getResourceAsStream("/META-INF/services/java.sql.Driver")) {
            assertNotNull(serviceFile, "缺少 META-INF/services/java.sql.Driver");
            String registered = new String(serviceFile.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(registered.contains(JdbcSqlExecutor.BUNDLED_DRIVER_CLASS), registered);
            assertTrue(registered.contains("org.sqlite.JDBC"),
                    "随包的 SQLite 驱动也必须在登记表里: " + registered);
        }
    }
}
