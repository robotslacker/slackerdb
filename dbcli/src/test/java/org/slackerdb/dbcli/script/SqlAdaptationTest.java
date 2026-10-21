package org.slackerdb.dbcli.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析适配度的回归测试：**真实会写出来的语句**必须落在两个明确状态之一——
 *
 * <ol>
 *   <li><b>写完</b>（{@code isFinished=true}）：会被执行，{@code statement} 是关键字到终结符之间的原文；</li>
 *   <li><b>没写完</b>（{@code isFinished=false}）：控制台给续行提示符 {@code "   > "}、批处理继续读下一行。</li>
 * </ol>
 *
 * <p>不能出现"既不执行、也不给续行提示"（用户敲什么都停不下来）或"把前半句截断执行"这两类坏结果，
 * 因此每条语句都同时钉住**完整形态**与**未写完形态**。</p>
 *
 * <p>覆盖的构造：{@code WITH}/CTE、{@code INSERT … RETURNING}、{@code INSERT … ON CONFLICT}、
 * {@code CREATE OR REPLACE VIEW|FUNCTION|PROCEDURE}、PostgreSQL 的美元引号体（{@code $$}/{@code $tag$}）、
 * {@code DO}、{@code TRUNCATE}/{@code ALTER TABLE}/{@code VACUUM}/{@code ANALYZE}/{@code EXPLAIN}/
 * {@code SET}/{@code SHOW}/{@code GRANT}/{@code REVOKE}/{@code CALL}/{@code COMMENT}/{@code REFRESH}/
 * {@code COPY}、事务形式的 {@code BEGIN;}、以及引号与注释里的 {@code ';'}。</p>
 */
class SqlAdaptationTest {

    @Test
    @DisplayName("完整语句：解析成功、会执行（errorCode=0 且有 IR）")
    void completeStatementsAreFinished() {
        String[] complete = {
            "with cte as (select 1 as x) select * from cte;",
            "with recursive r(n) as (select 1 union all select n+1 from r where n < 5) select * from r;",
            "insert into t(a) values (1) returning id;",
            "insert into t(a) values (1) on conflict (a) do nothing;",
            "create or replace view v as select 1;",
            "create or replace function f() returns int as $$ select 1; $$ language sql;",
            "create or replace function f() returns int as $$\nbegin\n  return 1;\nend;\n$$ language plpgsql;",
            "do $$ begin raise notice 'x'; end; $$;",
            "do $body$ begin raise notice 'x'; end; $body$;",
            "truncate table t;",
            "alter table t add column b int;",
            "vacuum t;",
            "analyze t;",
            "explain select 1;",
            "set search_path to public;",
            "show search_path;",
            "grant select on t to u;",
            "revoke select on t from u;",
            "call p();",
            "comment on table t is 'x';",
            "refresh materialized view mv;",
            "copy t from '/tmp/a.csv' with (format csv);",
            "copy t from stdin;",
            "begin;",
            "select 1; -- it's a comment; with a semicolon",
            "insert into t values ('line1\nline2; line3');",
        };

        for (String sql : complete) {
            SqlAnalyzer.Result result = SqlAnalyzer.analyze(sql);
            String where = "输入 >>>" + sql + "<<<";
            assertTrue(result.isFinished(), "应当判定为写完：" + where + "，实际 errorMsg=" + result.errorMsg());
            assertEquals(0, result.errorCode(), "完整语句不应有错误码：" + where + "，errorMsg=" + result.errorMsg());
            assertNotNull(result.parsedObject(), "完整语句应当有 IR：" + where);
        }
    }

    @Test
    @DisplayName("未写完形态：判成没写完（控制台给续行提示符，批处理继续读下一行）")
    void incompleteStatementsNeedMoreLines() {
        String[] incomplete = {
            "select 1,",
            "create table t(",
            "insert into t values (",
            "with cte as (",
            "with cte as (select 1 as x) select *",
            "insert into t(a) values (1) returning",
            "insert into t(a) values (1) on conflict (a) do",
            "create or replace view v as",
            "create or replace view v as select 1",
            "truncate table t",
            "alter table t",
            "do $$",
            "do $$ begin raise notice 'x'; end; $$",
            "create or replace function f() returns int as $$\nbegin\n  return 1;\nend;\n$$ language plpgsql",
            "select 'a;b",
        };

        for (String sql : incomplete) {
            SqlAnalyzer.Result result = SqlAnalyzer.analyze(sql);
            String where = "输入 >>>" + sql + "<<<";
            assertFalse(result.isFinished(), "应当判定为没写完：" + where);
        }
    }

    @Test
    @DisplayName("真语法错误不是'没写完'：未知命令与拼错的关键字都要立刻报错")
    void syntaxErrorsAreNotIncomplete() {
        for (String sql : new String[] {"selec 1", "_nosuch thing"}) {
            SqlAnalyzer.Result result = SqlAnalyzer.analyze(sql);
            String where = "输入 >>>" + sql + "<<<";
            assertTrue(result.isFinished(), "语法错误不应判成没写完（否则控制台停不下来）：" + where);
            assertTrue(result.errorCode() != 0, "语法错误应当带错误码：" + where);
        }
    }

    @Test
    @DisplayName("行首 '/' 收尾要求顶格：行尾空白无所谓，缩进的 '/' 不算收尾")
    void slashTerminatorMustBeFlushLeft() {
        // 实测口径（词法：SQL_SLASH_END / SQL_SLASH 都是 '\n' 紧跟 '/'）：
        // 行尾空白能吃掉，行首缩进不行——缩进的 '/' 会一直判"没写完"
        assertTrue(SqlAnalyzer.analyze("select 1\n/").isFinished(), "顶格的 '/' 应当收尾");
        assertTrue(SqlAnalyzer.analyze("select 1\n/   ").isFinished(), "行尾空白不影响收尾");
        assertFalse(SqlAnalyzer.analyze("select 1\n   /").isFinished(), "缩进的 '/' 不算收尾");

        String block = "declare\nbegin\n  null;\nend;\n";
        assertTrue(SqlAnalyzer.analyze(block + "/").isFinished(), "块：顶格的 '/' 收尾");
        assertTrue(SqlAnalyzer.analyze(block + "/  ").isFinished(), "块：行尾空白不影响收尾");
        assertFalse(SqlAnalyzer.analyze(block + "  /").isFinished(), "块：缩进的 '/' 不算收尾");
    }

    @Test
    @DisplayName("未闭合的美元引号给一条可读文案（脚本结束时能指出原因）")
    void unclosedDollarQuoteHasReadableMessage() {
        SqlAnalyzer.Result result = SqlAnalyzer.analyze("select $$abc;");

        assertFalse(result.isFinished(), "未闭合的 $$ 应当判定为没写完");
        assertEquals("unclosed dollar-quoted string", result.errorMsg());
    }
}
