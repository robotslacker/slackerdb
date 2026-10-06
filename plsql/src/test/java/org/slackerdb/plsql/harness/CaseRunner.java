package org.slackerdb.plsql.harness;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.slackerdb.plsql.PlSqlEngine;
import org.slackerdb.plsql.detect.PlSqlDetector;
import org.slackerdb.plsql.detect.PlSqlScript;
import org.slackerdb.plsql.parse.PlSqlCompiler;
import org.slackerdb.plsql.spi.DefaultJdbcHost;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 一致性用例执行器：建库 → setup → 执行脚本 → 断言（错误 / 查询结果）。
 *
 * <p>断言只针对**可观测结果**（表内容、异常），不做空断言——</p>
 */
public final class CaseRunner {

    private CaseRunner() {
    }

    public static void run(ConformanceCase c) {
        if ("parse".equalsIgnoreCase(c.mode) || "compile".equalsIgnoreCase(c.mode)) {
            runParse(c);
            return;
        }
        if ("detect".equalsIgnoreCase(c.mode)) {
            runDetect(c);
            return;
        }
        runExec(c);
    }

    /** L0：检测与切分（不连库）。 */
    private static void runDetect(ConformanceCase c) {
        PlSqlScript script = PlSqlDetector.analyze(c.script);

        if (c.expectsError()) {
            if (!script.hasError()) {
                fail("期望检测报错（含 '" + c.expectError + "'），但检测通过：kind=" + kindOf(script)
                        + ", statements=" + script.size() + c.describe());
            }
            if (!script.error().toLowerCase().contains(c.expectError.toLowerCase())) {
                fail("检测错误不含 '" + c.expectError + "'，实际为：" + script.error() + c.describe());
            }
            return;
        }

        if (script.hasError()) {
            fail("期望检测成功，但报错：" + script.error() + c.describe());
        }
        if (!c.expectKind.isEmpty()) {
            String actual = kindOf(script);
            assertEquals(c.expectKind.toUpperCase(), actual,
                    "语句分类不符" + c.describe());
        }
        if (c.expectStatements >= 0) {
            assertEquals(c.expectStatements, script.size(),
                    "语句数不符（实际：" + script.statements() + "）" + c.describe());
        }
    }

    static String kindOf(PlSqlScript script) {
        if (script.hasError()) {
            return "ERROR";
        }
        if (script.isEmpty()) {
            return "EMPTY";
        }
        if (script.isSingleBlock()) {
            return "BLOCK";
        }
        if (script.isScript()) {
            return "SCRIPT";
        }
        return "SQL";
    }

    /**
     * 引擎入口（唯一入口，便于替换）：经 SPI 门面执行。
     * 旧实现直连 JDBC（已删除的 {@code PlSqlVisitor}）已在 P2 被门面取代，
     * 这样一致性语料同时验证了 dbserver 将要走的那条路径（宿主实现方见
     * {@code org.slackerdb.dbserver.sql.PlSqlHostImpl}）。
     */
    private static void runEngine(Connection conn, String script) {
        PlSqlEngine.execute(new DefaultJdbcHost(conn), script);
    }

    private static void runExec(ConformanceCase c) {
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb::memory:", "", "")) {
            conn.setAutoCommit(false);

            for (String setup : c.setups) {
                try (Statement st = conn.createStatement()) {
                    st.execute(setup);
                } catch (SQLException e) {
                    fail("setup 执行失败：" + setup + "\n" + e.getMessage() + c.describe());
                }
            }
            // 提交 setup：DuckDB 的 DDL 也是事务性的，块内 ROLLBACK（异常处理常见写法）
            // 会把未提交的建表一起回滚，导致"表不存在"的假失败。
            conn.commit();

            Throwable error = null;
            try {
                runEngine(conn, c.script);
            } catch (Throwable t) {
                error = t;
            }

            if (c.expectsError()) {
                if (error == null) {
                    fail("期望报错（含 '" + c.expectError + "'），但执行成功。" + c.describe());
                }
                String message = flatten(error);
                if (!message.toLowerCase().contains(c.expectError.toLowerCase())) {
                    fail("错误消息不含 '" + c.expectError + "'，实际为：\n" + message + c.describe());
                }
                return;
            }

            if (error != null) {
                fail("期望执行成功，但抛出了异常：\n" + flatten(error) + c.describe());
            }

            for (ConformanceCase.QueryExpectation q : c.queries) {
                assertQuery(c, conn, q);
            }
        } catch (SQLException e) {
            fail("用例基建失败：" + e.getMessage() + c.describe());
        }
        // 连接由 try-with-resources 关闭；不做收尾 rollback ——
        // 块内执行过 ROLLBACK 后 DuckDB 会退出显式事务，再 rollback 会报
        // "cannot rollback - no transaction is active"（与真实客户端行为一致，不属于用例失败）。
    }

    private static void runParse(ConformanceCase c) {
        PlSqlCompiler.Result result = null;
        PlSqlCompiler.PlSqlCompileException failure = null;
        try {
            result = "compile".equalsIgnoreCase(c.mode)
                    ? PlSqlCompiler.compile(c.script)
                    : PlSqlCompiler.parse(c.script);
        } catch (PlSqlCompiler.PlSqlCompileException e) {
            failure = e;
        }

        if (c.expectsError()) {
            if (failure == null) {
                fail("期望解析报错（含 '" + c.expectError + "'），但解析通过。" + c.describe());
            }
            if (!failure.getMessage().toLowerCase().contains(c.expectError.toLowerCase())) {
                fail("解析错误不含 '" + c.expectError + "'，实际为：" + failure.getMessage() + c.describe());
            }
            if (c.expectLine > 0) {
                assertEquals(c.expectLine, failure.getLine(),
                        "错误行号不符（实际 line " + failure.getLine() + ", column " + failure.getColumn()
                                + "；消息：" + failure.getMessage() + "）" + c.describe());
            }
            if (c.expectColumn > 0) {
                assertEquals(c.expectColumn, failure.getColumn(),
                        "错误列号不符（实际 line " + failure.getLine() + ", column " + failure.getColumn()
                                + "；消息：" + failure.getMessage() + "）" + c.describe());
            }
            return;
        }

        if (failure != null) {
            fail("期望解析通过，但报错：" + failure.getMessage() + failure.positionSuffix() + c.describe());
        }

        if (!c.expectAst.isEmpty()) {
            String actual = AstShape.of(result.block());
            assertEquals(c.expectAst, actual, "AST 结构不符" + c.describe());
        }
    }

    private static void assertQuery(ConformanceCase c, Connection conn,
                                    ConformanceCase.QueryExpectation q) throws SQLException {
        List<List<String>> actual = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(q.sql)) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    row.add(canonical(rs.getObject(i)));
                }
                actual.add(row);
            }
        }

        List<List<String>> expected = q.rows;
        if (expected.size() != actual.size()) {
            fail("查询行数不符：期望 " + expected.size() + " 行，实际 " + actual.size() + " 行"
                    + "\nSQL: " + q.sql
                    + "\n期望: " + expected
                    + "\n实际: " + actual + c.describe());
        }
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), actual.get(i),
                    "第 " + (i + 1) + " 行不符\nSQL: " + q.sql + c.describe());
        }
    }

    /** 把值规范化为可比较文本，避免数值类型/标度差异造成的假失败。 */
    static String canonical(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Float || value instanceof Double) {
            return new BigDecimal(String.valueOf(value)).stripTrailingZeros().toPlainString();
        }
        if (value instanceof byte[] bytes) {
            return "byte[" + bytes.length + "]";
        }
        return String.valueOf(value);
    }

    static String flatten(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable current = t;
        int depth = 0;
        while (current != null && depth < 8) {
            if (depth > 0) {
                sb.append("\n  caused by: ");
            }
            sb.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage());
            current = current.getCause();
            depth++;
        }
        return sb.toString();
    }

    private static final class CollectingListener extends BaseErrorListener {
        private final List<String> errors;

        CollectingListener(List<String> errors) {
            this.errors = errors;
        }

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                int charPositionInLine, String msg, RecognitionException e) {
            errors.add("line " + line + ":" + charPositionInLine + " " + msg);
        }
    }
}
