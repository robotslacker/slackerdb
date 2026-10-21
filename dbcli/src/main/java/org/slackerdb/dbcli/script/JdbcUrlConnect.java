package org.slackerdb.dbcli.script;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code _connect} 的**通用 JDBC 形态**：{@code [{<user>}[/<password>]@]jdbc:<scheme>:<url>}。
 *
 * <h2>为什么在分析器入口按文本识别，而不写进 ANTLR 连接语法</h2>
 * <p>连接语法（{@code SQLParser.g4} 的 {@code connect}）表达的是**本客户端要自己解释**的两种形状：
 * 简写的 {@code <host>:<port>/<service>} 与本机的 {@code jdbc:slackerdb://<host>:<port>/[<db>]}。
 * 其它驱动的 URL 属于**驱动自己的语法**，客户端只负责原样透传：
 * {@code jdbc:sqlite:C:\my data\x.db}、{@code jdbc:h2:mem:t;DB_CLOSE_DELAY=-1}、
 * {@code jdbc:mysql://h/db?a=b+c} 里可以有 {@code : / \ 空格 ; @ ? +} 等任意字符。
 * 连接模式的 token（{@code CONNECT_STRING} 不含 {@code : / \} 等）拼不回原文，
 * 硬塞进语法只会不断漏字符。因此这里按"命令字 + 可选用户信息 + {@code jdbc:} 起的 URL 原文"识别，
 * **URL 逐字保留**。</p>
 *
 * <h2>边界</h2>
 * <ul>
 *   <li>scheme 是 {@code slackerdb} 时**这里不管**（返回 null）：本机 scheme 走连接语法，
 *       由语法产出 host/port/database，再由 {@code JdbcSqlExecutor} 校验；</li>
 *   <li>不是 {@code _connect} / 没有 {@code jdbc:} / 跨行 / 缺 URL 正文时返回 null，
 *       交回连接语法按原来的口径报 {@code Invalid connection string [...]}。</li>
 *   <li><b>URL 里有 {@code ;}（H2/SQL Server 那类拼参数的写法）或空格时，整段加引号</b>：
 *       {@code _connect "jdbc:h2:mem:t;DB_CLOSE_DELAY=-1"}。{@code ;} 是脚本的语句分隔符，
 *       不加引号会被当成语句结束（脚本切分器只认引号里的 {@code ;} 是数据）。</li>
 * </ul>
 *
 * <p>IR 与语法给出的同构：{@code name / connectString / driver / driverSchema / jdbcUrl}
 * （有用户名/口令时再加 {@code username / password}）。{@code connectString} 是使用者写的原文
 * （不含命令字与结尾 {@code ;}），错误回显用它。</p>
 */
final class JdbcUrlConnect {

    /** 命令字。 */
    private static final String COMMAND = "_connect";

    /** 所有 JDBC URL 的前缀。 */
    private static final String JDBC_PREFIX = "jdbc:";

    /** 由连接语法（也就是本类之外）解释的 scheme：它不进"原样透传"这条路径。 */
    private static final String NATIVE_SCHEME = "slackerdb";

    private JdbcUrlConnect() {
    }

    /**
     * 识别一条通用 JDBC 形态的 {@code _connect}。
     *
     * @param sqlText 分析器拿到的原文（已 strip）
     * @return 解析结果；{@code null} 表示"不是通用 JDBC 形态"，调用方继续走 ANTLR
     */
    static SqlAnalyzer.Result analyze(String sqlText) {
        String text = sqlText == null ? "" : sqlText.strip();
        if (!text.regionMatches(true, 0, COMMAND, 0, COMMAND.length())) {
            return null;
        }
        // 命令字必须是整词：`_connectx` 不是 _connect
        if (text.length() > COMMAND.length() && !Character.isWhitespace(text.charAt(COMMAND.length()))) {
            return null;
        }
        String connectString = text.substring(COMMAND.length()).strip();
        if (connectString.endsWith(";")) {
            // 候选语句由 StatementSplitter 按顶层 ';' 切出来，这个分号是语句终结符
            connectString = connectString.substring(0, connectString.length() - 1).strip();
        }
        if (connectString.isEmpty() || connectString.indexOf('\n') >= 0 || connectString.indexOf('\r') >= 0) {
            // 空取值、或跨行的 URL（URL 里不该有换行）：交回连接语法
            return null;
        }
        // 整个取值也可以整段加引号（`_connect "user/pass@jdbc:h2:mem:t;DB_CLOSE_DELAY=-1"`）：
        // 去掉最外层那一对引号后，里面的 `;`/空格都已经是 URL 的正文。
        String body = stripOuterQuotes(connectString);

        // 用户名/口令是可选的 `a/b@` 前缀；@ 只在紧跟 jdbc:（中间可以是一个引号）时才算分隔符，
        // 这样 `jdbc:oracle:thin:@host:1521:sid` 里那个 @ 不会被当成用户信息。
        int at = indexOfUserInfoEnd(body);
        String userInfo = null;
        String url;
        if (at >= 0) {
            userInfo = body.substring(0, at).strip();
            url = body.substring(at + 1).strip();
            if (userInfo.isEmpty()) {
                return null;
            }
        } else if (startsJdbcUrl(body)) {
            url = body;
        } else {
            return null;
        }
        // URL 单独带引号（`_connect main/secret@"jdbc:sqlite:/tmp/a b.db"`）：去掉成对引号
        url = unquote(url);

        int colon = url.indexOf(':', JDBC_PREFIX.length());
        if (colon < 0) {
            return null;
        }
        String scheme = url.substring(JDBC_PREFIX.length(), colon);
        if (!isScheme(scheme) || NATIVE_SCHEME.equalsIgnoreCase(scheme)) {
            return null;
        }
        if (colon + 1 >= url.length()) {
            // `jdbc:sqlite:`：只有 scheme 没有 URL 正文，构不成可用的连接串
            return null;
        }

        Map<String, Object> parsedObject = new LinkedHashMap<>();
        parsedObject.put("name", "CONNECT");
        parsedObject.put("connectString", connectString);
        parsedObject.put("driver", "jdbc");
        parsedObject.put("driverSchema", scheme);
        // 驱动自己的语法：原样透传，本客户端不解释 host/port/database
        parsedObject.put("jdbcUrl", url);
        if (userInfo != null) {
            int slash = userInfo.indexOf('/');
            String user = slash < 0 ? userInfo : userInfo.substring(0, slash);
            if (user.isEmpty()) {
                return null;
            }
            parsedObject.put("username", user);
            if (slash >= 0) {
                // 与语法访问器一致：口令两侧成对的引号去掉，用户名原样保留
                parsedObject.put("password", unquotePassword(userInfo.substring(slash + 1)));
            }
        }
        return new SqlAnalyzer.Result(true, parsedObject, 0, null, false, false);
    }

    /**
     * {@code @}（后面可以跟一个引号）紧跟 {@code jdbc:} 的位置；没有则 -1。
     *
     * <p>只认"紧跟着 {@code jdbc:}（大小写不敏感）"的 {@code @}：URL 正文里的 {@code @}
     * （{@code jdbc:oracle:thin:@host:1521:sid}）不属于用户信息分隔符。</p>
     */
    private static int indexOfUserInfoEnd(String text) {
        for (int index = text.indexOf('@'); index >= 0; index = text.indexOf('@', index + 1)) {
            if (text.regionMatches(true, skipQuote(text, index + 1), JDBC_PREFIX, 0, JDBC_PREFIX.length())) {
                return index;
            }
        }
        return -1;
    }

    /** 位置 {@code from} 上是否是一个引号（是就跳过它）。 */
    private static int skipQuote(String text, int from) {
        if (from < text.length()) {
            char character = text.charAt(from);
            if (character == '"' || character == '\'') {
                return from + 1;
            }
        }
        return from;
    }

    /** 文本（可以带一层引号）是不是一条 {@code jdbc:} URL。 */
    private static boolean startsJdbcUrl(String text) {
        return text.regionMatches(true, skipQuote(text, 0), JDBC_PREFIX, 0, JDBC_PREFIX.length());
    }

    /** JDBC 的 subprotocol 形状：字母开头，后面可以是字母/数字/下划线/点/加号/减号。 */
    private static boolean isScheme(String scheme) {
        if (scheme.isEmpty() || !Character.isLetter(scheme.charAt(0))) {
            return false;
        }
        for (int index = 0; index < scheme.length(); index++) {
            char character = scheme.charAt(index);
            if (!Character.isLetterOrDigit(character) && character != '_' && character != '.'
                    && character != '+' && character != '-') {
                return false;
            }
        }
        return true;
    }

    /**
     * 去掉整个取值最外层成对的单/双引号（没有引号就原样返回）。
     *
     * <p>用途：URL 里的 {@code ;} 在脚本里是语句分隔符、空格会把一段 URL 拆成两截；
     * 整段加引号后脚本切分器就把它们当数据（见类注释的"边界"）。</p>
     */
    private static String stripOuterQuotes(String value) {
        String text = value == null ? "" : value.strip();
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                return text.substring(1, text.length() - 1).strip();
            }
        }
        return text;
    }

    /** 去掉整段 URL 外面成对的引号（只当引号里的内容以 {@code jdbc:} 开头时）。 */
    private static String unquote(String value) {
        String text = value == null ? "" : value.strip();
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                String inner = text.substring(1, text.length() - 1);
                if (inner.regionMatches(true, 0, JDBC_PREFIX, 0, JDBC_PREFIX.length())) {
                    return inner;
                }
            }
        }
        return text;
    }

    /** 去掉口令两侧成对的单/双引号（与 {@code SqlIrVisitor.visitConnectPassword} 同一口径）。 */
    private static String unquotePassword(String value) {
        String text = value == null ? "" : value.strip();
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return text.substring(1, text.length() - 1);
            }
        }
        return text;
    }
}
