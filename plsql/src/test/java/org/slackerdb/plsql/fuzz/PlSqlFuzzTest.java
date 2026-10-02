package org.slackerdb.plsql.fuzz;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slackerdb.plsql.PlSqlEngine;
import org.slackerdb.plsql.PlSqlException;
import org.slackerdb.plsql.spi.DefaultJdbcHost;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * L8：模糊 / 属性测试。
 *
 * <p>两条属性：</p>
 * <ol>
 *   <li><b>合法块</b>（随机组合 IF/LOOP/WHILE/FOR/赋值/DML，嵌套 ≤3）：
 *       要么正常结束，要么抛 {@link PlSqlException}（带消息/位置）；
 *       <b>绝不允许</b> NPE / StackOverflow / 挂死；</li>
 *   <li><b>破坏后的块</b>（随机删/插/重复 token）：同上，且语法错误必须带位置
 *       （{@code 42601} + line ≥ 1），证明"错误可见、可定位"。</li>
 * </ol>
 *
 * <p>种子固定（1..40），失败可复现；每条用例带超时护栏。</p>
 */
class PlSqlFuzzTest {

    private static final int[] SEEDS = seeds();

    private static int[] seeds() {
        int[] values = new int[40];
        for (int i = 0; i < values.length; i++) {
            values[i] = i + 1;
        }
        return values;
    }

    static java.util.stream.IntStream seedStream() {
        return java.util.Arrays.stream(SEEDS);
    }

    // ------------------------------------------------------------------
    // 属性 1：合法块
    // ------------------------------------------------------------------
    @ParameterizedTest(name = "seed={0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40})
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void legalBlocksOnlyFailAsPlSqlErrors(int seed) throws SQLException {
        Random random = new Random(seed);
        String script = legalBlock(random, 0);
        runAndCheck(script, "合法块");
    }

    // ------------------------------------------------------------------
    // 属性 2：破坏后的块
    // ------------------------------------------------------------------
    @ParameterizedTest(name = "seed={0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40})
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void corruptedBlocksFailCleanlyAndLocally(int seed) throws SQLException {
        Random random = new Random(seed * 7919L);
        String script = corrupt(random, legalBlock(random, 0));
        PlSqlException failure = runAndCheck(script, "破坏块");
        if (failure != null && PlSqlException.SYNTAX_ERROR.equals(failure.getSqlState())) {
            assertTrue(failure.getLine() >= 1,
                    "语法错误必须带位置：" + failure.getMessage() + " script=" + script);
        }
    }

    /**
     * 执行并断言"要么成功、要么 PlSqlException"。
     *
     * @return 失败时的异常（成功时为 null）
     */
    private PlSqlException runAndCheck(String script, String label) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb::memory:", "", "")) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("create table fz(i int, s text)");
            }
            connection.commit();
            try {
                PlSqlEngine.execute(new DefaultJdbcHost(connection), script);
                return null;
            } catch (PlSqlException expected) {
                assertNotNull(expected.getMessage(), label + " 的 PlSqlException 必须带消息：" + script);
                return expected;
            } catch (Throwable unexpected) {
                fail(label + " 抛出了非 PlSqlException：" + unexpected + "\nscript:\n" + script);
                return null; // 不可达
            }
        }
    }

    // ------------------------------------------------------------------
    // 生成器
    // ------------------------------------------------------------------
    /** 生成一个语法合法的随机块。 */
    static String legalBlock(Random random, int depth) {
        StringBuilder script = new StringBuilder();
        script.append("declare\n  x0 int;\n  x1 int;\n  x2 int;\n  s0 text;\nbegin\n");
        script.append("  let x0 = 0;\n  let x1 = 1;\n  let x2 = 2;\n  let s0 = 'seed';\n");
        script.append(randomStatements(random, depth, 3));
        script.append("end;");
        return script.toString();
    }

    private static String randomStatements(Random random, int depth, int count) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < count; i++) {
            body.append(randomStatement(random, depth));
        }
        return body.toString();
    }

    private static String randomStatement(Random random, int depth) {
        int choice = depth >= 2 ? random.nextInt(6) : random.nextInt(10);
        return switch (choice) {
            case 0 -> "  let x0 = " + randomExpression(random) + ";\n";
            case 1 -> "  insert into fz values(" + randomExpression(random) + ", 'r');\n";
            case 2 -> "  let s0 = 'v' || 'w';\n";
            case 3 -> "  insert into fz values(1, :s0);\n";
            case 4 -> "  pass;\n";
            case 5 -> "  null;\n";
            case 6 -> "  if " + randomCondition(random) + " then\n"
                    + randomStatements(random, depth + 1, 2)
                    + "  else\n"
                    + randomStatements(random, depth + 1, 1)
                    + "  end if;\n";
            case 7 -> "  loop\n    let x0 = :x0 + 1;\n    exit when :x0 >= 3;\n"
                    + randomStatements(random, depth + 1, 1)
                    + "  end loop;\n";
            case 8 -> "  while :x1 < 3 loop\n    let x1 = :x1 + 1;\n"
                    + randomStatements(random, depth + 1, 1)
                    + "  end loop;\n";
            default -> "  for i in 1..3 loop\n" + randomStatements(random, depth + 1, 1) + "  end loop;\n";
        };
    }

    private static String randomExpression(Random random) {
        String[] parts = {":x0", ":x1", "0", "1", "2", "3", "7", "100", ":x0 + :x1", ":x1 - 1",
                ":x0 * 2", "(:x0 + 1) * 2", "abs(:x0)", "coalesce(:x0, 0)", "-:x0", ":x1 % 2"};
        return parts[random.nextInt(parts.length)];
    }

    private static String randomCondition(Random random) {
        String[] parts = {":x0 > 0", ":x0 >= :x1", ":x1 < 3", ":x0 = 0", ":x0 <> 1",
                ":x0 > 0 and :x1 < 3", ":x0 > 0 or :x1 > 3", "not (:x0 > 0)",
                ":x0 between 0 and 10", ":x0 in (0, 1, 2)"};
        return parts[random.nextInt(parts.length)];
    }

    /** 随机破坏：删 token / 插 token / 重复 token。 */
    static String corrupt(Random random, String script) {
        String[] tokens = script.split("(?<=\\s)|(?=\\s)");
        List<String> result = new ArrayList<>(List.of(tokens));
        int mutations = 1 + random.nextInt(3);
        String[] junk = {";", "(", ")", "then", "end", "..", "$$", "@", "x0", ":=", "loop", "'", "\""};
        for (int i = 0; i < mutations && !result.isEmpty(); i++) {
            int index = random.nextInt(result.size());
            switch (random.nextInt(3)) {
                case 0 -> result.remove(index);
                case 1 -> result.add(index, " " + junk[random.nextInt(junk.length)] + " ");
                default -> result.add(index, result.get(index));
            }
        }
        return String.join("", result);
    }
}
