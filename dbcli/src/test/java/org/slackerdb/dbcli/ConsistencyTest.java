package org.slackerdb.dbcli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.consistency.ConsistencyFixtures;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 契约 C-5：dbcli 的类型名与语句切分必须与控制台"同源一致"（同一份 TSV 夹具）。
 */
class ConsistencyTest {

    @Test
    @DisplayName("type-names: jdbcType -> unified type name")
    void typeNamesMatchFixture() throws Exception {
        List<String[]> rows = ConsistencyFixtures.read("type-names.tsv");
        for (String[] row : rows) {
            int jdbcType = Integer.parseInt(row[0]);
            String expected = "(null)".equals(row[2]) ? null : row[2];
            assertEquals(expected, org.slackerdb.dbcli.sql.SqlColumnTypes.nameOf(jdbcType, row[1]),
                    "jdbcType=" + jdbcType + " driverName=" + row[1]);
        }
    }

    @Test
    @DisplayName("statement-splitting: top-level semicolons and 'comment/whitespace only'")
    void statementSplittingMatchesFixture() throws Exception {
        for (String[] row : ConsistencyFixtures.read("statement-splitting.tsv")) {
            String text = row[0];
            assertEquals(Integer.parseInt(row[1]),
                    org.slackerdb.dbcli.engine.StatementSplitter.topLevelSemicolons(text).size(),
                    "wrong semicolon count: [" + text + "]");
            assertEquals(Boolean.parseBoolean(row[2]),
                    org.slackerdb.dbcli.engine.StatementSplitter.isBlankOrCommentOnly(text),
                    "wrong blank/comment verdict: [" + text + "]");
        }
    }
}
