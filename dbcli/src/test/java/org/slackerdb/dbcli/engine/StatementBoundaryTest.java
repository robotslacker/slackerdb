package org.slackerdb.dbcli.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StatementBoundary} 的判据：**只有这四种位置**才值得让分析器解析。
 *
 * <p>这些断言就是"性能闸门"的契约：改错任何一条，要么多解析（慢），要么该解析时不解析（语句卡住）。
 * 判定本身不决定"哪一段被执行"——那仍然由 {@code SqlAnalyzer} 负责。</p>
 */
class StatementBoundaryTest {

    private static StatementBoundary boundary(String... lines) {
        StatementBoundary boundary = new StatementBoundary();
        for (String line : lines) {
            boundary.feedLine(line);
        }
        return boundary;
    }

    @Test
    @DisplayName("单行 SQL：没有收尾符 → NONE（不解析）；有 ';' → SEMICOLON")
    void singleLineSql() {
        assertEquals(StatementBoundary.Kind.NONE, boundary("select 1").kind());
        assertEquals(StatementBoundary.Kind.SEMICOLON, boundary("select 1;").kind());
    }

    @Test
    @DisplayName("单行 _ 命令：按行收尾 → COMMAND；带 ';' → SEMICOLON")
    void singleLineCommands() {
        assertEquals(StatementBoundary.Kind.COMMAND, boundary("_help").kind());
        assertEquals(StatementBoundary.Kind.COMMAND, boundary("_sleep 0").kind());
        assertEquals(StatementBoundary.Kind.SEMICOLON, boundary("_help;").kind());
    }

    @Test
    @DisplayName("多行语句：中间行不是边界；最后一行有 ';' 才是")
    void multiLineSql() {
        assertEquals(StatementBoundary.Kind.NONE, boundary("select *", " from t", " where id = 1").kind());
        assertEquals(StatementBoundary.Kind.SEMICOLON,
                boundary("select *", " from t", " where id = 1;").kind());
    }

    @Test
    @DisplayName("同一行多条语句：第二句是 SQL 且未收尾 → NONE；第二句是 _ 命令 → COMMAND")
    void statementsOnOneLine() {
        assertEquals(StatementBoundary.Kind.NONE, boundary("select 1; select 2").kind());
        assertEquals(StatementBoundary.Kind.COMMAND, boundary("select 1; _sleep 0").kind());
        assertEquals(StatementBoundary.Kind.SEMICOLON, boundary("select 1; select 2;").kind());
    }

    @Test
    @DisplayName("块语句：body 里的 ';' 不是边界，只有行首 '/' 才是")
    void blockStatements() {
        assertEquals(StatementBoundary.Kind.NONE, boundary("create procedure p() begin").kind());
        assertEquals(StatementBoundary.Kind.SEMICOLON,
                boundary("create procedure p() begin", "select 1;").kind());
        assertEquals(StatementBoundary.Kind.SLASH,
                boundary("create procedure p() begin", "select 1;", "end;", "/").kind());
    }

    @Test
    @DisplayName("_echo 块：body 不是边界，只有 'echo off' 是")
    void echoBlocks() {
        assertEquals(StatementBoundary.Kind.NONE, boundary("_echo t", "hello").kind());
        assertEquals(StatementBoundary.Kind.ECHO_OFF, boundary("_echo t", "hello", "echo off").kind());
    }

    @Test
    @DisplayName("字符串里的 ';' 与 '/' 不算边界（含未闭合引号 → NONE）")
    void quotesHideTerminators() {
        assertEquals(StatementBoundary.Kind.NONE, boundary("select 'a;b'").kind());
        assertEquals(StatementBoundary.Kind.SEMICOLON, boundary("select 'a;b';").kind());
        assertEquals(StatementBoundary.Kind.NONE, boundary("select 'unclosed").kind());
        assertEquals(StatementBoundary.Kind.NONE, boundary("select 'unclosed", "/").kind());
    }

    @Test
    @DisplayName("分号后只有注释/空白 → 仍然是 SEMICOLON")
    void commentsAfterTerminator() {
        assertEquals(StatementBoundary.Kind.SEMICOLON, boundary("select 1; -- trailing").kind());
        assertEquals(StatementBoundary.Kind.SEMICOLON,
                boundary("select 1;", "-- note", "").kind());
    }

    @Test
    @DisplayName("startsBlock：declare/begin/create procedure 是块；create table 与 $$ 函数体不是")
    void startsBlock() {
        assertTrue(StatementBoundary.startsBlock("declare\n  v int;"));
        assertTrue(StatementBoundary.startsBlock("declare"));
        assertTrue(StatementBoundary.startsBlock("begin\n  null;"));
        assertTrue(StatementBoundary.startsBlock("begin"));
        assertTrue(StatementBoundary.startsBlock("create procedure p() begin"));
        assertTrue(StatementBoundary.startsBlock("create or replace function f return int is"));
        assertTrue(StatementBoundary.startsBlock("-- note\ncreate trigger t before insert"));

        assertFalse(StatementBoundary.startsBlock("begin;"), "事务形式不是块");
        assertFalse(StatementBoundary.startsBlock("create table t (id int)"));
        assertFalse(StatementBoundary.startsBlock("select 1"));
        assertFalse(StatementBoundary.startsBlock("_start child.sql"));
        assertFalse(StatementBoundary.startsBlock(
                        "create function f() returns int as $$ select 1; $$ language sql;"),
                "美元引号函数体按普通 ';' 收尾");
    }
}
