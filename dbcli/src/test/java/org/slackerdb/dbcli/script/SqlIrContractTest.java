package org.slackerdb.dbcli.script;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IR 契约测试：把**保留命令**的 {@code assertEqual({...}, ret_CommandSplitResult)} 形状逐条冻结成 JUnit。
 *
 * <p>切片口径：{@code test_SQLAnalyze_*} 系列中，
 * 除 6 条随命令删除而作废（IF/Loop/Script/Data/Monitor/Plugin）之外的全部保留 —— 即 **31 条**
 * （含 SSH/JOB/COMPARE）。</p>
 *
 * <p>期望 IR **逐字段精确相等**（字段名拼写、null、int 值都照冻结形态写死），
 * 不合并、不放宽、也不做包含匹配。</p>
 */
class SqlIrContractTest {

    @Test
    @DisplayName("越界/非数字的数值参数：可读错误，不抛 NumberFormatException")
    void numericArgumentsAreReadableErrors() {
        SqlAnalyzer.Result exit = SqlAnalyzer.analyze("_exit 99999999999");
        assertEquals(1, exit.errorCode(), "越界退出码必须是脚本错误");
        assertTrue(exit.errorMsg().contains("Invalid exit code [99999999999]"), exit.errorMsg());

        SqlAnalyzer.Result whenever = SqlAnalyzer.analyze("_whenever error exit 99999999999");
        assertEquals(1, whenever.errorCode());
        assertTrue(whenever.errorMsg().contains("Invalid whenever exit code"), whenever.errorMsg());

        SqlAnalyzer.Result port = SqlAnalyzer.analyze("_ssh connect h with user u password p port abc");
        assertEquals(1, port.errorCode(), "非数字端口必须是脚本错误");
        assertTrue(port.errorMsg().contains("Invalid SSH port [abc]"), port.errorMsg());
    }

    // ------------------------------------------------------------------ 工具

    /** 按固定书写顺序构造期望 IR（允许 null 值，故不用 Map.of）。 */
    private static Map<String, Object> ir(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** 一条完整的断言四元组：isFinished / parsedObject / errorCode / errorMsg 全部精确相等。 */
    private static void assertAnalyze(String text, boolean isFinished, Map<String, Object> parsedObject,
                                      int errorCode, String errorMsg) {
        SqlAnalyzer.Result result = SqlAnalyzer.analyze(text);
        String where = "输入 >>>" + text + "<<<";
        assertEquals(isFinished, result.isFinished(), "isFinished 不一致：" + where);
        assertEquals(parsedObject, result.parsedObject(), "IR 不一致：" + where);
        assertEquals(errorCode, result.errorCode(), "errorCode 不一致：" + where);
        assertEquals(errorMsg, result.errorMsg(), "errorMsg 不一致：" + where);
    }

    /** 只断言 IR 的 name（契约里只写 ["name"] 的测试就用这一条，不加强也不削弱）。 */
    private static void assertAnalyzeName(String text, boolean isFinished, String name,
                                          int errorCode, String errorMsg) {
        SqlAnalyzer.Result result = SqlAnalyzer.analyze(text);
        String where = "输入 >>>" + text + "<<<";
        assertEquals(isFinished, result.isFinished(), "isFinished 不一致：" + where);
        assertEquals(name, result.parsedObject().get("name"), "name 不一致：" + where);
        assertEquals(errorCode, result.errorCode(), "errorCode 不一致：" + where);
        assertEquals(errorMsg, result.errorMsg(), "errorMsg 不一致：" + where);
    }

    // ------------------------------------------------- test_SQLAnalyze_NullString

    @Test
    @DisplayName("NullString")
    void sqlAnalyzeNullString() {
        assertAnalyze("", true, null, 0, null);
    }

    // ----------------------------------------------------- test_SQLAnalyze_Connect

    @Test
    @DisplayName("Connect —— 三种写法（简写 / jdbc:slackerdb:// / 其它驱动 URL）的 IR 契约")
    void sqlAnalyzeConnect() {
        // 真正被接受的三种（见 JdbcSqlExecutorTest）：
        //   <host>:<port>/<service>[?k=v]                                    （无缺省值、无用户名/口令）
        //   [<user>[/<password>]@]jdbc:slackerdb://<host>:<port>/[<database>]（本客户端校验 host/端口/库名）
        //   [<user>[/<password>]@]jdbc:<scheme>:<url>                        （其它驱动：URL 原样透传）
        // 第三种不经过连接语法（见 script/JdbcUrlConnect）：URL 是驱动自己的语法，
        // IR 只记 jdbcUrl 原文，不拆 host/port/service。

        // 历史形态（jdbc:db:tcp://…）如今按"其它驱动"原样透传；口令的引号在 IR 里被剥掉
        String jdbcDbTcpUrl = "user/password@jdbc:db:tcp://10.15.1.19:6231/xdb";
        assertAnalyze("_connect " + jdbcDbTcpUrl, true,
                otherDriver(jdbcDbTcpUrl, "db", "jdbc:db:tcp://10.15.1.19:6231/xdb", "user", "password"),
                0, null);
        assertAnalyze("_connect user/\"password\"@jdbc:db:tcp://10.15.1.19:6231/xdb", true,
                otherDriver("user/\"password\"@jdbc:db:tcp://10.15.1.19:6231/xdb", "db",
                        "jdbc:db:tcp://10.15.1.19:6231/xdb", "user", "password"), 0, null);
        assertAnalyze("_connect user/'password'@jdbc:db:tcp://10.15.1.19:6231/xdb", true,
                otherDriver("user/'password'@jdbc:db:tcp://10.15.1.19:6231/xdb", "db",
                        "jdbc:db:tcp://10.15.1.19:6231/xdb", "user", "password"), 0, null);

        // 达梦形态（缺 @ 的写法）：`SYSDBA` 是 host、`Dameng123:62310jdbc` 是 service，
        // 剩下的 `:dm://…` 无法消费 → errorCode=1（与"其它驱动"无关：它根本没写成 jdbc: URL）
        assertAnalyze("_connect SYSDBA/Dameng123:62310jdbc:dm://10.15.1.19:6231/", true,
                ir("connectString", "SYSDBA/Dameng123:62310jdbc",
                        "host", "SYSDBA", "name", "CONNECT", "service", "Dameng123:62310jdbc"),
                1, "line1:35  mismatched input ':' expecting {<EOF>, '?', CONNECT_SEMICOLON} ");

        // 简写：不带 scheme 的 [user@]host[:port][/service][?k=v]
        assertAnalyze("_connect 127.0.0.1:43175/db1", true,
                ir("connectString", "127.0.0.1:43175/db1",
                        "host", "127.0.0.1", "name", "CONNECT", "port", "43175", "service", "db1"),
                0, null);
        assertAnalyze("_connect dbhost/db1", true,
                ir("connectString", "dbhost/db1",
                        "host", "dbhost", "name", "CONNECT", "service", "db1"), 0, null);
        assertAnalyze("_connect dbhost", true,
                ir("connectString", "dbhost", "host", "dbhost", "name", "CONNECT"), 0, null);
        assertAnalyze("_connect main@dbhost:5432/db1", true,
                ir("connectString", "main@dbhost:5432/db1",
                        "host", "dbhost", "name", "CONNECT", "port", "5432", "service", "db1",
                        "username", "main"), 0, null);
        assertAnalyze("_connect main/secret@dbhost/db1", true,
                ir("connectString", "main/secret@dbhost/db1",
                        "host", "dbhost", "name", "CONNECT", "password", "secret", "service", "db1",
                        "username", "main"), 0, null);
        assertAnalyze("_connect dbhost:5432/db1?autocommit=false", true, ir(
                "connectString", "dbhost:5432/db1?autocommit=false",
                "host", "dbhost",
                "name", "CONNECT",
                "parameters", List.of(ir("parameterName", "autocommit", "parameterValue", "false")),
                "port", "5432",
                "service", "db1"), 0, null);

        // slackerdb 全写形态：jdbc:slackerdb://（用户名/口令与库名都可省略）
        assertAnalyze("_connect main/secret@jdbc:slackerdb://127.0.0.1:43175/db1", true, ir(
                "connectString", "main/secret@jdbc:slackerdb://127.0.0.1:43175/db1",
                "driver", "jdbc",
                "driverSchema", "slackerdb",
                "host", "127.0.0.1",
                "name", "CONNECT",
                "password", "secret",
                "port", "43175",
                "service", "db1",
                "username", "main"), 0, null);
        // 口令的双引号在 IR 里被剥掉（connectString 原样保留引号）
        assertAnalyze("_connect main/\"secret\"@jdbc:slackerdb://127.0.0.1:43175/db1", true, ir(
                "connectString", "main/\"secret\"@jdbc:slackerdb://127.0.0.1:43175/db1",
                "driver", "jdbc",
                "driverSchema", "slackerdb",
                "host", "127.0.0.1",
                "name", "CONNECT",
                "password", "secret",
                "port", "43175",
                "service", "db1",
                "username", "main"), 0, null);
        // 省略库名：没有 service 这个键，URL 交给驱动按默认库处理
        assertAnalyze("_connect jdbc:slackerdb://127.0.0.1:43175/", true, ir(
                "connectString", "jdbc:slackerdb://127.0.0.1:43175/",
                "driver", "jdbc",
                "driverSchema", "slackerdb",
                "host", "127.0.0.1",
                "name", "CONNECT",
                "port", "43175"), 0, null);

        // 不带 @ 的 `a/b`：现行口径是 host=a、service=b（用户名必须写 `a@b` 才是用户名）。
        assertAnalyze("_connect admin/123456", true,
                ir("connectString", "admin/123456",
                        "host", "admin", "name", "CONNECT", "service", "123456"), 0, null);
        // 只有用户名（没有目标）：仍然解析得出来，由引擎报"无效连接串"
        assertAnalyze("_connect admin@", true,
                ir("connectString", "admin@", "name", "CONNECT", "username", "admin"), 0, null);

        // 其它驱动：Oracle（用户名带引号，原样保留）
        assertAnalyze("_connect \"工具人1号\"/123456@jdbc:oracle:thin://192.168.1.72:1521:xe", true,
                otherDriver("\"工具人1号\"/123456@jdbc:oracle:thin://192.168.1.72:1521:xe", "oracle",
                        "jdbc:oracle:thin://192.168.1.72:1521:xe", "\"工具人1号\"", "123456"), 0, null);
        assertAnalyze("_connect system/123456@jdbc:oracle:thin://192.168.1.72:1521/xe", true,
                otherDriver("system/123456@jdbc:oracle:thin://192.168.1.72:1521/xe", "oracle",
                        "jdbc:oracle:thin://192.168.1.72:1521/xe", "system", "123456"), 0, null);
        // Teradata / 命名 h2 会话 / IPv6 / 连接参数
        assertAnalyze("_connect testdblink/testdblink@jdbc:teradata://192.168.1.136/testbase", true,
                otherDriver("testdblink/testdblink@jdbc:teradata://192.168.1.136/testbase", "teradata",
                        "jdbc:teradata://192.168.1.136/testbase", "testdblink", "testdblink"), 0, null);
        assertAnalyze("_connect sa/sa@jdbc:h2tcp:tcp://[FE80::5689:98FF:FE14]:19091/mem:test", true,
                otherDriver("sa/sa@jdbc:h2tcp:tcp://[FE80::5689:98FF:FE14]:19091/mem:test", "h2tcp",
                        "jdbc:h2tcp:tcp://[FE80::5689:98FF:FE14]:19091/mem:test", "sa", "sa"), 0, null);
        assertAnalyze("_connect aa/bb@jdbc:dm://10.10.10.10:6231?autocommit=false&dd=yy", true,
                otherDriver("aa/bb@jdbc:dm://10.10.10.10:6231?autocommit=false&dd=yy", "dm",
                        "jdbc:dm://10.10.10.10:6231?autocommit=false&dd=yy", "aa", "bb"), 0, null);

        // 其它驱动：SQLite（URL 里有 ':'、'\'、空格也要逐字保留）
        assertAnalyze("_connect jdbc:sqlite:/tmp/demo.db", true,
                otherDriver("jdbc:sqlite:/tmp/demo.db", "sqlite", "jdbc:sqlite:/tmp/demo.db", null, null),
                0, null);
        assertAnalyze("_connect main/secret@jdbc:sqlite:C:\\my data\\x.db", true,
                otherDriver("main/secret@jdbc:sqlite:C:\\my data\\x.db", "sqlite",
                        "jdbc:sqlite:C:\\my data\\x.db", "main", "secret"), 0, null);
        // 空库（:memory:）与 URL 正文里的 @（Oracle 风格）都不拆
        assertAnalyze("_connect jdbc:sqlite::memory:", true,
                otherDriver("jdbc:sqlite::memory:", "sqlite", "jdbc:sqlite::memory:", null, null), 0, null);
        assertAnalyze("_connect jdbc:oracle:thin:@host:1521:sid", true,
                otherDriver("jdbc:oracle:thin:@host:1521:sid", "oracle",
                        "jdbc:oracle:thin:@host:1521:sid", null, null), 0, null);
    }

    /**
     * 其它驱动形态的期望 IR：只有原文与 scheme（URL 是驱动自己的语法，客户端不拆）。
     *
     * @param user     用户名（null 表示没写）
     * @param password 口令（null 表示没写）
     */
    private static Map<String, Object> otherDriver(String connectString, String schema, String url,
                                                   String user, String password) {
        Map<String, Object> parsed = ir(
                "connectString", connectString,
                "driver", "jdbc",
                "driverSchema", schema,
                "jdbcUrl", url,
                "name", "CONNECT");
        if (user != null) {
            parsed.put("username", user);
        }
        if (password != null) {
            parsed.put("password", password);
        }
        return parsed;
    }


    // ------------------------------------------------------------------------ LOAD

    @Test
    @DisplayName("Load —— _LOAD JDBCDRIVER <jar> 的 IR（目标与取值都只做文本搬运）")
    void sqlAnalyzeLoad() {
        assertAnalyze("_load jdbcdriver /opt/drivers/pg.jar", true,
                ir("file", "/opt/drivers/pg.jar", "name", "LOAD", "target", "JDBCDRIVER"), 0, null);
        // 引号里的空格原样保留；引号本身在 IR 里被剥掉
        assertAnalyze("_load jdbcdriver \"C:\\my dir\\pg driver.jar\"", true,
                ir("file", "C:\\my dir\\pg driver.jar", "name", "LOAD", "target", "JDBCDRIVER"), 0, null);
        // 没加引号的空格：词法拆开、访问器按单个空格拼回
        assertAnalyze("_load jdbcdriver C:\\my dir\\pg.jar", true,
                ir("file", "C:\\my dir\\pg.jar", "name", "LOAD", "target", "JDBCDRIVER"), 0, null);
        // 缺取值 / 缺目标：解析得出来，由引擎给可读错误
        assertAnalyze("_load jdbcdriver", true,
                ir("file", "", "name", "LOAD", "target", "JDBCDRIVER"), 0, null);
        assertAnalyze("_load", true, ir("name", "LOAD"), 0, null);
        // 目标不认识：整个取值就是 target（引擎报 Unsupported _LOAD target）
        assertAnalyze("_load map rules.map", true,
                ir("name", "LOAD", "target", "map rules.map"), 0, null);
    }

    // ---------------------------------------------------------------------- Exit/Quit
    @Test
    @DisplayName("ExitWithCode")
    void sqlAnalyzeExitWithCode() {
        // 同一冻结断言里还调了一次 APIAnalyze("_exit 3")：API 命名空间不存在，故不再断言
        assertAnalyze("_exit 3", true, ir("name", "EXIT", "exitValue", 3), 0, null);
    }

    @Test
    @DisplayName("ExitWithoutCode")
    void sqlAnalyzeExitWithoutCode() {
        assertAnalyze("_exit ", true, ir("name", "EXIT", "exitValue", 0), 0, null);
    }

    @Test
    @DisplayName("QuitWithCode")
    void sqlAnalyzeQuitWithCode() {
        assertAnalyze("_quit 3", true, ir("name", "QUIT", "exitValue", 3), 0, null);
    }

    @Test
    @DisplayName("QuitWithoutCode")
    void sqlAnalyzeQuitWithoutCode() {
        assertAnalyze("_quit ", true, ir("name", "QUIT", "exitValue", 0), 0, null);
    }

    @Test
    @DisplayName("Disconnect")
    void sqlAnalyzeDisconnect() {
        assertAnalyze("_disconnect", true, ir("name", "DISCONNECT"), 0, null);
    }

    // ------------------------------------------------------------------- Assert（新语法）

    @Test
    @DisplayName("Assert 花括号外观 —— 期望值逐字节冻结（含首尾各一个空格）")
    void sqlAnalyzeAssert() {
        // 现行语法下表达式仍是 "去掉 {% 与 %} 后的原文"：
        // ASSERT_BRACED 把前导空白一起吃进 token，strip() 后正好还原成花括号之间的原文。
        assertAnalyze("_Assert {% assert expresssion %}", true,
                ir("assertName", null, "name", "ASSERT", "expression", " assert expresssion "), 0, null);
    }

    @Test
    @DisplayName("Assert 裸固定形态（dbcli 自己的契约：'_ASSERT <取值器> <比较符> <字面量>[, 名]'）")
    void sqlAnalyzeAssertBareAppearance() {
        assertAnalyze("_ASSERT RESULT.ROWS[0][0] = 10", true,
                ir("name", "ASSERT", "expression", "RESULT.ROWS[0][0] = 10", "assertName", null), 0, null);

        assertAnalyze("_ASSERT {% RESULT.ERRORCODE = 0 %}", true,
                ir("name", "ASSERT", "expression", " RESULT.ERRORCODE = 0 ", "assertName", null), 0, null);

        assertAnalyze("_ASSERT RESULT.ERRORCODE = 0, 错误码检查;", true,
                ir("name", "ASSERT", "expression", "RESULT.ERRORCODE = 0", "assertName", "错误码检查"), 0, null);
    }

    // ---------------------------------------------------------------------- SET

    @Test
    @DisplayName("Set")
    void sqlAnalyzeSet() {
        assertAnalyze("_set", true, ir("name", "SET"), 0, null);
    }

    @Test
    @DisplayName("SetVariable")
    void sqlAnalyzeSetVariable() {
        assertAnalyze("_set @aa bbb", true,
                ir("name", "SET", "optionName", "@aa", "optionValue", "bbb"), 0, null);
    }

    @Test
    @DisplayName("Set2Parameter")
    void sqlAnalyzeSet2Parameter() {
        assertAnalyze("_set XX YY", true,
                ir("name", "SET", "optionName", "XX", "optionValue", "YY"), 0, null);
    }

    @Test
    @DisplayName("Set3Parameter")
    void sqlAnalyzeSet3Parameter() {
        assertAnalyze("_set ZZ DD FF", true,
                ir("name", "SET", "optionName", "ZZ", "optionValue", "DD FF"), 0, null);
    }

    // --------------------------------------------------------------- Sleep / Echo

    @Test
    @DisplayName("Sleep")
    void sqlAnalyzeSleep() {
        assertAnalyze("_sleep 3", true, ir("name", "SLEEP", "sleepTime", "3"), 0, null);
    }

    @Test
    @DisplayName("Echo")
    void sqlAnalyzeEcho() {
        String echoContent = "aaa" + "\n"
                + "bbb" + "\n"
                + "-- fasdfads" + "\n"
                + "# fdsfdasfdsafdsa";
        String echo = "_echo aa.txt \n" + echoContent + "\n" + "echo off";

        assertAnalyze(echo, true, ir("name", "ECHO", "param", "aa.txt", "block", echoContent), 0, null);

        // 空正文（`_echo title` 紧跟 `echo off`）是合法语句，block 是空串：
        // 这条以前是"永远凑不出完整语句"（ECHO_OFF 要求前置换行），现在由正则的 `^` 备选覆盖。
        assertAnalyze("_echo t\necho off", true, ir("name", "ECHO", "param", "t", "block", ""), 0, null);
    }

    // ------------------------------------------------------------------ SELECT / SQL

    @Test
    @DisplayName("Select")
    void sqlAnalyzeSelect() {
        assertAnalyze("Select 1+2 from dual;", true,
                ir("name", "SELECT", "statement", "Select 1+2 from dual"), 0, null);
    }

    @Test
    @DisplayName("SelectNotFinish")
    void sqlAnalyzeSelectNotFinish() {
        assertAnalyzeName("Select 1+2 from dual", false, "SELECT", 1, "missing SQL_END at '<EOF>'");
    }

    @Test
    @DisplayName("SelectWithSlash")
    void sqlAnalyzeSelectWithSlash() {
        assertAnalyze("Select 1+2 from dual\n/", true,
                ir("name", "SELECT", "statement", "Select 1+2 from dual"), 0, null);
    }

    @Test
    @DisplayName("SelectMultiLine")
    void sqlAnalyzeSelectMultiLine() {
        assertAnalyze("Select 1+2,\n 3+4 \n from dual\n/", true,
                ir("name", "SELECT", "statement", "Select 1+2,\n 3+4 \n from dual"), 0, null);
    }

    @Test
    @DisplayName("_USE 已删除：不再有 USE 语句（交给引擎报 Unknown command）")
    void sqlAnalyzeUseIsGone() {
        SqlAnalyzer.Result result = SqlAnalyzer.analyze("_use sql");
        assertFalse(result.parsedObject() != null && "USE".equals(result.parsedObject().get("name")),
                "语法里不该再有 USE 语句: " + result.parsedObject());
    }

    @Test
    @Disabled("ANTLR 4.13.2 与冻结文案采集时用的 4.11.1：同一份 connect 语法下 InputMismatchException 的"
            + "构造位置不同，expecting 集合由 <EOF> 变为 connect 之后可达的 token 集合")
    @DisplayName("Connect 错误文案的旧版字节级契约 —— 保留断言备查")
    void sqlAnalyzeConnectErrorTextReferenceCase() {
        assertAnalyze("_connect SYSDBA/Dameng123:62310jdbc:dm://10.15.1.19:6231/", true,
                ir("name", "CONNECT", "password", "Dameng123", "username", "SYSDBA"),
                1, "line1:25  mismatched input ':' expecting <EOF> ");
    }


    @Test
    @DisplayName("CreateProcedure")
    void sqlAnalyzeCreateProcedure() {
        assertAnalyze("Create Procedure proc \n test ..;\n/", true,
                ir("name", "PROCEDURE", "statement", "Create Procedure proc \n test ..;"), 0, null);
    }

    // ------------------------------------------------------------------- Start

    @Test
    @DisplayName("Start")
    void sqlAnalyzeStart() {
        assertAnalyze("_start c:\\abcd\\defg.sql", true,
                ir("argv", List.of(), "name", "START", "script", "c:\\abcd\\defg.sql"), 0, null);

        assertAnalyze("_start c:\\abcd\\defg.sql  abc def", true,
                ir("argv", List.of("abc", "def"), "name", "START", "script", "c:\\abcd\\defg.sql"), 0, null);
    }

    // ------------------------------------------------------------ Declare / Begin

    @Test
    @DisplayName("Declare")
    void sqlAnalyzeDeclare() {
        assertAnalyze("Declare Begin xxx \n end;\n/", true,
                ir("name", "DECLARE", "statement", "Declare Begin xxx \n end;"), 0, null);
    }

    @Test
    @DisplayName("Begin")
    void sqlAnalyzeBegin() {
        assertAnalyze("Begin xxx \n end;\n/", true,
                ir("name", "BEGIN", "statement", "Begin xxx \n end;"), 0, null);
    }

    // ------------------------------------------------------------------- Spool

    @Test
    @DisplayName("Spool")
    void sqlAnalyzeSpool() {
        assertAnalyze("_Spool aa.txt", true, ir("file", "aa.txt", "name", "SPOOL"), 0, null);

        // "off" 现在由 SPOOL_OFF 关键字匹配（词法里它排在 SPOOL_EXPRESSION 之前，等长时先声明者胜），
        // IR 里保留原文大小写：小写写法得到 'off'。
        assertAnalyze("_Spool off", true, ir("file", "off", "name", "SPOOL"), 0, null);
        assertAnalyze("_Spool OFF", true, ir("file", "OFF", "name", "SPOOL"), 0, null);

        // 不带参数 = 停止当前 spool：这是合法语句（以前靠 errorCode=1 的错误恢复来兜）
        assertAnalyze("_Spool", true, ir("file", "", "name", "SPOOL"), 0, null);

        // 名字里带 off 的普通路径仍然是文件，不是关键字
        assertAnalyze("_Spool off.txt", true, ir("file", "off.txt", "name", "SPOOL"), 0, null);
    }

    // ------------------------------------------------------------------ Session

    @Test
    @DisplayName("Session —— 5 个动作 + 1 个错误形态")
    void sqlAnalyzeSession() {
        assertAnalyze("_Session save xxx", true,
                ir("action", "SAVE", "name", "SESSION", "sessionName", "xxx"), 0, null);
        assertAnalyze("_Session release xxx", true,
                ir("action", "RELEASE", "name", "SESSION", "sessionName", "xxx"), 0, null);
        assertAnalyze("_Session restore xxx", true,
                ir("action", "RESTORE", "name", "SESSION", "sessionName", "xxx"), 0, null);
        assertAnalyze("_Session saveurl xxx", true,
                ir("action", "SAVEURL", "name", "SESSION", "sessionName", "xxx"), 0, null);
        assertAnalyze("_Session show xxx", true,
                ir("action", "SHOW", "name", "SESSION", "sessionName", "xxx"), 0, null);
        assertAnalyze("_Session yyy xxx", true,
                ir("action", "", "name", "SESSION", "sessionName", "yyy"),
                1, "line1:13  extraneous input 'xxx' expecting <EOF> ");
    }

    // -------------------------------------------------------------------- Host

    @Test
    @DisplayName("Host")
    void sqlAnalyzeHost() {
        String script = "\"\"\"" + "\n" + "help" + "\n" + "dir" + "\n" + "\"\"\"";
        assertAnalyze("_HOST " + script, true, ir("name", "HOST", "script", "help & dir"), 0, null);

        assertAnalyze("_HOST ls dir1", true, ir("name", "HOST", "script", "ls dir1"), 0, null);
    }

    // ---------------------------------------------------------------- Whenever

    @Test
    @DisplayName("Whenever")
    void sqlAnalyzeWhenever() {
        assertAnalyze("_WHENEVER ERROR CONTINUE", true,
                ir("action", "continue", "condition", "error", "exitCode", 0, "name", "WHENEVER"), 0, null);

        assertAnalyze("_WHENEVER ERROR EXIT 3", true,
                ir("action", "exit", "condition", "error", "exitCode", 3, "name", "WHENEVER"), 0, null);
    }

    // --------------------------------------------------------------------- SSH

    @Test
    @DisplayName("Ssh —— 10 个形态（含两个多行 EXECUTE 与一个未闭合引号）")
    void sqlAnalyzeSsh() {
        assertAnalyze("_SSH disconnect", true, ir("action", "disconnect", "name", "SSH"), 0, null);

        assertAnalyze("_SSH save node1", true,
                ir("action", "save", "name", "SSH", "sessionName", "node1"), 0, null);

        assertAnalyze("_SSH restore node1", true,
                ir("action", "restore", "name", "SSH", "sessionName", "node1"), 0, null);

        assertAnalyze("_SSH execute ls aa bb cc --dd --ex ", true,
                ir("action", "execute", "command", "ls aa bb cc --dd --ex", "name", "SSH"), 0, null);

        assertAnalyze("_SSH connect node1 with user root password PaSsW@rd926 ", true, ir(
                "action", "connect",
                "host", "node1",
                "name", "SSH",
                "password", "PaSsW@rd926",
                "user", "root"), 0, null);

        assertAnalyze("_SSH connect node1 with user root", true, ir(
                "action", "connect",
                "host", "node1",
                "name", "SSH",
                "user", "root"), 0, null);

        assertAnalyze("_SSH connect node1 with user root keyfile aabcdef==fadsfsd", true, ir(
                "action", "connect",
                "host", "node1",
                "keyFile", "aabcdef==fadsfsd",
                "name", "SSH",
                "user", "root"), 0, null);

        // 多行 EXECUTE（三引号形态：首行空、尾行缩进空格），逐字节冻结
        String sshExecuteMultiline1 = "\n"
                + "                _ssh execute  \"source ~/.bash_profile;cli  << EOF\n"
                + "                restore database 'xxdb1'  from backupset 'set02';\n"
                + "                exit;\n"
                + "                EOF\n"
                + "                \"\n"
                + "            ";
        assertAnalyze(sshExecuteMultiline1, true, ir(
                "action", "execute",
                "command", "source ~/.bash_profile;cli  << EOF\n"
                        + "                restore database 'xxdb1'  from backupset 'set02';\n"
                        + "                exit;\n"
                        + "                EOF\n"
                        + "                ",
                "name", "SSH"), 0, null);

        // 多行 EXECUTE：引号内再出现成对引号（"pass1"），最终命令保留内层引号
        String sshExecuteMultiline2 = "\n"
                + "                _ssh execute  \"source ~/.bash_profile;cli << EOF\n"
                + "                restore database 'xxdb'  from backupset 'set01' IDENTIFIED BY \"pass1\";\n"
                + "                exit;\n"
                + "                EOF\";\n"
                + "            ";
        assertAnalyze(sshExecuteMultiline2, true, ir(
                "action", "execute",
                "command", "source ~/.bash_profile;cli << EOF\n"
                        + "                restore database 'xxdb'  from backupset 'set01' "
                        + "IDENTIFIED BY \"pass1\";\n"
                        + "                exit;\n"
                        + "                EOF",
                "name", "SSH"), 0, null);

        // 未闭合引号：访问器给出 "Missing <EOF> un-closed qutote string."（原文案已冻结，
        // 拼写不能"顺手改对"），与词法/语法监听器的文案以 " < " 合并，
        // 因此断言用 startsWith（只锁前缀，因为后缀来自 ANTLR 版本相关的报错）。
        String sshExecuteUnclosed = "\n"
                + "                _ssh execute  \"source ~/.bash_profile;cli << EOF\n"
                + "                restore database 'xxdb'  from backupset 'set01' IDENTIFIED BY \"pass1\";\n"
                + "            ";
        SqlAnalyzer.Result result = SqlAnalyzer.analyze(sshExecuteUnclosed);
        assertTrue(result.errorMsg().startsWith("Missing <EOF> un-closed qutote string."),
                "errorMsg 应以访问器文案开头，实际: " + result.errorMsg());
        assertEquals(1, result.errorCode());
        assertFalse(result.isFinished());
        assertEquals(ir("action", "execute", "command", "", "name", "SSH"), result.parsedObject());
    }

    // --------------------------------------------------------------------- SSH PORT（新增语法）

    @Test
    @DisplayName("Ssh PORT —— 新增的可选子句：带 PORT 时 IR 多一个 int 的 port，不带时没有该键")
    void sqlAnalyzeSshPort() {
        // 口令 + PORT：PORT 子句接在口令之后（不写 PORT 时该键不出现，见下一条测试）
        assertAnalyze("_SSH connect node1 with user root password PaSsW@rd926 port 2222", true, ir(
                "action", "connect",
                "host", "node1",
                "name", "SSH",
                "password", "PaSsW@rd926",
                "port", 2222,
                "user", "root"), 0, null);

        // KEYFILE + PORT（私钥路径比口令形式长一个 token，PORT 的下标随之后移）
        assertAnalyze("_SSH connect node1 with user root keyfile aabcdef==fadsfsd port 22022", true, ir(
                "action", "connect",
                "host", "node1",
                "keyFile", "aabcdef==fadsfsd",
                "name", "SSH",
                "port", 22022,
                "user", "root"), 0, null);

        // 不带 PORT：IR 里**没有** port 键（端口由分派器用默认 22 兜底，既有契约一字不变）
        assertAnalyze("_SSH connect node1 with user root password PaSsW@rd926 ", true, ir(
                "action", "connect",
                "host", "node1",
                "name", "SSH",
                "password", "PaSsW@rd926",
                "user", "root"), 0, null);

        // PORT 子句要跟在一个认证子句（PASSWORD 或 KEYFILE）之后：它是那条备选语法的一部分。
        // 单独 `... user root port 2022` 不成立 —— USER 的表达式规则能吃下裸词 "port"，
        // 而 ANTLR 在"短备选 + 多余 token"时会先选短备选，故不把这种形态写进契约（见 §3.8 的说明）。
        assertAnalyze("_SSH connect node1 with user root password p port 2022", true, ir(
                "action", "connect",
                "host", "node1",
                "name", "SSH",
                "password", "p",
                "port", 2022,
                "user", "root"), 0, null);

        // 尾部分号与大小写不敏感
        assertAnalyze("_ssh connect node1 WITH USER root PASSWORD p PORT 22;", true, ir(
                "action", "connect",
                "host", "node1",
                "name", "SSH",
                "password", "p",
                "port", 22,
                "user", "root"), 0, null);

        // **纯数字口令 + 纯数字端口**：这是最容易回归的一格 —— 端口号语法很容易让"数字"变成
        // 另一个 token，从而把整条 connect 备选拆散（真机脚本里口令就是 "123456" 这种形态）
        assertAnalyze("_ssh connect 192.168.183.128 with user root password 123456 port 22", true, ir(
                "action", "connect",
                "host", "192.168.183.128",
                "name", "SSH",
                "password", "123456",
                "port", 22,
                "user", "root"), 0, null);

        // 老形态：纯数字口令、没有 PORT（PORT 子句加进来之后它必须照旧可用）
        assertAnalyze("_ssh connect node1 with user root password 123456", true, ir(
                "action", "connect",
                "host", "node1",
                "name", "SSH",
                "password", "123456",
                "user", "root"), 0, null);
    }

    // -------------------------------------------------------------- SSH SFTP（11 个动作）

    @Test
    @DisplayName("Sftp —— 11 个动作的 IR（数值参数也是 SSH_EXPRESSION，纯数字不再被打散）")
    void sqlAnalyzeSshSftp() {
        assertAnalyze("_ssh sftp getcwd", true,
                ir("action", "sftp_cwd", "name", "SSH"), 0, null);

        assertAnalyze("_ssh sftp chdir /tmp", true,
                ir("action", "sftp_chdir", "dir", "/tmp", "name", "SSH"), 0, null);

        assertAnalyze("_ssh sftp listdir /tmp", true,
                ir("action", "sftp_listdir", "dir", "/tmp", "name", "SSH"), 0, null);

        assertAnalyze("_ssh sftp put /l/a.txt /r/a.txt", true,
                ir("action", "sftp_put", "localFile", "/l/a.txt", "name", "SSH", "remoteFile", "/r/a.txt"), 0, null);

        assertAnalyze("_ssh sftp get /r/a.txt /l/a.txt", true,
                ir("action", "sftp_get", "localFile", "/l/a.txt", "name", "SSH", "remoteFile", "/r/a.txt"), 0, null);

        assertAnalyze("_ssh sftp rename /r/a.txt /r/b.txt", true,
                ir("action", "sftp_rename", "name", "SSH", "newFile", "/r/b.txt", "oldFile", "/r/a.txt"), 0, null);

        assertAnalyze("_ssh sftp remove /f", true,
                ir("action", "sftp_remove", "file", "/f", "name", "SSH"), 0, null);

        // 大小写不敏感、行尾分号
        assertAnalyze("_SSH SFTP GETCWD;", true,
                ir("action", "sftp_cwd", "name", "SSH"), 0, null);
    }

    /**
     * 真机缺陷回归：SFTP 的数值参数（chmod/mkdir 的权限位、chown 的 uid/gid、truncate 的大小）
     * 在词法上都是 {@code SSH_EXPRESSION}。
     *
     * <p>语法里若仍写 {@code INT}，解析器会走"插入缺失的 INT"的错误恢复：多余的 {@code SSH_EXPRESSION}
     * 被当作 extraneous **丢掉、不进语法树**，访问器取 {@code ctx.INT(0)} 就是 null → NPE
     * （真机退出码 255）。所以这里逐条冻结"数值参数进不进 IR"，并且**类型也冻结**：
     * 权限位是八进制字符串（分派器按 8 进制还原），uid/gid/大小是 int。</p>
     */
    @Test
    @DisplayName("Sftp 数值参数 —— chmod/mkdir 的权限位与 chown/truncate 的整数值都进 IR")
    void sqlAnalyzeSshSftpNumbers() {
        // 权限位：IR 里是"八进制原文"（String），与 SshCommandDispatcher 的解析口径一致
        Map<String, Object> chmod = SqlAnalyzer.analyze("_ssh sftp chmod /f 640").parsedObject();
        assertEquals(ir("action", "sftp_chmod", "fileName", "/f", "fileMod", "640", "name", "SSH"), chmod);
        assertTrue(chmod.get("fileMod") instanceof String, "fileMod 是八进制字符串，实际: " + chmod.get("fileMod"));

        Map<String, Object> mkdir = SqlAnalyzer.analyze("_ssh sftp mkdir /d 755").parsedObject();
        assertEquals(ir("action", "sftp_mkdir", "dir", "/d", "dirMod", "755", "name", "SSH"), mkdir);
        assertTrue(mkdir.get("dirMod") instanceof String, "dirMod 是八进制字符串，实际: " + mkdir.get("dirMod"));

        // 真机脚本里的形态（含末尾分号）
        assertAnalyze("_ssh sftp mkdir /tmp/dbcli-sftp 750;", true,
                ir("action", "sftp_mkdir", "dir", "/tmp/dbcli-sftp", "dirMod", "750", "name", "SSH"), 0, null);

        // uid/gid 是十进制 int
        assertAnalyze("_ssh sftp chown /f 0 0", true,
                ir("action", "sftp_chown", "fileName", "/f", "gid", 0, "name", "SSH", "uid", 0), 0, null);
        assertAnalyze("_ssh sftp chown /f 1000 1001", true,
                ir("action", "sftp_chown", "fileName", "/f", "gid", 1001, "name", "SSH", "uid", 1000), 0, null);

        // 大小是十进制 int
        assertAnalyze("_ssh sftp truncate /f 12", true,
                ir("action", "sftp_truncate", "file", "/f", "fileSize", 12, "name", "SSH"), 0, null);
    }

    @Test
    @DisplayName("Sftp 数值参数非法 —— 走访问器错误通道（errorCode=1 + 可读 errorMsg），不是 NPE、也不是未捕获异常")
    void sqlAnalyzeSshSftpInvalidNumbers() {
        // 权限位按八进制校验：非数字、以及 8/9 这种不属于八进制的值都被拒
        assertSftpNumberError("_ssh sftp chmod /f abc",
                "Invalid SFTP file mode [abc]: expected an octal permission mode in 0..07777.");
        assertSftpNumberError("_ssh sftp mkdir /d 8",
                "Invalid SFTP directory mode [8]: expected an octal permission mode in 0..07777.");

        // uid/gid、大小是十进制
        assertSftpNumberError("_ssh sftp chown /f x y",
                "Invalid SFTP uid [x]: expected a non-negative integer.");
        assertSftpNumberError("_ssh sftp chown /f 0 y",
                "Invalid SFTP gid [y]: expected a non-negative integer.");
        assertSftpNumberError("_ssh sftp truncate /f abc",
                "Invalid SFTP file size [abc]: expected a non-negative integer.");
    }

    /**
     * 非法数值必须是**脚本级错误**：{@code errorCode != 0}（SSH 组的约定是 1）+ 可读 {@code errorMsg}，
     * 且语句本身仍是"完整的"（{@code isFinished=true}）—— 否则引擎会把它当成"没写完"继续攒行，
     * 最后报成"脚本末尾语句未结束"，把一个笔误变成另一件更难解释的事。
     */
    private static void assertSftpNumberError(String script, String expectedMessage) {
        SqlAnalyzer.Result result = SqlAnalyzer.analyze(script);

        assertTrue(result.isFinished(), "语句是完整的，不该被当成续行: " + script);
        assertEquals(1, result.errorCode(), "SSH 组的错误码约定是 1（即 errorCode != 0）: " + script);
        assertEquals(expectedMessage, result.errorMsg(), script);
        // IR 仍然给得出来（调用方要能看出是哪条 SSH 语句出错），只是取不出来的数值不在里面
        assertNotNull(result.parsedObject(), script);
        assertEquals("SSH", result.parsedObject().get("name"), script);
    }

    // --------------------------------------------------------------------- JOB

    @Test
    @DisplayName("Job —— 11 个形态（register/deregister 已从语法删除）")
    void sqlAnalyzeJob() {
        assertAnalyze("_JOB JOBMANAGER ON", true,
                ir("action", "startJobmanager", "name", "JOB"), 0, null);
        assertAnalyze("_JOB JOBMANAGER OFF", true,
                ir("action", "stopJobmanager", "name", "JOB"), 0, null);
        assertAnalyze("_JOB WAIT abcd", true,
                ir("action", "wait", "jobName", "abcd", "name", "JOB", "param", ir()), 0, null);
        assertAnalyze("_JOB WAIT abcd timeout=10", true,
                ir("action", "wait", "jobName", "abcd", "name", "JOB", "param", ir("timeout", "10")), 0, null);
        assertAnalyze("_JOB SHOW abcd", true,
                ir("action", "show", "jobName", "abcd", "name", "JOB"), 0, null);
        assertAnalyze("_JOB ABORT abcd", true,
                ir("action", "abort", "jobName", "abcd", "name", "JOB"), 0, null);
        assertAnalyze("_JOB SHUTDOWN abcd", true,
                ir("action", "shutdown", "jobName", "abcd", "name", "JOB"), 0, null);
        assertAnalyze("_JOB TIMER tp1", true,
                ir("action", "timer", "name", "JOB", "timerPoint", "tp1"), 0, null);
        assertAnalyze("_JOB START abcd", true,
                ir("action", "start", "jobName", "abcd", "name", "JOB"), 0, null);
        assertAnalyze("_JOB SET ABCD Key1=Value1 Key2=Value2", true, ir(
                "action", "set",
                "jobName", "ABCD",
                "name", "JOB",
                "param", ir("Key1", "Value1", "Key2", "Value2")), 0, null);
        assertAnalyze("_JOB CREATE ABCD Key1=Value1 Key2=Value2", true, ir(
                "action", "create",
                "jobName", "ABCD",
                "name", "JOB",
                "param", ir("Key1", "Value1", "Key2", "Value2")), 0, null);
    }

    // ----------------------------------------------------------------- COMPARE

    @Test
    @DisplayName("Compare —— 15 个形态")
    void sqlAnalyzeCompare() {
        assertAnalyze("_COMPARE aa.txt bb.txt MASK NOCASE", true, ir(
                "action", "compare",
                "compareOptions", ir("case", false, "mask", true),
                "name", "COMPARE",
                "referenceFile", "bb.txt",
                "targetFile", "aa.txt"), 0, null);

        assertAnalyze("_COMPARE MASKLINE \"aa bb\"=>\"cc bb\"", true, ir(
                "action", "mask",
                "compareOptions", ir(),
                "name", "COMPARE",
                "source", "\"aa bb\"",
                "target", "\"cc bb\""), 0, null);

        assertAnalyze("_COMPARE NOMASKLINE \"aa bb\"", true, ir(
                "action", "nomask",
                "compareOptions", ir(),
                "name", "COMPARE",
                "source", "\"aa bb\""), 0, null);

        assertAnalyze("_COMPARE SKIPLINE \"aa*bb\"", true, ir(
                "action", "skip",
                "compareOptions", ir(),
                "name", "COMPARE",
                "source", "aa*bb"), 0, null);

        assertAnalyze("_COMPARE NOSKIPLINE \"aa*bb\"", true, ir(
                "action", "noskip",
                "compareOptions", ir(),
                "name", "COMPARE",
                "source", "aa*bb"), 0, null);

        assertAnalyze("_COMPARE RESET", true,
                ir("action", "reset", "compareOptions", ir(), "name", "COMPARE"), 0, null);

        assertAnalyze("_COMPARE SET", true,
                ir("action", "set", "compareOptions", ir(), "name", "COMPARE"), 0, null);

        assertAnalyze("_COMPARE SET MASK CASE IGBLANK NOTRIM", true, ir(
                "action", "set",
                "compareOptions", ir("case", true, "igblank", true, "mask", true, "trim", false),
                "name", "COMPARE"), 0, null);

        assertAnalyze("_COMPARE SET OUTPUT CONSOLE", true,
                ir("action", "set", "compareOptions", ir("output", List.of("console")), "name", "COMPARE"), 0, null);

        assertAnalyze("_COMPARE SET OUTPUT DIFFFILE", true,
                ir("action", "set", "compareOptions", ir("output", List.of("diffFile")), "name", "COMPARE"), 0, null);

        assertAnalyze("_COMPARE SET OUTPUT CONSOLE DIFFFILE", true, ir(
                "action", "set",
                "compareOptions", ir("output", List.of("console", "diffFile")),
                "name", "COMPARE"), 0, null);

        assertAnalyze("_COMPARE SET ALGORITHM LCS", true,
                ir("action", "set", "compareOptions", ir("algorithm", "lcs"), "name", "COMPARE"), 0, null);

        assertAnalyze("_COMPARE SET ALGORITHM MYERS", true,
                ir("action", "set", "compareOptions", ir("algorithm", "myers"), "name", "COMPARE"), 0, null);

        assertAnalyze("_COMPARE SET WORK ENCODING UTF-8", true, ir(
                "action", "set",
                "compareOptions", ir("workEncoding", "UTF-8"),
                "name", "COMPARE"), 0, null);

        assertAnalyze("_COMPARE SET REFERENCE ENCODING GBK", true, ir(
                "action", "set",
                "compareOptions", ir("refEncoding", "GBK"),
                "name", "COMPARE"), 0, null);
    }

    // -------------------------------------------- 通用 SQL / 美元引号 / 事务形式 BEGIN

    /*
     * 通用 SQL 语句的 IR 契约。
     *
     * 这一类语句（WITH / ALTER / TRUNCATE / …）客户端**不解释语义**，只负责"认出它是一条 SQL、
     * 等它写完、原样转发"，因此 IR 与其它 SQL 语句同构，但 name 统一是 "SQL"：
     * 例如 SET 不能按关键字取名（会与 _SET 命令的 IR 名撞车）。
     */
    @Test
    @DisplayName("通用 SQL —— WITH / TRUNCATE / ALTER / VACUUM / ANALYZE / EXPLAIN")
    void sqlAnalyzeGenericSql() {
        assertAnalyze("with cte as (select 1 as x) select * from cte;", true,
                ir("name", "SQL", "statement", "with cte as (select 1 as x) select * from cte"), 0, null);
        assertAnalyze("truncate table t;", true,
                ir("name", "SQL", "statement", "truncate table t"), 0, null);
        assertAnalyze("alter table t add column b int;", true,
                ir("name", "SQL", "statement", "alter table t add column b int"), 0, null);
        assertAnalyze("vacuum t;", true, ir("name", "SQL", "statement", "vacuum t"), 0, null);
        assertAnalyze("analyze t;", true, ir("name", "SQL", "statement", "analyze t"), 0, null);
        assertAnalyze("explain select 1;", true,
                ir("name", "SQL", "statement", "explain select 1"), 0, null);
    }

    @Test
    @DisplayName("通用 SQL —— SET / SHOW / GRANT / REVOKE / CALL / COMMENT / REFRESH / COPY")
    void sqlAnalyzeGenericSqlMore() {
        assertAnalyze("set search_path to public;", true,
                ir("name", "SQL", "statement", "set search_path to public"), 0, null);
        assertAnalyze("show search_path;", true,
                ir("name", "SQL", "statement", "show search_path"), 0, null);
        assertAnalyze("grant select on t to u;", true,
                ir("name", "SQL", "statement", "grant select on t to u"), 0, null);
        assertAnalyze("revoke select on t from u;", true,
                ir("name", "SQL", "statement", "revoke select on t from u"), 0, null);
        assertAnalyze("call p();", true, ir("name", "SQL", "statement", "call p()"), 0, null);
        assertAnalyze("comment on table t is 'x';", true,
                ir("name", "SQL", "statement", "comment on table t is 'x'"), 0, null);
        assertAnalyze("refresh materialized view mv;", true,
                ir("name", "SQL", "statement", "refresh materialized view mv"), 0, null);
        assertAnalyze("copy t from '/tmp/a.csv' with (format csv);", true,
                ir("name", "SQL", "statement", "copy t from '/tmp/a.csv' with (format csv)"), 0, null);
    }

    @Test
    @DisplayName("通用 SQL 多行：内部换行原样保留，只有 ';' 收尾")
    void sqlAnalyzeGenericSqlMultiLine() {
        assertAnalyze("with cte as (\n  select 1 as x\n)\nselect * from cte;", true,
                ir("name", "SQL", "statement", "with cte as (\n  select 1 as x\n)\nselect * from cte"), 0, null);
    }

    @Test
    @DisplayName("通用 SQL 未写完：'missing SQL_END' ⇒ 继续读下一行")
    void sqlAnalyzeGenericSqlNotFinish() {
        assertAnalyzeName("with cte as (", false, "SQL", 1, "missing SQL_END at '<EOF>'");
        assertAnalyzeName("truncate table t", false, "SQL", 1, "missing SQL_END at '<EOF>'");
    }

    @Test
    @DisplayName("美元引号体：$$ … $$ 内部的 ';' 不是语句终结符")
    void sqlAnalyzeDollarQuotedBody() {
        assertAnalyze("do $$ begin raise notice 'x'; end; $$;", true,
                ir("name", "SQL", "statement", "do $$ begin raise notice 'x'; end; $$"), 0, null);
        // 带标签的写法同样整块收下
        assertAnalyze("do $body$ begin raise notice 'x'; end; $body$;", true,
                ir("name", "SQL", "statement", "do $body$ begin raise notice 'x'; end; $body$"), 0, null);
    }

    @Test
    @DisplayName("PG 风格 CREATE FUNCTION：美元引号体 + ';' 收尾（不是 Oracle 的行首 '/'）")
    void sqlAnalyzePgStyleFunction() {
        assertAnalyze("create or replace function f() returns int as $$ select 1; $$ language sql;", true,
                ir("name", "CREATE",
                        "statement", "create or replace function f() returns int as $$ select 1; $$ language sql"),
                0, null);
        // 多行函数体：内部换行与 ';' 都原样保留
        assertAnalyze("create or replace function f() returns int as $$\nbegin\n  return 1;\nend;\n$$ language plpgsql;",
                true,
                ir("name", "CREATE",
                        "statement", "create or replace function f() returns int as $$\nbegin\n  return 1;\nend;\n$$ language plpgsql"),
                0, null);
    }

    @Test
    @DisplayName("美元引号没闭合 ⇒ 判成没写完，后续的 ';' 不能当终结符")
    void sqlAnalyzeUnclosedDollarQuote() {
        assertAnalyzeName("do $$", false, "SQL", 1, "unclosed dollar-quoted string");
        // 这里语句结构上是完整的（select + ';'），但那个 ';' 落在未闭合的 $$ 里
        assertAnalyzeName("select $$abc;", false, "SELECT",
                1, "unclosed dollar-quoted string");
    }

    @Test
    @DisplayName("事务形式的 BEGIN;：IR 是 BEGIN，语句原文不含终结符")
    void sqlAnalyzeBeginTransaction() {
        assertAnalyze("begin;", true, ir("name", "BEGIN", "statement", "begin"), 0, null);
        assertAnalyze("Begin ;", true, ir("name", "BEGIN", "statement", "Begin"), 0, null);
    }

    @Test
    @DisplayName("多行字符串里的 ';' 不算终结符（等闭引号那一行）")
    void sqlAnalyzeMultiLineString() {
        assertAnalyze("insert into t values ('line1\nline2; line3');", true,
                ir("name", "INSERT", "statement", "insert into t values ('line1\nline2; line3')"), 0, null);
        // 引号没闭合：继续读下一行，而不是把第一行截断执行
        assertAnalyzeName("select 'a;b", false, "SELECT", 1, "missing SQL_END at '<EOF>'");
    }

    // ------------------------------------------------------------ 未纳入本类清单的测试（见报告）

    // 以下测试**未纳入**（命令不在当前语法里，无法解析）：
    //   test_SQLAnalyze_Script(旧编号 553) / Loop(663) / If(711) / Data(1124) / Monitor(1175) / Plugin(1277)
    // 以及 5 个 API 侧测试：test_APIAnalyze_MultiPart / Get / Get2 / Set / SESSION（旧编号 1699-1920）。
    // 另有 1 处只断言 isFinished 的空值测试已由 IrErrorTextTest 覆盖，此处不再重复。
}
