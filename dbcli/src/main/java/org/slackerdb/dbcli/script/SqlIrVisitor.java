package org.slackerdb.dbcli.script;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.misc.Interval;
import org.slackerdb.dbcli.common.UserNumber;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.slackerdb.dbcli.antlr.SQLLexer;
import org.slackerdb.dbcli.antlr.SQLParser;
import org.slackerdb.dbcli.antlr.SQLParserBaseVisitor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SQL 命名空间的 IR 构造器：把 ANTLR 语法树遍历成 {@code Map<String, Object>} 形状的 IR。
 *
 * <p>两点必须说清楚：</p>
 * <ul>
 *   <li>它**不是解释器**：不碰数据库、不求值、不维护作用域，只有 {@link #visitProg} 是入口，
 *       结果通过 4 个 getter 暴露（用"单槽"字段 + getter，而不是返回值层层上传）；</li>
 *   <li>每个 {@code visitXxx} 末尾都检查 {@code ctx.exception}：非 null 时置
 *       {@code errorCode}/{@code errorMsg}（多数方法是 -1，SSH/JOB/COMPARE 是 1：
 *       这两组错误的恢复语义不同，因此不强行统一）。</li>
 * </ul>
 *
 * <p><b>范围</b>：只实现下列命令 —— {@code EXIT QUIT USE(仅 SQL) SLEEP START
 * HOST HELP SET SPOOL ECHO ASSERT WHENEVER COMPARE JOB SSH CONNECT DISCONNECT
 * SESSION} 与全部 SQL 语句。{@code _IF/_ENDIF/_LOOP/{@code {% %}}/_DATA/_MONITOR/_<PLUGIN>/
 * 历史上的"加载"类命令（驱动/脚本/插件/映射文件）与 {@code _USE} 都不在语法里，因此这里没有对应 visit 方法。</p>
 *
 * <p><b>{@code _ASSERT} 的两种外观</b>：
 * {@code _ASSERT {% 取值器 比较符 字面量 %}} 与裸形态 {@code _ASSERT 取值器 比较符 字面量}，
 * IR 只负责"取出文本"，语义交给 {@code assert} 包的固定形态求值器。
 * 花括号外观的 {@code expression} 保留 {@code {%} 之后、{@code %}} 之前的那两个空格
 * （先去掉花括号再取原文，空格是契约的一部分，测试逐字节断言）。</p>
 */
public class SqlIrVisitor extends SQLParserBaseVisitor<Object> {

    /** ECHO 块的结束标记 {@code \n *echo\s+off}。 */
    /** {@code ECHO} 块结尾的 {@code echo off}：允许它就在块的开头（= 空正文）。 */
    private static final Pattern ECHO_OFF = Pattern.compile("(?:^|\\n)[ \\t]*echo[ \\t]+off",
            Pattern.CASE_INSENSITIVE);

    /** 词法符号表。 */
    private final TokenStream tokens;

    /** 默认解析空间（大写）；命名空间只有 SQL 一个取值，此字段保持既有 IR 形状。 */
    private final String defaultNameSpace;

    /** 解析是否正常完成。 */
    private boolean isFinished = true;

    /** 返回去掉了注释信息的解析结果。 */
    private Map<String, Object> parsedObject = null;

    /** 如果成功，返回 0；如果失败，返回 -1（SSH/JOB/COMPARE 返回 1）。 */
    private int errorCode = 0;

    /** 如果成功，返回空；如果失败，返回解析的错误提示信息。 */
    private String errorMsg = null;

    /**
     * 数值参数（SFTP 权限位 / uid / gid / 大小、SSH 端口、_EXIT/_WHENEVER 的退出码）解析失败时的可读消息。
     *
     * <p>这些位置在语法上是通用的 {@code SSH_EXPRESSION}/{@code INT}（见 .g4 的说明），"是不是合法整数"
     * 只能在访问器里判定。判定失败时**不能**把异常扔出去：脚本里写错一个数字就会让进程
     * 吐出 Java 栈并以 255 退出（退出码 255 的语义是"实现缺陷"，不该由脚本笔误触发），
     * 因此这里走访问器既有的错误通道（{@code errorCode}/{@code errorMsg}），
     * 由引擎按普通脚本错误处理（一行可读错误 + 错误计数 + {@code _WHENEVER} 语义）。</p>
     */
    private String argumentError = null;

    public SqlIrVisitor(TokenStream tokens, String defaultNameSpace) {
        this.tokens = tokens;
        this.defaultNameSpace = defaultNameSpace == null ? null : defaultNameSpace.toUpperCase();
    }

    public boolean isFinished() {
        return this.isFinished;
    }

    public Map<String, Object> getParsedObject() {
        return this.parsedObject;
    }

    public int getErrorCode() {
        return this.errorCode;
    }

    public String getErrorMsg() {
        return this.errorMsg;
    }

    public String getDefaultNameSpace() {
        return this.defaultNameSpace;
    }

    // ------------------------------------------------------------------ 内部工具

    /** 子访问器的返回值三元组：{@code (parsedObject, errorCode, errorMsg)}。 */
    private record SubResult(Map<String, Object> parsedObject, int errorCode, String errorMsg) {
    }

    /** JOB 的 "(jobName, param)" 解析结果。 */
    private record JobParams(String jobName, Map<String, Object> param) {
    }

    private static Map<String, Object> object() {
        return new LinkedHashMap<>();
    }

    /** 通用收尾：先归零，再看 {@code ctx.exception}（非 null 时 -1）。 */
    private static SubResult tail(ParserRuleContext ctx, Map<String, Object> parsedObject) {
        int errorCode = 0;
        String errorMsg = null;
        if (ctx.exception != null) {
            errorCode = -1;
            errorMsg = ctx.exception.getMessage();
        }
        return new SubResult(parsedObject, errorCode, errorMsg);
    }

    /** 同样的收尾，但错误码用 1（SSH/JOB/COMPARE 这一组的约定）。 */
    private static SubResult tailOne(ParserRuleContext ctx, Map<String, Object> parsedObject) {
        int errorCode = 0;
        String errorMsg = null;
        if (ctx.exception != null) {
            errorCode = 1;
            errorMsg = ctx.exception.getMessage();
        }
        return new SubResult(parsedObject, errorCode, errorMsg);
    }

    /** 把三元组写回"单槽"字段。 */
    private Map<String, Object> apply(SubResult result) {
        this.parsedObject = result.parsedObject();
        this.errorCode = result.errorCode();
        this.errorMsg = result.errorMsg();
        return result.parsedObject();
    }

    /** 取节点的原文区间。 */
    private static Interval getSourceInterval(ParserRuleContext ctx) {
        return ctx.getSourceInterval();
    }

    /** 取区间内指定通道的 token 文本（默认通道之外的 token 会被跳过）。 */
    private String getText(Interval interval, int channel) {
        StringBuilder builder = new StringBuilder();
        for (int index = interval.a; index <= interval.b; index++) {
            Token token = this.tokens.get(index);
            if (token.getChannel() == channel) {
                builder.append(token.getText());
            }
        }
        return builder.toString();
    }


    /**
     * SQL 语句文本 = 起始关键字原文 + 该语句区间内 {@code SQLSTATEMENT_CHANNEL} 通道的全文
     * （空白与换行都在该通道上，因此内部换行被保留）。
     */
    private String statementText(ParserRuleContext ctx, String headText) {
        return headText + getText(getSourceInterval(ctx), SQLLexer.SQLSTATEMENT_CHANNEL);
    }

    /** 先剥双引号、再剥单引号（两端都剥，剥到不再是引号为止）。 */
    private static String unquote(String text) {
        return stripChars(stripChars(text, '"'), '\'');
    }

    /** 从两端去掉全部指定字符。 */
    private static String stripChars(String text, char target) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == target) {
            start++;
        }
        while (end > start && text.charAt(end - 1) == target) {
            end--;
        }
        return text.substring(start, end);
    }

    /** 两端是成对的同一引号时剥掉一层（单双引号都试）。 */
    private static String trimMatchingQuote(String text) {
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return text.substring(1, text.length() - 1);
            }
        }
        return text;
    }

    /**
     * SFTP 动作里的整数参数：从 {@code SSH_EXPRESSION} 取文本并解析。
     *
     * <p>语法上这些位置是通用的 {@code SSH_EXPRESSION}（词法里它排在 {@code SSH_INT} 之前，
     * 所以 "750"、"0" 这类纯数字也是一个表达式），因此"是不是合法整数"只能在访问器里判定。
     * 非法值给一条可读错误（走 {@link #argumentError} 这条错误通道，不抛异常），
     * 调用方据此把 {@code errorCode} 置 1。</p>
     *
     * @param expression 数值参数节点
     * @param label      IR 里的字段名（错误文案用，例如 {@code "uid"}）
     * @param radix      进制：权限位按八进制书写（chmod/mkdir），uid/gid/大小按十进制
     * @return 解析出的整数；文本不是该进制下的合法整数时返回 null（并记下可读消息）
     */
    private Integer sftpNumber(TerminalNode expression, String label, int radix) {
        try {
            UserNumber.Rule rule = radix == 8
                    ? UserNumber.Rule.FILE_MODE : UserNumber.Rule.NON_NEGATIVE_INTEGER;
            return UserNumber.integer("SFTP " + label, expression.getText(), rule, radix);
        } catch (UserNumber.InvalidNumberException invalidNumber) {
            // 只记消息、不抛异常：脚本笔误是"脚本级错误"，不是"实现缺陷"。
            // 多个参数都非法时保留**第一个**（从左往右读，先报最左边的那个笔误）
            noteArgumentError(invalidNumber.getMessage());
            return null;
        }
    }

    /**
     * 通用整数参数：解析失败时记一条可读错误并返回 null（调用方据此把这条语句判成脚本错误）。
     *
     * <p>解析与文案都走 {@link UserNumber}：与 {@code _SET}/{@code _JOB}/{@code _SLEEP} 同一套规则词汇，
     * 因此 {@code _exit 99999999999} 与 {@code _ssh … port abc} 的错误句式完全一致。</p>
     *
     * @param node  语法文本节点
     * @param label 错误文案里的参数名（例如 {@code SSH port}）
     * @param rule  规则（整数/端口…），同时决定文案与范围校验
     */
    private Integer intArgument(TerminalNode node, String label, UserNumber.Rule rule) {
        if (node == null) {
            noteArgumentError("Invalid " + label + " []: expected " + rule.description() + ".");
            return null;
        }
        try {
            return UserNumber.integer(label, node.getText(), rule, 10);
        } catch (UserNumber.InvalidNumberException invalidNumber) {
            noteArgumentError(invalidNumber.getMessage());
            return null;
        }
    }

    /** 记下**第一条**数值参数错误（保留最左边的笔误，后来的不覆盖）。 */
    private void noteArgumentError(String message) {
        if (this.argumentError == null) {
            this.argumentError = message;
        }
    }

    /** 把"数值参数不合法"的结论写回结果（语句本身是完整的，只把 errorCode 置 1）。 */
    private SubResult withArgumentError(SubResult result, Map<String, Object> parsedObject) {
        return this.argumentError == null ? result
                : new SubResult(parsedObject, 1, this.argumentError);
    }

    /**
     * SFTP 的权限位：按八进制校验（因此 "750" 合法、"8" 这种不是八进制数字的值会被拒），
     * 但返回**脚本原文**——IR 的 {@code fileMod}/{@code dirMod} 仍是"八进制字符串"这一既有契约，
     * 由分派器按 8 进制还原成 int。
     */
    private String sftpMode(TerminalNode expression, String label) {
        // 权限位是八进制且必须落在 0..07777（负数/超范围都不是权限）
        sftpNumber(expression, label, 8);
        return expression.getText();
    }

    /** JOB 的 {@code (jobName, param)}：第 0 个表达式是作业名，其余按 KEY VALUE 成对。 */
    private static JobParams jobParams(List<TerminalNode> expressions) {
        String jobName = null;
        String paramKey = null;
        Map<String, Object> param = object();
        int position = 0;
        for (TerminalNode expression : expressions) {
            if (position == 0) {
                jobName = expression.getText().strip();
            } else if (paramKey == null) {
                paramKey = expression.getText().strip();
            } else {
                param.put(paramKey, expression.getText().strip());
                paramKey = null;
            }
            position++;
        }
        return new JobParams(jobName, param);
    }

    // ---------------------------------------------------------------------- 入口

    /**
     * 访问语法树的程序节点。
     *
     * <p>只做 {@code visitChildren}，4 个结果通过 getter 暴露（{@link SqlAnalyzer} 负责读取）。</p>
     */
    @Override
    public Object visitProg(SQLParser.ProgContext ctx) {
        // 每次 analyze() 都是一次全新的结论：把上一次（或"还没走到任何子节点就失败"时）留下的
        // 粘性状态清掉。访问器是"单槽字段"设计，粘性状态会让一次失败污染下一次分析。
        this.isFinished = true;
        this.parsedObject = null;
        this.errorCode = 0;
        this.errorMsg = null;
        this.argumentError = null;
        visitChildren(ctx);
        return null;
    }

    /**
     * 这次分析的**不可变**结论（{@link SqlAnalyzer} 只从它读取，不再逐个 getter 取）。
     *
     * <p>字段仍是"单槽"（一次访问对应一次分析），但对外只暴露这一份快照：
     * 调用方拿到之后不会被后续 visit 覆盖。</p>
     */
    public record IrResult(boolean isFinished, Map<String, Object> parsedObject, int errorCode, String errorMsg) {
    }

    /** 当前分析的快照（在 {@code visit} 返回之后调用）。 */
    public IrResult result() {
        return new IrResult(isFinished, parsedObject, errorCode, errorMsg);
    }

    @Override
    public Object visitCommand(SQLParser.CommandContext ctx) {
        return visitChildren(ctx);
    }

    @Override
    public Object visitSql(SQLParser.SqlContext ctx) {
        return visitChildren(ctx);
    }

    // ----------------------------------------------------------------- 基础命令

    @Override
    public Object visitExit(SQLParser.ExitContext ctx) {
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "EXIT");
        // 退出码超出 int（例如 `_exit 99999999999`）走可读错误通道，而不是抛 NumberFormatException
        Integer exitValue = ctx.INT() == null ? Integer.valueOf(0)
                : intArgument(ctx.INT(), "exit code", UserNumber.Rule.INTEGER);
        parsedObject.put("exitValue", exitValue == null ? 0 : exitValue);
        return apply(withArgumentError(tail(ctx, parsedObject), parsedObject));
    }

    @Override
    public Object visitQuit(SQLParser.QuitContext ctx) {
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "QUIT");
        Integer exitValue = ctx.INT() == null ? Integer.valueOf(0)
                : intArgument(ctx.INT(), "exit code", UserNumber.Rule.INTEGER);
        parsedObject.put("exitValue", exitValue == null ? 0 : exitValue);
        return apply(withArgumentError(tail(ctx, parsedObject), parsedObject));
    }

    @Override
    public Object visitSleep(SQLParser.SleepContext ctx) {
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "SLEEP");
        // 缺参数时只在存在才记录（不因为取缺失参数而抛异常）
        if (ctx.SLEEP_EXPRESSION() != null) {
            parsedObject.put("sleepTime", ctx.SLEEP_EXPRESSION().getText());
        }
        return apply(tail(ctx, parsedObject));
    }

    @Override
    public Object visitDisconnect(SQLParser.DisconnectContext ctx) {
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "DISCONNECT");
        return apply(tail(ctx, parsedObject));
    }

    @Override
    public Object visitHelp(SQLParser.HelpContext ctx) {
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "HELP");
        parsedObject.put("topic", "");
        if (ctx.HELP_COMMAND() != null) {
            parsedObject.put("topic", ctx.HELP_COMMAND().getText().strip().toUpperCase());
        }
        return apply(tail(ctx, parsedObject));
    }

    // -------------------------------------------------------------------- CONNECT

    @Override
    public Object visitConnect(SQLParser.ConnectContext ctx) {
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "CONNECT");

        // 用户信息 + 主机/端口/服务/查询参数（带 scheme 的旧写法也走这条，由引擎拒绝）
        if (ctx.connectjdbc() != null) {
            parsedObject.putAll(visitConnectjdbc(ctx.connectjdbc()).parsedObject());
        }
        return apply(tail(ctx, parsedObject));
    }

    @Override
    public SubResult visitConnectjdbc(SQLParser.ConnectjdbcContext ctx) {
        Map<String, Object> parsedObject = object();
        // 连接串原文（不含命令字与结尾 ';'）：IR 里的 host/port 等字段是被拆开的结果，
        // 拼不回使用者的原话，而"无效连接串"的错误必须原样回显它。
        // ctx.getText() 从命令字开始（隐藏通道的空白不参与拼接），去掉命令字就是连接串。
        String command = ctx.CONNECT().getText();
        parsedObject.put("connectString", ctx.getText().substring(command.length()));
        if (ctx.connectUserInfo() != null) {
            parsedObject.putAll(visitConnectUserInfo(ctx.connectUserInfo()).parsedObject());
        }
        if (ctx.connectDriver() != null) {
            parsedObject.putAll(visitConnectDriver(ctx.connectDriver()).parsedObject());
        }
        if (ctx.connectDriverSchema() != null) {
            parsedObject.putAll(visitConnectDriverSchema(ctx.connectDriverSchema()).parsedObject());
        }
        if (ctx.connectHost() != null) {
            parsedObject.putAll(visitConnectHost(ctx.connectHost()).parsedObject());
        }
        if (ctx.connectPort() != null) {
            parsedObject.putAll(visitConnectPort(ctx.connectPort()).parsedObject());
        }
        if (ctx.connectService() != null) {
            parsedObject.putAll(visitConnectService(ctx.connectService()).parsedObject());
        }
        if (ctx.connectParameters() != null) {
            parsedObject.putAll(visitConnectParameters(ctx.connectParameters()).parsedObject());
        }
        return tail(ctx, parsedObject);
    }

    @Override
    public SubResult visitConnectService(SQLParser.ConnectServiceContext ctx) {
        Map<String, Object> parsedObject = object();
        if (!ctx.CONNECT_STRING().isEmpty()) {
            if (ctx.CONNECT_STRING().size() == 2) {
                parsedObject.put("service", ctx.CONNECT_STRING(0).getText() + ":" + ctx.CONNECT_STRING(1).getText());
            } else {
                parsedObject.put("service", ctx.CONNECT_STRING(0).getText());
            }
        }
        return tail(ctx, parsedObject);
    }

    @Override
    public SubResult visitConnectUserInfo(SQLParser.ConnectUserInfoContext ctx) {
        Map<String, Object> parsedObject = object();
        // 用户名
        if (ctx.connectUser() != null) {
            parsedObject.putAll(visitConnectUser(ctx.connectUser()).parsedObject());
        }
        // password
        if (ctx.connectPassword() != null) {
            parsedObject.putAll(visitConnectPassword(ctx.connectPassword()).parsedObject());
        }
        return tail(ctx, parsedObject);
    }

    @Override
    public SubResult visitConnectUser(SQLParser.ConnectUserContext ctx) {
        Map<String, Object> parsedObject = object();
        if (ctx.CONNECT_STRING() != null) {
            parsedObject.put("username", ctx.CONNECT_STRING().getText());
        }
        return tail(ctx, parsedObject);
    }

    @Override
    public SubResult visitConnectPassword(SQLParser.ConnectPasswordContext ctx) {
        Map<String, Object> parsedObject = object();
        if (ctx.CONNECT_STRING() != null) {
            String password = ctx.CONNECT_STRING().getText().strip();
            if (password.startsWith("\"") && password.endsWith("\"")) {
                password = password.substring(1, password.length() - 1);
            } else if (password.startsWith("'") && password.endsWith("'")) {
                password = password.substring(1, password.length() - 1);
            }
            parsedObject.put("password", password);
        }
        return tail(ctx, parsedObject);
    }

    @Override
    public SubResult visitConnectDriver(SQLParser.ConnectDriverContext ctx) {
        Map<String, Object> parsedObject = object();
        if (ctx.JDBC() != null) {
            parsedObject.put("driver", "jdbc");
        }
        return tail(ctx, parsedObject);
    }

    @Override
    public SubResult visitConnectDriverSchema(SQLParser.ConnectDriverSchemaContext ctx) {
        Map<String, Object> parsedObject = object();
        if (ctx.CONNECT_STRING() != null) {
            parsedObject.put("driverSchema", ctx.CONNECT_STRING().getText());
        }
        return tail(ctx, parsedObject);
    }

    @Override
    public SubResult visitConnectHost(SQLParser.ConnectHostContext ctx) {
        Map<String, Object> parsedObject = object();
        parsedObject.put("host", ctx.getText());
        return tail(ctx, parsedObject);
    }

    @Override
    public SubResult visitConnectPort(SQLParser.ConnectPortContext ctx) {
        Map<String, Object> parsedObject = object();
        parsedObject.put("port", ctx.getText());
        return tail(ctx, parsedObject);
    }

    @Override
    public SubResult visitConnectParameters(SQLParser.ConnectParametersContext ctx) {
        Map<String, Object> parsedObject = object();
        if (!ctx.connectParameter().isEmpty()) {
            List<Map<String, Object>> parameters = new ArrayList<>();
            for (SQLParser.ConnectParameterContext parameter : ctx.connectParameter()) {
                SubResult result = visitConnectParameter(parameter);
                if (result.errorCode() != 0) {
                    return new SubResult(parsedObject, result.errorCode(), result.errorMsg());
                }
                parameters.add(result.parsedObject());
            }
            parsedObject.put("parameters", parameters);
        }
        return tail(ctx, parsedObject);
    }

    @Override
    public SubResult visitConnectParameter(SQLParser.ConnectParameterContext ctx) {
        Map<String, Object> parsedObject = object();
        if (ctx.connectParameterName() != null) {
            parsedObject.putAll(visitConnectParameterName(ctx.connectParameterName()).parsedObject());
        }
        if (ctx.connectParameterValue() != null) {
            parsedObject.putAll(visitConnectParameterValue(ctx.connectParameterValue()).parsedObject());
        }
        return tail(ctx, parsedObject);
    }

    @Override
    public SubResult visitConnectParameterName(SQLParser.ConnectParameterNameContext ctx) {
        Map<String, Object> parsedObject = object();
        parsedObject.put("parameterName", ctx.getText());
        return tail(ctx, parsedObject);
    }

    @Override
    public SubResult visitConnectParameterValue(SQLParser.ConnectParameterValueContext ctx) {
        Map<String, Object> parsedObject = object();
        parsedObject.put("parameterValue", ctx.getText());
        return tail(ctx, parsedObject);
    }

    @Override
    public Object visitExpression(SQLParser.ExpressionContext ctx) {
        // expression 子访问器返回 (expression, errorCode, errorMsg)；这里没有调用方，
        // 保留同样的"错误检查"语义并返回原文（expression 只被 disconnect 用到，而它不递归子节点）。
        String expression = ctx.getText();
        if (ctx.exception != null) {
            this.errorCode = -1;
            this.errorMsg = ctx.exception.getMessage();
        }
        return expression;
    }

    // ------------------------------------------------------------------- SESSION

    @Override
    public Object visitSession(SQLParser.SessionContext ctx) {
        String action = "";
        if (ctx.SESSION_SAVE() != null) {
            action = "SAVE";
        } else if (ctx.SESSION_RELEASE() != null) {
            action = "RELEASE";
        } else if (ctx.SESSION_RESTORE() != null) {
            action = "RESTORE";
        } else if (ctx.SESSION_SAVEURL() != null) {
            action = "SAVEURL";
        } else if (ctx.SESSION_SHOW() != null) {
            action = "SHOW";
        }

        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "SESSION");
        parsedObject.put("action", action);
        if (ctx.SESSION_NAME() != null) {
            parsedObject.put("sessionName", ctx.SESSION_NAME().getText());
        } else {
            parsedObject.put("sessionName", null);
        }
        return apply(tail(ctx, parsedObject));
    }

    // -------------------------------------------------------------------- ASSERT

    @Override
    public Object visitAssert(SQLParser.AssertContext ctx) {
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "ASSERT");

        // 外观一：{% ... %}（ASSERT_BRACED 把前导空白一起吃进 token，故先 strip 再剥花括号）
        // 外观二：裸固定形态（ASSERT_BODY，取值器 比较符 字面量）
        String expression = "";
        if (ctx.ASSERT_BRACED() != null) {
            String text = ctx.ASSERT_BRACED().getText().strip();
            if (text.startsWith("{%")) {
                text = text.substring(2);
            }
            if (text.endsWith("%}")) {
                text = text.substring(0, text.length() - 2);
            }
            // 剥掉花括号后**不再** strip，保留两端空格（契约的一部分）
            expression = text;
        } else if (!ctx.ASSERT_BODY().isEmpty()) {
            expression = ctx.ASSERT_BODY(0).getText().strip();
        }
        parsedObject.put("expression", expression);

        // 断言名（可选，逗号之后）
        if (ctx.ASSERT_COMMA() != null && !ctx.ASSERT_BODY().isEmpty()) {
            String assertName = ctx.ASSERT_BODY(ctx.ASSERT_BODY().size() - 1).getText().strip();
            if (assertName.startsWith("\"") && assertName.endsWith("\"")) {
                assertName = assertName.substring(1, assertName.length() - 1);
            }
            if (assertName.startsWith("'") && assertName.endsWith("'")) {
                assertName = assertName.substring(1, assertName.length() - 1);
            }
            parsedObject.put("assertName", assertName);
        } else {
            parsedObject.put("assertName", null);
        }
        return apply(tail(ctx, parsedObject));
    }

    // ---------------------------------------------------------------------- HOST

    @Override
    public Object visitHost(SQLParser.HostContext ctx) {
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "HOST");

        if (ctx.HOST_EXPRESSION() != null) {
            // 多行 `_HOST """ … """` 是"多条命令"：IR 里用 " & " 连接（既有契约，测试冻着它）。
            // 拆分因此要由引擎**按引号感知**地做（见 ScriptRunner.runHost）。
            String script = ctx.HOST_EXPRESSION().getText().strip();
            if (script.startsWith("\"\"\"")) {
                // 多行块写法：剥掉两端**全部**引号（""" 是块标记，不是命令的一部分）
                script = stripChars(script, '"');
                script = stripChars(script, '\'');
                script = stripChars(script, '\n');
                script = script.replace("\n", " & ");
            } else {
                // 单行写法：只剥**成对**的最外层引号。以前无条件 stripChars 会把
                // `echo "a & b"` 的收尾引号也吃掉（命令变成 `echo "a & b`）。
                script = trimMatchingQuote(script);
            }
            parsedObject.put("script", script);
        }
        return apply(tail(ctx, parsedObject));
    }

    // ------------------------------------------------------------------ WHENEVER

    @Override
    public Object visitWhenever(SQLParser.WheneverContext ctx) {
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "WHENEVER");

        if (ctx.WHENEVER_CONTINUE() != null) {
            parsedObject.put("action", "continue");
        }
        if (ctx.WHENEVER_EXIT() != null) {
            parsedObject.put("action", "exit");
        }
        if (ctx.WHENEVER_ERROR() != null) {
            parsedObject.put("condition", "error");
        }
        if (ctx.WHENEVER_EXITCODE() != null) {
            Integer exitCode = intArgument(ctx.WHENEVER_EXITCODE(), "whenever exit code", UserNumber.Rule.INTEGER);
            parsedObject.put("exitCode", exitCode == null ? 0 : exitCode);
        } else {
            parsedObject.put("exitCode", 0);
        }
        return apply(withArgumentError(tail(ctx, parsedObject), parsedObject));
    }

    // ---------------------------------------------------------------------- LOAD

    /**
     * {@code _LOAD JDBCDRIVER <jar>}（以及"目标不认识"的形态）。
     *
     * <p>IR：{@code name=LOAD}；{@code target} 是目标原文（{@code JDBCDRIVER} / 别的词），
     * {@code file} 只有 JDBCDRIVER 形态才有。两个字段都**只做文本搬运**：
     * 目标认不认识、文件存在不存在由引擎（{@code LoadCommand}）判定。</p>
     */
    @Override
    public Object visitLoad(SQLParser.LoadContext ctx) {
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "LOAD");
        if (ctx.loadJdbcDriver() != null) {
            parsedObject.put("target", "JDBCDRIVER");
            parsedObject.put("file", joinExpressions(ctx.loadJdbcDriver().LOAD_EXPRESSION()));
        } else if (ctx.loadTarget() != null) {
            parsedObject.put("target", joinExpressions(ctx.loadTarget().LOAD_EXPRESSION()));
        }
        return apply(tail(ctx, parsedObject));
    }

    /**
     * 把 {@code _LOAD} 的取值 token 拼回一段文本：未加引号的文件名里如果有空格，
     * 词法会把它拆成多个 token，这里按单个空格拼回；整段加引号时只有一个 token（引号在此剥掉）。
     */
    private static String joinExpressions(List<TerminalNode> expressions) {
        List<String> parts = new ArrayList<>(expressions.size());
        for (TerminalNode expression : expressions) {
            parts.add(expression.getText());
        }
        return trimMatchingQuote(String.join(" ", parts).strip());
    }

    // --------------------------------------------------------------------- SPOOL

    @Override
    public Object visitSpool(SQLParser.SpoolContext ctx) {
        String content = "";
        if (ctx.SPOOL_EXPRESSION() != null) {
            content = ctx.SPOOL_EXPRESSION().getText().strip();
            if (content.startsWith("'") && content.endsWith("'")) {
                content = content.substring(1, content.length() - 1);
            } else if (content.startsWith("\"") && content.endsWith("\"")) {
                content = content.substring(1, content.length() - 1);
            }
        }
        // SPOOL_OFF 在词法里排在 SPOOL_EXPRESSION 之前（等长先声明者胜），因此 `_spool off`
        // 现在真的走这个分支。**保留原文大小写**：IR 的契约是"文件字段就是脚本里写的那一串"，
        // 行为上引擎按大小写无关把它当"停止"。
        if (ctx.SPOOL_OFF() != null) {
            content = ctx.SPOOL_OFF().getText();
        }
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "SPOOL");
        // 没有参数（`_spool`）= 停止当前 spool，file 是空串
        parsedObject.put("file", content);
        return apply(tail(ctx, parsedObject));
    }

    // ---------------------------------------------------------------------- ECHO

    @Override
    public Object visitEcho(SQLParser.EchoContext ctx) {
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "ECHO");

        // 删除 BLOCK 末尾的 ECHO OFF（用 Pattern.split(..., -1) 保留尾部的空串，
        // 否则长度判定会错）
        String block = ctx.EchoBlock() == null ? null : ctx.EchoBlock().getText();
        String[] blocks = block == null ? new String[0] : ECHO_OFF.split(block, -1);
        if (blocks.length > 1) {
            parsedObject.put("block", blocks[0]);
        } else {
            this.isFinished = false;
        }

        // 需要输出的文件名：ECHO token 第一个空格之后的内容
        String echoText = ctx.ECHO().getText();
        String param = "";
        int spaceIndex = echoText.indexOf(' ');
        if (spaceIndex >= 0) {
            param = echoText.substring(spaceIndex + 1);
            int lineEnd = param.indexOf('\n');
            if (lineEnd >= 0) {
                param = param.substring(0, lineEnd);
            }
            int carriageReturn = param.indexOf('\r');
            if (carriageReturn >= 0) {
                param = param.substring(0, carriageReturn);
            }
        }
        parsedObject.put("param", param.strip());
        return apply(tail(ctx, parsedObject));
    }

    // ----------------------------------------------------------------------- SET

    @Override
    public Object visitSet(SQLParser.SetContext ctx) {
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "SET");

        List<String> expressions = new ArrayList<>();
        for (TerminalNode expression : ctx.SET_EXPRESSION()) {
            expressions.add(expression.getText());
        }
        if (!expressions.isEmpty()) {
            parsedObject.put("optionName", expressions.get(0));
        }
        if (expressions.size() >= 2) {
            String optionValue = String.join(" ", expressions.subList(1, expressions.size()));
            if ((optionValue.startsWith("\"") && optionValue.endsWith("\""))
                    || (optionValue.startsWith("'") && optionValue.endsWith("'"))) {
                optionValue = optionValue.substring(1, optionValue.length() - 1);
            }
            parsedObject.put("optionValue", optionValue);
        }
        return apply(tail(ctx, parsedObject));
    }

    // --------------------------------------------------------------------- START

    @Override
    public Object visitStart(SQLParser.StartContext ctx) {
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "START");

        // 第一个参数为脚本名称，随后的参数为运行参数
        List<String> argv = new ArrayList<>();
        List<TerminalNode> expressions = ctx.START_EXPRESSION();
        if (expressions.isEmpty()) {
            parsedObject.put("script", null);
        } else {
            parsedObject.put("script", expressions.get(0).getText());
            for (int index = 1; index < expressions.size(); index++) {
                argv.add(expressions.get(index).getText());
            }
        }
        parsedObject.put("argv", argv);
        return apply(tail(ctx, parsedObject));
    }

    // ------------------------------------------------------------------ SQL 语句

    @Override
    public Object visitSqlCreate(SQLParser.SqlCreateContext ctx) {
        String statement = statementText(ctx, ctx.SQL_CREATE().getText());
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "CREATE");
        parsedObject.put("statement", statement);
        SubResult result = tail(ctx, parsedObject);
        markSqlFinished(ctx.SQL_END());
        return apply(result);
    }

    @Override
    public Object visitSqlDrop(SQLParser.SqlDropContext ctx) {
        String statement = statementText(ctx, ctx.SQL_DROP().getText());
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "DROP");
        parsedObject.put("statement", statement);
        SubResult result = tail(ctx, parsedObject);
        markSqlFinished(ctx.SQL_END());
        return apply(result);
    }

    @Override
    public Object visitSqlInsert(SQLParser.SqlInsertContext ctx) {
        String statement = statementText(ctx, ctx.SQL_INSERT().getText());
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "INSERT");
        parsedObject.put("statement", statement);
        SubResult result = tail(ctx, parsedObject);
        markSqlFinished(ctx.SQL_END());
        return apply(result);
    }

    @Override
    public Object visitSqlUpdate(SQLParser.SqlUpdateContext ctx) {
        String statement = statementText(ctx, ctx.SQL_UPDATE().getText());
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "UPDATE");
        parsedObject.put("statement", statement);
        SubResult result = tail(ctx, parsedObject);
        markSqlFinished(ctx.SQL_END());
        return apply(result);
    }

    @Override
    public Object visitSqlDelete(SQLParser.SqlDeleteContext ctx) {
        String statement = statementText(ctx, ctx.SQL_DELETE().getText());
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "DELETE");
        parsedObject.put("statement", statement);
        SubResult result = tail(ctx, parsedObject);
        markSqlFinished(ctx.SQL_END());
        return apply(result);
    }

    @Override
    public Object visitSqlSelect(SQLParser.SqlSelectContext ctx) {
        String statement = statementText(ctx, ctx.SQL_SELECT().getText());
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "SELECT");
        parsedObject.put("statement", statement);
        SubResult result = tail(ctx, parsedObject);
        markSqlFinished(ctx.SQL_END());
        return apply(result);
    }

    @Override
    public Object visitSqlReplace(SQLParser.SqlReplaceContext ctx) {
        String statement = statementText(ctx, ctx.SQL_REPLACE().getText());
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "REPLACE");
        parsedObject.put("statement", statement);

        // REPLACE 与别的 SQL 语句不同：缺 SQL_END 时把 errorCode 也置 -1
        SubResult result = tail(ctx, parsedObject);
        int errorCode = result.errorCode();
        String errorMsg = result.errorMsg();
        if (ctx.exception != null) {
            this.isFinished = false;
        }
        if (ctx.SQL_END() == null || (!";".equals(ctx.SQL_END().getText()) && !"\n/".equals(ctx.SQL_END().getText()))) {
            errorCode = -1;
            this.isFinished = false;
        }
        return apply(new SubResult(parsedObject, errorCode, errorMsg));
    }

    /** 如果 SQL 没有结尾，要返回"没有结尾"的标志。 */
    private void markSqlFinished(TerminalNode sqlEnd) {
        if (sqlEnd == null || (!";".equals(sqlEnd.getText()) && !"\n/".equals(sqlEnd.getText()))) {
            this.isFinished = false;
        }
    }

    @Override
    public Object visitSqlCommit(SQLParser.SqlCommitContext ctx) {
        String statement = statementText(ctx, ctx.SQL_COMMIT().getText());
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "COMMIT");
        parsedObject.put("statement", statement);
        SubResult result = tail(ctx, parsedObject);
        markSqlFinished(ctx.SQL_END());
        return apply(result);
    }

    @Override
    public Object visitSqlRollback(SQLParser.SqlRollbackContext ctx) {
        String statement = statementText(ctx, ctx.SQL_ROLLBACK().getText());
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "ROLLBACK");
        parsedObject.put("statement", statement);
        SubResult result = tail(ctx, parsedObject);
        markSqlFinished(ctx.SQL_END());
        return apply(result);
    }

    /**
     * 通用 SQL 语句（WITH / ALTER / TRUNCATE / VACUUM / ANALYZE / EXPLAIN / SET / SHOW /
     * GRANT / REVOKE / CALL / COMMENT / REFRESH / COPY / DO）。
     *
     * <p>IR 与其它 SQL 语句同构，但 name 统一是 {@code "SQL"}：语句的语义由服务端决定，
     * 客户端只做"认出 + 切分 + 转发"。**不按关键字取 name** 是有意为之——
     * 例如 {@code SET} 与 {@code _SET} 命令的 IR 名同为 "SET"，按关键字取名会让一条 SQL 被
     * 当成 {@code _SET} 命令执行。</p>
     */
    @Override
    public Object visitSqlGeneric(SQLParser.SqlGenericContext ctx) {
        String statement = statementText(ctx, ctx.SQL_GENERIC().getText());
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "SQL");
        parsedObject.put("statement", statement);
        SubResult result = tail(ctx, parsedObject);
        markSqlFinished(ctx.SQL_END());
        return apply(result);
    }

    /**
     * 事务形式的 {@code BEGIN;}：语句原文是关键字本身（词法把关键字后面的 ';' 也收进了 token，
     * 这里把它切掉——发给服务端的语句不带终结符，与其它 SQL 语句一致）。
     */
    @Override
    public Object visitSqlBeginTxn(SQLParser.SqlBeginTxnContext ctx) {
        String tokenText = ctx.SQL_BEGIN_TXN().getText();
        int semicolon = tokenText.indexOf(';');
        String head = (semicolon >= 0 ? tokenText.substring(0, semicolon) : tokenText).strip();
        String statement = statementText(ctx, head);
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "BEGIN");
        parsedObject.put("statement", statement);
        SubResult result = tail(ctx, parsedObject);
        return apply(result);
    }

    @Override
    public Object visitSqlDeclare(SQLParser.SqlDeclareContext ctx) {
        String statement = null;
        Map<String, Object> parsedObject = object();
        if (ctx.SQL_DECLARE() != null) {
            statement = statementText(ctx, ctx.SQL_DECLARE().getText());
            parsedObject.put("name", "DECLARE");
            parsedObject.put("statement", statement);
        }
        if (ctx.SQL_BEGIN() != null) {
            statement = statementText(ctx, ctx.SQL_BEGIN().getText());
            parsedObject.put("name", "BEGIN");
            parsedObject.put("statement", statement);
        }
        SubResult result = tail(ctx, parsedObject);
        if (ctx.SQL_SLASH() == null || !"\n/".equals(ctx.SQL_SLASH().getText())) {
            this.isFinished = false;
        }
        return apply(result);
    }

    @Override
    public Object visitSqlCreateProcedure(SQLParser.SqlCreateProcedureContext ctx) {
        String statement = statementText(ctx, ctx.SQL_CREATE_PROCEDURE().getText());
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "PROCEDURE");
        parsedObject.put("statement", statement);
        SubResult result = tail(ctx, parsedObject);
        if (ctx.SQL_SLASH() == null || !"\n/".equals(ctx.SQL_SLASH().getText())) {
            this.isFinished = false;
        }
        return apply(result);
    }

    // ---------------------------------------------------------------------- JOB

    @Override
    public Object visitJob(SQLParser.JobContext ctx) {
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "JOB");

        if (ctx.JOB_MANGER() != null) {
            if (ctx.JOB_ON() != null) {
                parsedObject.put("action", "startJobmanager");
            }
            if (ctx.JOB_OFF() != null) {
                parsedObject.put("action", "stopJobmanager");
            }
        } else if (ctx.JOB_SHOW() != null) {
            parsedObject.put("action", "show");
            parsedObject.put("jobName", ctx.JOB_EXPRESSION(0).getText().strip());
        } else if (ctx.JOB_WAIT() != null) {
            parsedObject.put("action", "wait");
            JobParams params = jobParams(ctx.JOB_EXPRESSION());
            parsedObject.put("jobName", params.jobName());
            parsedObject.put("param", params.param());
        } else if (ctx.JOB_SHUTDOWN() != null) {
            parsedObject.put("action", "shutdown");
            parsedObject.put("jobName", ctx.JOB_EXPRESSION(0).getText().strip());
        } else if (ctx.JOB_ABORT() != null) {
            parsedObject.put("action", "abort");
            parsedObject.put("jobName", ctx.JOB_EXPRESSION(0).getText().strip());
        } else if (ctx.JOB_START() != null) {
            parsedObject.put("action", "start");
            parsedObject.put("jobName", ctx.JOB_EXPRESSION(0).getText().strip());
        } else if (ctx.JOB_TIMER() != null) {
            parsedObject.put("action", "timer");
            parsedObject.put("timerPoint", ctx.JOB_EXPRESSION(0).getText().strip());
        } else if (ctx.JOB_SET() != null) {
            parsedObject.put("action", "set");
            JobParams params = jobParams(ctx.JOB_EXPRESSION());
            parsedObject.put("jobName", params.jobName());
            parsedObject.put("param", params.param());
        } else if (ctx.JOB_CREATE() != null) {
            parsedObject.put("action", "create");
            JobParams params = jobParams(ctx.JOB_EXPRESSION());
            parsedObject.put("jobName", params.jobName());
            parsedObject.put("param", params.param());
        }

        // 处理错误信息（这一组用错误码 1，并置 isFinished=false）
        SubResult result = tailOne(ctx, parsedObject);
        if (ctx.exception != null) {
            this.isFinished = false;
        }
        return apply(result);
    }

    // ---------------------------------------------------------------------- SSH

    @Override
    public Object visitSsh(SQLParser.SshContext ctx) {
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "SSH");

        if (ctx.SSH_SAVE() != null) {
            parsedObject.put("action", "save");
            parsedObject.put("sessionName", ctx.SSH_EXPRESSION().isEmpty()
                    ? null : ctx.SSH_EXPRESSION(0).getText());
        } else if (ctx.SSH_RESTORE() != null) {
            parsedObject.put("action", "restore");
            parsedObject.put("sessionName", ctx.SSH_EXPRESSION().isEmpty()
                    ? null : ctx.SSH_EXPRESSION(0).getText());
        } else if (ctx.SSH_SET() != null) {
            parsedObject.put("action", "set");
            if (ctx.SSH_EXPRESSION().size() >= 2) {
                String option = trimMatchingQuote(ctx.SSH_EXPRESSION(0).getText());
                // 这里检查 value（而不是重复检查 option：那样两个分支等价，是死逻辑）
                String value = trimMatchingQuote(ctx.SSH_EXPRESSION(1).getText());
                parsedObject.put("option", option);
                parsedObject.put("value", value);
            }
        } else if (ctx.SSH_DISCONNECT() != null) {
            parsedObject.put("action", "disconnect");
        } else if (ctx.SSH_EXECUTE() != null) {
            parsedObject.put("action", "execute");
            parsedObject.put("command", "");
            List<String> commands = new ArrayList<>();
            for (TerminalNode expression : ctx.SSH_EXPRESSION()) {
                commands.add(expression.getText());
            }
            String command = String.join(" ", commands).strip();
            if ((command.startsWith("\"") && !command.endsWith("\""))
                    || (command.startsWith("'") && !command.endsWith("'"))) {
                // 括号不闭合
                this.isFinished = false;
                return apply(new SubResult(parsedObject, 1, "Missing <EOF> un-closed qutote string."));
            }
            if (command.startsWith("\"") && command.endsWith("\"")) {
                command = command.substring(1, command.length() - 1);
            }
            if (command.startsWith("'") && command.endsWith("'")) {
                command = command.substring(1, command.length() - 1);
            }
            boolean pairQuote = true;
            for (char quoteChar : new char[] {'"', '\''}) {
                for (int index = 0; index < command.length(); index++) {
                    if (command.charAt(index) == quoteChar) {
                        pairQuote = !pairQuote;
                    }
                }
                if (!pairQuote) {
                    // 引号配对失败
                    this.isFinished = false;
                    return apply(new SubResult(parsedObject, 1, "Missing <EOF> un-closed qutote string."));
                }
            }
            parsedObject.put("command", command);
        } else if (ctx.SSH_CONNECT() != null) {
            parsedObject.put("action", "connect");
            String sshHost = ctx.SSH_EXPRESSION(0).getText();
            sshHost = trimMatchingQuote(sshHost);
            parsedObject.put("host", sshHost);
            if (ctx.SSH_USER() != null) {
                parsedObject.put("user", ctx.SSH_EXPRESSION(1).getText());
            }
            if (ctx.SSH_PASSWORD() != null) {
                parsedObject.put("password", ctx.SSH_EXPRESSION(2).getText());
            }
            if (ctx.SSH_KEYFILE() != null) {
                String keyFileName = ctx.SSH_EXPRESSION(2).getText();
                if (keyFileName.startsWith("\"") && keyFileName.endsWith("\"")) {
                    keyFileName = keyFileName.substring(1, keyFileName.length() - 1);
                }
                parsedObject.put("keyFile", keyFileName);
            }
            // PORT 是**可选**子句：没写时 IR 里干脆没有 port 键，由分派器用
            // SshConnectOptions.DEFAULT_PORT（22）兜底 —— 这样既有脚本的 IR 契约一字不变。
            // 端口取"口令/私钥之后的那一个表达式"：纯数字时它就是一个 SSH_EXPRESSION（见 .g4 里的说明），
            // 非数字（例如 PORT abc）在这里收敛成"访问器异常"，由引擎按错误语句处理。
            if (ctx.SSH_PORT() != null) {
                // 端口不是合法整数时（`PORT abc`）走可读错误通道，而不是抛出 NumberFormatException
                // ——那是脚本笔误，不该表现为"实现缺陷"（进程 255 + Java 栈）。
                Integer port = intArgument(ctx.SSH_EXPRESSION(
                        ctx.SSH_PASSWORD() != null || ctx.SSH_KEYFILE() != null ? 3 : 2),
                        "SSH port", UserNumber.Rule.PORT);
                if (port != null) {
                    parsedObject.put("port", port);
                }
            }
        } else if (ctx.SFTP_CHMOD() != null) {
            parsedObject.put("action", "sftp_chmod");
            parsedObject.put("fileName", ctx.SSH_EXPRESSION(0).getText());
            // 权限位在 IR 里仍是"八进制原文"（分派器按 8 进制解析，契约不变），这里只校验它是不是合法整数
            parsedObject.put("fileMod", sftpMode(ctx.SSH_EXPRESSION(1), "file mode"));
        } else if (ctx.SFTP_GETCWD() != null) {
            parsedObject.put("action", "sftp_cwd");
        } else if (ctx.SFTP_CHDIR() != null) {
            parsedObject.put("action", "sftp_chdir");
            parsedObject.put("dir", ctx.SSH_EXPRESSION(0).getText());
        } else if (ctx.SFTP_MKDIR() != null) {
            parsedObject.put("action", "sftp_mkdir");
            parsedObject.put("dir", ctx.SSH_EXPRESSION(0).getText());
            parsedObject.put("dirMod", sftpMode(ctx.SSH_EXPRESSION(1), "directory mode"));
        } else if (ctx.SFTP_CHOWN() != null) {
            parsedObject.put("action", "sftp_chown");
            parsedObject.put("fileName", ctx.SSH_EXPRESSION(0).getText());
            // 取不出来的数值**不进 IR**（errorCode 已经非 0，引擎不会执行这条语句）
            Integer uid = sftpNumber(ctx.SSH_EXPRESSION(1), "uid", 10);
            if (uid != null) {
                parsedObject.put("uid", uid);
            }
            Integer gid = sftpNumber(ctx.SSH_EXPRESSION(2), "gid", 10);
            if (gid != null) {
                parsedObject.put("gid", gid);
            }
        } else if (ctx.SFTP_GET() != null) {
            parsedObject.put("action", "sftp_get");
            parsedObject.put("remoteFile", ctx.SSH_EXPRESSION(0).getText());
            parsedObject.put("localFile", ctx.SSH_EXPRESSION(1).getText());
        } else if (ctx.SFTP_PUT() != null) {
            parsedObject.put("action", "sftp_put");
            parsedObject.put("localFile", ctx.SSH_EXPRESSION(0).getText());
            parsedObject.put("remoteFile", ctx.SSH_EXPRESSION(1).getText());
        } else if (ctx.SFTP_REMOVE() != null) {
            parsedObject.put("action", "sftp_remove");
            parsedObject.put("file", ctx.SSH_EXPRESSION(0).getText());
        } else if (ctx.SFTP_RENAME() != null) {
            parsedObject.put("action", "sftp_rename");
            parsedObject.put("oldFile", ctx.SSH_EXPRESSION(0).getText());
            parsedObject.put("newFile", ctx.SSH_EXPRESSION(1).getText());
        } else if (ctx.SFTP_LISTDIR() != null) {
            parsedObject.put("action", "sftp_listdir");
            parsedObject.put("dir", ctx.SSH_EXPRESSION(0).getText());
        } else if (ctx.SFTP_TRUNCATE() != null) {
            parsedObject.put("action", "sftp_truncate");
            parsedObject.put("file", ctx.SSH_EXPRESSION(0).getText());
            Integer fileSize = sftpNumber(ctx.SSH_EXPRESSION(1), "file size", 10);
            if (fileSize != null) {
                parsedObject.put("fileSize", fileSize);
            }
        }

        // 处理错误信息（这一组用错误码 1，并置 isFinished=false）
        SubResult result = tailOne(ctx, parsedObject);
        if (ctx.exception != null) {
            this.isFinished = false;
        }
        if (this.argumentError != null) {
            // 数值参数非法：走访问器既有的错误通道给可读错误（见 argumentError 的注释）。
            // 注意**不动 isFinished**：语句本身是完整的（`_ssh …` 不需要终结符），
            // 若把它标成"没写完"，引擎会继续累积后面的行、最后报"脚本末尾语句未结束"，
            // 那就把一个笔误变成了另一件更难解释的事。
            result = new SubResult(parsedObject, 1, this.argumentError);
        }
        return apply(result);
    }

    // ------------------------------------------------------------------ COMPARE

    @Override
    public Object visitCompare(SQLParser.CompareContext ctx) {
        Map<String, Object> parsedObject = object();
        parsedObject.put("name", "COMPARE");

        // 重置比较选项
        if (ctx.COMPARE_RESET() != null) {
            parsedObject.put("action", "reset");
        }

        // 比较选项
        Map<String, Object> compareOptions = object();
        if (!ctx.COMPARE_CASE().isEmpty()) {
            compareOptions.put("case", true);
        }
        if (!ctx.COMPARE_NOCASE().isEmpty()) {
            compareOptions.put("case", false);
        }
        if (!ctx.COMPARE_MASK().isEmpty()) {
            compareOptions.put("mask", true);
        }
        if (!ctx.COMPARE_NOMASK().isEmpty()) {
            compareOptions.put("mask", false);
        }
        if (!ctx.COMPARE_IGBLANK().isEmpty()) {
            compareOptions.put("igblank", true);
        }
        if (!ctx.COMPARE_NOIGBLANK().isEmpty()) {
            compareOptions.put("igblank", false);
        }
        if (!ctx.COMPARE_TRIM().isEmpty()) {
            compareOptions.put("trim", true);
        }
        if (!ctx.COMPARE_NOTRIM().isEmpty()) {
            compareOptions.put("trim", false);
        }
        List<String> output = new ArrayList<>();
        if (!ctx.COMPARE_CONSOLE().isEmpty()) {
            output.add("console");
        }
        if (!ctx.COMPARE_DIFFFILE().isEmpty()) {
            output.add("diffFile");
        }
        if (!ctx.COMPARE_HTMLFILE().isEmpty()) {
            output.add("htmlFile");
        }
        if (!output.isEmpty()) {
            compareOptions.put("output", output);
        }
        // 算法与字符集都已经没有对应选项了（差分只有一种算法、文件一律 UTF-8），但语法仍认得
        // `SET ALGORITHM ...` / `SET WORK|REFERENCE ENCODING ...`：这里照旧产出 IR 键，
        // 分派器据此报 `unknown compare option [...]`，比解析层的 "mismatched input" 好排查得多。
        if (ctx.COMPARE_LCS() != null) {
            compareOptions.put("algorithm", "lcs");
        }
        if (ctx.COMPARE_MYERS() != null) {
            compareOptions.put("algorithm", "myers");
        }
        if (ctx.COMPARE_DIFFLIB() != null) {
            compareOptions.put("algorithm", "difflib");
        }
        if (ctx.COMPARE_ENCODING() != null) {
            if (ctx.COMPARE_WORK() != null) {
                compareOptions.put("workEncoding", ctx.COMPARE_EXPRESSION(0).getText());
            }
            if (ctx.COMPARE_REFERENCE() != null) {
                compareOptions.put("refEncoding", ctx.COMPARE_EXPRESSION(0).getText());
            }
        }
        parsedObject.put("compareOptions", compareOptions);

        // maskline 命令
        if (ctx.COMPARE_MASKLINE() != null) {
            parsedObject.put("action", "mask");
            if (!ctx.COMPARE_EXPRESSION().isEmpty()) {
                parsedObject.put("source", ctx.COMPARE_EXPRESSION(0).getText());
                if (ctx.COMPARE_EXPRESSION().size() > 1) {
                    parsedObject.put("target", ctx.COMPARE_EXPRESSION(1).getText());
                }
            }
        }
        if (ctx.COMPARE_NOMASKLINE() != null) {
            parsedObject.put("action", "nomask");
            if (!ctx.COMPARE_EXPRESSION().isEmpty()) {
                parsedObject.put("source", ctx.COMPARE_EXPRESSION(0).getText());
            }
        }

        // skipline 命令
        if (ctx.COMPARE_SKIPLINE() != null) {
            parsedObject.put("action", "skip");
            if (!ctx.COMPARE_EXPRESSION().isEmpty()) {
                parsedObject.put("source", unquote(ctx.COMPARE_EXPRESSION(0).getText().strip()));
            }
        }
        if (ctx.COMPARE_NOSKIPLINE() != null) {
            parsedObject.put("action", "noskip");
            if (!ctx.COMPARE_EXPRESSION().isEmpty()) {
                parsedObject.put("source", unquote(ctx.COMPARE_EXPRESSION(0).getText().strip()));
            }
        }

        // SET
        if (ctx.COMPARE_SET() != null) {
            parsedObject.put("action", "set");
        }

        // 非特殊设置选项，即默认的 Compare 命令
        if (ctx.COMPARE_SET() == null
                && ctx.COMPARE_SKIPLINE() == null
                && ctx.COMPARE_NOSKIPLINE() == null
                && ctx.COMPARE_MASKLINE() == null
                && ctx.COMPARE_NOMASKLINE() == null
                && ctx.COMPARE_RESET() == null
                && !ctx.COMPARE_EXPRESSION().isEmpty()) {
            parsedObject.put("action", "compare");
            parsedObject.put("targetFile", ctx.COMPARE_EXPRESSION(0).getText());
            if (ctx.COMPARE_EXPRESSION().size() > 1) {
                parsedObject.put("referenceFile", ctx.COMPARE_EXPRESSION(1).getText());
            }
        }

        // 处理错误信息（这一组用错误码 1，并置 isFinished=false）
        SubResult result = tailOne(ctx, parsedObject);
        if (ctx.exception != null) {
            this.isFinished = false;
        }
        return apply(result);
    }
}
