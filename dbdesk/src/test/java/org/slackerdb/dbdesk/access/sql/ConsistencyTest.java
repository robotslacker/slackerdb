package org.slackerdb.dbdesk.access.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.consistency.ConsistencyFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 契约 C-5：控制台的类型名与语句切分必须与 dbcli "同源一致"（同一份 TSV 夹具）。
 */
class ConsistencyTest {

    @Test
    @DisplayName("type-names：jdbcType → 统一类型名")
    void typeNamesMatchFixture() throws Exception {
        for (String[] row : ConsistencyFixtures.read("type-names.tsv")) {
            int jdbcType = Integer.parseInt(row[0]);
            String expected = "(null)".equals(row[2]) ? null : row[2];
            assertEquals(expected, org.slackerdb.dbdesk.access.SqlColumnTypes.nameOf(jdbcType, row[1]),
                    "jdbcType=" + jdbcType + " driverName=" + row[1]);
        }
    }

    @Test
    @DisplayName("statement-splitting：顶层分号与'只剩注释/空白'")
    void statementSplittingMatchesFixture() throws Exception {
        for (String[] row : ConsistencyFixtures.read("statement-splitting.tsv")) {
            String text = row[0];
            assertEquals(Integer.parseInt(row[1]), StatementSplitter.topLevelSemicolons(text).size(),
                    "分号数不对: [" + text + "]");
            assertEquals(Boolean.parseBoolean(row[2]), StatementSplitter.isBlankOrCommentOnly(text),
                    "空白/注释判定不对: [" + text + "]");
        }
    }
}