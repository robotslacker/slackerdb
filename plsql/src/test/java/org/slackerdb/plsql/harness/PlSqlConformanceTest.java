package org.slackerdb.plsql.harness;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 数据驱动的一致性测试入口：语料位于
 * {@code plsql/src/test/resources/plsql/conformance}，新增用例只需加 {@code *.plsql} 文件，
 * 无需改 Java 代码。
 */
class PlSqlConformanceTest {

    static Stream<Arguments> cases() {
        List<ConformanceCase> all = CaseLoader.loadAll();
        assertFalse(all.isEmpty(),
                "未发现任何一致性用例，请检查目录：" + CaseLoader.ROOT);
        return all.stream().map(c -> Arguments.of(c.displayName(), c));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void conformance(String name, ConformanceCase testCase) {
        CaseRunner.run(testCase);
    }
}
