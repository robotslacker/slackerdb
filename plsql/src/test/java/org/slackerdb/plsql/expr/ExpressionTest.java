package org.slackerdb.plsql.expr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slackerdb.plsql.PlSqlException;
import org.slackerdb.plsql.ast.Expr;
import org.slackerdb.plsql.ast.PlSqlAst;
import org.slackerdb.plsql.parse.PlSqlCompiler;
import org.slackerdb.plsql.spi.DefaultJdbcHost;
import org.slackerdb.plsql.types.PlSqlTypeException;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L4：表达式编译与求值。
 *
 * <p>每个用例都走**真实链路**：PL/SQL 源码 → 结构文法 → 表达式树 → 编译成
 * 「SQL + 绑定参数」→ 在真实 DuckDB 上求值。</p>
 */
class ExpressionTest {

    private Connection connection;
    private ExprEvaluator evaluator;

    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection("jdbc:duckdb::memory:", "", "");
        connection.setAutoCommit(false);
        evaluator = new ExprEvaluator(new DefaultJdbcHost(connection));
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
    }

    // ---------- 辅助 ----------

    /**
     * 表达式用例的公共声明段：用例里用到的名字都必须声明
     * （编译期语义检查会拦住未声明引用）。
     */
    private static final String WRAPPER_HEAD = """
            declare
              v int;
              x int;
              y int;
              z int;
              m int;
              name text;
              s text;
              cursor c is select 1;
            begin
              v := """;

    /** 取一段表达式源码对应的表达式树（借道赋值语句，避免被当成条件）。 */
    private static Expr valueExpr(String expressionText) {
        PlSqlCompiler.Result result =
                PlSqlCompiler.compile(WRAPPER_HEAD + expressionText + "; end;");
        PlSqlAst.Assign assign = (PlSqlAst.Assign) result.block().statements().get(0);
        return assign.value();
    }

    /** 取一段条件源码对应的表达式树（走同一声明段，保证语义检查通过）。 */
    private static Expr conditionExpr(String expressionText) {
        PlSqlCompiler.Result result = PlSqlCompiler.compile(
                WRAPPER_HEAD + "1; if " + expressionText + " then null; end if; end;");
        PlSqlAst.If ifStmt = (PlSqlAst.If) result.block().statements().get(1);
        return ifStmt.branches().get(0).condition();
    }

    private static String sql(String expressionText) {
        return new ExprCompiler().compile(valueExpr(expressionText)).sql();
    }

    private static List<Expr> binds(String expressionText) {
        return new ExprCompiler().compile(valueExpr(expressionText)).binds();
    }

    private Object evaluate(String expressionText, Map<String, Object> variables) throws SQLException {
        return evaluator.evaluate(valueExpr(expressionText), resolver(variables));
    }

    private boolean condition(String expressionText, Map<String, Object> variables) throws SQLException {
        return evaluator.evaluateCondition(conditionExpr(expressionText), resolver(variables));
    }

    private static ExprEvaluator.ValueResolver resolver(Map<String, Object> variables) {
        return node -> {
            if (node instanceof Expr.Name name) {
                return variables.get(name.name());
            }
            if (node instanceof Expr.CursorAttr attr) {
                return variables.get(attr.cursor() + "%" + attr.attr().name().toLowerCase());
            }
            throw new IllegalStateException("非绑定节点：" + node);
        };
    }

    private static Map<String, Object> vars(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    private static long number(Object value) {
        assertNotNull(value, "求值结果为 null");
        return new BigDecimal(String.valueOf(value)).longValue();
    }

    // ---------- 编译：SQL 文本与绑定 ----------
    @Test
    void compilesLiteralsWithoutBinds() {
        assertEquals("(1 + (2 * 3))", sql("1 + 2 * 3"));
        assertEquals("((1 + 2) * 3)", sql("(1 + 2) * 3"));
        assertEquals("(2 ** 3)", sql("2 ** 3"));
        assertEquals("(10 % 3)", sql("10 % 3"));
        assertEquals("('a' || 'b')", sql("'a' || 'b'"));
        assertEquals("(-1)", sql("-1"));
        assertEquals("(NOT true)", sql("not true"));
        assertEquals("(1 < 2)", sql("1 < 2"));
        assertEquals("(1 <> 2)", sql("1 != 2"));
        assertEquals("(1 = 2)", sql("1 == 2"));
        assertEquals("'a''b'", sql("'a''b'"));
        assertTrue(binds("1 + 2").isEmpty());
    }

    @Test
    void compilesVariablesAsBinds() {
        assertEquals("(? + 1)", sql("x + 1"));
        assertEquals("(? = ?)", sql("x = y"));
        assertEquals("((? = 1) AND (? = 2))", sql("x = 1 && y = 2"));
        assertEquals("(? IS NULL)", sql("x is null"));
        assertEquals("(? IS NOT NULL)", sql("x is not null"));
        assertEquals("(? LIKE ?)", sql("x like y"));
        assertEquals("(? NOT LIKE 'a%')", sql("x not like 'a%'"));
        assertEquals("(? IN (1, 2))", sql("x in (1, 2)"));
        assertEquals("(? NOT IN (1))", sql("x not in (1)"));
        assertEquals("(? BETWEEN 1 AND 3)", sql("x between 1 and 3"));
        assertEquals("upper(?)", sql("upper(x)"));
        assertEquals("(CASE WHEN ? THEN 1 ELSE 2 END)", sql("case when x then 1 else 2 end"));
        assertEquals("?", sql("c%found"));
        assertEquals("?", sql("c%notfound"));
        assertEquals("?", sql("c%rowcount"));
    }

    @Test
    void bindOrderFollowsAppearance() {
        List<Expr> bound = binds("x + y * z");
        assertEquals(3, bound.size());
        assertEquals("x", ((Expr.Name) bound.get(0)).name());
        assertEquals("y", ((Expr.Name) bound.get(1)).name());
        assertEquals("z", ((Expr.Name) bound.get(2)).name());
    }

    @Test
    void compatAliasesNormalizeToStandardOperators() {
        assertEquals("((? = 1) AND (? = 2))", sql("x == 1 && y == 2"));
        assertEquals("((? <> 1) OR (? <> 2))", sql("x != 1 or y != 2"));
        assertEquals("(NOT ?)", sql("!x"));
    }

    @Test
    void compileResultIsCachedPerNode() {
        ExprCompiler compiler = new ExprCompiler();
        Expr target = valueExpr("x + 1");
        assertSame(compiler.compile(target), compiler.compile(target));
    }

    // ---------- 求值：算术 / 字符串 / 函数 ----------
    @Test
    void evaluatesArithmetic() throws SQLException {
        assertEquals(7, number(evaluate("1 + 2 * 3", Map.of())));
        assertEquals(9, number(evaluate("(1 + 2) * 3", Map.of())));
        assertEquals(8, number(evaluate("2 ** 3", Map.of())));
        assertEquals(1, number(evaluate("10 % 3", Map.of())));
        assertEquals(6, number(evaluate("x * 2", vars("x", 3))));
        assertEquals(0, new BigDecimal(String.valueOf(evaluate("7 / 2", Map.of())))
                .compareTo(new BigDecimal("3.5")));
    }

    @Test
    void evaluatesStringsAndFunctions() throws SQLException {
        assertEquals("ab", evaluate("'a' || 'b'", Map.of()));
        assertEquals("AB", evaluate("upper(x)", vars("x", "ab")));
        assertEquals(3, number(evaluate("length(x)", vars("x", "abc"))));
        assertEquals(5, number(evaluate("coalesce(x, 5)", vars("x", null))));
        assertEquals(2, number(evaluate("abs(x)", vars("x", -2))));
    }

    /** 绑定参数的关键收益：值里的单引号不会破坏 SQL（历史实现是拼字符串）。 */
    @Test
    void stringValuesWithQuoteAreBoundNotInlined() throws SQLException {
        assertEquals("O'BRIEN", evaluate("upper(name)", vars("name", "o'brien")));
        assertEquals("a'; drop table t; --", evaluate("name", vars("name", "a'; drop table t; --")));
    }

    @Test
    void evaluatesComparisonsAndPredicates() throws SQLException {
        assertTrue(condition("x > 1", vars("x", 2)));
        assertFalse(condition("x > 1", vars("x", 1)));
        assertTrue(condition("x is null", vars("x", null)));
        assertFalse(condition("x is null", vars("x", 1)));
        assertTrue(condition("x is not null", vars("x", 1)));
        assertTrue(condition("x between 1 and 3", vars("x", 2)));
        assertFalse(condition("x between 1 and 3", vars("x", 4)));
        assertTrue(condition("x in (1, 2, 3)", vars("x", 2)));
        assertTrue(condition("x like 'a%'", vars("x", "abc")));
        assertFalse(condition("x like 'a%'", vars("x", "bcd")));
        assertTrue(condition("x not like 'a%'", vars("x", "bcd")));
    }

    @Test
    void evaluatesCaseAndCursorAttributes() throws SQLException {
        assertEquals("p", evaluate("case when x > 0 then 'p' else 'n' end", vars("x", 1)));
        assertEquals("n", evaluate("case when x > 0 then 'p' else 'n' end", vars("x", -1)));
        assertTrue(condition("c%found", vars("c%found", true)));
        assertFalse(condition("c%notfound", vars("c%notfound", false)));
        assertEquals(3, number(evaluate("c%rowcount", vars("c%rowcount", 3))));
    }

    // ---------- 三值逻辑与短路 ----------
    @Test
    void nullConditionIsFalse() throws SQLException {
        assertFalse(condition("null = 1", Map.of()));
        assertFalse(condition("x = 1", vars("x", null)));
        assertFalse(condition("x is null and y = 1", vars("x", null, "y", null)));
    }

    @Test
    void andOrShortCircuit() throws SQLException {
        // 右侧必然报错（未知函数）：短路必须避免求值
        assertFalse(condition("1 = 2 and no_such_fn(1) = 1", Map.of()));
        assertTrue(condition("1 = 1 or no_such_fn(1) = 1", Map.of()));
        // 非短路路径确实会报错，证明上面的用例不是因为"求值器算错"而通过
        assertThrows(SQLException.class, () -> condition("1 = 1 and no_such_fn(1) = 1", Map.of()));
    }

    @Test
    void threeValuedLogic() throws SQLException {
        assertFalse(condition("x = 1 and y = 1", vars("x", null, "y", 2)));
        assertTrue(condition("x = 1 or y = 1", vars("x", null, "y", 1)));
        assertFalse(condition("not (x is null)", vars("x", null)));
        // NOT NULL → NULL → 条件为 false
        assertFalse(condition("not (x = 1)", vars("x", null)));
    }

    /**
     * 除零： 要求 {@code ZERO_DIVIDE(22012)}。后端 DuckDB 的 {@code /} 会返回
     * {@code Infinity}，因此引擎对可直接取值的除数做本地检查（字面量/变量/游标属性）。
     */
    @Test
    void divisionByZeroRaisesZeroDivide() {
        PlSqlException literal = assertThrows(PlSqlException.class, () -> evaluate("1 / 0", Map.of()));
        assertEquals(PlSqlException.ZERO_DIVIDE, literal.getSqlState());

        PlSqlException byVariable = assertThrows(PlSqlException.class,
                () -> evaluate("100 / y", vars("y", 0)));
        assertEquals(PlSqlException.ZERO_DIVIDE, byVariable.getSqlState());

        PlSqlException modulo = assertThrows(PlSqlException.class, () -> evaluate("10 % 0", Map.of()));
        assertEquals(PlSqlException.ZERO_DIVIDE, modulo.getSqlState());
    }

    /** 除数非零时一切照旧（避免误伤正常路径）。 */
    @Test
    void divisionWorksForNonZeroDivisor() throws SQLException {
        assertEquals(0, new BigDecimal(String.valueOf(evaluate("7 / 2", Map.of())))
                .compareTo(new BigDecimal("3.5")));
        assertEquals(5, number(evaluate("10 / y", vars("y", 2))));
    }

    // ---------- 错误路径 ----------
    @Test
    void nonBooleanConditionIsTypeError() {
        PlSqlTypeException error = assertThrows(PlSqlTypeException.class,
                () -> condition("1 + 1", Map.of()));
        assertEquals(PlSqlTypeException.DATATYPE_MISMATCH, error.getSqlState());
    }

    @Test
    void unknownFunctionSurfacesAsSqlError() {
        assertThrows(SQLException.class, () -> evaluate("no_such_fn(x)", vars("x", 1)));
    }
}
