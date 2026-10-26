package org.slackerdb.dbdesk.access.dataset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据集执行（真库）：参数绑定、默认值、行数上限、注入防护、只读拒绝。
 *
 * <pre>
 *   mvn -Pdbdesk -pl dbdesk test "-Ddbdesk.it.url=jdbc:postgresql://127.0.0.1:4409/m0" "-Dtest=DatasetRunnerTest"
 * </pre>
 */
@EnabledIfSystemProperty(named = "dbdesk.it.url", matches = ".+")
class DatasetRunnerTest {

    private static final String URL = System.getProperty("dbdesk.it.url");

    private static Dataset ds(String sql, List<Dataset.Param> params) {
        return new Dataset("id-" + sql.hashCode(), "测试", sql, URL, params, List.of(), null);
    }

    @Test
    @DisplayName("参数按名字绑定；没传的用定义里的默认值")
    void bindsParametersAndDefaults() throws Exception {
        Dataset dataset = ds("select cast(:a as varchar) as a, cast(:b as varchar) as b", List.of(
                new Dataset.Param("a", "text", "默认A", ""),
                new Dataset.Param("b", "text", "默认B", "")));

        DatasetRunner.Result withBoth = DatasetRunner.run(dataset, Map.of("a", "X", "b", "Y"), 10);
        assertEquals(List.of("a", "b"), withBoth.columns());
        assertEquals("X", String.valueOf(withBoth.rows().get(0).get(0)));
        assertEquals("Y", String.valueOf(withBoth.rows().get(0).get(1)));

        DatasetRunner.Result withDefaults = DatasetRunner.run(dataset, Map.of(), 10);
        assertEquals("默认A", String.valueOf(withDefaults.rows().get(0).get(0)));
    }

    @Test
    @DisplayName("参数值被当成值处理：' OR 1=1 -- 不会改变语义（注入防护）")
    void parameterValueIsNotInterpreted() throws Exception {
        Dataset dataset = ds("select count(*) as n from (select 1 as x) t where :p = :p",
                List.of(new Dataset.Param("p", "text", "v", "")));

        DatasetRunner.Result result = DatasetRunner.run(dataset,
                Map.of("p", "' OR 1=1 --"), 10);

        assertTrue(result.rows().size() == 1, "应正常返回一行");
        assertEquals(1L, ((Number) result.rows().get(0).get(0)).longValue(), "条件恒真，计数应为 1");
    }

    @Test
    @DisplayName("行数上限：超过 maxRows 时 truncated=true 且只回 maxRows 行")
    void truncatesAtMaxRows() throws Exception {
        DatasetRunner.Result result = DatasetRunner.run(ds("select * from m0_probe", List.of()),
                Map.of(), 100);

        assertEquals(100, result.rows().size());
        assertTrue(result.truncated(), "撞到上限应标记 truncated");
    }

    @Test
    @DisplayName("写语句被拒绝（数据集是展示层，不该有写权限）")
    void rejectsWrites() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DatasetRunner.run(ds("delete from m0_probe", List.of()), Map.of(), 10));
        assertTrue(error.getMessage().contains("只允许"));
    }

    @Test
    @DisplayName("WITH 子句可用，聚合在 SQL 里完成")
    void supportsWithAndAggregation() throws Exception {
        Dataset dataset = ds("with t as (select * from m0_probe limit 10) "
                + "select count(*) as n, max(id) as max_id from t", List.of());
        DatasetRunner.Result result = DatasetRunner.run(dataset, Map.of(), 10);

        assertEquals(1, result.rows().size());
        assertEquals(10L, ((Number) result.rows().get(0).get(0)).longValue());
        assertFalse(result.truncated());
    }
}
