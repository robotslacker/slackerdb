package org.slackerdb.plsql.harness;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个数据驱动的一致性用例（见 plsql/）。
 *
 * <p>用例来自 {@code plsql/src/test/resources/plsql/conformance} 下的 {@code *.plsql} 文件：
 * 文件顶部是 {@code --!} 指令头，其后是 PL/SQL 脚本正文；一个文件可用 {@code --! ---}
 * 分成多个用例。</p>
 */
public final class ConformanceCase {

    public String id = "";
    public String layer = "";
    public String req = "";
    public List<String> tags = new ArrayList<>();
    /** exec（默认，连库执行） | parse（只解析，不连库） */
    public String mode = "exec";
    /** 执行脚本前要跑的 SQL（建表/插数据） */
    public List<String> setups = new ArrayList<>();
    /** 执行脚本后要校验的查询 */
    public List<QueryExpectation> queries = new ArrayList<>();
    /** {@code none} 或错误消息需要包含的子串（不区分大小写） */
    public String expectError = "none";
    /** 仅 detect 模式使用：期望的语句分类 */
    public String expectKind = "";
    /** 仅 detect 模式使用：期望切分出的语句数（-1 = 不断言） */
    public int expectStatements = -1;
    /** parse 模式：期望错误位置（1 起；-1 = 不断言） */
    public int expectLine = -1;
    public int expectColumn = -1;
    /** parse 模式：期望 AST 结构摘要（空 = 不断言） */
    public String expectAst = "";

    public Path file;
    public String script = "";
    public String rawText = "";

    public boolean expectsError() {
        return expectError != null
                && !expectError.isBlank()
                && !"none".equalsIgnoreCase(expectError.trim());
    }

    public String displayName() {
        String prefix = (layer == null || layer.isEmpty()) ? "" : layer + "-";
        return prefix + id;
    }

    /** 校验失败时的上下文（脚本原文 + 来源文件）。 */
    public String describe() {
        return "\n--- case " + displayName() + " (" + file + ") ---\n" + rawText.trim() + "\n--- end case ---";
    }

    public static final class QueryExpectation {
        public String sql;
        public List<List<String>> rows = new ArrayList<>();
    }
}
