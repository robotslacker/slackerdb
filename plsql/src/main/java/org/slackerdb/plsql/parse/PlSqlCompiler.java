package org.slackerdb.plsql.parse;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.slackerdb.plsql.ast.PlSqlAst;
import org.slackerdb.plsql.block.PlSqlBlockLexer;
import org.slackerdb.plsql.block.PlSqlBlockParser;

import java.util.ArrayList;
import java.util.List;

/**
 * PL/SQL 编译器：遮罩 → 结构文法 → AST。
 *
 * <p>编译失败抛 {@link PlSqlCompileException}，携带 {@code line:column} 与消息；
 * 位置基于原文（遮罩保持行列）。</p>
 */
public final class PlSqlCompiler {

    /** 一条编译诊断。 */
    public record Diagnostic(int line, int column, String message) {
        @Override
        public String toString() {
            return "line " + line + ":" + column + " " + message;
        }
    }

    /** 编译失败（语法错误 / 预处理错误）。 */
    public static final class PlSqlCompileException extends RuntimeException {
        private final int line;
        private final int column;
        private final transient List<Diagnostic> diagnostics;

        PlSqlCompileException(String message, int line, int column, List<Diagnostic> diagnostics) {
            super(message);
            this.line = line;
            this.column = column;
            this.diagnostics = List.copyOf(diagnostics);
        }

        /** 1 起；0 表示未知。 */
        public int getLine() {
            return line;
        }

        /** 1 起；0 表示未知。 */
        public int getColumn() {
            return column;
        }

        public List<Diagnostic> getDiagnostics() {
            return diagnostics;
        }

        /** 供错误响应拼接的位置后缀。 */
        public String positionSuffix() {
            return line > 0 ? " (line " + line + ", column " + column + ")" : "";
        }
    }

    /** 编译产物：AST + 遮罩表 + 骨架（诊断/调试用）。 */
    public record Result(PlSqlAst.Block block, PlSqlSourcePreparer.PreparedSource source, String skeleton) {
    }

    private PlSqlCompiler() {
    }

    /**
     * 只做结构解析（遮罩 + 文法 + AST），<b>不做</b>语义检查。
     *
     * <p>供文法/结构测试使用；引擎入口用 {@link #compile}。</p>
     */
    public static Result parse(String script) {
        return parse(script, false);
    }

    /**
     * 完整编译：结构解析 + 语义检查（未声明变量/游标、重复声明等在执行前拦下）。
     *
     * @throws PlSqlCompileException 语法或语义错误（带 {@code line:column}）
     */
    public static Result compile(String script) {
        return parse(script, true);
    }

    private static Result parse(String script, boolean withSemantics) {
        PlSqlSourcePreparer.PreparedSource prepared = PlSqlSourcePreparer.prepare(script);
        if (prepared.hasError()) {
            throw new PlSqlCompileException(prepared.error, extractLine(prepared.error), 1, List.of());
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        PlSqlBlockLexer lexer = new PlSqlBlockLexer(CharStreams.fromString(prepared.skeleton));
        lexer.removeErrorListeners();
        lexer.addErrorListener(new CollectingListener(diagnostics));

        PlSqlBlockParser parser = new PlSqlBlockParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(new CollectingListener(diagnostics));

        PlSqlBlockParser.ScriptContext scriptContext;
        try {
            scriptContext = parser.script();
        } catch (RuntimeException e) {
            throw new PlSqlCompileException(String.valueOf(e.getMessage()), 0, 0, diagnostics);
        }

        if (!diagnostics.isEmpty()) {
            Diagnostic first = diagnostics.get(0);
            throw new PlSqlCompileException(first.message(), first.line(), first.column(), diagnostics);
        }

        PlSqlAst.Block block = new AstBuilder(prepared, script).block(scriptContext.block());
        if (withSemantics) {
            // 编译期语义检查：未声明变量/游标、重复声明等（带精确位置）
            SemanticChecker.check(block);
        }
        return new Result(block, prepared, prepared.skeleton);
    }

    /** 便捷入口：只判断能否编译。 */
    public static boolean canCompile(String script) {
        try {
            compile(script);
            return true;
        } catch (PlSqlCompileException e) {
            return false;
        }
    }

    private static int extractLine(String message) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("line\\s+(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(message == null ? "" : message);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static final class CollectingListener extends BaseErrorListener {
        private final List<Diagnostic> diagnostics;

        CollectingListener(List<Diagnostic> diagnostics) {
            this.diagnostics = diagnostics;
        }

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                int charPositionInLine, String msg, RecognitionException e) {
            // ANTLR 的列是从 0 起的，这里统一成 1 起，方便与用户看到的编辑器列号对齐
            diagnostics.add(new Diagnostic(line, charPositionInLine + 1, msg));
        }
    }
}
