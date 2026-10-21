package org.slackerdb.dbcli.script;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.slackerdb.dbcli.antlr.SQLLexer;
import org.slackerdb.dbcli.antlr.SQLParser;

import java.util.Map;

/**
 * SQL 分析入口：把一段脚本文本分析成"是否完成 + IR + 错误码/错误信息"。
 *
 * <p>三类错误源的合并顺序**已冻结**（改顺序会改变文案，因此不能动）：</p>
 * <ol>
 *   <li>{@code isFinished}：访问器先给，词法监听器与语法监听器只能把它压成 false；</li>
 *   <li>{@code errorCode}/{@code errorMsg}：先取访问器，再被词法监听器覆盖，最后被语法监听器覆盖；</li>
 *   <li>访问器若有非空 {@code errorMsg}，则以 {@code "<访问器消息> < <前面的消息>"} 前置拼接。</li>
 * </ol>
 *
 * <p>解析前必须先 {@code strip()} 首尾空白：否则累积缓冲末尾的换行会变成 CRLF token，
 * 让 {@code prog : command EOF} 报 "extraneous input"。
 * 内部换行必须保留。</p>
 *
 * <p><b>文案的用途已经收窄</b>：{@code errorMsg} 不再打印给使用者（dbcli 不解释语法，认不下来的
 * 原始文本会送数据库执行，见 {@code engine.ScriptRunner}）。分析结果现在只供三处消费：
 * {@code isFinished}（继续读下一行还是执行）、{@code parsedObject}（能执行就走 IR 路径），
 * 以及 {@link Result#parserError()}（区分"解析层报错"与"访问器给出的可读业务错误"）。
 * </p>
 */
public final class SqlAnalyzer {

    private SqlAnalyzer() {
    }

    /**
     * 分析结果：{@code isFinished} / {@code parsedObject} / {@code errorCode} / {@code errorMsg}
     * 加两个"错误性质"标志。
     *
     * @param isFinished     解析是否正常完成（false 表示"语句还没写完"或报错）
     * @param parsedObject   IR 结果（{@code dict} 等价物），空输入时为 null
     * @param errorCode      0 成功 / 1 有错 / -1 访问器异常
     * @param errorMsg       错误提示（成功为 null）
     * @param parserError    {@code true} 表示**词法/语法监听器**报过错：{@code errorMsg} 里是 ANTLR 的
     *                       位置文案（{@code line…:… …} 之类），**不得展示给使用者**；
     *                       {@code false} 且 {@code errorCode != 0} 表示错误来自访问器（IR 层），
     *                       {@code errorMsg} 是一条可读的业务提示（如 {@code Invalid SFTP file mode [abc]…}）
     * @param incompleteInput {@code true} 表示"解析没成功，但看起来只是**还没写完**"：{@code isFinished=false}，
     *                       或所有报错都落在**输入末尾**（ANTLR 的 offending token 是 {@code <EOF>}，
     *                       或词法报错一路延续到输入结尾，如未闭合的引号/文本）。
     *                       引擎据此对 {@code _} 命令继续读下一行（EOF 时提示 incomplete），
     *                       而不是报"不认识这条命令"。依据是 offending token 与输入指针，
     *                       与"末尾是不是 {@code ;}"这类文本形态无关
     */
    public record Result(boolean isFinished, Map<String, Object> parsedObject, int errorCode, String errorMsg,
                         boolean parserError, boolean incompleteInput) {
    }

    /** 默认命名空间是 SQL（命名空间只有这一个取值）。 */
    public static Result analyze(String sqlCommandPlainText) {
        return analyze(sqlCommandPlainText, "SQL");
    }

    public static Result analyze(String sqlCommandPlainText, String defaultNameSpace) {
        // 去除语句可能的前导换行或者空格
        String sqlText = sqlCommandPlainText == null ? "" : sqlCommandPlainText.strip();

        // 通用 JDBC 形态（jdbc:<scheme>:…，scheme 不是 slackerdb）先认：它是**驱动自己的语法**，
        // 连接语法（host:port/service、jdbc:slackerdb://…）表达不了，见 {@link JdbcUrlConnect}。
        // 命中时 URL 原样透传，不经过 ANTLR，也不会被连接模式的 token 改写。
        Result jdbcUrl = JdbcUrlConnect.analyze(sqlText);
        if (jdbcUrl != null) {
            return jdbcUrl;
        }

        // 调用 Antlr 进行语法解析，并自定义错误监听
        IrErrorListener lexerListener = new IrErrorListener();
        SQLLexer lexer = new SQLLexer(CharStreams.fromString(sqlText));
        lexer.removeErrorListeners();
        lexer.addErrorListener(lexerListener);

        IrErrorListener parserListener = new IrErrorListener();
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        SQLParser parser = new SQLParser(tokenStream);
        parser.removeErrorListeners();
        parser.addErrorListener(parserListener);

        SQLParser.ProgContext tree = parser.prog();

        SqlIrVisitor visitor = new SqlIrVisitor(tokenStream, defaultNameSpace);
        visitor.visit(tree);
        // 访问器的结论以**不可变快照**的形式取出：后续对监听器结果的合并只改局部变量
        SqlIrVisitor.IrResult ir = visitor.result();

        // 词法和语法解析，任何一个失败，都认为失败
        boolean isFinished = ir.isFinished();
        if (!lexerListener.isFinished()) {
            isFinished = false;
        }
        if (!parserListener.isFinished()) {
            isFinished = false;
        }

        // "解析层报过错"（= errorMsg 里是 ANTLR 的位置文案）与"IR 层给出了可读的业务错误"
        // 必须能分开：前者一律不展示给使用者（引擎把原始文本送库），后者照旧一行可读错误。
        boolean parserError = lexerListener.getErrorCode() != 0 || parserListener.getErrorCode() != 0;

        // "只是没写完"还是"写错了"：没写完 = 语句还没收尾，或者所有报错都落在输入末尾
        //（ANTLR 的 offending token 是 <EOF>）。只要出现过一个"落在真实 token 上"的报错，
        // 就是写错了（例如 `_use XXX` 里那个认不出来的 X）。SQL 侧两种都送库，这个标志
        // 只影响 `_` 命令的兜底文案（见 ScriptRunner.handleUnparsable）。
        boolean errorOnRealToken = lexerListener.isErrorOnRealToken()
                || parserListener.isErrorOnRealToken();
        boolean incompleteInput = !isFinished || (parserError && !errorOnRealToken);

        int errorCode = ir.errorCode();
        String errorMsg = null;
        if (lexerListener.getErrorCode() != 0) {
            errorCode = lexerListener.getErrorCode();
            errorMsg = lexerListener.getErrorMsg();
        }
        if (parserListener.getErrorCode() != 0) {
            errorCode = parserListener.getErrorCode();
            errorMsg = parserListener.getErrorMsg();
        }
        String parseErrorMsg = ir.errorMsg();
        if (parseErrorMsg != null && !parseErrorMsg.strip().isEmpty()) {
            if (errorMsg != null) {
                // 如果访问器有错误信息，则把访问器的错误消息前置
                errorMsg = parseErrorMsg + " < " + errorMsg;
            } else {
                errorMsg = parseErrorMsg;
            }
        }

        // 未闭合的美元引号：词法把落单的 '$$' 归成 SQL_DOLLAR_OPEN（成对的整块由 SQL_DOLLAR_QUOTE 吃下），
        // 说明函数体/脚本块还没写完，后面的 ';' 属于正文，不能当语句终结符。
        // 放在最后：它比 "missing SQL_END" 更贴近根因（差的是闭合的 $$，不是终结符），
        // 因此这里覆盖文案；没有落单 '$$' 的输入完全不受影响。
        if (hasUnclosedDollarQuote(tokenStream)) {
            isFinished = false;
            errorCode = 1;
            errorMsg = "unclosed dollar-quoted string";
        }
        return new Result(isFinished, ir.parsedObject(), errorCode, errorMsg, parserError,
                incompleteInput);
    }

    /** 词法流里是否出现过"落单的 '$$'"（= 美元引号体没写完）。 */
    private static boolean hasUnclosedDollarQuote(CommonTokenStream tokenStream) {
        for (Token token : tokenStream.getTokens()) {
            if (token.getType() == SQLLexer.SQL_DOLLAR_OPEN) {
                return true;
            }
        }
        return false;
    }
}
