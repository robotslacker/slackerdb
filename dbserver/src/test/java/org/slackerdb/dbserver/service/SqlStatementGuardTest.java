package org.slackerdb.dbserver.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlStatementGuard} 的只读判定契约。
 *
 * <p>这组用例的取舍很明确：<b>漏放比误拒严重得多</b>。所以断言里既有
 * "写语句必须被拒"，也有"读语句必须被放行"——后者防止有人为了堵漏洞把判定做死，
 * 把工具面变成不可用。</p>
 */
public class SqlStatementGuardTest {

    // ------------------------------------------------------------ 必须拒绝

    @Test
    @DisplayName("Plain DML and DDL are rejected")
    void rejectsDmlAndDdl() {
        assertDenied("insert into t values (1)");
        assertDenied("update t set a = 1");
        assertDenied("delete from t");
        assertDenied("create table t(a int)");
        assertDenied("drop table t");
        assertDenied("alter table t add column b int");
        assertDenied("truncate t");
        assertDenied("merge into t using s on t.id = s.id when matched then update set a = 1");
    }

    @Test
    @DisplayName("A read-looking statement with a write inside is rejected")
    void rejectsWriteHiddenInCte() {
        // 官方 Postgres MCP 参考服务器的 BEGIN TRANSACTION READ ONLY 正是被这类语句绕过的
        assertDenied("with gone as (delete from t returning *) select * from gone");
        assertDenied("with c as (insert into t values (1) returning id) select * from c");
    }

    @Test
    @DisplayName("Instance-level and filesystem-level statements are rejected")
    void rejectsInstanceLevelStatements() {
        assertDenied("attach 'x.db' as other");
        assertDenied("detach other");
        assertDenied("install httpfs");
        assertDenied("load httpfs");
        assertDenied("copy t to 'out.parquet'");
        assertDenied("export database 'dir'");
        assertDenied("pragma enable_checkpoint_on_shutdown");
        assertDenied("call some_proc()");
        assertDenied("begin transaction");
        assertDenied("commit");
        assertDenied("set threads = 4");
    }

    @Test
    @DisplayName("Anonymous blocks and multiple statements with any write are rejected")
    void rejectsBlocksAndMixedStatements() {
        assertDenied("do $$ begin insert into t values (1); end $$");
        assertDenied("select 1; insert into t values (1)");
        assertDenied("select 1; select 2; drop table t");
    }

    @Test
    @DisplayName("Empty input is rejected rather than treated as a harmless read")
    void rejectsEmpty() {
        assertDenied("");
        assertDenied("   ");
        assertDenied("-- just a comment");
    }

    // ------------------------------------------------------------ 必须放行

    @Test
    @DisplayName("Ordinary read-only queries are allowed")
    void allowsReads() {
        assertAllowed("select 1");
        assertAllowed("select * from t where a = 1 order by b limit 10");
        assertAllowed("with c as (select 1 as a) select * from c");
        assertAllowed("describe t");
        assertAllowed("desc t");
        assertAllowed("show tables");
        assertAllowed("explain select 1");
        assertAllowed("summarize select 1");
        assertAllowed("values (1), (2)");
        assertAllowed("from t select a");
        assertAllowed("select 1; select 2");
    }

    @Test
    @DisplayName("Write keywords inside string literals do not cause a false rejection")
    void allowsLiteralsContainingKeywords() {
        assertAllowed("select 'delete from t' as note");
        assertAllowed("select 'insert' as a, 'drop' as b");
        assertAllowed("select * from t where note = 'create table'");
    }

    @Test
    @DisplayName("Leading comments do not change the verdict")
    void handlesLeadingComments() {
        assertAllowed("-- read only\nselect 1");
        assertAllowed("/* block */ select 1");
        assertDenied("-- harmless\ninsert into t values (1)");
    }

    @Test
    @DisplayName("Quoted identifiers that happen to match keywords are allowed")
    void allowsQuotedIdentifiers() {
        assertAllowed("select \"delete\" from t");
        assertAllowed("select \"update\", \"insert\" from t");
    }

    // ------------------------------------------------------------ 断言辅助

    private static void assertDenied(String sql) {
        SqlStatementGuard.Verdict verdict = SqlStatementGuard.classify(sql);
        assertFalse(verdict.readOnly(), "expected DENY but was allowed: " + sql);
        assertTrue(verdict.reason() != null && !verdict.reason().isBlank(),
                "a rejection must carry a reason the model can act on: " + sql);
    }

    private static void assertAllowed(String sql) {
        SqlStatementGuard.Verdict verdict = SqlStatementGuard.classify(sql);
        assertTrue(verdict.readOnly(), "expected ALLOW but was denied (" + verdict.reason() + "): " + sql);
    }
}
