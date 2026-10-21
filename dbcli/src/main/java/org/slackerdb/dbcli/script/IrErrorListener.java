package org.slackerdb.dbcli.script;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;

/**
 * 词法/语法错误监听器：把 ANTLR 的报错归一成"一条错误码 + 一条冻结文案"。
 *
 * <p>三条契约（字节级，被生产控制流依赖，改不得）：</p>
 * <ol>
 *   <li>报错信息以 {@code "missing SQL_END"} 开头 ⇒ 只是"语句还没写完"：
 *       {@code isFinished=false}、{@code errorCode=1}、{@code errorMsg=msg}（原文，不加前后缀）；</li>
 *   <li>其余报错 ⇒ {@code errorCode=1}、{@code errorMsg="line"+line+":"+column+"  "+msg+" "}
 *       （**冒号后两个空格、结尾一个空格**）；</li>
 *   <li>多次报错时**后一次覆盖前一次**，因此取到的是"最后一个错"。</li>
 * </ol>
 *
 * <p><b>这些文案不再打印给使用者</b>（dbcli 不解释语法：认不下来的原始文本会送数据库执行）。保留归一化
 * 只为两件事：① 用第 1 条判定"这一条还没写完"（继续读下一行）；② 让
 * {@link SqlAnalyzer.Result#parserError()} 与 {@link SqlAnalyzer.Result#incompleteInput()} 能区分
 * "解析层报过错"、"只是没写完"与"访问器给出的可读业务错误"（后者才照旧打印）。
 * 因此第 2 条的字节级格式现在是**分析层**契约（{@code IrErrorTextTest} 冻着它），不是展示契约。</p>
 */
public class IrErrorListener extends BaseErrorListener {

    /** 如果成功，返回 0；如果失败，返回 1。 */
    private int errorCode = 0;

    /** 解析是否正常完成。 */
    private boolean isFinished = true;

    /** 如果成功，返回空串；如果失败，返回解析的错误提示信息。 */
    private String errorMsg = "";

    /**
     * 是否出现过"落在**真实 token** 上"的报错（= 写错了/不认识，而不是输入还没写完）。
     *
     * <p>判据只看 ANTLR 给的 offending symbol 与词法层的输入指针（见 {@link #isAtEndOfInput}），
     * 不用"最后一个字符是不是 {@code ;}"那类文本启发式。</p>
     */
    private boolean errorOnRealToken;

    public int getErrorCode() {
        return this.errorCode;
    }

    public boolean isFinished() {
        return this.isFinished;
    }

    public String getErrorMsg() {
        return this.errorMsg;
    }

    /** 是否有过一次"落在真实 token 上"的报错（{@code false} = 所有报错都在输入末尾，即"还没写完"）。 */
    public boolean isErrorOnRealToken() {
        return this.errorOnRealToken;
    }

    /**
     * 这次报错是否发生在**输入末尾**（= "这一条还没写完"）。
     *
     * <p>两种形状都算：</p>
     * <ol>
     *   <li><b>语法报错</b>：offending symbol 就是 {@code <EOF>}
     *       （{@code missing X at '<EOF>'}、{@code <EOF>} 作为被拒 token 的其它形状）；</li>
     *   <li><b>词法报错</b>（offending symbol 为 null）：错误位置之后**已经没有有效字符**——
     *       识别器的输入指针就在输入末尾。这说明这段认不出来的字符一路延续到结尾
     *       （典型：还没闭合的引号/文本，例如 {@code _ssh execute "abc}）。</li>
     * </ol>
     *
     * <p>依据是 token 类型与输入指针，**不看**报错文案里有没有引号、也不看末尾是不是 {@code ;}。</p>
     */
    private static boolean isAtEndOfInput(Recognizer<?, ?> recognizer, Object offendingSymbol) {
        if (offendingSymbol instanceof Token token) {
            return token.getType() == Token.EOF;
        }
        if (recognizer instanceof Lexer lexer) {
            CharStream input = lexer.getInputStream();
            return input != null && input.index() >= input.size();
        }
        return false;
    }

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
                            String msg, RecognitionException e) {
        if (!isAtEndOfInput(recognizer, offendingSymbol)) {
            this.errorOnRealToken = true;
        }
        if (msg != null && msg.startsWith("missing SQL_END")) {
            // 语句还没结束，继续读下一行
            this.isFinished = false;
            this.errorCode = 1;
            this.errorMsg = msg;
        } else {
            // 冻结文案：冒号后两个空格、结尾一个空格
            this.errorCode = 1;
            this.errorMsg = "line" + line + ":" + charPositionInLine + "  " + msg + " ";
        }
        super.syntaxError(recognizer, offendingSymbol, line, charPositionInLine, msg, e);
    }
}
