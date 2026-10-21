package org.slackerdb.dbcli.antlr;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条命令的解析探针（位于 test 作用域）。
 *
 * <p>为什么需要它：本套语法的入口规则是 {@code prog : command EOF}（一次只吃一条命令），
 * 因此脚本要么"逐行累积 → 反复解析 → 直到成功或判定还差续行"，要么先把整段切好再解析。
 * "还差续行"的判据是：报错信息以 {@code missing SQL_END} 开头时 {@code isFinished=false}
 * （继续读下一行）。</p>
 *
 * <p><b>这不是正式实现</b>：正式的执行循环在 {@code script/} 包。放在测试里只验证
 * "6 个 .g4 生成的 Java 解析器能吃下真实脚本"，不固化引擎接口。</p>
 */
final class ScriptParseProbe {

    /** 一次解析的结果。 */
    record Outcome(boolean ok, boolean needMoreLines, String errorMessage) {
    }

    private ScriptParseProbe() {
    }

    /** 解析一段文本；{@code needMoreLines=true} 表示"语句还没结束，继续喂下一行"。 */
    static Outcome parseOnce(String rawText) {
        // ★ 契约：解析前先 strip 掉累积缓冲的首尾空白，
        //   否则末尾的 CRLF 会让 `prog : command EOF` 报 "extraneous input"（connect/session 等规则
        //   并不接受 CRLF）。内部换行必须保留（多行语句靠它拼接），所以只能 strip 首尾。
        String text = rawText.strip();

        CollectingErrorListener lexerErrors = new CollectingErrorListener();
        CollectingErrorListener parserErrors = new CollectingErrorListener();

        SQLLexer lexer = new SQLLexer(CharStreams.fromString(text));
        lexer.removeErrorListeners();
        lexer.addErrorListener(lexerErrors);

        SQLParser parser = new SQLParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(parserErrors);
        parser.prog();

        boolean needMore = lexerErrors.needMoreLines || parserErrors.needMoreLines;
        boolean hasError = lexerErrors.hasError || parserErrors.hasError;
        String message = parserErrors.firstMessage != null ? parserErrors.firstMessage : lexerErrors.firstMessage;
        // 判定"需要续行"时不算错误（它只是说明语句还没写完）
        return new Outcome(!hasError || needMore, needMore, message);
    }

    /**
     * 逐行累积解析整个脚本，返回每一块成功解析的文本（用于断言与排错）。
     *
     * @throws AssertionError 出现无法通过继续累积来消除的语法错误时抛出，并带上出错的那一块
     */
    static List<String> parseWholeScript(List<String> lines) {
        List<String> parsedChunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String line : lines) {
            buffer.append(line).append('\n');
            if (buffer.toString().isBlank()) {
                buffer.setLength(0);
                continue;
            }
            Outcome outcome = parseOnce(buffer.toString());
            if (outcome.needMoreLines()) {
                continue;
            }
            if (!outcome.ok()) {
                throw new AssertionError("语法错误。出错块 >>>" + buffer + "<<< 报错: " + outcome.errorMessage());
            }
            parsedChunks.add(buffer.toString());
            buffer.setLength(0);
        }
        if (!buffer.toString().isBlank()) {
            throw new AssertionError("脚本末尾仍有未解析完的缓冲 >>>" + buffer + "<<<");
        }
        return parsedChunks;
    }

    /** 错误收集器：判"需要续行"的规则与错误文案格式（与 {@code IrErrorListener} 同一套冻结文案）。 */
    private static final class CollectingErrorListener extends BaseErrorListener {

        private boolean hasError;
        private boolean needMoreLines;
        private String firstMessage;

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int column,
                                String msg, RecognitionException e) {
            hasError = true;
            if (msg != null && msg.startsWith("missing SQL_END")) {
                // 语句还没写完，等下一行
                needMoreLines = true;
            }
            if (firstMessage == null) {
                // 错误文案格式（已冻结）：line{line}:{col}  {msg}  —— 冒号后两个空格、结尾一个空格
                firstMessage = "line" + line + ":" + column + "  " + msg + " ";
            }
        }
    }
}
