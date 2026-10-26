package org.slackerdb.dbdesk.access.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlError} 的解析用例：服务端只把行列写在文案里（pgwire 的 Position 恒为 0）。
 */
class SqlErrorTest {

    @Test
    void parsesLineAndColumnFromCaret() {
        String message = "ERROR: Binder Error: Referenced column \"no_such_column\" not found in FROM clause!\n"
                + "Candidate bindings: \"c1\", \"c2\", \"c3\"\n"
                + "\n"
                + "LINE 1: select no_such_column from m0_probe\n"
                + "               ^\n";

        SqlError error = SqlError.parse(message);

        assertEquals(1, error.line());
        assertEquals(8, error.column(), "插入符在 'no_such_column' 的起始处（1-based 第 8 列）");
        assertTrue(error.hasPosition());
        assertTrue(error.summary().contains("Binder Error"), "摘要应保留第一行，实际：" + error.summary());
        assertFalse(error.summary().startsWith("ERROR:"), "摘要应去掉 ERROR: 前缀");
    }

    @Test
    void parsesMultiLineStatement() {
        String message = "ERROR: Catalog Error: Table with name nope does not exist!\n\n"
                + "LINE 2: from nope\n"
                + "             ^\n";

        SqlError error = SqlError.parse(message);

        assertEquals(2, error.line());
        assertEquals(6, error.column());
    }

    @Test
    void degradesGracefullyWithoutPosition() {
        SqlError error = SqlError.parse("ERROR: Parser Error: syntax error at end of input");

        assertEquals(0, error.line());
        assertEquals(0, error.column());
        assertFalse(error.hasPosition());
        assertTrue(error.summary().startsWith("Parser Error"));
    }

    @Test
    void handlesNullAndBlank() {
        assertEquals(0, SqlError.parse(null).line());
        assertEquals(0, SqlError.parse("   ").line());
    }
}
