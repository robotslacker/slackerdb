package org.slackerdb.dbdesk.access.dataset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 数据集编译（:name → ?）与只读校验：纯逻辑，不需要数据库。 */
class DatasetCompileTest {

    @Test
    @DisplayName(":name 换成 ?，顺序按出现次序；重复参数按次数绑定")
    void compilesNamedParameters() {
        DatasetRunner.Compiled compiled = DatasetRunner.compile(
                "select * from t where id >= :minId and id <= :maxId or id = :minId");
        assertEquals("select * from t where id >= ? and id <= ? or id = ?", compiled.sql());
        assertEquals(List.of("minId", "maxId", "minId"), compiled.parameterOrder());
    }

    @Test
    @DisplayName("字符串、注释、类型转换里的冒号都不是参数")
    void ignoresColonsInLiteralsAndComments() {
        DatasetRunner.Compiled compiled = DatasetRunner.compile(
                "select ':notAParam' as a, x::int as b -- :comment\n /* :block */ , :real as c");
        assertEquals("select ':notAParam' as a, x::int as b -- :comment\n /* :block */ , ? as c",
                compiled.sql());
        assertEquals(List.of("real"), compiled.parameterOrder());
    }

    @Test
    @DisplayName("双引号标识符里的冒号不受影响")
    void ignoresQuotedIdentifier() {
        DatasetRunner.Compiled compiled = DatasetRunner.compile("select \"a:b\" from t where k = :k");
        assertEquals("select \"a:b\" from t where k = ?", compiled.sql());
        assertEquals(List.of("k"), compiled.parameterOrder());
    }

    @Test
    @DisplayName("只允许单条 SELECT / WITH；写语句与多语句一律拒绝")
    void rejectsNonQuery() {
        DatasetRunner.ensureSingleQuery("select 1");
        DatasetRunner.ensureSingleQuery("with t as (select 1) select * from t");
        DatasetRunner.ensureSingleQuery("-- 注释\nselect 1;");

        assertThrows(IllegalArgumentException.class, () -> DatasetRunner.ensureSingleQuery("delete from t"));
        assertThrows(IllegalArgumentException.class, () -> DatasetRunner.ensureSingleQuery("select 1; drop table t"));
        assertThrows(IllegalArgumentException.class, () -> DatasetRunner.ensureSingleQuery("insert into t values (1)"));
    }

    @Test
    @DisplayName("结尾分号不会算成第二条语句")
    void trailingSemicolonIsFine() {
        DatasetRunner.Compiled compiled = DatasetRunner.compile("select :a;");
        assertEquals("select ?;", compiled.sql());
    }
}
